#!/usr/bin/env bash
# Build this app in the PMCA toolchain container — the supported way to build it.
#
# The host needs a container runtime and git. It does not need a JDK, an Android SDK or an NDK, and
# it does not matter which JDK the host happens to have: the image pins JDK 17, build-tools 30.0.3,
# the API 28 platform jar and NDK r16b (the last NDK with the GCC 4.9 toolchain this armeabi /
# android-14 target needs). See docker/README.md for why that toolchain cannot be swapped for a
# newer one.
#
#   ./build-container.sh                 build the image if needed, then build the app
#   ./build-container.sh --with-tests    also run ./tools/test.sh (the no-argument entrypoint does this)
#   ./build-container.sh ./build.sh      one command instead
#   ./build-container.sh bash            a shell inside the toolchain
#   RUNTIME=podman ./build-container.sh  force a runtime (default: podman, else docker)
#
# Environment passed through when set: the four ANDROID_KEYSTORE_* variables (so the build can sign
# with the real key, which is what makes an APK installable over an existing one).
set -euo pipefail
cd "$(dirname "$0")"
REPO="$PWD"

# shellcheck source=tools/config.sh
. tools/config.sh

IMAGE="${IMAGE:-pmca-build}"
DOCKER_DIR="$REPO/docker"
CACHE="${CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/pmca}"

say() { printf '\033[1m==> %s\033[0m\n' "$*"; }
die() { printf 'build-container: %s\n' "$*" >&2; exit 1; }

# ---- which runtime ----
if [ -n "${RUNTIME:-}" ]; then
    command -v "$RUNTIME" >/dev/null 2>&1 || die "RUNTIME=$RUNTIME is not on PATH"
elif command -v podman >/dev/null 2>&1; then
    RUNTIME=podman
elif command -v docker >/dev/null 2>&1; then
    RUNTIME=docker
else
    die "no container runtime: install podman (or docker), or build on the host with ./build.sh and tools/setup-toolchain.sh"
fi

mkdir -p "$CACHE"

# ---- the container runtime has to be able to start a container at all ----
# Rootless podman maps container ids onto subordinate host ids through newuidmap, which is a setuid
# helper: on a host where /usr is mounted nosuid, or where /run is not writable, it cannot acquire
# the capability and every command fails before the image is even read. Fail with the reason and the
# doctor command rather than with podman's own message.
if ! "$RUNTIME" info >/dev/null 2>&1; then
    say "$RUNTIME cannot run containers here. Diagnostics follow."
    "$RUNTIME" info 2>&1 | sed 's/^/    /' | head -20 || true
    cat >&2 <<EOF

build-container: $RUNTIME is installed but unusable in this environment.
  The usual causes, in order:
    1. newuidmap cannot acquire its capability  -> /usr (or /) is mounted nosuid
    2. /run/user/\$UID or the configured runroot is read-only
    3. /etc/subuid and /etc/subgid lack an entry for \$USER
  Check with:
    findmnt -no OPTIONS /usr        # look for nosuid
    ls -l /usr/bin/newuidmap        # it must be setuid (rwsr-xr-x)
    podman info
  On a normal desktop none of these is true. In a restricted sandbox they often are, and then the
  container path cannot work no matter how the image is built.
EOF
    exit 1
fi

# ---- the image ----
if ! "$RUNTIME" image inspect "$IMAGE" >/dev/null 2>&1; then
    say "image '$IMAGE' is absent; building it from docker/Dockerfile (about 1 GB of downloads, once)"
    # --progress=plain keeps the NDK download's progress bar visible.
    "$RUNTIME" build --progress=plain -t "$IMAGE" "$DOCKER_DIR"
    say "image '$IMAGE' built"
fi

# ---- the command ----
args=()
if [ "$#" -eq 0 ]; then
    say "building in $RUNTIME, image $IMAGE (build.sh, then tools/test.sh)"
elif [ "$1" = "--with-tests" ]; then
    shift
    say "building in $RUNTIME, image $IMAGE"
else
    say "running in $RUNTIME, image $IMAGE: $*"
    args=("$@")
fi

# The build account has to end up on the host ids, or the APK and out/ belong to root. Rootless
# podman also needs keep-id, because container uid 1000 is not host uid 1000 by default and the
# mounted repository would not be writable.
env_args=(-e "BUILD_UID=$(id -u)" -e "BUILD_GID=$(id -g)")
for v in ANDROID_KEYSTORE_B64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD JUNIT_JAR; do
    [ -n "${!v:-}" ] && env_args+=(-e "$v")
done

userns_args=()
[ "$RUNTIME" = podman ] && userns_args=(--userns=keep-id)

# :Z relabels the mount for SELinux hosts; harmless elsewhere, so it is only added when asked for.
mount_suffix="${MOUNT_SUFFIX:-}"
tty_args=()
[ -t 0 ] && [ -t 1 ] && tty_args=(-t)

exec "$RUNTIME" run --rm -i "${tty_args[@]}" "${userns_args[@]}" \
    -v "$REPO:/work$mount_suffix" \
    -v "$CACHE:/home/user/.cache" \
    "${env_args[@]}" \
    "$IMAGE" ${args[@]+"${args[@]}"}
