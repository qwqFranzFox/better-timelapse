#!/usr/bin/env bash
# Build the PMCA app inside the toolchain image, so the host needs no JDK, SDK or NDK.
#
#   ./docker/pmca-build.sh                 ./build.sh, then ./tools/test.sh
#   ./docker/pmca-build.sh ./build.sh      one command
#   ./docker/pmca-build.sh bash            a shell inside the toolchain
#
# Image: docker/Dockerfile. Build it first:
#   docker build -t pmca-build docker
set -euo pipefail

IMAGE="${IMAGE:-pmca-build}"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/pmca"
REPO="$(cd "$(dirname "$0")/.." && pwd)"

if command -v docker > /dev/null 2>&1; then
  RUNTIME=docker
elif command -v podman > /dev/null 2>&1; then
  RUNTIME=podman
else
  echo "pmca-build: install docker or podman first" >&2
  exit 1
fi

if ! "$RUNTIME" image inspect "$IMAGE" > /dev/null 2>&1; then
  echo "pmca-build: image '$IMAGE' is absent. Build it with:" >&2
  echo "  $RUNTIME build -t $IMAGE \"$REPO/docker\"" >&2
  exit 1
fi

# Keep the signing key and the unit-test jar out of the image, and off the command line.
# BUILD_UID/BUILD_GID, not UID/GID: bash keeps UID as a read-only special variable, so a variable of
# that name cannot be relied on to carry the host id into the container.
env_args=(-e "BUILD_UID=$(id -u)" -e "BUILD_GID=$(id -g)")
for v in ANDROID_KEYSTORE_B64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD JUNIT_JAR; do
  [ -n "${!v:-}" ] && env_args+=(-e "$v")
done

mkdir -p "$CACHE"

# Rootless podman maps a container account onto a subordinate host id, so container uid 1000 is not the
# host user 1000 and cannot write a repository that belongs to it. keep-id makes the two ids the same,
# and the entrypoint then finds itself already on the wanted id. Without it the build dies with
# "Permission denied" on the first file it rewrites inside the repository.
userns_args=()
[ "$RUNTIME" = podman ] && userns_args=(--userns=keep-id)

# -t only when a terminal is attached, so the helper works from a pipe or a CI job.
tty_args=()
[ -t 0 ] && [ -t 1 ] && tty_args=(-t)

# SELinux hosts (Fedora, and Arch with SELinux enabled) need :Z on the mounts.
echo "pmca-build: $RUNTIME, repository $REPO"
exec "$RUNTIME" run --rm -i "${tty_args[@]}" "${userns_args[@]}" \
  -v "$REPO:/work" \
  -v "$CACHE:/home/user/.cache" \
  "${env_args[@]}" \
  "$IMAGE" "$@"
