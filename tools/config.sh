#!/usr/bin/env bash
# Project identity, in one place. Sourced by build.sh and tools/test.sh so a rename is one edit.
#
# The package name and the app id are load-bearing: once an APK is installed on a camera, an
# update must carry the same package name *and* be signed with the same certificate, or the
# camera refuses to install over it (docs/PLATFORM-FACTS.md §3). Treat both as frozen.

# Java package. The source tree is src/<package as directories>/, and aapt generates R.java into
# out/gen/<package as directories>/R.java from the manifest's package attribute.
APP_PACKAGE="com.github.qwqfranzfox.bettertimelapse"
APP_PACKAGE_PATH="com/github/qwqfranzfox/bettertimelapse"

# The native library: System.loadLibrary("bettertimelapse") in NativeStore.java, and
# LOCAL_MODULE := bettertimelapse in jni/Android.mk. All three must agree.
NATIVE_LIB="bettertimelapse"

# What lands next to build.sh.
APK_NAME="BetterTimelapse"

# Source roots, relative to the repository root.
SOURCE_DIR="src"
TEST_DIR="test"

# The classes tools/test.sh compiles WITHOUT android.jar. Being listed here is what makes "no
# android.* import in a decision class" a compile error rather than a convention, so add a class
# the moment it stops needing Android — and never add one that does.
#
# These four hold the whole panel: what the plan is, what a detent is worth, which key is which, and
# every string and duration on screen. None of them can see Android, which is why the arithmetic
# that the old app got wrong can be checked off the camera.
UNITS=(
    Plan
    Steps
    Keys
    Hud
    Schedule
    Engine
    Frames
    BodyCycle
    CardNames
    LogLines
    DisplayApi
    Screen
    Teardown
    PanelModes
    PanelMachine
)
