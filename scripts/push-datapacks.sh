#!/bin/sh
# Debug builds only: copy the packs (dict_*.db, pitch_*.db) straight into the app's private
# folder, skipping the in-app folder picker. core.db comes with the app; the app rebuilds it
# with these packs' accents and scores on its next start. Usage: scripts/push-datapacks.sh
set -e
cd "$(dirname "$0")/.."
PKG=app.jisho
TMP=/data/local/tmp/jisho-datapacks
adb shell mkdir -p "$TMP"
adb push datapacks/dict_*.db datapacks/pitch_*.db "$TMP/"
adb shell run-as "$PKG" mkdir -p files/datapacks
for f in datapacks/dict_*.db datapacks/pitch_*.db; do
  name=$(basename "$f")
  adb shell "cat $TMP/$name | run-as $PKG sh -c 'cat > files/datapacks/$name'"
done
adb shell rm -rf "$TMP"
adb shell am force-stop "$PKG"   # the next start sees the new packs and rebuilds core.db
adb shell run-as "$PKG" ls -l files/datapacks
