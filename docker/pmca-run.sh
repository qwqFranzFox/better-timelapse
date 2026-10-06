#!/usr/bin/env bash
# Run any project in the PMCA toolchain image. Unlike pmca-build.sh this script does not assume the
# project layout: it mounts the directory you name and runs the command you give.
#
#   ./docker/pmca-run.sh ../some-other-app                  # ./build.sh in that project
#   ./docker/pmca-run.sh ../some-other-app make -j4         # any command
#   ./docker/pmca-run.sh . ./build.sh                       # this repository explicitly
#   LEGACY=1 ./docker/pmca-run.sh ../PMCADemo ./gradlew assembleDebug   # the JDK 8 image, see Dockerfile.legacy
#
# Environment:
#   IMAGE       image name, default pmca-build (or pmca-build-legacy with LEGACY=1)
#   LEGACY      set to 1 to use the JDK 8 + Gradle image
#   CACHE       cache directory on the host, default $XDG_CACHE_HOME/pmca
set -euo pipefail

if [ "$#" -lt 1 ]; then
  sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

PROJECT="$1"; shift
[ -d "$PROJECT" ] || { echo "pmca-run: '$PROJECT' is not a directory" >&2; exit 1; }
PROJECT="$(cd "$PROJECT" && pwd)"

IMAGE="${IMAGE:-pmca-build}"
[ "${LEGACY:-0}" = "1" ] && [ "$IMAGE" = "pmca-build" ] && IMAGE=pmca-build-legacy
CACHE="${CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/pmca}"

if command -v docker > /dev/null 2>&1; then RUNTIME=docker
elif command -v podman > /dev/null 2>&1; then RUNTIME=podman
else echo "pmca-run: install docker or podman first" >&2; exit 1; fi

if ! "$RUNTIME" image inspect "$IMAGE" > /dev/null 2>&1; then
  echo "pmca-run: image '$IMAGE' is absent. Build it with:" >&2
  if [ "${LEGACY:-0}" = "1" ]; then
    echo "  $RUNTIME build --progress=plain -t $IMAGE -f \"\$(dirname \"$0\")/Dockerfile.legacy\" \"\$(dirname \"$0\")\"" >&2
  else
    echo "  $RUNTIME build --progress=plain -t $IMAGE \"\$(dirname \"$0\")\"" >&2
  fi
  exit 1
fi

# The signing key and the unit-test jar stay out of the image and off the command line.
# BUILD_UID/BUILD_GID, never UID/GID: bash keeps UID as a read-only special variable.
env_args=(-e "BUILD_UID=$(id -u)" -e "BUILD_GID=$(id -g)")
for v in ANDROID_KEYSTORE_B64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD JUNIT_JAR GRADLE_USER_HOME; do
  [ -n "${!v:-}" ] && env_args+=(-e "$v")
done

mkdir -p "$CACHE"
tty_args=()
[ -t 0 ] && [ -t 1 ] && tty_args=(-t)
# See pmca-build.sh: rootless podman needs keep-id, or the container cannot write the mount.
userns_args=()
[ "$RUNTIME" = podman ] && userns_args=(--userns=keep-id)

echo "pmca-run: $RUNTIME, image $IMAGE, project $PROJECT"
# An empty command list lets the entrypoint run its own default (./build.sh, then ./tools/test.sh).
exec "$RUNTIME" run --rm -i "${tty_args[@]}" "${userns_args[@]}" \
  -v "$PROJECT:/work" \
  -v "$CACHE:/home/user/.cache" \
  "${env_args[@]}" \
  "$IMAGE" "$@"
