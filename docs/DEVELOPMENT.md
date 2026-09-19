# Development notes

Practical notes for building, testing and debugging Material Files. The README has the short
version of the build requirements.

## Toolchain

- JDK 21 (`JAVA_HOME` must point at it; the Gradle daemon does not pick up a newer default).
- Gradle 9.7 and AGP 9.4 via the wrapper (compileSdk 37, minSdk 35). Versions of everything else live in
  `gradle/libs.versions.toml`; Dependabot proposes bumps.
- `local.properties` (git-ignored) with `sdk.dir=...`.
- Only `libsu` still comes from JitPack; dav4jvm is vendored (see Checks).

## Checks

```sh
./gradlew ktlintCheck checkSourceFileLength testDebugUnitTest :dav4jvm:test lintDebug lintVitalRelease assembleDebug assembleDebugAndroidTest
```

- **ktlint.** Every Kotlin file is clean; there is no baseline. `ktlintCheck` fails on any
  violation, so run `./gradlew :app:ktlintFormat` before committing and fix by hand what it
  cannot (it prints the rule). Two rules contradict each other on an annotated function type;
  route such a type through a `typealias` instead of suppressing either.
- **File length.** `checkSourceFileLength` fails the build when any Kotlin or Java file under
  `app/src` exceeds 500 lines. Split the file; there is no exemption list.
- **dav4jvm** is vendored in `dav4jvm/` (MPL 2.0; origin commit and the two modifications are in
  its README). It is a plain Kotlin JVM module with its own 85 tests (`:dav4jvm:test`); ktlint and
  the length rule do not apply to it, so keep upstream's formatting when touching it.
- **Unit tests** live in `app/src/test`. `TestPath` in `provider/common` is a provider-less
  `ByteStringListPath` with real resolve/normalize/relativize semantics for path tests. Note that
  `Path.iterator()` is deliberately unsupported in this code base; use `path.names`.
- **Dependency verification.** `gradle/verification-metadata.xml` pins a SHA-256 for every
  artifact the checks resolve. After a dependency or plugin bump, regenerate it with
  `./gradlew --write-verification-metadata sha256 :app:ktlintCheck :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :dav4jvm:test :app:lintDebug :app:lintVitalRelease`.
  A regeneration on macOS misses what only a Linux resolution fetches: the
  `aapt2-<version>-linux.jar` (and `-windows.jar`; checksums are published as `.sha256` sidecars
  under `https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/`) and Gradle
  `.module` files the local cache happened to have as POM-only. The first CI step resolves
  everything on Linux in dry-run mode and fails with the missing entries as a diff, so add
  exactly those lines. Keep the file in Gradle's own ordering (versions sort as strings), or
  that diff is never empty.
- **Screenshots.** The `screenshots` CI job installs the debug build on an API 35 emulator, runs
  `scripts/screenshots/capture.sh` through the inset-sensitive screens and, once
  `screenshots/baseline/api35/` exists, pixel-diffs against it with `scripts/screenshots/compare.py`
  (more than 0.5 % of pixels changed fails). Commit the baseline from the job's artifact, not
  from a local emulator; see `screenshots/README.md`.
- **Logging.** The provider and file-job layers record exceptions they survive with
  `Throwable.logWarning(tag, operation)` from `util/Logging.kt`, which puts the class, the
  operation and usually the path into logcat. Do not add `printStackTrace()`.

## Protocol integration tests

`python3 tools/network-tests.py` runs the JVM suite with a disposable, loopback-only Samba
server (Docker required). FTP and FTPS use embedded Apache FTPServer; WebDAV uses a local HTTP
fixture. CI runs this command. The ordinary JVM command explicitly skips the SMB test when no
fixture port is provided. TLS fixture keys and the `test-only` passwords are public test data,
never production credentials. The certificates cover trusted, unknown, wrong-host and expired
servers in both implicit and explicit FTPS modes.

## Instrumented tests and the emulator

The instrumented tests in `app/src/androidTest` use UiAutomator against a real Android build and
run in CI on API 35 and 36 emulators. Locally, run them on one emulator, pinned by serial, because Gradle would otherwise
run them on every connected device:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Notes that cost time to rediscover:

- The Play Store `android-36` system image refuses to boot with under about 7 GB of free disk,
  regardless of partition-size flags. Keep the AVD on a drive with room.
- The connected test task reinstalls the app, which resets the `MANAGE_EXTERNAL_STORAGE` app op.
  Before driving the app by hand afterwards, re-grant it and dismiss the Settings screen the
  rationale dialog opens:
  `adb shell appops set me.zhanghai.android.files MANAGE_EXTERNAL_STORAGE allow`,
  `adb shell pm grant me.zhanghai.android.files android.permission.POST_NOTIFICATIONS`,
  `adb shell am force-stop com.android.settings`.
- Open a folder directly with
  `adb shell am start -n me.zhanghai.android.files/.filelist.FileListActivity -a android.intent.action.VIEW -d file:///storage/emulated/0/Movies/Test -t inode/directory`.
  The first launch right after an install often fails silently; launch twice.
- Media3 controls auto-hide after 5 s and are still present in the accessibility tree while
  hidden. Tap the player to reveal them before tapping a control, and pause first when a test
  needs a stable position.
- The test clip `app/src/androidTest/assets/clip.mp4` is a 30 s, 10 KB black video. Regenerate it
  with `ffmpeg -f lavfi -i color=c=black:s=64x64:r=5:d=30 -f lavfi -i anullsrc=r=8000:cl=mono -t 30 -c:v libx264 -crf 51 -pix_fmt yuv420p -c:a aac -b:a 8k -shortest clip.mp4`.
- Android 16 ignores the video player's orientation lock on screens 600 dp and wider. Known.

## Debugging on a device without logcat

- `adb shell dumpsys activity exit-info me.zhanghai.android.files` and
  `adb shell dumpsys dropbox --print data_app_crash` give crash stacks.
- `adb shell dumpsys batterystats me.zhanghai.android.files`: "Discharge step durations" shows
  when the idle drain rate changed; "Wakeup reason" and "All kernel wake locks" name the cause.
  Wireless debugging itself holds a multicast lock and keeps the SoC awake, so turn it off when
  measuring battery.
- `adb shell dumpsys power | grep FileJobService` shows whether the job service holds its wake
  lock; it must not while a conflict or error dialog is waiting for the user.

## Security-relevant design points

- Exported activities accept a path in a private extra so that the app can open archive, remote
  and root-only paths in its own viewers. Those extras are honoured only when the intent also
  carries the per-install `TrustedIntentToken`; intents that leave the app (choosers, share
  sheets, pick results for other callers) must not carry it. See `util/IntentPathExtensions.kt`.
- Stored servers (with passwords and keys) and the FTP server configuration live in the
  `..._preferences_no_backup.xml` file, which `res/xml/backup_rules.xml` and
  `res/xml/data_extraction_rules.xml` exclude from every kind of backup and device transfer.
- The FTP server defaults to a named user, read-only, and refuses to start with an empty password
  unless anonymous login is switched on explicitly.
- Stored servers are additionally encrypted with an AES-GCM key in the Android Keystore
  (`util/CredentialCipher.kt`, `settings/EncryptedParcelValueSettingLiveData.kt`).
- SFTP host keys are trust-on-first-use (`provider/sftp/client/TrustOnFirstUseHostKeyVerifier.kt`,
  stored by `storage/SftpServerHostKeyStore.kt`); a changed key is refused and the user is shown
  both fingerprints before deciding.
- The network security config trusts system certificate authorities only. Cleartext stays
  permitted because it only affects WebDAV, where `dav://` is an explicit per-server choice.
- jCIFS-NG (used for NetBIOS name resolution and LAN discovery) negotiates SMB 2 or 3 only.

## File-save recovery

Text saves stage a complete replacement and atomically replace local files. Local Linux saves force the staged file to storage before
replacement and force the parent directory afterward before reporting success. Other providers
preserve the original under a temporary sibling name and restore it if committing the
replacement fails. Providers that cannot safely rename fail
without truncating the original. If restoring the original also fails, the error names the
recovery copy; keep it until its contents have been recovered. Text drafts are stored in the
app's private no-backup directory on an ordered background worker: `persistDraft()` is called
from `onStop()` and `onSaveInstanceState()`, and drafts are removed after save/discard. Abrupt
process termination before either callback runs, or before the queued write completes, is not
covered by draft recovery. Revision checks prevent stale editor instances from replacing
or deleting a newer draft. Save transactions serialize per target and reject detected changes
to the original during staging. Required extended-attribute copy failures abort the save.

## SonarCloud test coverage

SonarCloud automatic analysis reads source code but cannot import test coverage. This project
uses CI-based analysis to import JaCoCo XML from the app's JVM tests, the vendored WebDAV
library tests, and Android instrumented tests on API 35 and 36. Coverage describes Java/Kotlin
execution; these reports do not measure native C execution or replace physical-device testing.

Coverage is opt-in through `-Pcoverage=true` (or `ORG_GRADLE_PROJECT_coverage=true`). Normal
local debug builds and the screenshot job keep their existing runtime. To create the JVM
reports, including the real protocol tests, run:

```sh
ORG_GRADLE_PROJECT_coverage=true python3 tools/network-tests.py :app:createDebugUnitTestCoverageReport :dav4jvm:jacocoTestReport
python3 tools/coverage-reports.py unit
```

For an emulator running API 36, generate and collect its coverage with:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew -Pcoverage=true :app:createDebugAndroidTestCoverageReport
python3 tools/coverage-reports.py android --api-level 36
```

The HTML reports are under `app/build/reports/coverage/test/debug/`,
`app/build/reports/coverage/androidTest/debug/connected/`, and
`dav4jvm/build/reports/jacoco/test/`. The collection script validates the XML and places it in
`coverage/` with distinct names so uploads from separate CI jobs cannot overwrite each other.

CI waits for both Android jobs, downloads all four reports, rejects missing/empty coverage,
builds the source and native compilation database for analysis, and runs `./gradlew sonar`. The scanner imports
`coverage/**/*.xml` through `sonar.coverage.jacoco.xmlReportPaths`. Test-result XML is separate
from coverage XML; uploading passing JUnit results alone does not measure coverage. Native C
analysis uses the arm64 CMake compilation database generated by the same build; it is not
dropped when moving from automatic analysis to CI.

One-time setup for **jtn0123/MaterialFiles**:

1. Add a SonarCloud analysis token as the GitHub Actions repository secret `SONAR_TOKEN`.
   Keep the token out of source files, logs, and chat.
2. In SonarCloud project `jtn0123_MaterialFiles`, open **Administration → Analysis method**
   and turn off **Automatic analysis** when the CI workflow is ready to take over.
3. Run the workflow and verify the scanner imports all reports and the SonarCloud dashboard
   displays coverage. A passing scan without imported reports is not sufficient evidence.

The Sonar job fails clearly when its token is missing. External-fork PRs produce test and
coverage artifacts but do not receive the token. Existing quality-gate rules are retained;
this change measures coverage without imposing a new percentage threshold or excluding
untested application code to improve the number.
