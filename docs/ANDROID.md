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

Open **Home → Find my NAS** or **Sources → Add NAS Source**. The app searches
for SMB services advertised through Bonjour/mDNS for ten seconds. Selecting a
device fills its address and port and tries to list shared folders as a guest.
If access fails, enter your NAS username and password in the sign-in fields and
retry **Find shared folders**. Choose a share, browse to your movie folder, then
select **Use this folder & scan**. Subfolders are included in the scan.

If discovery returns no devices, choose **Enter NAS address** and provide the
NAS hostname or LAN address (not `localhost`). Port 445 is the SMB default.
**Enter share** supports NAS accounts that can access a known share but cannot
list all shares. Existing sources retain the manual editor and connection test.

Discovery requires the NAS to advertise `_smb._tcp` and the network to carry
those announcements. An emulator can reach the NAS by address while discovery
returns nothing; it uses a virtual network. This is not evidence that the NAS
is offline. A physical TV must also share a reachable network with the NAS.

Credentials stay in memory during setup and are saved encrypted only when you
save the source. Do not include passwords in issue reports or screenshots.

TV remote/keyboard navigation uses arrows and Enter. Buttons, tabs, and movie
cards also support pointer taps. For mouse testing, the TV emulator must expose
a touch screen; a `no-touch` virtual device will not forward taps to the app.

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

The development build passes 43 JVM tests and builds the debug APK. Lint
reports no errors and three existing warnings: `VectorRaster`,
`DataExtractionRules`, and `ObsoleteSdkInt`.

The API 36 Android TV emulator installs and launches the app, and direct TCP
access to a local NAS on port 445 was verified. Live pointer testing was
interrupted before completion. NAS authentication, folder browsing, playback,
physical-TV navigation, and physical-network discovery still need validation.

Google Play billing and the commercial trial policy are not implemented for
Android. Google Play account/product setup, internal testing, and store
submission also remain pending. Community and debug builds do not enforce the
commercial trial or purchase rules.
