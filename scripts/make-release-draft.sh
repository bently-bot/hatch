#!/usr/bin/env bash
# Make a DRAFT GitHub release for Hatch with the assets the in-app updater needs:
#   hatch-<version>.apk + hatch-<version>.apk.sha256 (sha256sum format). Never publishes.
# Usage: make-release-draft.sh <version> <signed-release.apk> <notes.md> [target-sha]
set -euo pipefail
V=${1:?version, e.g. 0.3.0}; APK=${2:?release apk}; NOTES=${3:?notes file}; TARGET=${4:-main}
REPO=${REPO:-bently-bot/hatch}
D=$(mktemp -d); cp "$APK" "$D/hatch-$V.apk"
(cd "$D" && sha256sum "hatch-$V.apk" > "hatch-$V.apk.sha256" && cat "hatch-$V.apk.sha256")
gh release create "v$V" "$D/hatch-$V.apk" "$D/hatch-$V.apk.sha256" -R "$REPO" --draft --target "$TARGET" \
  --title "Hatch $V" --notes-file "$NOTES"
