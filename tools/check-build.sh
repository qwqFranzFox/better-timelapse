#!/usr/bin/env bash
# The M0/M1 red test: prove an APK exists, and that it is installable on a PMCA body.
#
# Two questions, two tools, because they are not the same question:
#   apksigner          is the signature well formed?
#   check-apk.py       would this install and run — armeabi only, ARM code, a v1 signature, dex and
#                      the native library inside, and a source manifest with a legal minSdk and an
#                      X.Y.Z version?
#
# The second is the one that matters and the one nobody checks. The camera ignores v2 and v3
# signatures, so an APK signed v2-only installs nowhere — a TimeLapse release shipped exactly that
# way and nobody could install it (docs/PLATFORM-FACTS.md §3). A build that silently produced x86
# would install and then fail to load its own library.
#
#   tools/check-build.sh                    check ./BetterTimelapse.apk (names come from tools/config.sh)
#   tools/check-build.sh path/to/app.apk
#
# The verifier is itself tested: tools/check-apk-selftest.py mutates one property at a time and
# asserts the verdict flips, so a wrong `ok` cannot pass unnoticed.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=tools/config.sh
. tools/config.sh

APK="${1:-$APK_NAME.apk}"

say() { printf '\033[1m==> %s\033[0m\n' "$*"; }
die() { printf 'check-build: %s\n' "$*" >&2; exit 1; }

[ -f "$APK" ] || die "$APK is absent — build first (./build-container.sh, or ./build.sh with a host toolchain)"
[ -s "$APK" ] || die "$APK is empty"
command -v python3 >/dev/null 2>&1 || die "python3 is required for the APK checks and is not on PATH (the container image ships it)"

say "$APK: $(wc -c < "$APK") bytes, sha256 $(sha256sum "$APK" | cut -c1-16)…"
python3 tools/check-apk.py "$APK" --package "$APP_PACKAGE" --native-lib "lib$NATIVE_LIB.so"

say "the version this build produced"
# version.sh derives it, and the APK carries it because aapt read the injected copy of the manifest.
( . tools/version.sh; echo "    versionName $VERSION_NAME  versionCode $VERSION_CODE" )

say "the files the build wrote"
ls -l "$APK" 2>/dev/null | sed 's/^/    /' || true
[ -f "$APK.sha256" ] && sed 's/^/    /' "$APK.sha256" || true

echo
echo "CHECK BUILD OK: $APK"
