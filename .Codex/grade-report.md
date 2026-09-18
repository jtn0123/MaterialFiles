# Codebase Grade Report

**Project:** MaterialFiles
**Audited:** 2026-09-18
**Revision:** 0dcc6a68 (local working tree; pre-existing untracked dav4jvm/ preserved)
**Stack:** Native Android, Kotlin/Java/XML, NIO-style providers, JNI/CMake, Gradle; minSdk 35, targetSdk 36, compileSdk 37.

## Summary

| ID | Category | Grade | Items |
|----|----------|-------|-------|
| A | Architecture & Design | B | 1 |
| B | Backend Quality (filesystem and jobs) | C+ | 3 |
| C | Frontend Quality | B− | 2 |
| D | Testing & Reliability | B− | 2 |
| E | Security | D+ | 2 |
| F | Dependencies & Tech Currency | B | 2 |
| G | Performance & Scalability | B− | 2 |
| H | Documentation & Onboarding | B | 1 |
| I | Developer Experience & Tooling | B | 1 |
| **Overall** | | **C+** | **16** |

**Top 5 highest-leverage fixes:** E1, B1, B2, B3, D1

The structure and tooling are solid, but server authentication and data-preservation failure paths prevent a healthy B overall. Grades are judgment-based and weight security and reliability heavily. Projected lifts are directional, not additive guarantees.

## Implementation verification — 2026-09-18

All **16 actual findings** above were implemented locally. The request referred to 20; the audit contained only 16. Original grades and findings remain as historical context, not a fresh grade.

- Full build gate passed: `ktlintCheck checkSourceFileLength testDebugUnitTest lintDebug lintVitalRelease assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest` (2m 29s).
- `python3 tools/network-tests.py`: **122 JVM tests passed**, zero failures/errors/skips, with real FTP, both FTPS modes, WebDAV and a disposable Samba server.
- API 36 emulator: **21 Android tests passed**, zero failures/skips, including save interruption, symlink and attribute preservation, encrypted-password migration and reopening an unsaved draft. API 35 and physical devices were not run locally.
- Regression checks reproduced insecure FTPS defaults and failed-migration data loss before the fixes. Save tests also exposed and fixed an existing Linux extended-attribute copy bug.
- A fresh isolated Gradle wrapper download validated the pinned distribution digest. Scoped formatting and `git diff --check` passed.
- Final message wording correction also passed `lintDebug assembleDebug`. Lint reports 949 warnings and zero errors, including untranslated new messages and suggestions to replace explicit checked preference commits with KTX.
- Remaining limits: draft recovery is written at normal background/save-state lifecycle boundaries; abrupt termination before those boundaries is not covered. Providers without safe rename support reject saving without truncating the original. New messages currently use English fallback in untranslated locales. Existing lint warnings remain; this is not a warning-free re-audit or a remote CI run.
- PR preparation added a controlled folder loading/partial-results UI test and opt-in screenshot captures. The screenshot preparation run passed **22 Android tests**; ktlint also passed. Raw captures and reproduction instructions are in `docs/pr-audit/`.
- PR review follow-up: addressed nine CodeRabbit findings with preserved migration destinations, serialized saves and target-change checks, strict extended-attribute preservation, iterator-error handling, revision-protected background draft I/O, localized UI assertions and expanded regression coverage. The full local gate passes with **24 Android tests**, including rotation with a missing original file; the final protocol-fixture run passes **129 JVM tests** with zero failures or skips.
- Initial implementation was verified locally before the PR was requested. Pre-existing `dav4jvm/` was preserved.

## Original audit validation and scope

- At audit time, sampled source and configuration without production changes; implementation results are recorded below.
- JDK 21 used explicitly; 94 unit tests passed, with zero failures/errors/skips. Debug app and instrumented-test APK assembly passed.
- Full local gate passed in 2m 1s: `ktlintCheck checkSourceFileLength testDebugUnitTest lintDebug lintVitalRelease assembleDebug assembleDebugAndroidTest`. Android debug lint reported 940 warnings and 0 errors. Formatting success includes the existing baseline. No emulator tests, device UI review, network attack reproduction or performance benchmarks were run in this audit.
- Existing CI configuration was inspected; remote CI success was not verified.
- Findings describe verified code paths or specific coverage gaps, not claims that failures were observed on a user device.

FTPS default behavior was checked against [Apache FTPSClient source/documentation](https://commons.apache.org/proper/commons-net/xref/org/apache/commons/net/ftp/FTPSClient.html) and the cached 3.13.0 JAR. Default truncation semantics are documented in [Java Files.newOutputStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html). Staged replacement follows the pattern described by [Android AtomicFile](https://developer.android.com/reference/android/util/AtomicFile); the app needs a provider-aware implementation, not a blind replacement with that local-file API.

---

## A — Architecture & Design — B

The NIO-style provider boundary separates local, archive and network filesystems, and file-list responsibilities are split across focused classes. However, all code builds in one app module (`settings.gradle:1`), and provider policy still reads application globals (`app/src/main/java/me/zhanghai/android/files/provider/root/RootablePath.kt:17`). File-operation decisions and Android dialogs remain tightly connected.

#### ~~A1~~ ✓ done 2026-09-18 — Separate file-operation decisions from Android dialogs
- **Implemented:** Added an injectable file-job decision boundary and pure retry, skip, replace and merge policies with unit coverage.
- **Where:** `app/src/main/java/me/zhanghai/android/files/filejob/FileJobCopyMove.kt:100`, `app/src/main/java/me/zhanghai/android/files/filejob/FileJobDialogs.kt:73`
- **What's wrong:** Conflict decisions call service-backed dialogs directly, making retry, skip and replace branches difficult to exercise in ordinary unit tests.
- **Impact:** Moderate — changes to destructive operations require costly Android-level setup.
- **Fix:** Introduce a small conflict/error decision interface and inject it into the copy/move engine; keep the existing Android dialog implementation and add fake decision implementations for tests.
- **Effort:** M
- **Grade lift:** B → B+ by making core decision paths independently testable.

---

## B — Backend Quality (filesystem and jobs) — C+

This app has no hosted backend; this grade covers filesystem providers and background operations. `app/src/main/java/me/zhanghai/android/files/provider/common/ForeignCopyMove.kt:50` stages replacement copies and file jobs provide retry/conflict handling. Text writes and credential migrations do not consistently preserve data through failure.

#### ~~B1~~ ✓ done 2026-09-18 — Preserve the original file until text saves complete
- **Implemented:** Staged saves now preserve the original on failure, atomically replace local files, and retain a named recovery copy if remote rollback fails. Android tests cover interruption, mode and extended-attribute preservation, and symlinks.
- **Where:** `app/src/main/java/me/zhanghai/android/files/filejob/WriteFileJob.kt:43`, `app/src/main/java/me/zhanghai/android/files/provider/common/PathExtensions.kt:220`
- **What's wrong:** The editor opens the destination directly with default output-stream options, truncating an existing file before the replacement is fully written. A failed or canceled write can leave partial content. This is a source-confirmed failure path, not a device reproduction.
- **Impact:** Major — an interrupted save can destroy the previous on-disk contents.
- **Fix:** Stage a sibling with CREATE_NEW, close and flush successfully, then replace using provider-supported semantics. Preserve permissions and symlink behavior; define a safe fallback for providers that cannot rename. Add failure-injection tests before/during/after writes.
- **Effort:** M
- **Grade lift:** C+ → B− by closing a direct data-loss path.

#### ~~B2~~ ✓ done 2026-09-18 — Complete editor save callbacks on cancellation and failure
- **Implemented:** Job completion now runs exactly once for cancellation before execution and escaped failures; editor encoding/enqueue errors also leave the running state.
- **Where:** `app/src/main/java/me/zhanghai/android/files/filejob/WriteFileJob.kt:25`, `app/src/main/java/me/zhanghai/android/files/viewer/text/TextEditorViewModel.kt:132`, `app/src/main/java/me/zhanghai/android/files/viewer/text/TextEditorFragment.kt:291`
- **What's wrong:** The listener runs only after write() returns. InterruptedIOException and other escaping exceptions are handled by FileJob.runOn without notifying the listener; the editor can remain in Running with Save disabled.
- **Impact:** Major — a canceled save can prevent another save in the same editor session.
- **Fix:** Deliver an exactly-once completion outcome for success, failure and cancellation, including jobs canceled before execution; reset editor state while preserving the draft. Test notification cancellation and socket timeout.
- **Effort:** M
- **Grade lift:** C+ → B− by making save-state recovery reliable.

#### ~~B3~~ ✓ done 2026-09-18 — Check durable credential migration before deleting originals
- **Implemented:** Preference migration checks durable commits before removing originals and leaves the version marker unchanged on failure for retry.
- **Where:** `app/src/main/java/me/zhanghai/android/files/app/AppUpgradersFrom175.kt:39`, `app/src/main/java/me/zhanghai/android/files/app/AppUpgrader.kt:38`
- **What's wrong:** The migration discards the destination commit result and then removes original settings. The version marker also advances without migration success.
- **Impact:** Major — a failed preferences write can lose saved server configuration after restart.
- **Fix:** Use explicit commit results, retain originals until the destination is durably committed, propagate failure so the version marker does not advance, and make retries idempotent. Cover destination and source write failures.
- **Effort:** M
- **Grade lift:** C+ → B− by protecting upgrade data.

---

## C — Frontend Quality — B−

ViewModels, explicit loading/error states, Material widgets and view binding provide a solid base. `app/src/main/java/me/zhanghai/android/files/viewer/text/TextEditorViewModel.kt:108` decodes away from main and the editor bounds file size. Source review found incomplete-list presentation and process-death draft loss; visual polish and accessibility were not tested on a running device.

#### ~~C1~~ ✓ done 2026-09-18 — Recover unsaved editor drafts after process death
- **Implemented:** Private disk drafts retain text, encoding and cursor when the editor leaves the foreground. Unit tests cover large drafts; Android coverage confirms recovery in a fresh editor instance.
- **Where:** `app/src/main/java/me/zhanghai/android/files/viewer/text/TextEditorFragment.kt:123`, `app/src/main/java/me/zhanghai/android/files/viewer/text/TextEditorFragment.kt:142`, `app/src/main/java/me/zhanghai/android/files/viewer/text/TextEditorViewModel.kt:164`
- **What's wrong:** Automatic text view state saving is disabled to avoid oversized bundles; its replacement stores the draft only in a ViewModel field. Rotation survives, but process recreation loses unsaved text.
- **Impact:** Major — background process termination can discard edits.
- **Fix:** Persist a private recovery draft and keep a small draft reference, encoding and cursor in saved state. Restore or offer recovery after recreation; remove recovery data after a successful save or explicit discard.
- **Effort:** M
- **Grade lift:** B− → B by preserving user work across process recreation.

#### ~~C2~~ ✓ done 2026-09-18 — Indicate partial directory results when metadata fails
- **Implemented:** Directory and search metadata failures now keep partial results and display a missing-entry warning with refresh guidance.
- **Where:** `app/src/main/java/me/zhanghai/android/files/filelist/FileListLiveData.kt:45`
- **What's wrong:** Metadata IO failures are logged and the entries omitted; the final state still reports Success.
- **Impact:** Moderate — an incomplete folder can appear complete or empty.
- **Fix:** Retain placeholder rows for failed metadata reads or show an explicit partial-results notice with retry. Apply the same policy to search results.
- **Effort:** M
- **Grade lift:** B− → B by making incomplete results visible.

---

## D — Testing & Reliability — B−

The local unit run passed 94 tests across 15 suites. `.github/workflows/android.yml` configures instrumented tests on API 35 and 36, and tests cover archive paths, encryption, host-key decisions and copy/paste. Coverage remains narrow relative to 743 production Kotlin files and 259 provider files; no coverage percentage was measured.

#### ~~D1~~ ✓ done 2026-09-18 — [BE] Test real protocol security and connection failures
- **Implemented:** Added loopback FTP/FTPS/WebDAV and disposable SMB integration fixtures in CI. Coverage includes both FTPS modes, trust, expiry, hostname mismatch, login failures, transfers and truncated WebDAV responses.
- **Where:** `app/src/test/`, `app/src/androidTest/`, `app/src/main/java/me/zhanghai/android/files/provider/ftp/client/Protocol.kt:13`
- **What's wrong:** There are no FTP/FTPS, SMB or WebDAV connection integration suites in either test tree. The SFTP verifier helper tests do not exercise a live connection.
- **Impact:** Major — protocol security regressions can pass the existing test gate.
- **Fix:** Start with controlled FTPS servers testing untrusted, expired and wrong-host certificates in both modes, plus valid login and interrupted transfer. Add SMB/WebDAV fixtures for authentication and transfer failures.
- **Effort:** L
- **Grade lift:** B− → B by validating network boundaries end to end.

#### ~~D2~~ ✓ done 2026-09-18 — [BE] Cover saved-settings upgrade failures
- **Implemented:** Added injected destination/source commit failures, repeat migration, encryption failure and encrypted migration persistence tests.
- **Where:** `app/src/androidTest/`, `app/src/main/java/me/zhanghai/android/files/app/AppUpgradersFrom175.kt:25`
- **What's wrong:** Credential cipher tests cover the primitive, but no upgrade tests cover old settings, commit failure, or restart during migration.
- **Impact:** Major — upgrades can lose configuration despite green encryption tests.
- **Fix:** Add legacy preference fixtures and an injectable persistence layer; verify failed destination commits preserve originals and do not advance the version. Include repeated migration and encryption failure.
- **Effort:** M
- **Grade lift:** B− → B by protecting existing-user upgrades.

---

## E — Security — D+

Keystore-backed remote credential encryption, backup exclusions, trusted internal intents and SFTP host-key persistence are strong protections (`docs/DEVELOPMENT.md:80`). However, `app/src/main/java/me/zhanghai/android/files/provider/ftp/client/Protocol.kt:13` leaves FTPS server authentication unconfigured. That significant boundary failure dominates this grade; this is a targeted source review, not a penetration test.

#### ~~E1~~ ✓ done 2026-09-18 — Authenticate FTPS servers before sending credentials
- **Implemented:** Both FTPS modes now use platform certificate-chain validation and hostname verification, backed by positive and negative TLS integration tests.
- **Where:** `app/src/main/java/me/zhanghai/android/files/provider/ftp/client/Protocol.kt:13`, `app/src/main/java/me/zhanghai/android/files/provider/ftp/client/Client.kt:88`
- **What's wrong:** Both FTPS constructors retain Apache defaults: hostname checking is off and the default trust manager checks certificate validity dates rather than a trusted chain. The actual cached commons-net 3.13.0 bytecode was inspected. Connection and login proceed without configuring either protection.
- **Impact:** Major — an attacker able to intercept the connection can impersonate a server and receive credentials or file content.
- **Fix:** Configure the platform certificate-chain trust manager and enable endpoint hostname checking before connect for both FTPS and FTPES. Test trusted matching certificates and rejection of untrusted, expired and wrong-host certificates. Do not silently downgrade to plain FTP.
- **Effort:** M
- **Grade lift:** D+ → B− by repairing the significant server-authentication gap.

#### ~~E2~~ ✓ done 2026-09-18 — Encrypt the built-in FTP server password
- **Implemented:** The built-in FTP password now uses Android Keystore encryption with unambiguous legacy migration, checked persistence and device round-trip coverage.
- **Where:** `app/src/main/java/me/zhanghai/android/files/settings/Settings.kt:95`, `app/src/main/java/me/zhanghai/android/files/settings/SettingLiveDatas.kt:56`, `app/src/main/java/me/zhanghai/android/files/settings/PasswordPreference.kt:38`
- **What's wrong:** The FTP server password is stored as a plain preferences string; the UI masks it and backup rules exclude it, but it is not encrypted like remote storage credentials.
- **Impact:** Moderate — someone with app-data access can recover it; ordinary other apps do not have that access.
- **Fix:** Use an encrypted preference adapter backed by the existing Keystore cipher, migrate the old string safely, and ensure the preference UI does not bypass the adapter.
- **Effort:** M
- **Grade lift:** D+ → C− alone; B− → B after E1, by making credential storage consistent.

---

## F — Dependencies & Tech Currency — B

`gradle/libs.versions.toml` centralizes versions and explains compatibility pins; `gradle/verification-metadata.xml` verifies dependency artifacts and Dependabot handles regular updates. This assessment does not assert that every dependency is latest or CVE-free. Distribution and CI action immutability remain incomplete.

#### ~~F1~~ ✓ done 2026-09-18 — Pin the Gradle distribution checksum
- **Implemented:** Pinned the official Gradle distribution digest and verified a fresh isolated wrapper download.
- **Where:** `gradle/wrapper/gradle-wrapper.properties:3`
- **What's wrong:** The wrapper URL pins a release but omits distributionSha256Sum. Wrapper JAR validation in CI does not pin the downloaded Gradle ZIP.
- **Impact:** Moderate — local and CI bootstrap do not enforce an expected distribution digest.
- **Fix:** Add the official SHA-256 of the exact all distribution and verify wrapper download validation from an isolated cache.
- **Effort:** S
- **Grade lift:** B → B+ together with F2 by completing bootstrap verification.

#### ~~F2~~ ✓ done 2026-09-18 — Pin CI actions to immutable revisions
- **Implemented:** Pinned all CI action references to full commit hashes, retaining version comments.
- **Where:** `.github/workflows/android.yml:29`
- **What's wrong:** First- and third-party actions use movable major-version tags.
- **Impact:** Moderate — CI execution can change without a repository diff.
- **Fix:** Replace tags with reviewed full commit SHAs and readable version comments; keep Dependabot updates enabled.
- **Effort:** S
- **Grade lift:** B → B+ together with F1 by making CI dependencies reviewable.

---

## G — Performance & Scalability — B−

Directory reads, text decoding and file-list sorting use background execution, and thumbnail/list recycling limits repeated work. However, `app/src/main/java/me/zhanghai/android/files/ui/ListDiffer.kt:34` still calculates diffs synchronously, and directory loading publishes only after every entry is processed. These are source-identified bottlenecks; no latency or frame-time claims were measured.

#### ~~G1~~ ✓ done 2026-09-18 — Compute file-list diffs away from the main thread
- **Implemented:** File-list diffs now run in the background with generation checks and commit-time position maps/scroll restoration; other adapters retain synchronous behavior.
- **Where:** `app/src/main/java/me/zhanghai/android/files/ui/ListDiffer.kt:34`, `app/src/main/java/me/zhanghai/android/files/filelist/FileListContent.kt:165`
- **What's wrong:** Sorting runs in Dispatchers.Default, but adapter replacement occurs back on main and calls synchronous DiffUtil.calculateDiff for nonempty lists.
- **Impact:** Moderate — large refreshes or reorderings can block interaction.
- **Fix:** Use asynchronous diff computation with generation checks; commit list, selection mapping and adapter notifications consistently. Benchmark large refresh/reorder cases and verify selection remains attached to the right files.
- **Effort:** M
- **Grade lift:** B− → B by removing a known main-thread workload.

#### ~~G2~~ ✓ done 2026-09-18 — Publish directory entries progressively
- **Implemented:** Directory and search loading publish immutable incremental snapshots, with generation checks preventing stale results from replacing newer requests.
- **Where:** `app/src/main/java/me/zhanghai/android/files/filelist/FileListLiveData.kt:41`, `app/src/main/java/me/zhanghai/android/files/file/FileItem.kt:47`
- **What's wrong:** All directory entries and their metadata load before the first list is posted.
- **Impact:** Moderate — users wait for the slowest full-directory enumeration before seeing any files.
- **Fix:** Publish throttled immutable snapshots with a loading state and cancellation generation, retaining correct sorting/selection; test with a deliberately slow provider.
- **Effort:** M
- **Grade lift:** B− → B by improving time to first useful result.

---

## H — Documentation & Onboarding — B

README.md documents build requirements, outputs and checks, while docs/DEVELOPMENT.md records emulator troubleshooting and security design. Some instructions have drifted from the workflow: the development guide explicitly says instrumented tests are not in CI, while the workflow runs them on two API levels.

#### ~~H1~~ ✓ done 2026-09-18 — Align documented checks with CI
- **Implemented:** README and development instructions now match CI checks, API 35/36 emulator coverage and protocol fixture commands.
- **Where:** `docs/DEVELOPMENT.md:18`, `docs/DEVELOPMENT.md:42`, `README.md:47`, `.github/workflows/android.yml:44`
- **What's wrong:** The guide says instrumented tests are not in CI and the advertised CI-equivalent command omits lintDebug and instrumented APK assembly.
- **Impact:** Moderate — contributors can follow the guide and miss required checks.
- **Fix:** Document the actual static/build gate and separate emulator gate, including API 35/36 and local serial selection; maintain one shared verification command if practical.
- **Effort:** S
- **Grade lift:** B → B+ by removing actionable setup contradictions.

---

## I — Developer Experience & Tooling — B

CI combines formatting, source-length checks, unit tests, Android lint and emulator tests; Gradle caching and dependency verification are configured. A substantial formatting baseline (2,083 entries across 386 files) and a broad manual formatting workflow create maintenance friction. Build warnings also merit triage without assuming every warning is a defect.

#### ~~I1~~ ✓ done 2026-09-18 — Make touched-file formatting safe and repeatable
- **Implemented:** Added an exact-file formatter/checker with protection against out-of-scope edits; touched-file baseline entries were removed after clean formatting checks.
- **Where:** `docs/DEVELOPMENT.md:21`, `app/ktlint-baseline.xml:1`, `app/build.gradle:125`
- **What's wrong:** The documented process formats the entire app then asks contributors to revert unrelated files. With a large line-based baseline, small edits can create unrelated formatting churn.
- **Impact:** Moderate — formatting can disturb concurrent local edits and make reviews noisy.
- **Fix:** Provide a scoped formatter command for explicitly selected files, document it, and remove only those files from the baseline after their checks pass. Preserve unrelated changes and avoid regenerating the whole baseline.
- **Effort:** S
- **Grade lift:** B → B+ by simplifying a frequent maintenance task.
