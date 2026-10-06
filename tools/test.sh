#!/usr/bin/env bash
# Unit tests: the CI gate `test`, and how to run them locally.
#
# Compiles the camera-free classes (the UNITS in tools/config.sh) against a plain JDK — no SDK, no
# NDK, no android.jar — then everything under test/, and runs it with the JUnit console launcher.
# The launcher is one jar, fetched from Maven Central into out/test/ on first use and checked
# against the SHA-256 pinned below.
#
#   tools/test.sh              run everything
#   tools/test.sh Steps        only test classes whose name contains "Steps"
#   tools/test.sh --allow-none exit 0 when no test tree exists yet (the M1 skeleton has none)
#
# Two rules this script is the enforcement arm of:
#   - a class listed in UNITS may not import android.*, because android.jar is not on this
#     classpath: the import fails to compile here before it ever fails on a camera;
#   - a class that decides something without the camera belongs in UNITS with a test next to it,
#     and is called from the Activity, never the other way round.
#
# Toolchain: JAVA_HOME pointing at a JDK 17, or javac on PATH.
# JUNIT_JAR=<path> uses an already downloaded launcher instead (offline).
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=tools/config.sh
. tools/config.sh

ALLOW_NONE=0
FILTER_ARG=""
for arg in "$@"; do
    case "$arg" in
        --allow-none) ALLOW_NONE=1 ;;
        *) FILTER_ARG="$arg" ;;
    esac
done

JUNIT_VERSION=1.14.4
JUNIT_SHA256=7c6968cbcaf4301c729f202b23b7d736c5d88be625fc7d27ad5d746146a8bc28
JUNIT_URL="https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar"
JUNIT_JAR="${JUNIT_JAR:-out/test/junit-platform-console-standalone-$JUNIT_VERSION.jar}"

if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin"; else JAVA="$(dirname "$(command -v javac 2>/dev/null || echo /nonexistent/javac)")"; fi
[ -x "$JAVA/javac" ] || { echo "test.sh: no javac -- set JAVA_HOME to a JDK 17" >&2; exit 1; }

mkdir -p out/test
if [ ! -e "$JUNIT_JAR" ]; then
  echo "fetching junit-platform-console-standalone $JUNIT_VERSION"
  curl -sSfL --retry 3 -o "$JUNIT_JAR.part" "$JUNIT_URL"
  mv -f "$JUNIT_JAR.part" "$JUNIT_JAR"
fi
if ! printf '%s  %s\n' "$JUNIT_SHA256" "$JUNIT_JAR" | sha256sum -c --quiet -; then
  rm -f "$JUNIT_JAR"
  echo "test.sh: $JUNIT_JAR does not match the pinned SHA-256; removed it" >&2
  exit 1
fi

rm -rf out/test/classes out/test/test-classes out/test/reports
mkdir -p out/test/classes out/test/test-classes

# A project whose pure core has not started yet still has to pass: compiling an empty list of
# units succeeds, and the launcher's --fail-if-no-tests is what makes an empty *test* tree fail.
UNIT_FILES=()
for u in ${UNITS[@]+"${UNITS[@]}"}; do UNIT_FILES+=("$SOURCE_DIR/$APP_PACKAGE_PATH/$u.java"); done

echo "[1/3] javac units (same flags as build.sh, no android.jar)"
if [ "${#UNIT_FILES[@]}" -gt 0 ]; then
  "$JAVA/javac" -encoding UTF-8 --release 8 -Xlint:-options -d out/test/classes "${UNIT_FILES[@]}"
else
  echo "      no units listed yet in tools/config.sh"
fi

echo "[1b/3] font coverage"
# A character missing from the bundled font is a tofu box on a screen the user cannot debug, so this
# is a gate and not a report. It runs before the tests because it needs nothing but python3.
if command -v python3 >/dev/null 2>&1 && [ -f tools/check-font.py ]; then
  python3 tools/check-font.py
else
  echo "      SKIPPED: no python3 (the container image ships it)"
fi

echo "[2/3] javac tests"
if [ ! -d "$TEST_DIR" ] || [ -z "$(find "$TEST_DIR" -name '*.java' -print -quit 2>/dev/null)" ]; then
  if [ "$ALLOW_NONE" = "1" ]; then
    echo "      no $TEST_DIR/*.java yet — nothing is verified, and --allow-none says so out loud"
    exit 0
  fi
  echo "test.sh: no $TEST_DIR/*.java yet; add a test before claiming anything is verified" >&2
  echo "         (--allow-none passes instead, for a build whose pure core has not started)" >&2
  exit 1
fi
find "$TEST_DIR" -name '*.java' | sort > out/test/sources.txt
"$JAVA/javac" -encoding UTF-8 --release 8 -Xlint:-options -cp "out/test/classes:$JUNIT_JAR" -d out/test/test-classes "@out/test/sources.txt"

echo "[3/3] junit"
FILTER=()
[ -n "$FILTER_ARG" ] && FILTER=(--include-classname ".*$FILTER_ARG.*")
"$JAVA/java" -jar "$JUNIT_JAR" execute \
  --class-path "out/test/classes:out/test/test-classes" --scan-class-path \
  --reports-dir out/test/reports --details=tree --disable-banner --fail-if-no-tests "${FILTER[@]}"
