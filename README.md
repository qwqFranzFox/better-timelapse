# better-timelapse

A time-lapse app that runs **inside** a Sony camera. No phone, no cable, no computer: install it once through
`MENU → Application`, put the camera on a tripod, and leave.

Written for the bodies that still accept a PlayMemories Camera Apps app (Android 2.3.7, `armeabi`). Developed
and measured on an **ILCE-6300 / platform 2.15**.

> The design rule behind every decision in this repository: **the screen may never claim something the camera
> did not do.** A count that is not witnessed, a setting that was sent but did not apply, a warning that was
> fixed but stayed on screen — each of those is a defect here, not a polish item.

## What it does

| | |
|---|---|
| **Interval shooting** | An absolute timetable, not a chain of waits: frame *n* is due at `start + delay + n × interval`, so a late frame cannot drag the rest of the run out of phase. |
| **Card verification** | Every frame is checked against the files the camera actually wrote. "Taken" and "on the card" are two different numbers on screen, because a camera that accepts a shutter and writes nothing is a real failure mode on this hardware. |
| **Timing from the body** | The exposure is read from the camera (`shutter-speed-nume`/`deno`), the press window is derived from it, and long-exposure noise reduction is accounted for as the dark frame it is. |
| **A session log on the card** | `BTLPSE/LOG.TXT`, one line per event, appended and flushed per line: a run that dies at hour three loses its last line, not the night. It is the instrument this project is measured with. |
| **A screen that gets out of the way** | The panel opens full, drops to one line while shooting, and after a minute untouched shows only the state word. Both small modes drift slowly so the same pixels are not lit for hours. |
| **An exit rule** | Settings this app changes on the camera are put back the way it found them. |

## What is measured, and what is not

This is the part usually left out, and it is the reason the project exists.

**Verified on a camera** (ILCE-6300, platform 2.15)

- 5 s and 10 s intervals: 20 frames, 20 files, every frame accounted for, frame-to-frame error a **5 ms median**.
- The display can be switched off and back on between frames: **dark for 8.3 s of every 10 s**, captures
  unaffected, and any key wakes the panel.
- The camera's own post-shot review can be silenced from the app, so no photo flashes at the user all night.
- Frame counts, the session log, the file naming on the card, and the exit rule.

**Not verified, and therefore not claimed**

- **Runs of hours.** The longest measured run is minutes. Whether the app survives a night, and whether the
  camera kills it, is still open — the largest unknown in the project ([PLAN](docs/PLAN.md) M7).
- **Auto power-off handling** (`APO/NO`) is not implemented at all. A camera that powers itself down mid-run
  will still do so.
- **Continuing a run after the app is backgrounded.** It ends cleanly and says how many frames it got; it does
  not resume.
- **Plan persistence.** The interval and frame count do not survive a power cycle.

### A known limitation, measured

Switching this body's display output away for **more than a few seconds at a time** makes the camera ask to
repair its image database at the next power-on. It is not the card, not the interval, and not this app's card
reading — those were separated by experiment. It is that one action, and only when it lasts long enough: 5 s
intervals (2.75 s dark) are clean, 10 s (8.3 s dark) trigger it.

So screen-off is **off by default** and its dark window is capped at 2.75 s, the longest stretch this body has
been shown to tolerate. The one-line panel is what replaces it: less light, less wear, and no display switch at
all. See [DECISIONS](docs/DECISIONS.md) A50 and A51.

## Build

The host needs **podman (or docker) and git** — no JDK, no Android SDK, no NDK, and the host's own JDK version
does not matter. The image pins the toolchain the target requires: JDK 17, build-tools 30.0.3, platform
android-28, and **NDK r16b** (the last NDK with GCC 4.9, which `armeabi` + `android-14` needs).

```bash
./build-container.sh            # build the image (one-time, ~1 GB), then build.sh and the unit tests
./build-container.sh bash       # a shell inside the toolchain
./build-container.sh ./build.sh # one command
```

The result is `BetterTimelapse.apk` in the repository root. `tools/setup-toolchain.sh` plus `./build.sh` is the
equivalent path without a container, kept only for a host with no container runtime at all.

The unit tests need no camera and no SDK. They compile the camera-free decision classes with a plain JDK 17 and
**no `android.jar`** on the classpath, which is what keeps camera-free logic camera-free:

```bash
tools/test.sh                   # the pure core: plans, timing, keys, log format, display API, panel machine
tools/dev-verify.sh             # compiles every source file against hand-written platform stubs
```

## Install

The container builds; the **host** installs, because the container cannot see the camera's USB.

```bash
python3 pmca-console.py install -f BetterTimelapse.apk     # Sony-PMCA-RE
```

Then `MENU → Application → Application List` on the camera. The package name is the app's identity on the body:
a build with a different package name is a different app and cannot install over an existing one.

## Keys

The camera has no touch screen. C1 and TRASH are the two keys this app binds for its own purposes:

| Key | |
|---|---|
| **C1** | Switch the panel between full and one line |
| **TRASH** (short) | Pause and resume a run |
| **TRASH** (hold) | Stop the run |
| **MENU** (short) | End the run cleanly; from an idle screen, leave the app |
| **MENU** (hold) | Read the session log on the panel |
| ← → / wheel | Coarse and fine adjustment of the selected row |
| ↑ ↓ | Move between rows |
| **Centre** | Start; press again to confirm a run the app is holding back |
| ↓ (hold) | Rehearsal mode: the timetable runs, the shutter does not |

## Repository

```
src/com/github/qwqfranzfox/bettertimelapse/    the app
test/com/github/qwqfranzfox/bettertimelapse/   the unit tests
jni/                                           the settings-store driver (OpenMemories-Platform submodule)
tools/                                         test runner, verifier, font subsetting, APK checks, config
docs/                                          the design record — see below
docker/                                        the build image
```

Only some classes may use Android. `tools/config.sh` lists them and `tools/test.sh` compiles that list with no
`android.jar`, so an `android.*` import in a decision class is a compile error rather than a habit. The plan
arithmetic, the timing, the key model, the log format, the display API and the panel state machine all live on
that list — which is why they can be checked without a camera.

## Documentation

| | |
|---|---|
| [docs/PLATFORM-FACTS.md](docs/PLATFORM-FACTS.md) | Hard facts about the platform, each with how it was measured: what the camera will and will not do, reflection interfaces, the display API, scan codes, the rules for writing to the card. **Read this before touching camera code.** |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Every decision with its evidence, plus the open questions that still need a camera to answer. |
| [docs/PLAN.md](docs/PLAN.md) | Goals, non-goals, architecture, milestones, and the on-camera acceptance test for each. |
| [docs/PAIN-POINTS.md](docs/PAIN-POINTS.md) | The pre-existing app's defects, each with `file:line` evidence — the reason for a rewrite rather than a patch. |
| [docs/FEEL-PLAN.md](docs/FEEL-PLAN.md) | What using it feels like, the known rough edges, and the order they will be fixed in. |

## Built on the work of others

This app is an independent implementation, but not an independent idea. It exists because two projects came
before it, both MIT-licensed, and **no code from either is used here** — what was taken is what they learned,
what they measured, and what they got wrong.

| | What this project took from it |
|---|---|
| **[TimeLapse](https://github.com/jonasjuffinger/TimeLapse)** — Jonas Juffinger's time-lapse app, which runs on the same camera bodies as this one | The capture idiom that works on this hardware: a press/release pair (`takePicture` … `cancelTakePicture` + `startPreview`) with a live preview stream, and the 2500 ms shutter window it measured. Its display-off approach, which is the only thing known to darken this panel, and — documented line by line in [PAIN-POINTS](docs/PAIN-POINTS.md) — its twenty user-facing and twenty engineering defects, which are why this is a rewrite rather than a patch. |
| **[Recipe Lab](https://github.com/voxivoid/recipe-lab-sony-pmca)** — a Sony PMCA app by André Domingues | The engineering practice, most of it worth copying wholesale: no Gradle but a seven-step `build.sh`, a pinned toolchain in a container, decision classes that compile with **no `android.jar`** so the arithmetic can be tested off the camera, a bundled CJK font subset with a script and a coverage check, and its honesty about what a green build does and does not prove. Its capture loop on this same body (2500 ms press + 1200 ms settle) is also where this app's default interval comes from. |
| **[Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE)** and **[OpenMemories](https://github.com/ma1co/OpenMemories-Framework)** — ma1co | Everything that makes a third-party app possible on these cameras at all: the installer, the platform drivers this app's `jni/` submodule builds against, and the reverse-engineered API surface that says which Sony classes and methods exist. |
| **[FocusBracket](https://github.com/obs1dium/FocusBracket)** — obs1dium | Not used directly here, but it is the code base TimeLapse was built from, so the capture path this app inherits traces back through it. |

If you are writing an app for one of these cameras, read those repositories first. What the [docs](docs/) here
add is a measurement of what this particular body actually does.

## Licence

MIT — see [LICENSE](LICENSE). The bundled font is a subset of Sarasa Gothic under the SIL Open Font License 1.1,
and `jni/platform/` is a submodule with its own licence; the licence file notes both.
