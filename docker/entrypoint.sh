#!/usr/bin/env bash
# Entry of the PMCA build image. Starts as root so it can match the runtime account to the host user,
# then drops to that account for every build command.
#
#   (no arguments)      ./build.sh, then ./tools/test.sh
#   <command...>        run that command instead, still in /work
#   BUILD_UID/BUILD_GID the account ids the build runs as; defaults to 2000:2000
#                       (not named UID/GID: bash keeps UID as a read-only special variable)
#
# The image ships the account at 2000:2000. A runtime account with the host ids makes the APK and the
# out/ tree belong to the human who started the container, not to root.
set -euo pipefail

readonly TARGET_USER=user
readonly TARGET_HOME=/home/user
readonly WANT_UID="${BUILD_UID:-2000}"
readonly WANT_GID="${BUILD_GID:-2000}"

# The toolchain comes from the image, and a derived image may point JAVA_HOME at another JDK (the legacy
# image uses 8). So back each value with a default instead of overwriting it, then make PATH from what
# the image already had. This keeps a derived image from needing a patch to this file.
: "${JAVA_HOME:=/usr/lib/jvm/java-17-openjdk-amd64}"
: "${ANDROID_SDK:=/opt/android-sdk}"
: "${ANDROID_NDK:=/opt/android-ndk}"
export JAVA_HOME ANDROID_SDK ANDROID_NDK
export ANDROID_HOME="$ANDROID_SDK"
export PATH="$PATH:$JAVA_HOME/bin:$ANDROID_SDK/cmdline-tools/latest/bin:$ANDROID_SDK/tools-bin"

die() { echo "entrypoint: $*" >&2; exit 2; }

# Free one id by deleting the account that holds it. Only accounts whose home directory does not exist
# are touched. A base image keeps its service accounts that way (home /nonexistent or /run/...), and
# deleting one of those breaks nothing at runtime. An account with a real home directory is a person or
# a service that has state; it is never removed, and the caller reports the conflict instead.
evict_account() {
  local kind="$1" want="$2" name home uid
  if [ "$kind" = passwd ]; then
    name="$(getent passwd "$want" | cut -d: -f1)"
    home="$(getent passwd "$want" | cut -d: -f6)"
    uid="$(getent passwd "$want" | cut -d: -f3)"
  else
    name="$(getent group "$want" | cut -d: -f1)"
    home=
    uid=
  fi
  [ -n "$name" ] || return 1

  if [ "$kind" = passwd ]; then
    # Never touch an account below 100: those are core system accounts in every distribution, and the
    # build account is never one of them.
    if [ "${uid:-0}" -lt 100 ]; then
      echo "entrypoint: uid $want is the core account '$name'; not removing it" >&2
      return 1
    fi
    # A home directory normally means a real account, so it is kept. One exception: an account in the
    # ordinary-user range below the build account is a leftover the base image shipped (Ubuntu's
    # 'ubuntu' at 1000 is the usual one), and hosts occupy exactly that range. The image removes it at
    # build time; this rule covers an older image that still carries it.
    if [ -d "$home" ] && [ "${uid:-0}" -lt "$(id -u "$TARGET_USER")" ]; then
      echo "entrypoint: uid $want is the base image account '$name' (home $home) below the build account; removing it" >&2
      userdel -r "$name" 2> /dev/null || return 1
      echo "entrypoint: freed uid $want"
      return 0
    fi
    if [ -d "$home" ]; then
      echo "entrypoint: uid $want belongs to '$name', whose home $home exists; not removing it" >&2
      return 1
    fi
    userdel "$name" 2> /dev/null || return 1
  else
    groupdel "$name" 2> /dev/null || return 1
  fi
  echo "entrypoint: freed $kind $want by removing the unused account '$name'"
  return 0
}

# Match the account to the requested ids. A plain edit of the account files, because usermod can be
# absent and can refuse when the old group still has members.
#
# A collision is resolved, not skipped. Skipping it leaves the build account on 2000 while the mounted
# repository belongs to the host id, and the build then dies with "Permission denied" on the first file
# it rewrites inside the repository. Returns 1 when a conflict stays unresolved.
remap_account() {
  local old_uid old_gid remapped=0
  old_uid="$(id -u "$TARGET_USER")"
  old_gid="$(id -g "$TARGET_USER")"

  if [ "$old_uid" != "$WANT_UID" ]; then
    if getent passwd "$WANT_UID" > /dev/null && ! evict_account passwd "$WANT_UID"; then
      echo "entrypoint: uid $WANT_UID stays taken; the build account keeps uid $old_uid, which cannot write a repository owned by uid $WANT_UID" >&2
      return 1
    fi
    sed -i "s/^\($TARGET_USER:[^:]*:\)$old_uid:/\1$WANT_UID:/" /etc/passwd
    remapped=1
  fi

  # The group is a separate decision: one of the two ids can be free while the other is taken.
  if [ "$old_gid" != "$WANT_GID" ]; then
    if getent group "$WANT_GID" > /dev/null && ! evict_account group "$WANT_GID"; then
      echo "entrypoint: gid $WANT_GID stays taken; the build account keeps gid $old_gid" >&2
      return 1
    fi
    sed -i "s/^\($TARGET_USER:[^:]*:\)$old_gid:/\1$WANT_GID:/" /etc/group
    sed -i "s/^\($TARGET_USER:[^:]*:[0-9]*:\)$old_gid:/\1$WANT_GID:/" /etc/passwd
    remapped=1
  fi

  # Only a changed account needs the home tree moved with it.
  if [ "$remapped" = "1" ] && [ -d "$TARGET_HOME" ]; then
    chown -R "$(id -u "$TARGET_USER"):$(id -g "$TARGET_USER")" "$TARGET_HOME"
  fi
  return 0
}

# Run the given command as the build account. The environment exported above stays intact.
#
# When the wanted ids are already the current ids, run the command directly. That is the keep-id case:
# dropping to an id we already hold adds nothing, and setpriv --init-groups would look for an account
# entry that this image does not have for a host uid.
run_as_build_user() {
  local uid="$1" gid="$2"; shift 2
  if [ "$uid" = "$(id -u)" ] && [ "$gid" = "$(id -g)" ]; then
    "$@"
  elif command -v setpriv > /dev/null 2>&1; then
    setpriv --reuid "$uid" --regid "$gid" --init-groups -- "$@"
  elif command -v runuser > /dev/null 2>&1; then
    runuser -u "$TARGET_USER" -- "$@"
  elif command -v su > /dev/null 2>&1; then
    su -s /bin/bash "$TARGET_USER" -c "$(printf '%q ' "$@")"
  else
    echo "entrypoint: no setpriv, runuser or su; running as uid $(id -u)" >&2
    "$@"
  fi
}

[ -d /work ] || die "/work is absent. Mount the repository:  -v \"\$PWD:/work\""

# Rootless podman maps the container account onto a subordinate host id, so a container uid 1000 is NOT
# the host user 1000 and cannot write a repository that belongs to it. Two cases:
#
#   --userns=keep-id   the engine already put us on the wanted id. Nothing to remap, and every mount
#                      belongs to us. Change the account files would be pointless work.
#   root entry         the container started as uid 0. Move the build account onto the wanted ids, which
#                      is what makes the mount writable in a rootful engine.
if [ "$(id -u)" = "$WANT_UID" ]; then
  exec_uid="$(id -u)"
  exec_gid="$(id -g)"
  cd /work
  echo "entrypoint: already on $exec_uid:$exec_gid (user namespace kept), running in /work"
elif [ "$(id -u)" = "0" ]; then
  # Do not continue past an unresolved id conflict. The build would run as an account that cannot write
  # the mounted repository, and fail later with a confusing "Permission denied" instead.
  remap_account || die "cannot match the build account to $WANT_UID:$WANT_GID. Start the container without BUILD_UID/BUILD_GID, or add --userns=keep-id on podman / --user on docker."
  exec_uid="$(id -u "$TARGET_USER")"
  exec_gid="$(id -g "$TARGET_USER")"
  # The cache mount belongs to the host user as well.
  [ -d "$TARGET_HOME/.cache" ] && chown -R "$exec_uid:$exec_gid" "$TARGET_HOME/.cache"

  cd /work
  echo "entrypoint: $TARGET_USER ($exec_uid:$exec_gid) in /work"

else
  # Neither on the wanted id nor root: no remap is possible, so run where we are.
  exec_uid="$(id -u)"
  exec_gid="$(id -g)"
  cd /work
  echo "entrypoint: unprivileged uid $exec_uid:$exec_gid, no id change possible, running in /work"
fi

if [ "$#" = 0 ]; then
  [ -x ./build.sh ] || die "./build.sh is absent or not executable in /work. Mount the app repository at /work, or pass a command instead."
  run_as_build_user "$exec_uid" "$exec_gid" ./build.sh
  if [ -x ./tools/test.sh ]; then
    echo
    echo "== unit tests =="
    # --allow-none: this project's pure core arrives one milestone at a time, and until the first
    # test exists there is nothing to run. The flag makes that pass but say so out loud, instead of
    # letting a green test step read as "something was verified".
    run_as_build_user "$exec_uid" "$exec_gid" ./tools/test.sh --allow-none
  fi
  echo
  echo "== apk check (is this installable on a PMCA body) =="
  # apksigner proves the signature is well formed; this proves the contents are installable — abi,
  # minSdk, and v1 rather than v2-only.
  if [ -x ./tools/check-build.sh ]; then
    run_as_build_user "$exec_uid" "$exec_gid" ./tools/check-build.sh
  fi
  echo
  # Project-agnostic: report whatever APK the build just produced, not one fixed file name.
  echo "== apk files in /work =="
  find . -maxdepth 3 -name '*.apk' -newermt '-2 hours' -printf '%TY-%Tm-%Td %TH:%TM  %10s  %p\n' 2>/dev/null | sort || true
else
  run_as_build_user "$exec_uid" "$exec_gid" "$@"
fi
