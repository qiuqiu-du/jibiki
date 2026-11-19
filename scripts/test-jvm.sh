#!/bin/sh
# Computer-only tests (no phone needed): app JVM tests + data pack checks.
# Usage: scripts/test-jvm.sh
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

echo "== Data packs (tools/tests/check_packs.py)"
if [ -f datapacks/core.db ]; then
  tools/.venv/bin/python tools/tests/check_packs.py | tail -3
else
  echo "datapacks/ not generated; skipped (app tests that need it are skipped too)"
fi

echo "== App JVM tests"
./gradlew :app:testDebugUnitTest -q
python3 - <<'PY'
import glob, re
t = f = s = 0
for path in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    head = open(path, encoding="utf-8").read(600)
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', head)
    t += int(m[1]); s += int(m[2]); f += int(m[3]) + int(m[4])
print(f"{t} tests, {f} failed, {s} skipped")
PY
