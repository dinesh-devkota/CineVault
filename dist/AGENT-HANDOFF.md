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

## Continuation — 2026-11-04 (UTC), cloud review and library upgrades

This section supersedes earlier branch/readiness statements. The owner requested
a deep review of both public TV apps, fixes, Infuse-inspired improvements, GitHub
pushes, and merging development into `main` after both platforms pass validation.
The owner explicitly chose to **leave GitHub's repository description unchanged**;
do not follow the older instruction to edit that description.

### Completed reliability review

The reviewed reliability baseline `926992277097299effb7f39541e8e3944aa94887`
was fast-forwarded to `main` after all development and Android checks passed.
Its post-merge CI (`41772046761`), NAS integration (`41772046731`), and Android
(`41772046660`, 21 runtime tests) also passed.

- Both platforms: filename/release-tag parsing, folder TMDB tags, sample/trailer
  exclusion, old release years, and empty automatic-match input are corrected.
- tvOS: source-generation checks reject stale SMB/scanner results without
  disconnecting acquired reads; playback stop is idempotent and ignores late
  callbacks. Metadata intent tracking preserves manual selections, reset restores
  explicit filename/folder tags, and deleted movies cannot receive late responses.
- Android: failed credential decryption reports an actionable error instead of
  silently using an empty password or replacing its key. Room metadata/scanning
  mutations preserve manual choices and reject changed/deleted source state.
  Ref-counted SMB sessions retain active reads and invalidate stale setup/retries;
  Media3 byte ranges, EOF, and failed-open cleanup follow the DataSource contract.
- Android progress saves, manual edits, and resume reads share ordering. Player
  recreation preserves pause state. TV tab restoration waits for layout and
  acquires window/view focus before restoring the selected tab.

### Personal library upgrades

Both tvOS and Android TV now support a persistent local Watchlist with a detail
action, Home shelf, and Movies filter. Watching/resetting progress does not remove
an item from the Watchlist. Movies also saves genre, NAS source, and decade filters;
deleting a selected source safely falls back to All sources.

Android uses an explicit non-destructive Room 1-to-2 migration with a default-false
Watchlist column. tvOS uses an additive default-false SwiftData property. Migration
tests reopen frozen legacy stores and preserve existing metadata, progress, and
source/credential mappings. Field-specific updates prevent later scans/metadata
from overwriting Watchlist state.

Android filter controls remain composed outside the recycled poster grid when
results become empty, preserving D-pad focus. The Movies layout fits a full row
below the expanded filters; Home/Search retain their existing poster size.

Official Firecore GitHub sources were accessible, including InfuseSync. Firecore's
website/support pages and the App Store listing were blocked by this environment's
network policy, so a complete current Infuse feature comparison was not verified.
No Firecore artwork/code, cloud sync, Trakt, or new media-server integration was added.

### Validation and branch result

Upgrade source revision `b206a00de35be1eb5a3d99c78ec9e8ff089f65f7` passed all
five Development validation jobs in [run 41778573506](https://github.com/dinesh-devkota/CineVault/actions/runs/41778573506)
and both Android jobs in [run 41778573525](https://github.com/dinesh-devkota/CineVault/actions/runs/41778573525).
All 26 Android runtime tests executed successfully, with zero failures or skips.
The following handoff-only commit fast-forwards this reviewed history to `main`
and keeps the development branch at the same commit. Post-merge results are
available from the main CI, NAS integration, and Android workflows.

The final source coverage is 67 JVM tests (38 core, 29 app), 19 Swift core tests,
41 tvOS app tests, 26 Android instrumentation tests, and 5 Python helper tests.
Android debug/release lint has zero errors and the same three documented warnings.
Debug/release APKs and the unsigned community AAB build successfully. Hosted Android
tests include H.264 frames and an 8-second seek over the synthetic SMB share,
credentials, migration, persistence, and remote-control navigation.

One hosted emulator attempt timed out before boot with zero tests. The Android
runtime job now pins Ubuntu 24.04 and waits for udev device-permission updates
before checking KVM access; a premature KVM check was corrected. These failed
infrastructure runs do not count as executed application tests.

The Watchlist UI test refreshes API 34+ accessibility caches before querying exact
visible text. UIAutomator 2.3 otherwise searched stale Compose labels, while its
diagnostic getters refreshed those same nodes. This produced a false failure
despite the expected text, focused button, and saved filter being present. The
test keeps its D-pad sequence and timeout, requires nonempty visible bounds,
and lets lookup exceptions fail instead of treating them as absent text.

### Environment and remaining limits

The Linux toolchain and helpers are in `/workspace/.cinevault-env`; follow
`docs/CLOUD-DEVELOPMENT.md`. Keep the existing checkout and preserve current work.
Use the activation script and `flock /tmp/cinevault-gradle.lock` for shared Gradle
runs. This machine has no Android emulator or macOS/Xcode; platform runtime tests
run through GitHub Actions. Git HTTPS reads/pushes work with existing injected
authentication. GitHub API access remains blocked here; do not proxy API requests
through CI or ask for a token solely because `gh` cannot reach the API.

Physical Apple/Android TV, real NAS variants, broad codec coverage, signing,
TestFlight/Play publication, and Android billing remain separate work. The private
commercial validation described above is historical and was not repeated by this
public review. No signing/store upload or repository description edit occurred.
Saving the onboarding configuration does not publish the environment or prove
restoration in a fresh task; publication remains a user action in environment settings.

## Continuation — 2026-11-04 (America/Chicago)

- Installed an API 36 x86_64 Android TV emulator on the owner's Windows PC.
  Hardware acceleration is available. Reduced the virtual display to 720p,
  enabled touch input and host GPU rendering, and installed the development APK.
- Android now has a guided NAS setup: ten-second Bonjour/mDNS discovery,
  address/port autofill, guest share enumeration with sign-in and manual-share
  fallbacks, folder browsing, and validation before saving encrypted credentials
  and starting the scan. TV Material controls now also accept pointer taps.
- Local core/app tests, lint, and debug APK build passed. Lint still reports
  zero errors and the same three warnings. User Escape interrupted desktop UI
  validation; pointer behavior, authenticated NAS access, and playback are not
  yet verified. Do not describe these as completed real-device tests.
- The physical NAS answered an SMB Bonjour query from Windows, and direct
  TCP access to its SMB port from the emulator succeeded. The emulator's NSD
  search returned no devices: physical LAN announcements are not reaching this
  emulator setup. Use manual address entry for the local NAS test; do not
  hardcode the owner's address or credentials in the app.
- The owner requested pushing this work to the development branch and adding
  the same NAS setup experience to Apple TV. No main merge or store upload was
  requested. Apple Developer membership remains unavailable.

### Cine-Media library and playback update

- Display name is now Cine-Media; app IDs and existing library data stay compatible.
- Both platforms scan video and audio recursively, save discovered items in batches,
  report progress, and mark absent items missing only after traversal completes.
  Swift final-save failures restore missing flags and the previous scan date.
- TV filenames (S01E02, 1x02, or numbered files in Season folders) produce local
  show → season → episode browsing. TMDB TV endpoints provide show/season posters
  and episode details/stills with cached lookups. Matched genres determine film
  categories; audio remains Music. Room v3 adds nullable episode fields using
  non-destructive migrations. SwiftData fields are additive and optional.
- Added playback title/details and visible Back controls, manual TV matching, and
  Settings → Media Guide & Help on Android and Apple TV. Read docs/MEDIA-GUIDE.md.
- A read-only check of the owner's emulator confirmed the Media-root source
  completed with 24,722 present items and a saved last-scan timestamp. A second
  Movies-only source contains 503 overlapping items; neither source was removed.
  No online title matches existed at this check. The owner must enter their own
  free personal TMDB key in Settings; never request the key in chat.
- Android core/app tests passed during development. Final APK installation,
  focused runtime checks, and hosted Apple validation are tracked in the chat.
  Do not run the entire Android instrumentation suite on the owner's emulator:
  navigation tests clear app data. Use isolated CI for the full suite.
- Prior development run 41875619174 exhausted GitHub macOS arm64 runner capacity;
  no Apple job executed. Development validation now uses supported macos-15-intel
  runners. Windows cannot compile or run tvOS locally; physical Apple TV testing
  and signing remain outstanding until Apple account/device prerequisites exist.

### TV grouping correction — 2026-11-04

- Fixed Android and Swift recognition of bracketed `1x02`, spaced `S01 E02`,
  and `S01xE02` markers, plus prefixed/repeated season directories. The nearest
  series folder takes precedence, so intermediate folders such as `Completed`
  do not merge unrelated shows. Local grouping needs no metadata key or rescan.
- Home Watchlist and Unwatched rows now collapse episodes into one show tile
  before limiting the row to 20 items. Each tile opens seasons and episodes;
  row filters remain intact. Continue Watching and Search retain episode results.
- Read-only validation against the owner's existing library found all 192 `24`
  episode files grouped into one show with eight seasons of 24 episodes each.
  Two unnumbered bonus clips remain separate; the media guide explains Specials.
- Local Android validation passed 96 JVM tests (54 core, 42 app), debug lint
  with zero errors and three existing warnings, and both debug APK builds.
  The updated app was installed without clearing the user's library. The owner
  confirmed grouping looks fixed and requested a push and merge to the default
  branch, which is `main`. Hosted Apple and Android validation must be checked
  before that merge; do not infer success from the local Android results.
- The user stopped desktop control with Escape before the automated visual
  navigation check completed. Do not describe that click-through as verified.
- TVNavigationTest now refreshes API 34+ accessibility caches before polling
  visible labels, matching the existing Watchlist test and preserving D-pad
  actions and all screen assertions. Run the full suite only in isolated CI.

### Validated merge baseline

Source revision `36c3cde36f27d18563f5677f410e580800b69c53` passed every job in
[Development validation 41940552196](https://github.com/dinesh-devkota/CineVault/actions/runs/41940552196)
and [Android validation 41940552154](https://github.com/dinesh-devkota/CineVault/actions/runs/41940552154).
Swift core passed 38 tests plus five build-helper tests. The unsigned tvOS build,
Apple TV simulator NAS/runtime tests, and Home/detail/NAS preview captures passed.
Android passed all 32 runtime tests with zero failures or skips, plus unit tests,
lint, debug/release builds, and unsigned AAB packaging. These results validate the
earlier scan/share fixes as well as the TV grouping correction.

The owner explicitly authorized merging development into the default branch
after these checks. `main` is an ancestor of this revision, so a fast-forward
preserves all existing main history. This final handoff update changes only
documentation; its application source is identical to the validated revision.

### NAS exclusions, first-search timing, and media validation — 2026-11-04

- Android and Apple TV source editors now browse the scan root and let the user
  ignore whole folders (including descendants) or individual files. Rules are
  relative to the selected root. Saving hides matching entries immediately,
  preserves watch history, and scans again; including them restores them after
  a successful scan. Connection/share/root changes clear relative rules with a
  notice. Android Room v4 migrates existing source rows without data deletion;
  SwiftData fields are additive and optional. Late scans/metadata cannot revive
  ignored entries.
- NAS discovery now starts its 15-second browsing window after the system's
  discovery-start callback, allows bounded resolution grace, and retries failed
  resolution once. Results publish as they resolve; another scan cannot cancel
  an active search. A delayed synthetic advertisement test exercises first-scan
  discovery after the old 10-second cutoff. Physical emulator/LAN multicast
  forwarding is still network-dependent; do not claim every real NAS was tested.
- Scanning rejects `.d.ts` and `node_modules`, and reads at most 1024 bytes for
  ambiguous `.ts` candidates to distinguish transport streams from TypeScript.
  Existing bad entries disappear after a successful rescan; no NAS files are
  deleted. Real 188/192/204-byte transport streams remain recognizable for the
  catalog, but Android playback accepts only its extractor's 188-byte layout.
- Audited official Android Media3 documentation and the pinned 1.8.0 extractors.
  M2TS, WMA, and AIFF/AIF are not playable in this Android build. Both platforms
  gate Play controls by their actual container candidates and reject code paths;
  Android rechecks legacy `.ts` content before playback. Apple TV asynchronously
  loads asset playability, protects history on failure/cancellation, and surfaces
  native-player failures. Actual codec support still depends on the device.
  See `docs/MEDIA-GUIDE.md` for the audited table and primary-source links.
- Local Android build passed 118 JVM tests, debug lint (0 errors, 3 existing
  warnings), and debug/test APK assembly. Both APKs were installed with `-r` on
  the owner's emulator without clearing app data after the owner confirmed the
  metadata fetch had finished. All 18 isolated scanner, migration, metadata, and
  late-discovery runtime tests passed. Never run the full instrumentation suite
  on this emulator: other navigation tests clear the actual library.
- Five Python build-helper tests passed using Git Bash on Windows. The system
  `bash.exe` points to unavailable WSL and fails these helpers with exit 127;
  prefix the test process PATH with Git's `bin` when running them locally.
- This update is being pushed to the development branch. Hosted macOS/Android
  results must be checked separately; Windows cannot validate tvOS builds.
  No signing, TestFlight, store upload, or new physical Apple TV test occurred.
