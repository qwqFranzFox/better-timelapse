# Building in the toolchain container

The host needs a **container runtime** (podman or docker) and **git**. It does not need a JDK, an
Android SDK, or an NDK, and the JDK the host happens to ship is irrelevant.

| | |
|---|---|
| **[../build-container.sh](../build-container.sh)** | the entry point: builds the image if it is absent, then builds the app |
| **[Dockerfile](Dockerfile)** | the toolchain image: JDK 17, build-tools 30.0.3, platform android-28, NDK r16b |
| **[entrypoint.sh](entrypoint.sh)** | matches the build account to the host user ids, then runs the command |
| **[pmca-build.sh](pmca-build.sh)** | the same as `build-container.sh`, kept for parity with Recipe Lab |
| **[pmca-run.sh](pmca-run.sh)** | run any command in the image against any project directory |

```
./build-container.sh                 # build.sh, then tools/test.sh
./build-container.sh --with-tests    # (same thing; kept for symmetry)
./build-container.sh ./build.sh      # one command
./build-container.sh bash            # a shell inside the toolchain
RUNTIME=docker ./build-container.sh  # force the runtime
```

The image is built on first use (`podman build -t pmca-build docker`), which costs about 1 GB of
downloads once. `podman images` lists it afterwards.

## Why a container, and not the host toolchain

`jni/Application.mk` pins `APP_ABI := armeabi`, `APP_STL := stlport_static`,
`APP_PLATFORM := android-14` and `NDK_TOOLCHAIN_VERSION := 4.9`. That combination is what rules out
every NDK after r16b, and a Gradle-era Android plugin cannot run on JDK 17. On a rolling-release host
a JDK 17 and an NDK r16b are not installable side by side with the system ones anyway, so the image
pins them instead and a host upgrade changes nothing. Recipe Lab reached the same conclusion first;
this project keeps the same shape.

`tools/setup-toolchain.sh` in this repository does the same job *without* a container, installing
into `.toolchain/` inside the checkout. It exists as an escape hatch for a host that has no container
runtime at all — it is not the supported path, and nothing in the repository depends on it.

## What the container cannot do

**Install on the camera.** Sony-PMCA-RE talks to the camera over USB, and a container does not see
the USB device. Build in the container, install from the host:

```
./build-container.sh
Sony-PMCA-RE/.venv/bin/python Sony-PMCA-RE/pmca-console.py install -f BetterTimelapse.apk
```

## The `errno.h` contract

`OpenMemories-Platform` ships an `errno.h` shim that belongs to the libc-less updater build; it
shadows the NDK's `<errno.h>` and breaks the native link. `build.sh` parks it and restores it from an
`EXIT` trap. A container cannot pre-empt a `kill`, so:

**a dirty `jni/platform` after a container build means the container was killed, not that the build
is broken.** Restore it with `git -C jni/platform checkout -- errno.h`.

## If the container runtime will not start

`build-container.sh` checks `podman info` before doing anything and prints diagnostics. The causes it
cannot fix for you:

| symptom | cause | check |
|---|---|---|
| `newuidmap: Could not set caps` | `/usr` is mounted `nosuid`, so the setuid helper cannot work | `findmnt -no OPTIONS /usr`, `ls -l /usr/bin/newuidmap` (needs `rws`) |
| `chmod /run/user/NNNN/libpod: read-only file system` | the runtime directory is not writable | `findmnt -no OPTIONS /run` |
| every command fails before the image is read | `/etc/subuid` / `/etc/subgid` have no entry for the user | `cat /etc/subuid` |
| the mount is not writable inside the container | rootless podman without `--userns=keep-id` | the script adds it automatically for podman |

All four are sandbox/container-host restrictions, not project problems. On a normal desktop none of
them applies. Where they do apply, use `tools/setup-toolchain.sh` + `./build.sh` instead.
