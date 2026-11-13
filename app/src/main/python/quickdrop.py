#!/usr/bin/env python3
"""QuickDrop v3 - share files between your phone and any device on the same
Wi-Fi or hotspot. The other device scans a QR code (no app needed).

v3: modern live interface, detects which devices are connected, activity feed,
upload progress bars, drag & drop. The control page only works on THIS phone.
Standard library only."""

import errno
import html
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote, urlparse

START_PORT = 8080
OFFLINE_AFTER = 15  # seconds without a heartbeat = device shown as disconnected
TOKEN = secrets.token_urlsafe(6)  # random link: only people with the QR/link get in
SHARE_URL = ""  # filled in at startup

LOCK = threading.RLock()
DEVICES = {}  # ip -> info
LOG = []  # newest last


# Android app-private working folder. The Android Storage Access Framework
# bridge imports/exports files to the folder selected by the user.
FOLDER = os.path.join(os.path.expanduser("~"), "QuickDrop")
os.makedirs(FOLDER, exist_ok=True)
DIRECT = os.path.join(FOLDER, ".direct")  # files sent from one device to another
TRANSFERS = {}  # id -> info about a file waiting for its receiver
TRANSFER_TTL = 2 * 3600  # unclaimed direct files are removed after 2 hours


def reset_direct():
    shutil.rmtree(DIRECT, ignore_errors=True)
    os.makedirs(DIRECT, exist_ok=True)


def configure_folder(path):
    """Set the app-private working folder before starting the HTTP server."""
    global FOLDER, DIRECT
    FOLDER = os.path.abspath(path)
    os.makedirs(FOLDER, exist_ok=True)
    DIRECT = os.path.join(FOLDER, ".direct")
    os.makedirs(DIRECT, exist_ok=True)


def export_to_selected_folder(path, name):
    """Mirror host uploads to the SAF folder selected in the Android UI."""
    try:
        from java import jclass
        copied = jclass("com.quickdrop.app.StorageBridge").exportFile(path, name)
        if not copied:
            log(f"Could not mirror {name} to the selected Android folder")
    except Exception as exc:
        print("QuickDrop: could not copy file to selected folder:", exc)


def delete_from_selected_folder(name):
    try:
        from java import jclass
        jclass("com.quickdrop.app.StorageBridge").deleteFile(name)
    except Exception:
        pass


def local_ip():
    for target in ("10.255.255.255", "8.8.8.8"):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect((target, 1))  # sends nothing; just picks the right interface
            ip = s.getsockname()[0]
            if not ip.startswith("127."):
                return ip
        except OSError:
            pass
        finally:
            s.close()
    return "127.0.0.1"


def clean_name(name):
    name = os.path.basename(name.replace("\\", "/")).lstrip(".").strip()
    return name or "upload"


def unique_path(name):
    base, ext = os.path.splitext(name)
    path = os.path.join(FOLDER, name)
    i = 1
    while os.path.exists(path):
        path = os.path.join(FOLDER, f"{base} ({i}){ext}")
        i += 1
    return path


def log(msg):
    with LOCK:
        LOG.append({"t": time.strftime("%H:%M"), "msg": msg})
        del LOG[:-40]


def device_name(ua):
    ua = ua or ""
    if "iPhone" in ua:
        return "iPhone"
    if "iPad" in ua:
        return "iPad"
    if "Android" in ua:
        m = re.search(r"Android[^;)]*;\s*([^;)]+)", ua)
        model = m.group(1).split(" Build")[0].strip() if m else ""
        if model and model != "K" and len(model) < 30:
            return f"Android {model}"
        return "Android phone"
    if "Windows" in ua:
        return "Windows PC"
    if "Macintosh" in ua or "Mac OS X" in ua:
        return "Mac"
    if "CrOS" in ua:
        return "Chromebook"
    if "Linux" in ua:
        return "Linux device"
    return "Unknown device"


def is_local(ip):
    return ip in ("127.0.0.1", "::1")


def labels():
    """Unique display name for each device (iPhone, iPhone 2, or the nickname it chose)."""
    with LOCK:
        seen, out = {}, {}
        for d in sorted(DEVICES.values(), key=lambda d: d["seq"]):
            base = d["nick"] or d["name"]
            seen[base] = seen.get(base, 0) + 1
            out[d["ip"]] = base if seen[base] == 1 else f"{base} {seen[base]}"
        return out


def label_of(d):
    return labels().get(d["ip"], d["nick"] or d["name"])


def touch(ip, ua, nick=None):
    """Record that a device is alive. Returns its record (None for this phone)."""
    if is_local(ip):
        return None
    now = time.time()
    with LOCK:
        d = DEVICES.get(ip)
        if d is None:
            d = DEVICES[ip] = {
                "ip": ip, "id": secrets.token_hex(4), "seq": len(DEVICES),
                "name": device_name(ua), "nick": "", "last": now,
                "online": False, "up": 0, "down": 0,
            }
        d["last"] = now
        if d["name"] == "Unknown device":
            d["name"] = device_name(ua)
        if nick:
            clean = re.sub(r"[^\w .'-]", "", nick).strip()[:24]
            if clean:
                d["nick"] = clean
        if not d["online"]:
            d["online"] = True
            log(f"{label_of(d)} connected")
        return d


def refresh_online():
    now = time.time()
    with LOCK:
        for d in DEVICES.values():
            if d["online"] and now - d["last"] > OFFLINE_AFTER:
                d["online"] = False
                log(f"{label_of(d)} disconnected")


def drop_transfer(tid):
    with LOCK:
        t = TRANSFERS.pop(tid, None)
    if t:
        try:
            os.remove(t["path"])
        except OSError:
            pass


def expire_transfers():
    now = time.time()
    with LOCK:
        old = [k for k, t in TRANSFERS.items() if now - t["time"] > TRANSFER_TTL]
    for k in old:
        drop_transfer(k)


def list_files():
    out = []
    with os.scandir(FOLDER) as it:
        for e in it:
            if e.is_file() and not e.name.startswith("."):
                try:
                    st = e.stat()
                except OSError:
                    continue
                out.append({"name": e.name, "size": st.st_size, "mtime": st.st_mtime})
    out.sort(key=lambda f: -f["mtime"])
    return out


def build_state(admin, ip=None):
    refresh_online()
    expire_transfers()
    now = time.time()
    state = {"files": list_files()}
    with LOCK:
        lab = labels()
        online = sorted((d for d in DEVICES.values() if d["online"]), key=lambda d: d["seq"])
        state["peers"] = [{"id": d["id"], "name": lab[d["ip"]]} for d in online if d["ip"] != ip]
        if admin:
            devices = [{
                "name": lab[d["ip"]], "ip": d["ip"], "online": d["online"],
                "ago": int(now - d["last"]), "up": d["up"], "down": d["down"],
            } for d in DEVICES.values()]
            devices.sort(key=lambda d: (not d["online"], d["ago"]))
            state["devices"] = devices
            state["log"] = list(reversed(LOG))[:25]
        else:
            state["me"] = {"name": lab.get(ip, "")}
            state["inbox"] = [
                {"id": t["id"], "from": t["from"], "name": t["name"], "size": t["size"]}
                for t in sorted(TRANSFERS.values(), key=lambda t: -t["time"])
                if t["to_ip"] == ip
            ]
    return state


# ------------------------------------------------- built-in QR code maker
# (no extra apps needed: byte mode, error correction M, versions 1-10)
_ECC_PER_BLOCK = {"L": [7, 10, 15, 20, 26, 18, 20, 24, 30, 18],
                  "M": [10, 16, 26, 18, 24, 16, 18, 22, 22, 26]}
_NUM_BLOCKS = {"L": [1, 1, 1, 1, 1, 2, 2, 2, 2, 4],
               "M": [1, 1, 1, 2, 2, 4, 4, 4, 5, 5]}
_FORMAT_BITS = {"L": 1, "M": 0}


def _raw_modules(ver):
    r = (16 * ver + 128) * ver + 64
    if ver >= 2:
        n = ver // 7 + 2
        r -= (25 * n - 10) * n - 55
        if ver >= 7:
            r -= 36
    return r


def _gf_mul(x, y):
    z = 0
    for i in range(7, -1, -1):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def _rs_divisor(degree):
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            result[j] = _gf_mul(result[j], root)
            if j + 1 < degree:
                result[j] ^= result[j + 1]
        root = _gf_mul(root, 2)
    return result


def _rs_remainder(data, divisor):
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coef in enumerate(divisor):
            result[i] ^= _gf_mul(coef, factor)
    return result


def make_qr(text, ecl="M"):
    """Return the QR code as a list of rows of booleans (True = dark)."""
    data = text.encode("utf-8")
    for ver in range(1, 11):
        eccs, nblocks = _ECC_PER_BLOCK[ecl][ver - 1], _NUM_BLOCKS[ecl][ver - 1]
        cap = _raw_modules(ver) // 8 - eccs * nblocks
        cc_bits = 8 if ver < 10 else 16
        if 4 + cc_bits + 8 * len(data) <= cap * 8:
            break
    else:
        raise ValueError("text too long for QR code")

    # 1. bit stream -> data codewords
    bits = []

    def put(val, n):
        bits.extend((val >> i) & 1 for i in range(n - 1, -1, -1))

    put(4, 4)
    put(len(data), cc_bits)
    for b in data:
        put(b, 8)
    put(0, min(4, cap * 8 - len(bits)))
    put(0, -len(bits) % 8)
    words = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = 0xEC
    while len(words) < cap:
        words.append(pad)
        pad ^= 0xEC ^ 0x11

    # 2. error correction + interleave
    raw = _raw_modules(ver) // 8
    nshort, shortlen = nblocks - raw % nblocks, raw // nblocks
    divisor = _rs_divisor(eccs)
    blocks, k = [], 0
    for i in range(nblocks):
        dl = shortlen - eccs + (0 if i < nshort else 1)
        dat = words[k:k + dl]
        k += dl
        ecc = _rs_remainder(dat, divisor)
        if i < nshort:
            dat = dat + [0]
        blocks.append(dat + ecc)
    final = []
    for i in range(len(blocks[0])):
        for j, blk in enumerate(blocks):
            if i != shortlen - eccs or j >= nshort:
                final.append(blk[i])

    # 3. draw fixed patterns
    size = ver * 4 + 17
    mod = [[False] * size for _ in range(size)]
    func = [[False] * size for _ in range(size)]

    def setf(x, y, dark):
        mod[y][x] = dark
        func[y][x] = True

    for i in range(size):
        setf(6, i, i % 2 == 0)
        setf(i, 6, i % 2 == 0)

    def finder(cx, cy):
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                x, y = cx + dx, cy + dy
                if 0 <= x < size and 0 <= y < size:
                    setf(x, y, max(abs(dx), abs(dy)) not in (2, 4))

    finder(3, 3)
    finder(size - 4, 3)
    finder(3, size - 4)

    if ver > 1:
        n = ver // 7 + 2
        step = (ver * 4 + n * 2 + 1) // (n * 2 - 2) * 2
        pos = list(reversed([size - 7 - i * step for i in range(n - 1)] + [6]))
        for a, cx in enumerate(pos):
            for b, cy in enumerate(pos):
                if (a == 0 and b == 0) or (a == 0 and b == n - 1) or (a == n - 1 and b == 0):
                    continue
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        setf(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)

    def draw_format(mask):
        d = (_FORMAT_BITS[ecl] << 3) | mask
        rem = d
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        fb = ((d << 10) | rem) ^ 0x5412
        bit = lambda i: (fb >> i) & 1 == 1
        for i in range(6):
            setf(8, i, bit(i))
        setf(8, 7, bit(6))
        setf(8, 8, bit(7))
        setf(7, 8, bit(8))
        for i in range(9, 15):
            setf(14 - i, 8, bit(i))
        for i in range(8):
            setf(size - 1 - i, 8, bit(i))
        for i in range(8, 15):
            setf(8, size - 15 + i, bit(i))
        setf(8, size - 8, True)

    draw_format(0)
    if ver >= 7:
        rem = ver
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        vb = (ver << 12) | rem
        for i in range(18):
            a, b = size - 11 + i % 3, i // 3
            setf(a, b, (vb >> i) & 1 == 1)
            setf(b, a, (vb >> i) & 1 == 1)

    # 4. place data bits in the zigzag pattern
    i = 0
    for right in range(size - 1, 0, -2):
        if right == 6:
            right = 5
        for vert in range(size):
            for j in range(2):
                x = right - j
                y = size - 1 - vert if ((right + 1) & 2) == 0 else vert
                if not func[y][x] and i < len(final) * 8:
                    mod[y][x] = (final[i >> 3] >> (7 - (i & 7))) & 1 == 1
                    i += 1

    # 5. choose the best mask
    masks = [
        lambda x, y: (x + y) % 2 == 0,
        lambda x, y: y % 2 == 0,
        lambda x, y: x % 3 == 0,
        lambda x, y: (x + y) % 3 == 0,
        lambda x, y: (x // 3 + y // 2) % 2 == 0,
        lambda x, y: x * y % 2 + x * y % 3 == 0,
        lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0,
        lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0,
    ]

    def apply(m):
        for y in range(size):
            for x in range(size):
                if not func[y][x] and masks[m](x, y):
                    mod[y][x] = not mod[y][x]

    def penalty():
        score = 0
        for grid in (mod, [list(c) for c in zip(*mod)]):
            for row in grid:
                run = 1
                for x in range(1, size):
                    if row[x] == row[x - 1]:
                        run += 1
                    else:
                        score += 3 + run - 5 if run >= 5 else 0
                        run = 1
                score += 3 + run - 5 if run >= 5 else 0
        for y in range(size - 1):
            for x in range(size - 1):
                if mod[y][x] == mod[y][x + 1] == mod[y + 1][x] == mod[y + 1][x + 1]:
                    score += 3
        return score

    best, best_score = 0, None
    for m in range(8):
        apply(m)
        draw_format(m)
        sc = penalty()
        if best_score is None or sc < best_score:
            best, best_score = m, sc
        apply(m)  # undo
    apply(best)
    draw_format(best)
    return mod


def qr_svg(url):
    """QR code as an inline SVG image (empty string if it can't be made)."""
    try:
        m = make_qr(url)
    except Exception:
        return ""
    n, q, parts = len(m), 4, []
    for y, row in enumerate(m):
        x = 0
        while x < n:
            if row[x]:
                s = x
                while x < n and row[x]:
                    x += 1
                parts.append(f"M{s + q} {y + q}h{x - s}v1h-{x - s}z")
            else:
                x += 1
    t = n + 2 * q
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {t} {t}" '
            f'shape-rendering="crispEdges"><rect width="{t}" height="{t}" fill="#fff"/>'
            f'<path d="{"".join(parts)}" fill="#000"/></svg>')


def qr_text(url):
    """QR code drawn with block characters for the Termux screen."""
    m = make_qr(url)
    n, q = len(m), 2
    light = lambda x, y: not (0 <= x < n and 0 <= y < n and m[y][x])
    chars = {(True, True): "\u2588", (True, False): "\u2580",
             (False, True): "\u2584", (False, False): " "}
    return "\n".join(
        "".join(chars[(light(x, y), light(x, y + 1))] for x in range(-q, n + q))
        for y in range(-q, n + q, 2)
    )


# ---------------------------------------------------------------- web pages

CSS = """
:root{--bg:#f4f5fa;--card:#fff;--text:#14161c;--muted:#6b7280;--line:#e6e8ef;
--accent:#4f46e5;--accent2:#7c3aed;--ok:#16a34a;--bad:#dc2626;--soft:#eef0ff}
@media(prefers-color-scheme:dark){:root{--bg:#0e1015;--card:#181b22;--text:#eceef3;
--muted:#9aa3b2;--line:#272c37;--accent:#818cf8;--accent2:#a78bfa;--soft:#232845}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:var(--bg);color:var(--text);
font:16px/1.4 -apple-system,system-ui,"Segoe UI",Roboto,sans-serif}
header{background:linear-gradient(135deg,#4f46e5,#7c3aed);color:#fff;
padding:22px 16px 26px;border-radius:0 0 26px 26px}
.hin{max-width:620px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;gap:10px}
h1{margin:0;font-size:1.45rem;letter-spacing:-.3px} .sub{opacity:.85;font-size:.85rem;margin-top:2px}
.pill{background:rgba(255,255,255,.2);padding:7px 12px;border-radius:99px;font-size:.85rem;
white-space:nowrap;display:flex;align-items:center;gap:7px}
.pill i{width:9px;height:9px;border-radius:50%;background:#cbd5e1;display:inline-block}
.pill.on i{background:#4ade80;box-shadow:0 0 0 3px rgba(74,222,128,.35)}
main{max-width:620px;margin:-8px auto 0;padding:14px 14px 50px}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;
padding:16px;margin-bottom:14px;box-shadow:0 1px 3px rgba(0,0,0,.04)}
.ch{display:flex;align-items:baseline;justify-content:space-between;margin-bottom:10px}
h2{margin:0;font-size:1.02rem} .hint{color:var(--muted);font-size:.8rem}
.btn{font:inherit;font-size:.92rem;border:0;border-radius:99px;padding:10px 16px;cursor:pointer;
background:linear-gradient(135deg,var(--accent),var(--accent2));color:#fff}
.btn.ghost{background:var(--soft);color:var(--accent)}
.btn.danger{background:transparent;color:var(--bad);border:1px solid var(--line)}
.btn.sm{padding:6px 12px;font-size:.8rem}
.row{display:flex;gap:8px;flex-wrap:wrap;justify-content:center;margin-top:12px}
.qrbox{display:flex;flex-direction:column;align-items:center}
.qr{background:#fff;border-radius:14px;width:224px;height:224px;overflow:hidden}
.qr svg{width:100%;height:100%;display:block}
.link{margin-top:10px;font-size:.78rem;color:var(--muted);word-break:break-all;text-align:center}
.drop{border:2px dashed var(--line);border-radius:16px;padding:26px 12px;text-align:center;
cursor:pointer;background:var(--soft);transition:.15s}
.drop.over{border-color:var(--accent);transform:scale(1.01)}
.drop b{display:block;font-size:1rem;color:var(--accent)} .drop span{color:var(--muted);font-size:.82rem}
.prog{margin-top:10px} .prow{display:flex;justify-content:space-between;font-size:.82rem;gap:10px}
.pn{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bar{height:6px;background:var(--line);border-radius:9px;overflow:hidden;margin-top:4px}
.fill{height:100%;width:0;background:linear-gradient(90deg,var(--accent),var(--accent2));transition:width .15s}
ul{list-style:none;margin:0;padding:0}
.file{display:flex;align-items:center;gap:12px;padding:10px 0;border-top:1px solid var(--line)}
.file:first-child{border-top:0} .fi{font-size:1.5rem}
.fm{flex:1;min-width:0} .fm a{color:var(--text);text-decoration:none;font-weight:500;display:block;
overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.fm span{color:var(--muted);font-size:.78rem}
.dev{display:flex;align-items:center;gap:12px;padding:10px 0;border-top:1px solid var(--line)}
.dev:first-child{border-top:0}
.dot{width:11px;height:11px;border-radius:50%;background:#9ca3af;flex:none}
.dot.on{background:var(--ok);box-shadow:0 0 0 4px rgba(22,163,74,.2)}
.di{flex:1;min-width:0} .dn{font-weight:600} .dm{color:var(--muted);font-size:.78rem}
.ds{font-size:.82rem;color:var(--muted);white-space:nowrap}
.empty{color:var(--muted);font-size:.9rem;text-align:center;padding:14px 0}
.lg{display:flex;gap:10px;font-size:.85rem;padding:5px 0}.lg time{color:var(--muted);flex:none}
#toasts{position:fixed;left:0;right:0;bottom:18px;display:flex;flex-direction:column;
align-items:center;gap:8px;pointer-events:none}
.toast{background:#111827;color:#fff;padding:10px 16px;border-radius:99px;font-size:.88rem;
opacity:0;transform:translateY(10px);transition:.25s}.toast.show{opacity:1;transform:none}
.foot{display:flex;justify-content:center;margin-top:6px}
.chips{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}
.chip{font:inherit;font-size:.85rem;border:1px solid var(--line);background:var(--soft);
color:var(--accent);padding:7px 13px;border-radius:99px;cursor:pointer}
.chip.sel{background:linear-gradient(135deg,var(--accent),var(--accent2));color:#fff;border-color:transparent}
"""

JS = """
const ADMIN=__ADMIN__, LINK=__LINK__;
const $=s=>document.querySelector(s);
let MYNAME='';try{MYNAME=localStorage.getItem('qdname')||'';}catch(e){}
let target={id:'host',label:'the shared folder'},peersData=[],lastPeers='',lastInbox='',prevInbox=null;
const el=(t,c,x)=>{const e=document.createElement(t);if(c)e.className=c;if(x!==undefined)e.textContent=x;return e;};
function fmt(n){const u=['B','KB','MB','GB'];let i=0;while(n>=1024&&i<3){n/=1024;i++;}return (i?n.toFixed(1):n)+' '+u[i];}
function icon(n){const e=n.split('.').pop().toLowerCase();
 if(['jpg','jpeg','png','gif','webp','heic','bmp'].includes(e))return '\\u{1F5BC}\\uFE0F';
 if(['mp4','mov','mkv','webm','avi','3gp'].includes(e))return '\\u{1F3AC}';
 if(['mp3','m4a','wav','ogg','flac','aac'].includes(e))return '\\u{1F3B5}';
 if(e==='pdf')return '\\u{1F4D5}';
 if(['zip','rar','7z','tar','gz'].includes(e))return '\\u{1F5DC}\\uFE0F';
 if(e==='apk')return '\\u{1F4E6}';
 if(['doc','docx','txt','odt','rtf'].includes(e))return '\\u{1F4DD}';
 if(['xls','xlsx','csv'].includes(e))return '\\u{1F4CA}';
 return '\\u{1F4CE}';}
function ago(s){if(s<5)return 'just now';if(s<60)return s+'s ago';if(s<3600)return Math.floor(s/60)+'m ago';return Math.floor(s/3600)+'h ago';}
function toast(m){const t=el('div','toast',m);$('#toasts').appendChild(t);
 setTimeout(()=>t.classList.add('show'),10);
 setTimeout(()=>{t.classList.remove('show');setTimeout(()=>t.remove(),300);},3000);}

/* ---- uploads with progress ---- */
const queue=[];let busy=false;
function addProgress(name){
 const row=el('div','prog'),top=el('div','prow'),n=el('span','pn',name),p=el('span','pp','0%');
 top.append(n,p);const bar=el('div','bar'),fill=el('div','fill');bar.appendChild(fill);
 row.append(top,bar);$('#progress').appendChild(row);
 return{set(f){fill.style.width=(f*100)+'%';p.textContent=Math.round(f*100)+'%';},
  done(){fill.style.width='100%';p.textContent='Done';setTimeout(()=>row.remove(),1500);},
  fail(){p.textContent='Failed';fill.style.background='var(--bad)';setTimeout(()=>row.remove(),4000);}};
}
function enqueue(files){for(const f of files)queue.push({f,to:target.id,label:target.label});pump();}
function pump(){
 if(busy||!queue.length)return;busy=true;
 const job=queue.shift(),f=job.f,row=addProgress(f.name+(job.to!=='host'?' \\u2192 '+job.label:'')),x=new XMLHttpRequest();
 x.open('POST','upload');x.setRequestHeader('X-Filename',encodeURIComponent(f.name));x.setRequestHeader('X-To',job.to);
 x.upload.onprogress=e=>{if(e.lengthComputable)row.set(e.loaded/e.total);};
 x.onload=()=>{if(x.status===200){row.done();toast(job.to==='host'?(ADMIN?'Added '+f.name:'Sent '+f.name):'Sent '+f.name+' to '+job.label);}else row.fail();busy=false;refresh();pump();};
 x.onerror=()=>{row.fail();busy=false;pump();};
 x.send(f);
}
const drop=$('#drop'),input=$('#f');
drop.onclick=()=>input.click();
input.onchange=()=>{enqueue(input.files);input.value='';};
['dragenter','dragover'].forEach(v=>drop.addEventListener(v,e=>{e.preventDefault();drop.classList.add('over');}));
['dragleave','drop'].forEach(v=>drop.addEventListener(v,e=>{e.preventDefault();drop.classList.remove('over');}));
drop.addEventListener('drop',e=>enqueue(e.dataTransfer.files));

/* ---- rendering ---- */
let lastFiles='',prevOnline=null,failures=0;
const MIME={jpg:'image/jpeg',jpeg:'image/jpeg',png:'image/png',gif:'image/gif',webp:'image/webp',
 heic:'image/heic',mp4:'video/mp4',mov:'video/quicktime',mkv:'video/x-matroska',webm:'video/webm',
 '3gp':'video/3gpp',mp3:'audio/mpeg',m4a:'audio/mp4',wav:'audio/wav',ogg:'audio/ogg',aac:'audio/aac',
 flac:'audio/flac',pdf:'application/pdf',txt:'text/plain',csv:'text/csv',zip:'application/zip',
 doc:'application/msword',docx:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
 xls:'application/vnd.ms-excel',xlsx:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'};
function shareButton(f,href){
 const b=el('button','btn ghost sm','Share');
 b.onclick=async()=>{
  const type=MIME[f.name.split('.').pop().toLowerCase()]||'application/octet-stream';
  if(f.size>150*1048576){toast('Too big to share this way. In WhatsApp use Document and pick it from the QuickDrop folder.');return;}
  if(!navigator.canShare({files:[new File([''],f.name,{type})]})){toast('Your browser cannot share this file type. Use the QuickDrop folder instead.');return;}
  b.disabled=true;toast('Getting '+f.name+' ready...');
  try{
   const blob=await (await fetch(href)).blob();
   await navigator.share({files:[new File([blob],f.name,{type})]});
  }catch(e){if(e.name!=='AbortError')toast('Could not share this file.');}
  b.disabled=false;};
 return b;
}
function renderFiles(files){
 const ul=$('#files');ul.replaceChildren();
 if(!files.length){ul.appendChild(el('div','empty','No files yet.'));return;}
 for(const f of files){
  const li=el('li','file'),m=el('div','fm'),a=el('a','',f.name);
  a.href='f/'+encodeURIComponent(f.name);
  m.append(a,el('span','',fmt(f.size)));
  li.append(el('div','fi',icon(f.name)),m);
  if(ADMIN){const b=el('button','btn danger sm','Delete');
   b.onclick=async()=>{if(!confirm('Delete '+f.name+'?'))return;
    await fetch('delete?name='+encodeURIComponent(f.name),{method:'POST'});refresh();};
   if(navigator.canShare)li.appendChild(shareButton(f,a.href));
   li.appendChild(b);}
  ul.appendChild(li);
 }
}
function renderDevices(ds){
 const box=$('#devices');box.replaceChildren();
 if(!ds.length){box.appendChild(el('div','empty','No devices yet. Scan the QR code on the other phone.'));return;}
 for(const d of ds){
  const r=el('div','dev'),i=el('div','di');
  i.append(el('div','dn',d.name),el('div','dm',d.ip+' \\u00B7 '+(d.online?'Connected':'Last seen '+ago(d.ago))));
  r.append(el('span','dot'+(d.online?' on':'')),i,el('div','ds','\\u2B06 '+d.up+'   \\u2B07 '+d.down));
  box.appendChild(r);
 }
 const now=new Set(ds.filter(d=>d.online).map(d=>d.ip));
 if(prevOnline)for(const d of ds){
  if(d.online&&!prevOnline.has(d.ip))toast(d.name+' connected');
  if(!d.online&&prevOnline.has(d.ip))toast(d.name+' disconnected');}
 prevOnline=now;
 const n=now.size,p=$('#pill');p.className='pill'+(n?' on':'');
 $('#pt').textContent=n?(n+(n===1?' device':' devices')+' connected'):'Waiting for a device';
}
function renderLog(items){
 const box=$('#log');box.replaceChildren();
 if(!items.length){box.appendChild(el('div','empty','Nothing yet.'));return;}
 for(const i of items){const r=el('div','lg');r.append(el('time','',i.t),el('span','',i.msg));box.appendChild(r);}
}
function updateDropLabel(){
 $('#droplbl').textContent=target.id==='host'?'Goes to the shared folder (everyone can download)':'Goes only to '+target.label;}
function renderChips(){
 const box=$('#targets');box.replaceChildren();
 const mk=(id,label,text)=>{const c=el('button','chip'+(target.id===id?' sel':''),text);
  c.onclick=()=>{target={id,label};renderChips();updateDropLabel();};box.appendChild(c);};
 mk('host','the shared folder','\\u{1F4C1} Shared folder');
 for(const p of peersData)mk(p.id,p.name,'\\u{1F4F1} '+p.name);
}
function renderTargets(peers){
 let force=false;
 if(target.id!=='host'&&!peers.some(p=>p.id===target.id)){target={id:'host',label:'the shared folder'};force=true;}
 const pj=JSON.stringify(peers);
 if(force||pj!==lastPeers){lastPeers=pj;peersData=peers;renderChips();updateDropLabel();}
}
function renderInbox(items){
 const j=JSON.stringify(items);
 if(j!==lastInbox){
  lastInbox=j;
  $('#inboxcard').style.display=items.length?'':'none';
  const ul=$('#inbox');ul.replaceChildren();
  for(const t of items){
   const li=el('li','file'),m=el('div','fm'),a=el('a','',t.name);
   a.href='t/'+encodeURIComponent(t.id);
   m.append(a,el('span','','from '+t.from+' \\u00B7 '+fmt(t.size)));
   const b=el('button','btn danger sm','Dismiss');
   b.onclick=async()=>{await fetch('dismiss?id='+encodeURIComponent(t.id),{method:'POST'});refresh();};
   li.append(el('div','fi',icon(t.name)),m,b);ul.appendChild(li);
  }
  if(prevInbox)for(const t of items)if(!prevInbox.has(t.id)){
   toast('New file from '+t.from+': '+t.name);if(navigator.vibrate)navigator.vibrate(120);}
  prevInbox=new Set(items.map(t=>t.id));
 }
}
function apply(s){
 renderTargets(s.peers);
 const fj=JSON.stringify(s.files);
 if(fj!==lastFiles){lastFiles=fj;renderFiles(s.files);}
 $('#fcount').textContent=s.files.length+(s.files.length===1?' file':' files');
 if(ADMIN){renderDevices(s.devices);renderLog(s.log);}
 else{$('#pill').className='pill on';$('#pt').textContent='Connected';
  $('#me').textContent=s.me.name||'this device';renderInbox(s.inbox);}
}
async function refresh(){
 try{const r=await fetch('state?n='+encodeURIComponent(MYNAME),{cache:'no-store'});apply(await r.json());failures=0;}
 catch(e){if(++failures>=2){$('#pill').className='pill';$('#pt').textContent=ADMIN?'Stopped':'Disconnected';}}
}
refresh();setInterval(refresh,ADMIN?2000:3000);

/* ---- speed test (other phone) ---- */
async function testSpeed(){
 const out=$('#speedout'),b=$('#speedbtn');b.disabled=true;
 try{
  out.textContent='Testing download...';
  const t0=performance.now(),r=await fetch('speed?mb=40',{cache:'no-store'}),rd=r.body.getReader();
  let got=0;for(;;){const x=await rd.read();if(x.done)break;got+=x.value.length;}
  const down=got/1048576/((performance.now()-t0)/1000);
  out.textContent='Download '+down.toFixed(1)+' MB/s. Testing upload...';
  const blob=new Blob([new Uint8Array(16*1048576)]);
  const up=await new Promise((res,rej)=>{const x=new XMLHttpRequest(),t1=performance.now();
   x.open('POST','speedup');x.onload=()=>res(16/((performance.now()-t1)/1000));x.onerror=rej;x.send(blob);});
  out.textContent='Download '+down.toFixed(1)+' MB/s \\u00B7 Upload '+up.toFixed(1)+' MB/s';
  fetch('report?down='+down.toFixed(1)+'&up='+up.toFixed(1),{method:'POST'});
 }catch(e){out.textContent='Test failed. Try again.';}
 b.disabled=false;
}
if(!ADMIN&&$('#speedbtn'))$('#speedbtn').onclick=testSpeed;

/* ---- rename this device (other phone) ---- */
if(!ADMIN&&$('#rename'))$('#rename').onclick=e=>{e.preventDefault();
 const n=prompt('Name this device (other devices will see it):',MYNAME||$('#me').textContent);
 if(n!==null){MYNAME=n.trim();try{localStorage.setItem('qdname',MYNAME);}catch(x){}refresh();}};

/* ---- admin extras ---- */
if(ADMIN){
 $('#copy').onclick=async()=>{try{await navigator.clipboard.writeText(LINK);toast('Link copied');}
  catch(e){prompt('Copy this link:',LINK);}};
 if(navigator.share)$('#share').onclick=()=>navigator.share({title:'QuickDrop',text:'Open this to share files with me:',url:LINK}).catch(()=>{});
 else $('#share').style.display='none';
 $('#clear').onclick=async()=>{if(!confirm('Delete ALL files in the QuickDrop folder?'))return;
  await fetch('clear',{method:'POST'});refresh();};
 $('#stop').onclick=async()=>{if(!confirm('Stop sharing?'))return;
  try{await fetch('stop',{method:'POST'});}catch(e){}
  document.body.innerHTML='<div style="padding:60px 20px;text-align:center;font-family:sans-serif"><h1>QuickDrop stopped</h1><p>You can close this page.</p></div>';};
}
"""

HEAD = ('<!doctype html><html><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        '<meta name="theme-color" content="#4f46e5"><title>QuickDrop</title>'
        '<style>__CSS__</style></head><body>')

DROP = ('<div class="chips" id="targets"></div>'
        '<div class="drop" id="drop"><b>Tap to choose files</b>'
        '<span id="droplbl">Goes to the shared folder</span></div>'
        '<input id="f" type="file" multiple hidden><div id="progress"></div>')

PUBLIC_PAGE = HEAD + """
<header><div class="hin"><div><h1>QuickDrop</h1><div class="sub">You are <b id="me">this device</b> &middot; <a href="#" id="rename" style="color:#fff">rename</a></div></div>
<div class="pill on" id="pill"><i></i><span id="pt">Connected</span></div></div></header>
<main>
<section class="card" id="inboxcard" style="display:none"><div class="ch"><h2>Sent to you</h2><span class="hint">from other devices</span></div><ul id="inbox"></ul></section>
<section class="card"><div class="ch"><h2>Send files</h2><span class="hint">to a device or the shared folder</span></div>__DROP__</section>
<section class="card"><div class="ch"><h2>Files on this phone</h2><span class="hint" id="fcount"></span></div>
<ul id="files"></ul><div class="hint" style="margin-top:8px">Tap a file to download it.</div></section>
<section class="card"><div class="ch"><h2>Speed test</h2><span class="hint">checks the connection</span></div>
<button class="btn ghost" id="speedbtn">Test speed</button>
<div class="hint" id="speedout" style="margin-top:10px"></div></section>
</main><div id="toasts"></div><script>__JS__</script></body></html>"""

ADMIN_PAGE = HEAD + """
<header><div class="hin"><div><h1>QuickDrop</h1><div class="sub">Control panel &middot; this phone</div></div>
<div class="pill" id="pill"><i></i><span id="pt">Waiting for a device</span></div></div></header>
<main>
<section class="card"><div class="ch"><h2>Connect a device</h2><span class="hint">same Wi-Fi or hotspot</span></div>
<div class="qrbox"><div class="qr">__QR__</div><div class="link">__URL__</div>
<div class="row"><button class="btn ghost sm" id="copy">Copy link</button>
<button class="btn ghost sm" id="share">Share link</button></div></div></section>

<section class="card"><div class="ch"><h2>Connected devices</h2><span class="hint">&#11014; sent to you &middot; &#11015; downloaded</span></div>
<div id="devices"></div></section>

<section class="card"><div class="ch"><h2>Send &amp; share</h2><span class="hint" id="fcount"></span></div>
__DROP__<ul id="files" style="margin-top:10px"></ul>
<div class="row"><button class="btn danger sm" id="clear">Delete all files</button></div></section>

<section class="card"><div class="ch"><h2>Activity</h2></div><div id="log"></div></section>
<div class="foot"><button class="btn danger" id="stop">Stop sharing</button></div>
</main><div id="toasts"></div><script>__JS__</script></body></html>"""


def render_page(admin):
    tpl = ADMIN_PAGE if admin else PUBLIC_PAGE
    js = JS.replace("__ADMIN__", "true" if admin else "false").replace(
        "__LINK__", json.dumps(SHARE_URL if admin else "")
    )
    page = tpl.replace("__CSS__", CSS).replace("__DROP__", DROP).replace("__JS__", js)
    if admin:
        qr = qr_svg(SHARE_URL) or '<div class="hint" style="padding:70px 0;text-align:center">QR unavailable</div>'
        page = page.replace("__QR__", qr).replace("__URL__", html.escape(SHARE_URL))
    return page.encode("utf-8")


# ------------------------------------------------------------------- server

CHUNK = 1 << 20  # 1 MB blocks for fast transfers


class Handler(BaseHTTPRequestHandler):
    server_version = "QuickDrop"

    def setup(self):
        super().setup()
        try:  # tune the connection for big transfers
            self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1 << 20)
            self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
        except OSError:
            pass

    def log_message(self, fmt, *args):
        pass  # the Activity feed in the control panel replaces console noise

    def _send(self, code, body=b"", ctype="text/html; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj):
        self._send(200, json.dumps(obj).encode(), "application/json")

    def _route(self):
        """(mode, rest): mode 'admin' / 'public' / None. rest False = missing slash."""
        parts = urlparse(self.path).path.lstrip("/").split("/", 1)
        rest = parts[1] if len(parts) > 1 else False
        if parts[0] == "admin" and is_local(self.client_address[0]):
            return "admin", rest
        if parts[0] == TOKEN:
            return "public", rest
        return None, None

    def _device(self, mode):
        if mode == "public":
            return touch(self.client_address[0], self.headers.get("User-Agent", ""),
                         self._query("n"))
        return None

    def _query(self, key, default=""):
        return parse_qs(urlparse(self.path).query).get(key, [default])[0]

    # ---- fast file sending (zero-copy) with resume support -------------
    def _range(self, size):
        m = re.match(r"bytes=(\d*)-(\d*)$", self.headers.get("Range", "").strip())
        if not m or not (m.group(1) or m.group(2)):
            return 0, size - 1, False
        if m.group(1):
            start = int(m.group(1))
            end = int(m.group(2)) if m.group(2) else size - 1
        else:
            start, end = max(0, size - int(m.group(2))), size - 1
        end = min(end, size - 1)
        if start > end:
            return None
        return start, end, True

    def _pump(self, f, offset, length):
        sock, sent, fast = self.connection, 0, hasattr(os, "sendfile")
        while sent < length:
            want = min(4 * CHUNK, length - sent)
            if fast:
                try:
                    n = os.sendfile(sock.fileno(), f.fileno(), offset + sent, want)
                except (BrokenPipeError, ConnectionResetError):
                    raise
                except OSError:
                    fast = False  # this phone can't do it: use the normal way
                    continue
            else:
                f.seek(offset + sent)
                data = f.read(want)
                if not data:
                    break
                self.wfile.write(data)
                n = len(data)
            if n == 0:
                break
            sent += n

    def _serve_file(self, path, dev, name=None, what=None):
        size = os.path.getsize(path)
        rng = self._range(size) if size else (0, -1, False)
        if rng is None:
            return self._send(416, b"", extra={"Content-Range": f"bytes */{size}"})
        start, end, partial = rng
        name = name or os.path.basename(path)
        if dev and start == 0:
            with LOCK:
                dev["down"] += 1
                log(f"{label_of(dev)} {what or 'downloaded ' + name}")
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{quote(name)}")
        self.end_headers()
        try:
            with open(path, "rb") as f:
                try:
                    os.posix_fadvise(f.fileno(), 0, 0, os.POSIX_FADV_SEQUENTIAL)
                except (AttributeError, OSError):
                    pass
                self._pump(f, start, end - start + 1)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass

    # ---- requests -------------------------------------------------------
    def do_GET(self):
        mode, rest = self._route()
        if mode is None:
            return self._send(403, b"Forbidden", "text/plain")
        if rest is False:
            return self._send(301, b"", extra={"Location": "/admin/" if mode == "admin" else f"/{TOKEN}/"})
        dev = self._device(mode)
        if rest == "":
            return self._send(200, render_page(mode == "admin"))
        if rest == "state":
            return self._json(build_state(mode == "admin", self.client_address[0]))
        if rest == "speed":  # speed test: streams zeros from memory (no disk)
            try:
                mb = min(200, max(1, int(self._query("mb", "20"))))
            except ValueError:
                mb = 20
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(mb * CHUNK))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            block = bytes(CHUNK)
            try:
                for _ in range(mb):
                    self.wfile.write(block)
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass
            return
        if rest.startswith("t/"):  # a file another device sent to this one
            with LOCK:
                t = TRANSFERS.get(unquote(rest[2:]))
            if not t or not os.path.isfile(t["path"]):
                return self._send(404, b"Not found", "text/plain")
            if not dev or dev["ip"] != t["to_ip"]:
                return self._send(403, b"Forbidden", "text/plain")
            return self._serve_file(t["path"], dev, t["name"],
                                    f"received {t['name']} from {t['from']}")
        if rest.startswith("f/"):
            path = os.path.join(FOLDER, clean_name(unquote(rest[2:])))
            if not os.path.isfile(path):
                return self._send(404, b"Not found", "text/plain")
            return self._serve_file(path, dev)
        self._send(404, b"Not found", "text/plain")

    def do_POST(self):
        mode, rest = self._route()
        if mode is None:
            return self._send(403, b"Forbidden", "text/plain")
        dev = self._device(mode)

        if rest == "speedup":  # speed test: read and throw away
            remaining = int(self.headers.get("Content-Length", 0))
            while remaining > 0:
                chunk = self.rfile.read(min(CHUNK, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
            return self._send(200, b"ok", "text/plain")

        if rest == "report" and dev:  # the other phone reports its speed test result
            try:
                down, up = float(self._query("down", "0")), float(self._query("up", "0"))
                log(f"{label_of(dev)} speed test: \u2B07 {down:.1f} MB/s, \u2B06 {up:.1f} MB/s")
            except ValueError:
                pass
            return self._send(200, b"ok", "text/plain")

        if rest == "dismiss" and dev:  # receiver clears a file sent to them
            tid = self._query("id")
            with LOCK:
                t = TRANSFERS.get(tid)
            if t and t["to_ip"] == dev["ip"]:
                drop_transfer(tid)
            return self._send(200, b"ok", "text/plain")

        if rest == "upload":
            to = self.headers.get("X-To", "host")
            target = None
            if to != "host":  # direct send to one device
                with LOCK:
                    target = next((d for d in DEVICES.values() if d["id"] == to), None)
                if target is None or target is dev:
                    return self._send(404, b"device not found", "text/plain")
            remaining = int(self.headers.get("Content-Length", 0))
            name = clean_name(unquote(self.headers.get("X-Filename", "upload")))
            tid = secrets.token_hex(6)
            final = os.path.join(DIRECT, f"{tid}_{name}") if target else unique_path(name)
            temp = os.path.join(os.path.dirname(final), "." + os.path.basename(final) + ".part")
            ok = False
            try:
                with open(temp, "wb") as out:
                    while remaining > 0:
                        chunk = self.rfile.read(min(CHUNK, remaining))
                        if not chunk:
                            break
                        out.write(chunk)
                        remaining -= len(chunk)
                ok = remaining == 0
                if ok:
                    os.replace(temp, final)
            finally:
                if not ok and os.path.exists(temp):
                    os.remove(temp)
            if not ok:
                return self._send(400, b"incomplete", "text/plain")
            with LOCK:
                who = label_of(dev) if dev else "This phone"
                if dev:
                    dev["up"] += 1
                if target:
                    TRANSFERS[tid] = {
                        "id": tid, "from": who, "to_ip": target["ip"], "name": name,
                        "path": final, "size": os.path.getsize(final), "time": time.time(),
                    }
                    log(f"{who} sent {name} to {label_of(target)}")
                else:
                    log(f"{label_of(dev) if dev else 'You'} {'sent' if dev else 'added'} {os.path.basename(final)}")
            if not target:
                export_to_selected_folder(FOLDER, os.path.basename(final))
            return self._send(200, b"ok", "text/plain")

        if mode == "admin" and rest and rest.startswith("delete"):
            path = os.path.join(FOLDER, clean_name(self._query("name")))
            if os.path.isfile(path):
                os.remove(path)
                delete_from_selected_folder(os.path.basename(path))
                log(f"You deleted {os.path.basename(path)}")
            return self._send(200, b"ok", "text/plain")

        if mode == "admin" and rest == "clear":
            count = 0
            for f in list_files():
                try:
                    os.remove(os.path.join(FOLDER, f["name"]))
                    delete_from_selected_folder(f["name"])
                    count += 1
                except OSError:
                    pass
            log(f"You deleted all files ({count})")
            return self._send(200, b"ok", "text/plain")

        if mode == "admin" and rest == "stop":
            self._send(200, b"bye", "text/plain")
            try:
                from java import jclass
                jclass("com.quickdrop.app.StorageBridge").requestStop()
            except Exception:
                threading.Thread(target=stop_server, daemon=True).start()
            return

        self._send(404, b"Not found", "text/plain")


def make_server():
    for port in range(START_PORT, START_PORT + 20):
        try:
            return ThreadingHTTPServer(("0.0.0.0", port), Handler), port
        except OSError:
            continue
    raise SystemExit("Could not find a free port. Close another app using ports 8080–8099 and try again.")


SERVER = None
SERVER_PORT = None
SERVER_THREAD = None


def start_server():
    """Start the embedded HTTP server and return its URLs and chosen folder."""
    global SHARE_URL, SERVER, SERVER_PORT, SERVER_THREAD, TOKEN
    if SERVER is not None:
        return server_info()
    TOKEN = secrets.token_urlsafe(6)
    server, port = make_server()
    reset_direct()
    ip = local_ip()
    SHARE_URL = f"http://{ip}:{port}/{TOKEN}/"
    SERVER, SERVER_PORT = server, port
    SERVER_THREAD = threading.Thread(target=server.serve_forever, name="QuickDropHTTP", daemon=True)
    SERVER_THREAD.start()
    return server_info()


def server_info():
    if SERVER is None:
        return {"running": False}
    ip = local_ip()
    return {
        "running": True,
        "port": SERVER_PORT,
        "ip": ip,
        "share_url": SHARE_URL,
        "admin_url": f"http://127.0.0.1:{SERVER_PORT}/admin/",
        "folder": FOLDER,
    }


def stop_server():
    """Stop the HTTP server cleanly. Safe to call more than once."""
    global SERVER, SERVER_PORT, SERVER_THREAD
    server = SERVER
    if server is None:
        return
    SERVER = None
    SERVER_PORT = None
    thread = SERVER_THREAD
    SERVER_THREAD = None
    try:
        server.shutdown()
        server.server_close()
    finally:
        shutil.rmtree(DIRECT, ignore_errors=True)
    if thread and thread.is_alive():
        thread.join(timeout=2)


if __name__ == "__main__":
    info = start_server()
    print("QuickDrop is running:", info)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        stop_server()
