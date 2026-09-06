# Development notes

Practical notes for building, testing and debugging Material Files. The README has the short
version of the build requirements.

## Toolchain

- JDK 21 (`JAVA_HOME` must point at it; the Gradle daemon does not pick up a newer default).
- Gradle 9.7 and AGP 9.4 via the wrapper (compileSdk 37, minSdk 35). Versions of everything else live in
  `gradle/libs.versions.toml`; Dependabot proposes bumps.
- `local.properties` (git-ignored) with `sdk.dir=...`.
- The `dav4jvm` dependency is pinned by a full 40-character commit SHA. JitPack's build for the
  short hash has no modules and 404s, so keep the full hash when bumping it.

## Checks

```sh
./gradlew ktlintCheck checkSourceFileLength assembleDebug testDebugUnitTest lintVitalRelease
```

- **ktlint.** Existing violations are grandfathered in `app/ktlint-baseline.xml`, which is
  line-number based: inserting lines in a file re-flags the old violations below the insertion.
  The policy is that any file you edit gets formatted once so that it leaves the baseline: run
  `./gradlew :app:ktlintFormat` (it touches every file), revert the files you did not mean to
  change, fix what the formatter could not, and delete the file's block from the baseline. Do not
  regenerate the baseline to make a check pass; that silently absorbs new violations.
- **File length.** `checkSourceFileLength` fails the build when any Kotlin or Java file under
  `app/src` exceeds 500 lines. Split the file; there is no exemption list.
- **Unit tests** live in `app/src/test`. `TestPath` in `provider/common` is a provider-less
  `ByteStringListPath` with real resolve/normalize/relativize semantics for path tests. Note that
  `Path.iterator()` is deliberately unsupported in this code base; use `path.names`.
- **Dependency verification.** `gradle/verification-metadata.xml` pins a SHA-256 for every
  artifact the checks resolve. After a dependency or plugin bump, regenerate it with
  `./gradlew --write-verification-metadata sha256 :app:ktlintCheck :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintVitalRelease`,
  then re-add the `aapt2-<version>-linux.jar` and `-windows.jar` entries next to the macOS one
  (their checksums are published as `.sha256` sidecars under
  `https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/`). CI runs on Linux
  and fails without them.

## Instrumented tests and the emulator

The instrumented tests in `app/src/androidTest` use UiAutomator against a real Android build and
are not part of CI. Run them on one emulator, pinned by serial, because Gradle would otherwise
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
