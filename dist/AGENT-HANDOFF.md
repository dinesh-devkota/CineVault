# CineVault — agent handoff (2026-11-01)

A snapshot written by the previous Copilot agent. Check the live state (`git log` and the Actions tab) before acting on it.

**Latest code:** branch `dinesh-devkota-sonepar-build-cinevault-tvos-app`, which is `main` (`5fdc02d`) plus 6 Android TV commits plus this file.
- `main` contains neither the Android work nor this file. Merging requires the owner's approval.
- The repository has no pull requests.
- The working tree was clean at handoff (nothing uncommitted, untracked, or stashed). This commit adds only this file.

## Goal

Original request (verbatim): "Build a plex like app to range and manage movies but that loads the media files directly from network location like NAS for apple TV"

Follow-ups (verbatim): "start andriod tv version as well" and "I am stepping out and you have permission to build everything."

## Current state

| Area | Where | State | Evidence |
|---|---|---|---|
| tvOS app (community build) | `main` `5fdc02d` | All requested features implemented | CI run 41150270899 and NAS integration run 41150270974 passed |
| tvOS official (commercial) build | `main` + pinned private package | 12 of 13 simulator tests pass; one regression | Commercial build run 41156608641 failed |
| Android TV app | branch `5c5bc12` | Stages 1–6 of 7 done; Stage 7 not started | Android TV run 41339324494 passed |
| Devices and stores | — | Never tested on a real Apple TV, Android TV, or NAS. Nothing uploaded to TestFlight, the App Store, or Google Play | — |

## Owner decisions (do not re-ask)

- **No server:** the apps read the NAS directly over SMB. The app plays only movies from the NAS; YouTube support was declined.
- **No Mac:** the owner uses Windows. Apple builds and tests run on GitHub macOS runners, and the app is installed through TestFlight.
- **Style:** an Infuse-inspired UI; small commits, one per stage; commits and pushes as `dinesh-devkota`.
- **Official build pricing:** about US$1/month or US$12 lifetime through StoreKit, with usage tracked locally.
  - Three trial plays: plays 1–2 are unrestricted, and play 3 stops at 90%.
  - Resume continues the same play; Start Over uses a new one.
- **Commercial code:** lives in the private repo `dinesh-devkota/CineVaultCommercial`.
  - Developer access is granted manually after payment.
  - There is no CI in the private repo. Official builds run through the public `commercial.yml` with a pinned private SHA.
  - The community build must work without the private package.
- **Authorizations:**
  - Unsigned commercial validation runs are allowed.
  - TestFlight uploads are **not** authorized without the owner's explicit approval.
  - Do not push or merge to `main` without explicit approval. The owner did approve fast-forwarding `main` to `5fdc02d`.
- **Repo:** stays public. Once Android is on `main`, update the repo description to mention Android.

## Architecture

### tvOS (`main`)
- The project is defined in XcodeGen `project.yml`.
- `CineVault/` is a SwiftUI app using SwiftData and AVKit.
  - SMB access uses AMSMB2, a dynamic framework because it is LGPL.
  - An AVPlayer resource loader streams SMB byte ranges.
  - Passwords are stored in the Keychain; TMDB uses the user's own key.
  - Plays MP4, M4V, and MOV only.
- `Packages/CineVaultCore` holds the pure logic and its tests.
- `CineVaultTests/` includes tests against a local SMB test server (`scripts/nas_fixture.py`).
- The commercial build uses the `PlaybackServices` interface, the `CINEVAULT_COMMERCIAL` flag, and the `project.commercial.yml` overlay. `fastlane/Fastfile` handles signing.

### Android TV (`android/`, on the branch)
- Gradle project: AGP 8.13.2, Kotlin 2.2.21, JVM 17, minSdk 26, targetSdk 36. App ID is `com.dineshdevkota.cinevault`.
- `:core` is a pure-Kotlin port of the core logic: parser, library rules, TMDB helpers, scan diff, and edit rules.
- `:app` uses Compose for TV:
  - Room database, with secrets protected by the Android Keystore.
  - smbj for SMB, using the BouncyCastle provider; a library scanner.
  - OkHttp TMDB client.
  - Media3 ExoPlayer with an SMB data source. Resume position is saved every 10 s, and a movie counts as watched past 92%.
  - An edit screen.
- Plays mp4, m4v, mov, mkv, webm, avi, ts, m2ts, mpg, mpeg, and flv, depending on device codecs. wmv and iso files are listed in the library but cannot be played.
- Release minification is off because of smbj.

### Private module (high level)
StoreKit purchases plus the trial and usage rules for the official tvOS app. Never copy its source, tokens, or diagnostics into this repo.

### Workflows
- `ci.yml` and `nas-integration.yml` run on macOS for pushes to `main`, pull requests, and manual runs.
- `ui-preview.yml` runs manually.
- `commercial.yml` runs manually in the protected `commercial` environment.
- `android.yml` runs on Ubuntu for `android/**` changes or manually, and uploads the debug APK.

## Android stages (each passed CI)

| Stage | Commit | Contents | CI run |
|---|---|---|---|
| 1 | `a2ed045` | Gradle setup, `:core` port and tests, Android CI | 41152456391 |
| 2 | `865b56f` | Room, Keystore secrets, smbj service, scanner | 41153520674 |
| 3 | `1e3af13` | TV UI: tabs, movie detail, source editor | 41154615716 |
| 4 | `f9aa1a8` | ExoPlayer SMB playback, resume and watched state | 41156077485 |
| 5 | `973face` | TMDB metadata | 41338625536 |
| 6 | `5c5bc12` | Edit screen: rename, fix match, reset progress, reset to file name | 41339324494 |

## Exact last activity

1. Pushed Android Stage 6 (`5c5bc12`), and CI passed. The local build ran 41 JVM tests, reported only the 3 known lint warnings, and built the APK.
2. Started a read-only investigation of commercial run 41156608641, which used private pin `5f1a48a`.
   - Package tests passed 21/21 and the unsigned build succeeded.
   - Simulator tests passed 12/13. The failure was the slow-App-Store-catalog playback test, which timed out after 20 s waiting for the catalog request. It passed with the previous pin, `d36c8e3`.
   - Unconfirmed hypothesis: at launch, the app refresh waits on entitlement verification before requesting the catalog. The new retry for stalled entitlement reads may keep that wait running under StoreKitTest.
   - No fix was sent, and no private changes were made.
3. Stage 7 was not started. The owner then asked for this handoff.

## Not committed (by design)
- Build caches and outputs: `android/**/build`, `.gradle`, `.kotlin`.
- The debug APK. Download the `cinevault-androidtv-debug` artifact from run 41339324494 instead.
- `local.properties`, keystores, Apple signing files, and the generated `.xcodeproj`.
- The private module checkout (`/commercial/`).
- The previous agent's local session files: screenshots, diagnostics, a temporary diagnostics key, and a copy of private test source.

## Build and test
- **tvOS:** pushes and pull requests run CI and NAS integration. On a Mac:
  1. `brew install xcodegen && python3 scripts/generate_app_icons.py && xcodegen generate`
  2. `cd Packages/CineVaultCore && swift test`
  3. Build as in `ci.yml`.
- **Android (PowerShell):**
  1. Set `JAVA_HOME` (JDK 17 or later) and `ANDROID_HOME`.
  2. Run `cd android; .\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
  3. Expect 41 tests (28 in `:core`, 13 in `:app`) and exactly 3 lint warnings: VectorRaster, DataExtractionRules, ObsoleteSdkInt.
- **Commercial (owner or authorized developer only):**
  1. Run `gh workflow run commercial.yml -R dinesh-devkota/CineVault --ref main -f commercial_sha=<40-char SHA> -f upload_testflight=false`.
  2. Approve the `commercial` environment when prompted.

## Blockers
- **Commercial regression:** the failing test above blocks the official release.
- **Signing:** the `commercial` environment has only `COMMERCIAL_REPO_TOKEN`; Apple signing secrets are not set. Google Play setup has not started.
- **Testing:** nothing has run on a real device or NAS. The Android app has never been launched.
- **Platform:** there is no Mac or Swift on the owner's PC, so Apple validation runs only through Actions.
- **Merge:** Android needs the owner's approval before it can go to `main`.

## Next steps
1. **Owner:** decide whether to fast-forward `main` to this branch, then update the repo description.
2. **Private repo:** fix the slow-catalog regression, then rerun `commercial.yml` without signing.
3. **Android smoke test:** install the APK on an Android TV emulator or device (`adb install -r app-debug.apk`) and test with a real SMB share.
4. **Android Stage 7:**
   - Play Billing that mirrors the trial rules, probably kept private.
   - Release signing, an AAB, and the Play internal testing track.
   - A README section for Android.
5. **Apple release:** follow the README sections "One-time Apple setup", then TestFlight, then the App Store.
6. **Cleanup:**
   - Delete the encrypted diagnostics artifacts 13023439587, 13024633969, and 13026151087. They expire on 2026-11-07.
   - Replace or remove the temporary `COMMERCIAL_DIAGNOSTICS_CERT_BASE64` environment variable.
   - Never remove `COMMERCIAL_REPO_TOKEN`.

## References
- `README.md`: project layout, commercial builds, Apple setup, TestFlight, App Store, and tests.
- `docs/PRIVACY.md` and `.github/workflows/`.
- The private repo `dinesh-devkota/CineVaultCommercial` (authorized access only).
- The Copilot app session "Private commercial module".

## Continuation — 2026-11-03 (America/Chicago)

The owner requested continued work on the development branch. The Windows
checkout is now on `dinesh-devkota-sonepar-build-cinevault-tvos-app`, based on
`8534ab9`; `main` has not been changed. The earlier sections above remain a
historical snapshot, rather than a current release approval.

- GitHub CLI access is working as `dinesh-devkota`.
- Local Android validation passed all 41 JVM tests, lint (zero errors and the
  same three documented warnings), and the debug APK build using JDK 21 and
  the installed Android SDK. No Android device is connected.
- GitHub CI run `41762816806` passed the unsigned tvOS build, Swift core tests,
  and commercial build-helper tests. The Windows shell could not run three
  Bash-dependent helper tests; those tests passed on the macOS CI runner.
- UI preview run `41762822328` passed; its home/detail screenshots were inspected.
- NAS integration run `41762819399` exposed heap corruption after playback
  cleanup. Commit `cc280ca` removes forced SMB disconnection during a source
  reset so acquired reads retain their connection until completion. It adds a
  deterministic regression for both reset paths. NAS run `41764155265` passed
  all 20 simulator tests, and CI run `41764157884` passed the updated tvOS build
  and core/helper tests.
- Another active task added development validation in `0c97b64`; it was merged
  without discarding either task's changes (`8e32c7f`).
- The private fix is on `codex/fix-catalog-refresh`, commit `976a454`. Catalog
  loading now starts independently of playback authorization, with a gated
  regression test. The hosted slow-catalog test uses deterministic entitlement
  data; real StoreKit purchase/refund/restore tests are retained. Unsigned
  commercial run `41764500086` was approved by the owner and **passed** package
  tests, the app build, and the commercial simulator suite for that exact
  private revision using the existing trusted `main` workflow. TestFlight upload
  was disabled. The fix remains on the private development branch, not `main`.
- Android release preparation now includes an unsigned community AAB, explicit
  environment-based signing opt-in, and [build/NAS test instructions](../docs/ANDROID.md).
  Local tests/lint/debug APK/release AAB passed. Missing and partial settings,
  an invalid opt-in, and a missing keystore were rejected as intended. A bundle
  signed with a temporary validation key passed signature verification; the key
  was removed and the default output rebuilt and verified as unsigned.
  Billing, the commercial Android trial, Play upload, and real-device tests
  remain unfinished; this does not complete Android Stage 7.
- Public implementation revision `e82d53e` passed Android TV run `41765150142`
  and all five jobs in Development validation run `41765150044`: core/helpers,
  tvOS build, all 20 NAS simulator tests, Android tests/lint/APK/AAB, and UI
  previews. Documentation-only follow-ups do not change those tested sources.
- The owner has an Apple TV and **does not yet have an active Apple Developer
  membership**. TestFlight remains blocked on membership, app/account setup,
  signing credentials, and explicit upload approval. Unsigned simulator
  validation can continue.
- The `commercial` environment still has only `COMMERCIAL_REPO_TOKEN` as a
  secret. Do not ask the owner to paste signing credentials into chat.
