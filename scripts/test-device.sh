#!/bin/sh
# On-device tests (Android runtime differences + performance). Needs the phone on USB
# and the data packs inside the app (scripts/push-datapacks.sh).
# Usage: scripts/test-device.sh
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

if [ "$(adb get-state 2>/dev/null)" != "device" ]; then
  echo "No phone connected (adb get-state). Run scripts/test-jvm.sh for computer-only tests."
  exit 2
fi
if ! adb shell run-as app.jisho ls files/datapacks/core.db >/dev/null 2>&1; then
  echo "App or data packs missing on the phone: ./gradlew :app:installDebug && scripts/push-datapacks.sh"
  exit 2
fi

status=0
# Old results would otherwise be printed when the build fails before the tests run.
rm -f app/build/outputs/androidTest-results/connected/debug/*.xml
adb shell run-as app.jisho rm -f files/perf-report.txt files/handwriting-report.txt
./gradlew :app:connectedDebugAndroidTest -q || status=$?
python3 - <<'PY'
import glob, re
for path in glob.glob("app/build/outputs/androidTest-results/connected/debug/*.xml"):
    head = open(path, encoding="utf-8").read(800)
    m = re.search(r'tests="(\d+)" failures="(\d+)" errors="(\d+)" skipped="(\d+)"', head)
    print(f"{m[1]} device tests, {int(m[2]) + int(m[3])} failed, {m[4]} skipped")
PY
echo "== Performance (measured on the phone)"
adb shell run-as app.jisho cat files/perf-report.txt 2>/dev/null || echo "(no report: the performance test did not run)"
adb shell run-as app.jisho cat files/handwriting-report.txt 2>/dev/null || echo "(no handwriting report: model not downloaded)"
exit $status
