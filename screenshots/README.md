# Screenshot baseline

`baseline/api35/` holds the screenshots the CI `screenshots` job compares against, captured by
`scripts/screenshots/capture.sh` on the same emulator the job uses (API 35, google_apis, x86_64,
Pixel 6 profile, SwiftShader). Screenshots from another device or renderer do not match pixel for
pixel, so refresh the baseline from the job's `screenshots-api35` artifact, not from a local
emulator.

To update after an intended UI change: download the artifact of a green run on the branch,
replace the files under `baseline/api35/`, and commit them with the change.
