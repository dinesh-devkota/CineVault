# Android TV

CineVault reads movies directly from SMB shares without a media server or
transcoding. The development build includes library browsing, search, sources,
movie details, TMDB metadata, playback, and library editing.

## Build and install

Install JDK 17 or later, Android SDK Platform 36, and Build-Tools 35.0.0. Set
`JAVA_HOME` and `ANDROID_HOME` for your installation. From the repository root
in PowerShell:

```powershell
Set-Location android
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`, relative to the
repository root. Alternatively, download and unzip the
`cinevault-androidtv-debug` artifact from the **Android TV** GitHub Actions workflow.

Connect an Android TV device or TV emulator running Android 8.0/API 26 or later.
Enable debugging and accept the connection prompt. With Android SDK
Platform-Tools available, run from the repository root:

```powershell
adb devices
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

For a downloaded artifact, replace the APK path with its extracted location.
Open CineVault from the TV launcher. The device or emulator must be able to reach
the NAS; enter its hostname or LAN address, not `localhost`.

## Connect a NAS

Open **Add NAS** and provide:

- **Name:** a label for this source.
- **Host or IP:** the NAS hostname or LAN address.
- **Port:** leave blank for SMB's default port, 445.
- **Username** and **Password:** preferably a read-only NAS account. An empty
  username selects guest access.
- **Share:** the shared folder name, such as `media`.
- **Folder inside share:** an optional subfolder, such as `Movies`.

Use **Test Connection**, then **Save & Scan**. Credentials are stored encrypted.
Do not include passwords in issue reports or screenshots.

## First playback test

Use a small share containing media supported by the target device. An H.264/AAC
MP4 is a useful starting point.

1. Scan the source and confirm the expected movies appear.
2. Using only the TV remote, visit the library, search, sources, and movie
   details. Check that actions are reachable and focus remains visible.
3. Play a movie, pause, and seek backward and forward. The seek steps are
   10 seconds backward and 30 seconds forward. The first Back press hides
   visible player controls; the next exits playback.
4. Return to movie details and check Resume and Start Over. Progress is saved
   every 10 seconds while playing and when pausing or exiting. Confirm the
   watched state updates after playback passes 92% of the duration.
5. Try a wrong source password and an unavailable NAS. Check that failures are
   visible and that the app works again after restoring access.
6. Test a title edit, a TMDB match using your own TMDB API key, and a progress
   reset. Rescan and inspect the resulting library state.

Playback depends on the device's codecs. Supported file extensions are MP4,
M4V, MOV, MKV, WebM, AVI, TS, M2TS, MPG, MPEG, and FLV. WMV and ISO files can
appear in the library but cannot be played. A supported extension does not
guarantee support for its audio and video codecs.

Report failures with the device model, Android version, app commit, NAS type,
container/codecs, exact action, and displayed error. Remove credentials and
private media names from diagnostics.

## Release bundles

From `android`, create an unsigned Android App Bundle:

```powershell
.\gradlew.bat :app:bundleRelease
```

The output is `android/app/build/outputs/bundle/release/app-release.aab`.
Android CI uploads it as `cinevault-androidtv-community-unsigned`.
An unsigned bundle is not ready for Google Play distribution.

For a signed bundle, securely configure these environment variables:

- `CINEVAULT_KEYSTORE_PATH` (prefer an absolute path; relative paths use `android/app`)
- `CINEVAULT_STORE_PASSWORD`
- `CINEVAULT_KEY_ALIAS`
- `CINEVAULT_KEY_PASSWORD`

Then explicitly enable signing:

```powershell
.\gradlew.bat :app:bundleRelease -Pcinevault.releaseSigning=true
```

Keep the keystore and passwords outside the repository. Requested signing fails
when its required configuration is missing. Environment variables alone never
enable signing. Building a signed bundle does not upload it to Google Play or
enable billing.

## Validation and remaining work

The current development changes pass 67 JVM tests (38 core and 29 app), build
debug and release APKs, and produce an unsigned release bundle. Debug and release
lint report no errors and three existing warnings: `VectorRaster`,
`DataExtractionRules`, and `ObsoleteSdkInt`.

The **Android TV** workflow also runs an Android TV API 36 emulator with a
synthetic SMB share. Its runtime suite covers app launch, remote navigation,
Watchlist and saved filters, database upgrades, encrypted credentials, and video
playback and seeking over SMB. Check the workflow result for the revision being
installed; builds and JVM tests alone do not validate these runtime behaviors.
See [TESTING.md](TESTING.md) for coverage and local emulator instructions.

Physical Android TV hardware, real-NAS interoperability, and device-specific
codec compatibility still need testing.

Google Play billing and the commercial trial policy are not implemented for
Android. Google Play account/product setup, internal testing, and store
submission also remain pending. Community and debug builds do not enforce the
commercial trial or purchase rules.
