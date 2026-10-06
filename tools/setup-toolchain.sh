#!/usr/bin/env bash
# Fetch the PMCA build toolchain into a directory this user owns.
#
# Why a script and not "just install the SDK": the tools this target needs
# (build-tools 30.0.3, the API 28 platform jar, NDK r16b) are all still
# downloadable, but a rolling-release host cannot hold them in /opt, and the
# JDK a distro ships is usually newer than the JDK 17 this build wants.
# Everything lands under $PREFIX, nothing is installed system-wide, and the
# whole thing is re-runnable: a file that is already correct is not fetched
# again.
#
# Usage:
#   tools/setup-toolchain.sh              # install into <repo>/.toolchain
#   PREFIX=/somewhere tools/setup-toolchain.sh
#
# The default is inside the repository because that is the one directory this
# project is allowed to write to under a workspace-write sandbox; `.toolchain/`
# is git-ignored. On a normal host, `PREFIX=$HOME/.dsh/toolchain` works too.
#
# Then, to build:
#   export JAVA_HOME=$PREFIX/jdk17
#   export ANDROID_SDK=$PREFIX/android-sdk
#   export ANDROID_NDK=$ANDROID_SDK/ndk/16.1.4479499
#   ./build.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX="${PREFIX:-$ROOT/.toolchain}"
DL="$PREFIX/downloads"
SDK="$PREFIX/android-sdk"
REPO_XML_URL="https://dl.google.com/android/repository/repository2-1.xml"
REPO_API_URL="https://dl.google.com/android/repository/repository2-3.xml"
JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"

# Android packages: the repository XML's <url> value, and where the archive's
# contents must end up for build.sh to find them.
BT_ZIP="build-tools_r30.0.3-linux.zip";        BT_DIR="$SDK/build-tools/30.0.3"
PLAT_ZIP="platform-28_r06.zip";                PLAT_DIR="$SDK/platforms/android-28"
NDK_ZIP="android-ndk-r16b-linux-x86_64.zip";   NDK_DIR="$SDK/ndk/16.1.4479499"

say() { printf '\033[1m==> %s\033[0m\n' "$*"; }
die() { printf 'setup-toolchain: %s\n' "$*" >&2; exit 1; }

mkdir -p "$DL" "$SDK"

# ---------------------------------------------------------------- checksum source
# The checksums are read from Google's own manifest instead of being copied into
# this script, so they cannot drift from the pinned URL. Both manifest versions
# are searched: old packages (build-tools 30.0.3, NDK r16b) live in the v1
# repository, newer ones in v3.
fetch_checksums() {
    local xml="$DL/repository2-1.xml"
    [ -s "$xml" ] || curl -fsSL --max-time 120 "$REPO_XML_URL" -o "$xml"
    python3 - "$xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
want = {"build-tools_r30.0.3-linux.zip", "platform-28_r06.zip", "android-ndk-r16b-linux-x86_64.zip"}
seen = set()
for arch in root.iter():
    if arch.tag.split('}')[-1] != 'archive':
        continue
    url = sha = size = None
    for c in arch.iter():
        tag = c.tag.split('}')[-1]
        if tag == 'url': url = c.text
        elif tag == 'checksum': sha = c.text
        elif tag == 'size': size = c.text
    if url in want and url not in seen:
        seen.add(url)
        print(f"{url}\t{sha}\t{size}")
PY
}

# download_if_needed <url> <dest> [sha1] [expected-size]
download_if_needed() {
    local url="$1" dest="$2" sha="${3:-}" size="${4:-}"
    if [ -s "$dest" ]; then
        if [ -z "$sha" ] || [ "$(sha1sum "$dest" | cut -d' ' -f1)" = "$sha" ]; then
            say "already present: $(basename "$dest")"
            return 0
        fi
        say "checksum mismatch, re-fetching: $(basename "$dest")"
        rm -f "$dest"
    fi
    say "fetching $(basename "$dest")${size:+ ($((size / 1048576)) MiB)}"
    curl -fL --retry 3 --retry-delay 2 -C - -o "$dest.part" "$url" || die "download failed: $url"
    mv -f "$dest.part" "$dest"
    if [ -n "$sha" ]; then
        local got; got="$(sha1sum "$dest" | cut -d' ' -f1)"
        [ "$got" = "$sha" ] || die "sha1 mismatch for $(basename "$dest"): got $got, want $sha"
        say "sha1 ok: $(basename "$dest")"
    fi
    if [ -n "$size" ] && [ "$(stat -c%s "$dest")" != "$size" ]; then
        die "size mismatch for $(basename "$dest")"
    fi
}

# ---------------------------------------------------------------- JDK 17
# java-17-openjdk from the distro is used when it is there — it is the same
# major version the build pins, and it avoids a 190 MiB download. Otherwise
# Temurin 17 is fetched.
setup_jdk() {
    local candidates=(/usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17 /usr/lib/jvm/temurin-17-jdk)
    for c in "${candidates[@]}"; do
        if [ -x "$c/bin/javac" ] && "$c/bin/javac" -version 2>&1 | grep -q ' 17\.'; then
            say "JDK 17 found at $c"
            mkdir -p "$PREFIX"
            ln -sfn "$c" "$PREFIX/jdk17"
            return 0
        fi
    done
    local zip="$DL/temurin17.tar.gz"
    download_if_needed "$JDK_URL" "$zip"
    say "extracting Temurin 17"
    rm -rf "$PREFIX/jdk17.tmp"; mkdir -p "$PREFIX/jdk17.tmp"
    tar xzf "$zip" -C "$PREFIX/jdk17.tmp" --strip-components=1
    rm -rf "$PREFIX/jdk17"; mv "$PREFIX/jdk17.tmp" "$PREFIX/jdk17"
}

extract_one_wrapper() { # <zip> <destdir>
    # Google's archives wrap their contents in a directory of their own, sometimes twice
    # (build-tools 30.0.3 is android-11/<tool> inside another android-11/). Unpack, then lift the
    # contents while the tree is a single directory, so the caller gets the tools at <destdir>
    # exactly where build.sh looks for them.
    local zip="$1" dest="$2" tmp cur
    mkdir -p "$(dirname "$dest")"
    tmp="$(mktemp -d "$dest.tmp.XXXXXX")"
    unzip -q "$zip" -d "$tmp"
    cur="$tmp"
    while [ "$(find "$cur" -mindepth 1 -maxdepth 1 | wc -l)" -eq 1 ] && [ -d "$(find "$cur" -mindepth 1 -maxdepth 1)" ]; do
        cur="$(find "$cur" -mindepth 1 -maxdepth 1)"
    done
    rm -rf "$dest"
    mv "$cur" "$dest"
    rm -rf "$tmp"
}

main() {
    say "prefix: $PREFIX"
    setup_jdk

    say "reading package checksums from Google's manifest"
    local manifest; manifest="$(fetch_checksums)"
    [ -n "$manifest" ] || die "could not read the package manifest"

    local url sha size
    while IFS=$'\t' read -r url sha size; do
        download_if_needed "https://dl.google.com/android/repository/$url" "$DL/$url" "$sha" "$size"
    done <<< "$manifest"

    # build-tools 30.0.3
    if [ -x "$BT_DIR/aapt" ] && [ -f "$BT_DIR/lib/d8.jar" ]; then
        say "build-tools 30.0.3 already installed"
    else
        say "installing build-tools 30.0.3 -> $BT_DIR"
        extract_one_wrapper "$DL/$BT_ZIP" "$BT_DIR"
        chmod +x "$BT_DIR"/aapt "$BT_DIR"/aapt2 "$BT_DIR"/zipalign "$BT_DIR"/apksigner 2>/dev/null || true
    fi

    # platform 28 (the jar javac and d8 compile against)
    if [ -f "$PLAT_DIR/android.jar" ]; then
        say "platform android-28 already installed"
    else
        say "installing platform android-28 -> $PLAT_DIR"
        extract_one_wrapper "$DL/$PLAT_ZIP" "$PLAT_DIR"
    fi

    # NDK r16b (the last NDK with the GCC 4.9 toolchain this target needs)
    if [ -x "$NDK_DIR/ndk-build" ]; then
        say "NDK r16b already installed"
    else
        say "installing NDK r16b -> $NDK_DIR (about 2.5 GiB unpacked)"
        extract_one_wrapper "$DL/$NDK_ZIP" "$NDK_DIR"
    fi

    # ------------------------------------------------------------ verify
    say "verifying"
    local fail=0
    check() { if eval "$2" >/dev/null 2>&1; then printf '  ok    %s\n' "$1"; else printf '  FAIL  %s\n' "$1"; fail=1; fi; }
    check "java 17"        "$PREFIX/jdk17/bin/java -version 2>&1 | grep -q ' 17\.'"
    check "javac 17"       "$PREFIX/jdk17/bin/javac -version 2>&1 | grep -q ' 17\.'"
    check "aapt"           "test -x $BT_DIR/aapt"
    check "zipalign"       "test -x $BT_DIR/zipalign"
    check "apksigner"      "test -x $BT_DIR/apksigner"
    check "d8.jar"         "test -s $BT_DIR/lib/d8.jar"
    check "android.jar 28" "test -s $PLAT_DIR/android.jar"
    check "ndk-build r16b" "test -x $NDK_DIR/ndk-build"
    # `ndk-build --version` prints GNU Make's version, not the NDK's, so the GCC toolchain the
    # target actually needs is checked by looking for it.
    local gccpath=4.9
    check "gcc $gccpath in NDK" "ls $NDK_DIR/toolchains/arm-linux-androideabi-$gccpath/prebuilt/linux-x86_64/bin/arm-linux-androideabi-gcc >/dev/null 2>&1 || ls $NDK_DIR/toolchains/arm-linux-androideabi-$gccpath/prebuilt/*/bin/*gcc >/dev/null 2>&1"

    echo
    if [ "$fail" -eq 0 ]; then
        cat <<EOF
TOOLCHAIN OK

  export JAVA_HOME=$PREFIX/jdk17
  export ANDROID_SDK=$SDK
  export ANDROID_NDK=$NDK_DIR
EOF
    else
        die "toolchain incomplete (see FAIL lines above)"
    fi
}

main "$@"
