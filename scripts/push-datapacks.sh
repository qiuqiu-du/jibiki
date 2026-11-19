#!/bin/sh
# Debug builds only: copy datapacks/*.db straight into the app's private folder,
# skipping the in-app folder picker. Usage: scripts/push-datapacks.sh
set -e
cd "$(dirname "$0")/.."
PKG=app.jisho
TMP=/data/local/tmp/jisho-datapacks
adb shell mkdir -p "$TMP"
adb push datapacks/core.db datapacks/dict_*.db "$TMP/"
adb shell run-as "$PKG" mkdir -p files/datapacks
for f in datapacks/core.db datapacks/dict_*.db; do
  name=$(basename "$f")
  adb shell "cat $TMP/$name | run-as $PKG sh -c 'cat > files/datapacks/$name'"
done
adb shell rm -rf "$TMP"
adb shell run-as "$PKG" ls -l files/datapacks
