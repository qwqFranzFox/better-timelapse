#!/usr/bin/env bash
# Computes the version for a build. Sourced by build.sh and by CI.
#
# Source of truth: android:versionName in AndroidManifest.xml, which always holds the NEXT TARGET
# RELEASE (X.Y.Z, no suffix). Everything else is derived from it.
#
#   RELEASE=1  -> X.Y.Z                 versionCode = formula(P=999)
#   otherwise  -> X.Y.Z-dev.gHASH[+dirty] versionCode = formula(P=<monotonic stamp>)
#
# Why the versionCode is a stamp and not a commit count.
#
# The camera decides whether to replace an installed app by comparing versionCode, an int. Two builds
# of the *same commit* produce the same commit count, so they produced the same versionCode, so the
# camera had no reason to install the second one — the app on the body stayed the first build while
# every later build reported success. That is indistinguishable, from the camera screen, from "the
# fix did not work", and it cost two rounds of chasing a bug that may never have been installed.
#
# So the dev versionCode is a stamp that increases on every build:
#   * the number of commits, so it grows as history does;
#   * times a million;
#   * plus the build number reserved for this build.
# The last build's code is remembered in .build-number, so the value can only go up even if the clock
# moves backwards or two builds land in the same second.
#
# The versionName carries the commit hash and says whether the tree was dirty, so a photograph of the
# About line identifies the exact source a build came from.
#
# Exports: VERSION_BASE VERSION_NAME VERSION_CODE DEV_COUNT VERSION_HASH
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/AndroidManifest.xml"
STAMP_FILE="$ROOT/.build-number"

# DEV_BASE_VERSION lets a dev build label itself with the version the release tooling would publish
# next, instead of the one already released.
VERSION_BASE="${DEV_BASE_VERSION:-$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST")}"
[ -n "$VERSION_BASE" ] || { echo "version.sh: no android:versionName in $MANIFEST" >&2; exit 1; }

case "$VERSION_BASE" in
  [0-9]*.[0-9]*.[0-9]*) ;;
  *) echo "version.sh: versionName '$VERSION_BASE' is not X.Y.Z (the manifest must hold the next target release, without a suffix)" >&2; exit 1 ;;
esac

IFS=. read -r MAJOR MINOR PATCH <<<"$VERSION_BASE"

have_git() { git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; }

if [ "${RELEASE:-0}" = "1" ]; then
  # A release tag must match the manifest, or the tag is lying about what it contains.
  TAG="${RELEASE_TAG:-${GITHUB_REF_NAME:-}}"
  if [ -n "$TAG" ] && [ "$TAG" != "v$VERSION_BASE" ]; then
    echo "version.sh: tag '$TAG' does not match manifest version 'v$VERSION_BASE'" >&2
    exit 1
  fi
  DEV_COUNT=0
  P=999
  VERSION_HASH=""
  VERSION_NAME="$VERSION_BASE"
else
  if have_git; then
    DEV_COUNT="$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null | tr -dc '0-9')"
    VERSION_HASH="$(git -C "$ROOT" rev-parse --short=8 HEAD 2>/dev/null || true)"
    if [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]; then
      VERSION_HASH="${VERSION_HASH}+dirty"
    fi
  else
    DEV_COUNT=1
    VERSION_HASH="nogit"
  fi
  [ -n "$DEV_COUNT" ] || DEV_COUNT=1
  [ "$DEV_COUNT" -ge 1 ] || DEV_COUNT=1

  # Reserve this build's number: never lower than the last one, so the code only ever grows.
  NEXT=1
  if [ -f "$STAMP_FILE" ]; then
    NEXT="$(tr -dc '0-9' < "$STAMP_FILE" || echo 1)"
    [ -n "$NEXT" ] || NEXT=1
  fi
  NEXT=$((NEXT + 1))
  printf '%s\n' "$NEXT" > "$STAMP_FILE"

  # P must stay under a million: the formula multiplies the commit count by exactly that.
  if [ "$NEXT" -ge 1000000 ]; then
    echo "version.sh: build number $NEXT has outgrown the version code formula; cut a release" >&2
    exit 1
  fi
  P=$(( DEV_COUNT * 1000000 + NEXT ))
  if [ "$P" -gt 999999999 ]; then
    echo "version.sh: versionCode would overflow an int; cut a release" >&2
    exit 1
  fi
  VERSION_NAME="$VERSION_BASE-dev.g$VERSION_HASH"
fi

# versionCode = MAJOR*10_000_000 + MINOR*100_000 + PATCH*1_000 + P
# P=999 for a release, so a release outranks every prerelease before it.
VERSION_CODE=$(( MAJOR * 10000000 + MINOR * 100000 + PATCH * 1000 + P ))

export VERSION_BASE VERSION_NAME VERSION_CODE DEV_COUNT VERSION_HASH
