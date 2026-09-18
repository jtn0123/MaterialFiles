# Audit change screenshots

Raw screenshots from the app running on an API 36 emulator. All file names and contents are synthetic test fixtures. These are behavior illustrations from the updated app, not a comparison against an old APK.

- `draft-original.png`: file before editing.
- `draft-recovered.png`: a fresh editor instance restores unsaved changes; the test verifies the underlying file still contains its original contents.
- `folder-loading.png`: real folder screen with a controlled loading snapshot and real file rows.
- `folder-partial-results.png`: the same screen after injecting a partial metadata failure with two missing entries. This is a UI rendering test, not a live network-outage reproduction.

To reproduce with an emulator running:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.captureReviewScreenshots=true
```

Captures are written to the Android test output directory and pulled into `app/build/outputs/connected_android_test_additional_output/`. The screenshot option is off by default; the underlying assertions still run in CI.
