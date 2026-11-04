# Validation and regression coverage

Run the portable tests and Android build checks from the repository checkout:

```bash
swift test --package-path Packages/CineVaultCore
python3 -B -m unittest discover -s scripts/tests -p 'test_commercial_*.py'
cd android
./gradlew --no-daemon --max-workers=4 :core:test :app:testDebugUnitTest \
  :app:lintDebug :app:assembleDebug :app:lintRelease :app:assembleRelease
```

The managed Linux environment has cache and tool activation instructions in
[CLOUD-DEVELOPMENT.md](CLOUD-DEVELOPMENT.md); use its Swift wrapper there.

## Tests requiring an Android runtime

`android/app/src/androidTest` runs against actual Android APIs. It covers:

- D-pad access to all five tabs, Back navigation, and activity recreation.
- Android Keystore encryption, unique nonces, and corrupt credential errors.
- Room foreign-key deletion and metadata updates preserving manual titles and progress.
- Delayed metadata responses racing with manual matches and resets.
- NAS source edits/deletion while a scan is suspended.
- Media3 DataSource byte-range/EOF behavior and cleanup after a failed open.
- Authenticated SMB scans, range reads, bad credentials, and reconnection.

The `Development validation` workflow uses an Android TV API 36 x86_64 emulator
and a synthetic SMB share. The fixture has no access to a user's NAS or secrets.
For an emulator on your machine:

```bash
python3 -m pip install -r scripts/nas-test-requirements.txt
python3 scripts/nas_fixture.py /tmp/cinevault-nas-fixture
# In another terminal, with the Android TV emulator running:
cd android
./gradlew --no-daemon --max-workers=4 :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.nasHost=10.0.2.2
```

`10.0.2.2` reaches the emulator host's loopback interface. SMB-only tests skip when
`nasHost` is omitted during a manual device run; CI requires every runtime suite
to execute with no failures or skips. Reports are uploaded as
`cinevault-android-runtime-tests`.

## Apple validation

The public workflows build the tvOS app on macOS, run XCTest with the synthetic
SMB fixture, and check screenshots for stable Home/detail content. Regression
tests cover invalidated connections, cancelled scans, byte-range validation,
late playback callbacks, concurrent manual/automatic matches, and metadata reset.

## Further device testing

Automated checks do not establish compatibility with every NAS or codec. Before
a release, test real Synology/QNAP/Windows shares, disconnect/reconnect during
playback, seeking in large files, subtitle/audio selection, and remote focus
behavior on real Apple TV and Android TV hardware. Android Media3 playback/seek
tests with actual video and tvOS remote-navigation UI tests would further extend
the current stream and screenshot checks. The private commercial package and
store purchases/signing have their own protected validation workflow.

## Online sources consulted

- [AndroidX Media3 DataSource contract](https://github.com/androidx/media/blob/release/libraries/datasource/src/main/java/androidx/media3/datasource/DataSource.java): bounded requests, EOF, and failed-open cleanup.
- [Official Media3 player sample](https://github.com/androidx/media/blob/release/demos/main/src/main/java/androidx/media3/demo/main/PlayerActivity.java): lifecycle state and playback restoration.
- [Google Room test sample](https://github.com/android/architecture-components-samples/blob/main/BasicSample/app/src/androidTest/java/com/example/android/persistence/db/ProductDaoTest.java): database tests on an Android runtime.
- [Google UI Automator sample](https://github.com/android/testing-samples/blob/main/ui/uiautomator/BasicSample/app/src/androidTest/java/com/example/android/testing/uiautomator/BasicSample/ChangeTextBehaviorTest.java): device-level UI testing.
- [Kotlin cancellation guidance](https://github.com/Kotlin/kotlinx.coroutines/blob/master/docs/topics/coroutines-cancellation.md): cooperative cancellation and resource cleanup.
- [Android TV SDK image catalog](https://dl.google.com/android/repository/sys-img/android-tv/sys-img2-1.xml) and [emulator runner](https://github.com/ReactiveCircus/android-emulator-runner): available TV images and CI setup.
