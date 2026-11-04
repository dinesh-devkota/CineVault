# Development in the managed Linux environment

Read [the agent handoff](../dist/AGENT-HANDOFF.md) before continuing development.
The Android TV app and handoff are on
`dinesh-devkota-sonepar-build-cinevault-tvos-app`; `main` currently contains the
tvOS app. Use the existing checkout at `/workspace/CineVault`. Cloud tasks already
have isolated environments; do not create a Git worktree unless requested.

## Prepared tools

The environment stores tools and setup helpers outside the checkout, under
`/workspace/.cinevault-env`:

- Temurin JDK 17; the repository's checksum-pinned Gradle 8.14.3 wrapper.
- Android SDK platform 36, Build Tools 35.0.0, command-line tools, and `adb`.
- Swift 6.2.1 for the `CineVaultCore` package, with writable package and Clang
  caches and a four-job build limit.

These helpers are supplied by the cloud environment's saved installation script
and, after publication, its restored filesystem snapshot. They are not included
in a fresh Git clone. The saved installation script creates `install-jdk.sh`, `install-android-sdk.sh`,
`install-swift.sh`, `activate.sh`, and `swift-core.sh` in that directory.

## Start a session

Refresh Java's trust store from the current cloud machine's configured CA
certificates, then activate the tools:

```bash
bash /workspace/.cinevault-env/install-jdk.sh
source /workspace/.cinevault-env/activate.sh
cd /workspace/CineVault
```

`activate.sh` sets the JDK, SDK, Gradle cache, and tool paths. It also configures
Java to use the inherited cloud proxy and local trust store. Keep the inherited
proxy and TLS verification enabled. Processes and session credentials must be
initialized again after the environment is restored.

## Build and test

Run the Android JVM tests, lint, and debug APK build:

```bash
cd /workspace/CineVault/android
./gradlew --no-daemon --max-workers=4 \
  :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
Test reports are under each module's `build/reports/tests/`; the lint report is
under `android/app/build/reports/`.

Build and test the portable Swift package through its cache-configured wrapper:

```bash
/workspace/.cinevault-env/swift-core.sh build
/workspace/.cinevault-env/swift-core.sh test
```

Run the public Python build-helper tests:

```bash
cd /workspace/CineVault
python3 -B -m unittest discover -s scripts/tests -p 'test_commercial_*.py'
```

To refresh installed tools, run the saved installers in order:

```bash
bash /workspace/.cinevault-env/install-jdk.sh
bash /workspace/.cinevault-env/install-android-sdk.sh
bash /workspace/.cinevault-env/install-swift.sh
source /workspace/.cinevault-env/activate.sh
```

The installers preserve checksum or release-signature verification. Generated
build outputs and caches are local setup artifacts; leave tracked source,
dependency declarations, and lockfiles unchanged during environment setup.

## Validation during onboarding

On this Linux instance, the Android workflow passed 41 JVM tests (28 core and
13 app), lint with zero errors and three warnings (`VectorRaster`,
`DataExtractionRules`, `ObsoleteSdkInt`), and the debug APK build. The generated
APK's signature was verified. Swift debug and release builds and all 14 XCTest
tests passed. All five Python helper tests passed.

## Platform limits

This build environment does not include an Android emulator. Building an APK and
passing JVM tests do not verify playback, TV controls, or access to a real NAS.

The full tvOS app, Apple TV simulator, Keychain, and playback integration tests
require macOS with Xcode. Use the repository's existing GitHub Actions workflows
or a Mac for those checks. The commercial simulator regression described in the
handoff is historical work that this environment setup does not fix or revalidate.
Private commercial code, signing, and store publication require their separate
documented setup and authorization.
