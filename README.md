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

