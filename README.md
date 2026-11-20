<<<<<<< HEAD
# QuickDrop-Android
File transfer
=======
# QuickDrop for Android

This is the Android Studio project for packaging the existing QuickDrop v3 Python file-sharing server as an installable Android app. It keeps the original Python/HTML/JavaScript sharing interface and embeds Python with Chaquopy.

## Included in this first Android build

- QuickDrop app name and launcher icon.
- Embedded Python HTTP server; Termux is not required after installing the APK.
- Start and Stop controls in the Android app.
- Foreground notification while sharing is active, including a Stop action.
- Optional background mode. If **Keep sharing after leaving QuickDrop** is off, swiping QuickDrop away from Android Recents stops the server. If on, the foreground service continues until manually stopped.
- Android's folder picker (Storage Access Framework). The selected folder is imported when the server starts, and new uploads/deletions are mirrored back to it. The default when no folder is selected is the app's private `QuickDrop` folder.
- The original web control panel, QR code, uploads, downloads, device activity, direct transfers, and speed test are retained.
- GitHub Actions workflow which builds an installable debug APK and stores it as a workflow artifact.

## Build the APK using GitHub Actions

You do not need to install Android Studio on your phone. GitHub's cloud runner builds the APK.

1. On GitHub, create a new repository named `QuickDrop-Android`.
2. Extract this project ZIP on your computer. The folder you upload must contain `settings.gradle`, `build.gradle`, `app/`, and `.github/` at its top level.
3. Upload the project files to the repository. You can use GitHub's **Add file → Upload files** page, or push the folder using Git.
4. Open the repository's **Actions** tab.
5. Select **Build QuickDrop APK** from the workflow list.
6. Press **Run workflow**. The first build can take several minutes because Android and Python components must be downloaded.
7. When the run finishes successfully, open that workflow run and scroll to **Artifacts**.
8. Download `QuickDrop-debug-apk`, extract it, and install `app-debug.apk` on your Android phone. If Android asks, allow installation from the browser or file manager you used to download it.

The workflow also runs automatically when you push changes to the `main` or `master` branch.

## First launch

1. Open QuickDrop.
2. Optionally tap **Choose a folder** and grant access to a dedicated folder (for example, `Downloads/QuickDrop`) where you want shared files saved. You can choose a folder in internal storage or an SD card if Android exposes it in the picker.
3. Decide whether sharing should continue after leaving the app. Leave the checkbox off if you want swiping the app away from Recents to stop the server.
4. Tap **Start sharing** and allow notifications when Android asks.
5. Tap **Open QuickDrop control panel**. The page shows the QR code and share link for the other device.
6. Connect both devices to the same Wi-Fi network or connect the other device to your phone's hotspot. Open the share link or scan the QR code on the other device.
7. Tap **Stop sharing** in the app or use **Stop** in the persistent notification when finished.

## Important storage behaviour

Android does not give an app unrestricted filesystem access. QuickDrop therefore uses a private working folder and the Android folder-picker permission. On server start, existing files in the chosen folder are copied into QuickDrop's working folder. Files received by QuickDrop and file deletions are mirrored to the chosen folder. This initial version mirrors files at the top level of the selected folder; it does not traverse nested subfolders.

If you change the selected folder while the server is running, stop and start the server again to apply the new folder.

## Technical details

- Package: `com.quickdrop.app`
- Minimum Android: API 24
- Python runtime: 3.13 via Chaquopy 17.2.0
- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- Target SDK: 34
- ABI: `arm64-v8a` (appropriate for modern 64-bit Android phones)

## Known first-build limitations

- This project has been scaffolded for the Android runtime, but the APK still needs to be built and tested on the actual phone. GitHub Actions is the build step; a successful workflow is required before an APK exists.
- Android battery optimization and some phone manufacturers' background restrictions may still stop long-running services. For longer background sessions, allow QuickDrop to run unrestricted in the phone's battery/app settings.
- The selected-folder integration mirrors top-level files only. Files in subfolders are not included in the QuickDrop file list.
- The app serves over local HTTP on the local network, as the original Termux version did. Use it only on a trusted Wi-Fi/hotspot network and stop sharing when finished. The random share-token URL is intended to limit access, but it is not a substitute for encrypted HTTPS.
>>>>>>> 77445e7 (Initial QuickDrop Android app)


## Stage 1.5 — Native Wi-Fi Direct (initial transfer implementation)

This stage adds Android Wi-Fi Direct discovery and pairing using `WifiP2pManager`, plus a direct TCP file-transfer channel. Both Android phones must have QuickDrop installed. The existing hotspot + QR/browser workflow and Android Sharesheet remain separate and unchanged.

### First test
1. Install the same Stage 1.5 APK on two Android phones.
2. Enable Wi-Fi on both phones. Keep QuickDrop open on both. On Android 13+, allow **Nearby devices** when asked. On Android 12 and earlier, allow location permission. If discovery returns no devices, also turn the phone's Location setting on and scan again.
3. On one phone, tap **Find nearby QuickDrop devices** and select the other phone when it appears. Accept any Android connection prompt.
4. Android chooses one phone as the Wi-Fi Direct group owner. In this initial implementation, the group owner receives; the other phone selects files and sends them using **Send files via Wi-Fi Direct**.
5. Received files are saved in QuickDrop's storage folder. If a shared folder was selected in QuickDrop settings, the received file is also copied there.

### Current limitation
This is the first Wi-Fi Direct transfer pass and currently supports transfer from the non-group-owner phone to the group-owner phone. Android chooses the group owner during connection negotiation; the app does not yet let users choose that role. Bidirectional transfer, richer transfer progress, cancellation, and integrity verification are planned follow-up work. If a phone does not support Wi-Fi Direct, the existing browser and Sharesheet methods remain available.


## Stage 1.8 – Wi-Fi Direct send/receive interface

- Replaced the single Wi-Fi Direct action with equal-width **Send** and **Receive** buttons.
- Added a labelled nearby-device card: **Sending to nearby devices** or **Sharing to you**.
- Nearby Android devices appear as selectable device rows inside the card.
- The status reads **looking for devices** in Send mode and **receiving ready** in Receive mode while discovery is active.
- Added a slow looping QuickDrop water-drop animation with a small ripple pulse. It stops when a peer is found, a connection is formed, or a file is received.
- Wi-Fi Direct connection requests now use a group-owner preference based on the selected mode; Android still controls the final group-owner negotiation.



## Stage 1.11 — QuickDrop Direct handshake (connection validation)

This stage adds a separate QDD v1 handshake on TCP port 8989 after Android reports that the Wi-Fi Direct group has formed. The group owner listens; the group client connects to the group-owner address. Both sides exchange a protocol marker, device identity/name and an acceptance response before the app reports the QuickDrop connection as verified. The existing file-transfer channel on port 8988 is intentionally unchanged in this step; bidirectional transfer will be redesigned after handshake reliability is tested.

## Stage 1.12 — Native local-hotspot connection (initial connection test)

- Added a separate **Native Hotspot Sharing** section with **Create Connection**, **Connect to Sender**, and **Disconnect** controls.
- Sender uses Android `LocalOnlyHotspot`; receiver uses Android `WifiNetworkSpecifier` (Android 10+).
- The phones perform a small QuickDrop protocol handshake on TCP port 8989 before showing the connection as verified.
- This is separate from the existing browser server and Wi-Fi Direct implementation. Wi-Fi Direct source and controls are intentionally unchanged.
- This stage validates the native connection only. Existing Android Sharesheet imports still behave as before; automatic native file transmission will be implemented after connection testing is successful.
- Receiver currently enters the sender's hotspot SSID/password manually. Android displays its own approval prompt when joining.
- Android 8–9 can create a local hotspot but this first receiver implementation requires Android 10 or later.

Testing note: build with the repository's GitHub Actions workflow. A successful CI build does not by itself verify that two physical phones can establish the local-only hotspot connection; test on two Android devices.

## Stage 1.13 — Native Hotspot File Transfer and QR

This stage extends the separate native Android-to-Android hotspot connection. Wi-Fi Direct and the existing browser-sharing server remain separate and are not changed by this feature.

- Once connected, either phone can use **Choose Files to Send** to select one or multiple files through Android's document picker.
- Files received over the native link are saved in QuickDrop's app-private `QuickDrop` folder and mirrored to the selected shared folder when one is configured.
- Android Sharesheet imports are sent directly to the connected QuickDrop peer; when no native peer is connected, the existing local import behavior is retained.
- The sender can generate a QR code containing the hotspot name, password, address, and native transfer port. A QR reader can display those details; the receiver currently enters the SSID/password in **Connect to Sender**.
- Native transfer protocol version is now 2. Both phones must run this version for native file transfer. Browser Sharing and Wi-Fi Direct remain independent.

Initial limits: up to 100 files per selection and 2 GiB per file. Test on real devices for large files, interrupted transfers, reconnects, and selected-folder mirroring before relying on it for important data.
