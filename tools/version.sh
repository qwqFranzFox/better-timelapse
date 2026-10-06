#!/usr/bin/env bash
# Computes the version for a build. Sourced by build.sh and by CI.
#
# Source of truth: android:versionName in AndroidManifest.xml, which always holds the NEXT TARGET
# RELEASE (X.Y.Z, no suffix). Everything else is derived from it.
#
#   RELEASE=1  -> X.Y.Z          versionCode = formula(P=999)
#   otherwise  -> X.Y.Z-dev.N    versionCode = formula(P=N)
#                N = commits since the last v* tag, or the number of commits when no tag exists
#
# Exports: VERSION_BASE VERSION_NAME VERSION_CODE DEV_COUNT
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/AndroidManifest.xml"

# DEV_BASE_VERSION lets a dev build label itself with the version the release tooling would publish
# next, instead of the one already released.
VERSION_BASE="${DEV_BASE_VERSION:-$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST")}"
[ -n "$VERSION_BASE" ] || { echo "version.sh: no android:versionName in $MANIFEST" >&2; exit 1; }

case "$VERSION_BASE" in
  [0-9]*.[0-9]*.[0-9]*) ;;
  *) echo "version.sh: versionName '$VERSION_BASE' is not X.Y.Z (the manifest must hold the next target release, without a suffix)" >&2; exit 1 ;;
esac

IFS=. read -r MAJOR MINOR PATCH <<<"$VERSION_BASE"

# Count commits since the last v* tag. `git` may be absent (a source tarball, a fresh export) or
# the tree may not be a repository yet; both are normal here, so the count falls back to 1 rather
# than failing the build. A dev build is always at least .1 because 0 would mean "no build yet".
dev_count() {
  local tag
  if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    tag="$(git -C "$ROOT" describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
    if [ -n "$tag" ]; then
      git -C "$ROOT" rev-list --count "$tag..HEAD" 2>/dev/null && return
    else
      git -C "$ROOT" rev-list --count HEAD 2>/dev/null && return
    fi
  fi
  # No git repository yet, so there is no commit count to derive from — and a counter that never
  # moves is worse than no counter at all: every build then carries the same versionCode, which is
  # exactly the kind of "it did not change" the camera cannot distinguish from a failed install. So
  # fall back to a stored, incrementing build number.
  # Deliberately not under out/: that directory is build output and is wiped at the start of every
  # build, so a counter living there restarts at 1 every time — which is the same "every build has the
  # same versionCode" problem this fallback exists to fix.
  local counter="$ROOT/.build-number"
  local n=0
  [ -f "$counter" ] && n="$(tr -dc '0-9' < "$counter" || echo 0)"
  [ -n "$n" ] || n=0
  n=$((n + 1))
  printf '%s\n' "$n" > "$counter"
  echo "$n"
}

if [ "${RELEASE:-0}" = "1" ]; then
  # A release tag must match the manifest, or the tag is lying about what it contains.
  TAG="${RELEASE_TAG:-${GITHUB_REF_NAME:-}}"
  if [ -n "$TAG" ] && [ "$TAG" != "v$VERSION_BASE" ]; then
    echo "version.sh: tag '$TAG' does not match manifest version 'v$VERSION_BASE'" >&2
    exit 1
  fi
  DEV_COUNT=0
  P=999
  VERSION_NAME="$VERSION_BASE"
else
  DEV_COUNT="$(dev_count | tr -dc '0-9')"
  [ -n "$DEV_COUNT" ] || DEV_COUNT=1
  [ "$DEV_COUNT" -ge 1 ] || DEV_COUNT=1
  if [ "$DEV_COUNT" -gt 998 ]; then
    echo "version.sh: $DEV_COUNT commits since the last tag exceeds the 998 dev builds per cycle. Cut a release." >&2
    exit 1
  fi
  P="$DEV_COUNT"
  VERSION_NAME="$VERSION_BASE-dev.$DEV_COUNT"
fi

# versionCode = MAJOR*10_000_000 + MINOR*100_000 + PATCH*1_000 + P
# P=999 for a release, so a release always outranks every prerelease that preceded it — otherwise
# the camera refuses the release as a downgrade after an install of the dev build.
VERSION_CODE=$(( MAJOR * 10000000 + MINOR * 100000 + PATCH * 1000 + P ))

export VERSION_BASE VERSION_NAME VERSION_CODE DEV_COUNT
