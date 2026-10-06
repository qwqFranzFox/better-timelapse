# PMCA build container — Android 2.3.7 / armeabi / NDK r16b toolchain.
#
# The host needs no JDK, no Android SDK and no NDK. The host needs a container runtime and git.
#
#   docker build --progress=plain -t pmca-build docker
#   docker run --rm -it -v "$PWD:/work" -v "$HOME/.cache/pmca:/home/user/.cache" \
#     -e BUILD_UID="$(id -u)" -e BUILD_GID="$(id -g)" pmca-build
#
# The container starts as root only to put the build account on the host ids, then drops to it. The
# variables are BUILD_UID/BUILD_GID because bash keeps UID as a read-only special variable.
#
# Build with --progress=plain. The NDK download is the slow step and it prints a progress bar; the
# fancy progress renderer hides it.
#
# The image pins the four components the build requires:
#   JDK 17 · build-tools 30.0.3 · platform android-28 · NDK r16b (the last NDK with GCC 4.9)
#
# The layers are split so a failure names the step it happened in, and so an edit below the downloads
# does not download 1 GB again. Layer map:
#   1 base image       2 toolchain apt     3 cmdline-tools download   4 cmdline-tools unpack
#   5 NDK download     6 NDK unpack        7 SDK packages + self-check   8 build user   9 entrypoint
#
# Arch host note: podman works with the same arguments. Add :Z to each -v on SELinux systems.

FROM ubuntu:24.04

LABEL org.opencontainers.image.title="pmca-build" \
      org.opencontainers.image.description="Sony PMCA app toolchain: JDK 17, build-tools 30.0.3, platform 28, NDK r16b"

# ---- pinned versions and archive digests ----
# The digests below come from a verified build of these two archives. They are checked by default,
# and each download layer prints the digest it measured anyway. Pass an empty value to skip the check,
# or a new value when a vendor reissues an archive.
ARG CMDLINE_ARCHIVE=commandlinetools-linux-11076708_latest.zip
ARG CMDLINE_SIZE=153607504
ARG CMDLINE_SHA256="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258  commandlinetools-linux-11076708_latest.zip"
ARG NDK_ARCHIVE=android-ndk-r16b-linux-x86_64.zip
ARG NDK_SIZE=852525873
ARG NDK_SHA256="bcdea4f5353773b2ffa85b5a9a2ae35544ce88ec5b507301d8cf6a76b765d901  android-ndk-r16b-linux-x86_64.zip"
# Optional mirrors, for a network where dl.google.com is slow.
ARG CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
ARG NDK_URL="https://dl.google.com/android/repository/${NDK_ARCHIVE}"

# These four are build-time values first: layers 3 to 7 use them to place and call the toolchain.
# entrypoint.sh exports the same values again at run time, so the container never reads a stale copy.
ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    TZ=Etc/UTC \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    ANDROID_SDK=/opt/android-sdk \
    ANDROID_NDK=/opt/android-ndk \
    ANDROID_HOME=/opt/android-sdk \
    PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/tools-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# ---- layer 2: apt toolchain ----
# unzip serves both archives. bsdtar extracts large trees faster and is worth installing when the
# NDK unpack in layer 6 is slow: add bsdtar here and change the unpack to bsdtar -xf.
#
# python3 is here for tools/check-apk.py, the step-7 check that the APK really is installable on a
# PMCA body (v1 signature, armeabi, ARM code). It is the standard library only, no packages.
#
# The NDK r16b toolchain ships some 32-bit host binaries. On a normal amd64 host the 64-bit ones work
# as they are, so no i386 architecture is added here. If an NDK tool fails with "No such file or
# directory", add:  dpkg --add-architecture i386
# and append libc6:i386 libstdc++6:i386 zlib1g:i386 to the install list.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      openjdk-17-jdk-headless \
      ca-certificates curl unzip zip git make file python3 \
 && rm -rf /var/lib/apt/lists/*

# ---- layer 3: download the command line tools (154 MB) ----
# The version is pinned by archive digest, not by the _latest alias, so a Google release cannot move
# this file under the build.
#
# The layer is idempotent, and the archive lives in a build cache, so an edit anywhere in this file
# does not fetch it again. Without that pair, one edit above these layers costs another 1 GB: Docker
# invalidates every layer after the one that changed.
RUN --mount=type=cache,id=pmca-archives,target=/tmp/dl,sharing=locked set -eux; \
    have=0; [ -f "/tmp/dl/$CMDLINE_ARCHIVE" ] && have="$(wc -c < "/tmp/dl/$CMDLINE_ARCHIVE")"; \
    if [ "$have" != "$CMDLINE_SIZE" ]; then \
      echo "command line tools: $have cached bytes, want $CMDLINE_SIZE -- downloading"; \
      curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 --progress-bar \
           -o "/tmp/dl/$CMDLINE_ARCHIVE.part" "$CMDLINE_URL"; \
      mv "/tmp/dl/$CMDLINE_ARCHIVE.part" "/tmp/dl/$CMDLINE_ARCHIVE"; \
    else echo "command line tools: using the cached archive ($have bytes)"; fi; \
    size="$(wc -c < "/tmp/dl/$CMDLINE_ARCHIVE")"; \
    sum="$(sha256sum "/tmp/dl/$CMDLINE_ARCHIVE" | cut -d' ' -f1)"; \
    printf 'command line tools: %s bytes\n  sha256 %s  %s\n' "$size" "$sum" "$CMDLINE_ARCHIVE"; \
    [ "$size" = "$CMDLINE_SIZE" ] || { echo "size mismatch: expected $CMDLINE_SIZE" >&2; exit 1; }; \
    want="$(printf '%s' "$CMDLINE_SHA256" | awk '{print $1}')"; \
    if [ -n "$want" ]; then \
      [ "$sum" = "$want" ] || { echo "sha256 mismatch: expected $want" >&2; \
        echo "clear the archive cache: podman system prune --build-cache, then build again" >&2; exit 1; }; \
      echo "sha256 matches the pin"; \
    else echo "no CMDLINE_SHA256 pin: size checked only"; fi

# ---- layer 4: unpack the command line tools ----
RUN --mount=type=cache,id=pmca-archives,target=/tmp/dl,sharing=locked set -eux; \
    mkdir -p "$ANDROID_SDK/cmdline-tools" /tmp/dl/unpacked; \
    unzip -q "/tmp/dl/$CMDLINE_ARCHIVE" -d /tmp/dl/unpacked; \
    mv /tmp/dl/unpacked/cmdline-tools "$ANDROID_SDK/cmdline-tools/latest"; \
    rm -rf /tmp/dl/unpacked; \
    ls "$ANDROID_SDK/cmdline-tools/latest/bin"

# ---- layer 5: download the NDK (852 MB, the slow step, progress bar) ----
# --progress-bar prints percentage, transferred bytes, speed and ETA on one line. It needs a plain
# build progress mode: pass --progress=plain, or the renderer hides the bar.
RUN --mount=type=cache,id=pmca-archives,target=/tmp/dl,sharing=locked set -eux; \
    have=0; [ -f "/tmp/dl/$NDK_ARCHIVE" ] && have="$(wc -c < "/tmp/dl/$NDK_ARCHIVE")"; \
    if [ "$have" != "$NDK_SIZE" ]; then \
      echo "ndk: $have cached bytes, want $NDK_SIZE -- downloading"; \
      curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 --progress-bar \
           -o "/tmp/dl/$NDK_ARCHIVE.part" "$NDK_URL"; \
      mv "/tmp/dl/$NDK_ARCHIVE.part" "/tmp/dl/$NDK_ARCHIVE"; \
    else echo "ndk: using the cached archive ($have bytes)"; fi; \
    size="$(wc -c < "/tmp/dl/$NDK_ARCHIVE")"; \
    sum="$(sha256sum "/tmp/dl/$NDK_ARCHIVE" | cut -d' ' -f1)"; \
    printf 'ndk archive: %s bytes\n  sha256 %s  %s\n' "$size" "$sum" "$NDK_ARCHIVE"; \
    [ "$size" = "$NDK_SIZE" ] || { echo "size mismatch: expected $NDK_SIZE" >&2; exit 1; }; \
    want="$(printf '%s' "$NDK_SHA256" | awk '{print $1}')"; \
    if [ -n "$want" ]; then \
      [ "$sum" = "$want" ] || { echo "sha256 mismatch: expected $want" >&2; \
        echo "clear the archive cache: podman system prune --build-cache, then build again" >&2; exit 1; }; \
      echo "sha256 matches the pin"; \
    else echo "no NDK_SHA256 pin: size checked only"; fi

# ---- layer 6: unpack the NDK (thousands of small files, silent, then a line count) ----
RUN --mount=type=cache,id=pmca-archives,target=/tmp/dl,sharing=locked set -eux; \
    start="$(date +%s)"; \
    unzip -q "/tmp/dl/$NDK_ARCHIVE" -d /opt; \
    mv /opt/android-ndk-r16b "$ANDROID_NDK"; \
    echo "unpacked $(( $(date +%s) - start ))s:"; \
    find "$ANDROID_NDK" -type f | wc -l; \
    ls "$ANDROID_NDK/ndk-build"; \
    df -h /opt | tail -1

# ---- layer 7: the two SDK packages, a stable PATH, then prove every component answers ----
# sdkmanager installs build tools into a revisioned folder: /opt/android-sdk/build-tools/30.0.3/. The
# project scripts call aapt, d8, zipalign and apksigner by bare name, so publish a stable directory
# that holds links to them. The revision is found, not named a second time.
RUN set -eux; \
    yes | sdkmanager --licenses > /dev/null; \
    if ! sdkmanager "platforms;android-28" "build-tools;30.0.3"; then \
      echo "plain install refused; retrying with --include_obsolete"; \
      sdkmanager --include_obsolete "platforms;android-28" "build-tools;30.0.3"; \
    fi; \
    rm -rf "$ANDROID_SDK/.temp" 2>/dev/null || true; \
    bt="$(ls -d "$ANDROID_SDK"/build-tools/* | sort -V | tail -1)"; \
    echo "build tools: $bt"; \
    test -x "$bt/aapt"; \
    test -x "$bt/apksigner"; \
    test -x "$bt/zipalign"; \
    test -s "$bt/lib/d8.jar"; \
    unzip -l "$bt/lib/d8.jar" | grep -q 'com/android/tools/r8/D8'; \
    echo "d8.jar: $(wc -c < "$bt/lib/d8.jar") bytes, D8 class present"; \
    echo "platform: $(sed -n 's/^AndroidVersion.ApiLevel=//p' "$ANDROID_SDK/platforms/android-28/source.properties") (archive dir: android-9)"; \
    grep -q '^AndroidVersion.ApiLevel=28$' "$ANDROID_SDK/platforms/android-28/source.properties"; \
    grep -q '^Platform.Version=9$' "$ANDROID_SDK/platforms/android-28/source.properties"; \
    test -f "$ANDROID_SDK/platforms/android-28/android.jar"; \
    mkdir -p "$ANDROID_SDK/tools-bin"; \
    for t in aapt aapt2 apksigner d8 zipalign; do \
      if [ -e "$bt/$t" ]; then ln -sf "$bt/$t" "$ANDROID_SDK/tools-bin/$t"; fi; \
    done; \
    "$JAVA_HOME/bin/javac" -version; \
    command -v aapt; \
    command -v d8; \
    command -v zipalign; \
    command -v apksigner; \
    aapt version; \
    apksigner version; \
    "$ANDROID_NDK/ndk-build" --version
# No rm -rf /tmp/dl here. That path is the archive cache mount, and deleting it defeats the whole point
# of layers 3 and 5. Nothing temporary is left behind: each download writes to .part and renames.

# ---- layer 8: build account ----
# The ubuntu base ships a sample account named 'ubuntu' at uid 1000, with a real home directory. Most
# hosts also use 1000, so that account is in the way, and entrypoint.sh refuses to delete an account
# whose home exists. Remove it here instead, where the decision is explicit and testable: nothing in
# this image belongs to it, and no service runs as it.
RUN set -eux; \
    if getent passwd 1000 > /dev/null; then \
      name="$(getent passwd 1000 | cut -d: -f1)"; \
      home="$(getent passwd 1000 | cut -d: -f6)"; \
      echo "removing the base image account '$name' (uid 1000, home $home)"; \
      userdel -r "$name"; \
    fi; \
    groupadd --gid 2000 user; \
    useradd --create-home --uid 2000 --gid 2000 --shell /bin/bash user; \
    test "$(id -u user)" = 2000; \
    getent passwd 1000 > /dev/null && { echo "uid 1000 is still taken"; exit 1; } || true

# The OpenMemories-Platform submodule parks an updater-only errno.h that shadows the NDK header and
# fails the native link. build.sh parks it and restores it through an EXIT trap, so a mounted
# submodule only goes dirty when the build is killed. /work is mounted from the host, and the
# container cannot pre-empt a kill, so this note is the contract: a dirty submodule after a
# container build means the container was killed, not that the build is broken.
#   git -C jni/platform status                # expect clean
#   git -C jni/platform checkout -- errno.h   # or restore it by hand

# ---- layer 9: entrypoint ----
# No USER line: the container starts as root, entrypoint.sh matches the account to the host ids, and
# every build command runs unprivileged through setpriv.
WORKDIR /work
COPY --chmod=0755 entrypoint.sh /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD []
