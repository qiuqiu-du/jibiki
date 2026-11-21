#!/bin/sh
# One-stop build and deploy: data packs -> checks -> tests -> release APK -> phone.
#
# Usage: scripts/deploy.sh [steps...]
#   data      build datapacks/ from dictionaries/ (core.db + every dictionary whose source exists, ~6 min)
#   check     data pack checks (tools/tests/check_packs.py)
#   test      JVM tests (no phone needed; tests that need datapacks/ are skipped without it)
#   apk       release APK -> dist/jisho-<version>.apk (+ .sha256)
#   install   install dist/'s APK on the phone over USB (adb)
#   push      copy datapacks/ to the phone's Download/jisho/ (then pick that folder in the app)
#   all       data check test apk (the default; nothing touches the phone)
# Examples: scripts/deploy.sh              everything except the phone
#           scripts/deploy.sh apk install  rebuild the APK and install it
#
# Signing: see docs/release.md. Without a release key the APK is signed with this computer's
# debug key (fine for your own phone, not for sharing).
set -e
cd "$(dirname "$0")/.."

# JDK 17: keep a suitable JAVA_HOME, else Homebrew's (macOS); CI sets its own.
if [ -z "$JAVA_HOME" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "17'; then
  export JAVA_HOME="${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
fi
PY=tools/.venv/bin/python

step() { printf '\n== %s\n' "$1"; }
need_phone() {
  if [ "$(adb get-state 2>/dev/null)" != "device" ]; then
    echo "No phone connected (USB debugging on? adb devices)"; exit 2
  fi
}

data() {
  step "Data packs"
  if [ ! -d dictionaries ]; then echo "dictionaries/ not found: skipped (the APK does not need it)"; return; fi
  if [ ! -x "$PY" ]; then
    echo "Python environment missing: python3 -m venv tools/.venv && tools/.venv/bin/pip install -r tools/requirements.txt"
    echo "(python-lzo needs 'brew install lzo' first, see AGENTS.md)"; exit 2
  fi
  "$PY" tools/build_core.py
  # id:source file (tools/paths.py); a dictionary whose source is missing is skipped
  for pair in daijisen:daijisen/daijisen.mdx meikyo3:meikyo3/meikyo3.mdx \
              shinmeikai8:shinmeikai8/shinmeikai8.zip daijirin4:daijirin4/daijirin4.zip \
              shogakukan_jc3:shogakukan-jc3/shogakukan-jc3.mdx shinseiki_jc:shinseiki-jc/shinseiki-jc.mdx; do
    id=${pair%%:*}; src=dictionaries/${pair#*:}
    if [ -f "$src" ]; then echo "-- $id"; "$PY" tools/build_dict.py "$id"; else echo "-- $id: $src missing, skipped"; fi
  done
  du -ch datapacks/*.db | tail -1
}

check() {
  step "Data pack checks"
  if [ -f datapacks/core.db ]; then "$PY" tools/tests/check_packs.py | tail -3; else echo "datapacks/ not generated: skipped"; fi
}

test_jvm() {
  step "JVM tests"
  ./gradlew :app:testDebugUnitTest -q
  python3 - <<'PY'
import glob, re, sys
t = f = s = 0
for path in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', open(path, encoding="utf-8").read(600))
    t += int(m[1]); s += int(m[2]); f += int(m[3]) + int(m[4])
print(f"{t} tests, {f} failed, {s} skipped")
sys.exit(1 if f else 0)
PY
}

apk() {
  step "Release APK"
  ./gradlew :app:assembleRelease -q
  version=$(sed -n 's/.*"versionName" *: *"\([^"]*\)".*/\1/p' app/build/outputs/apk/release/output-metadata.json | head -1)
  mkdir -p dist
  out="dist/jisho-$version.apk"
  cp app/build/outputs/apk/release/app-release.apk "$out"
  (cd dist && shasum -a 256 "$(basename "$out")" > "$(basename "$out").sha256")
  ls -lh "$out" | awk '{print $5, $9}'
  # Which key signed it: a shared release key, or this computer's debug key.
  apksigner=$(ls "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/apksigner 2>/dev/null | tail -1)
  if [ -n "$apksigner" ]; then "$apksigner" verify --print-certs "$out" | grep -m1 'certificate DN'; fi
}

install_apk() {
  step "Install on the phone"
  need_phone
  latest=$(ls -t dist/jisho-*.apk 2>/dev/null | head -1)
  [ -n "$latest" ] || { echo "No APK in dist/: run scripts/deploy.sh apk"; exit 2; }
  # -r keeps the app's data; it fails if the installed app was signed with another key.
  adb install -r "$latest" || {
    echo "Install failed. If it says the signatures do not match, the phone has a build signed with"
    echo "another key: back up favorites in the app (Settings > Backup), uninstall it, then install again."
    exit 1
  }
}

push() {
  step "Copy data packs to the phone"
  need_phone
  ls datapacks/*.db >/dev/null 2>&1 || { echo "datapacks/ is empty: run scripts/deploy.sh data"; exit 2; }
  adb shell mkdir -p /sdcard/Download/jisho
  adb push datapacks/core.db datapacks/dict_*.db /sdcard/Download/jisho/
  echo "In the app, pick the folder Download/jisho to import (first launch, or Settings > Reimport)."
}

[ $# -eq 0 ] && set -- all
for s in "$@"; do
  case $s in
    all) data; check; test_jvm; apk ;;
    data) data ;;
    check) check ;;
    test) test_jvm ;;
    apk) apk ;;
    install) install_apk ;;
    push) push ;;
    *) sed -n '2,15p' "$0"; exit 2 ;;
  esac
done
