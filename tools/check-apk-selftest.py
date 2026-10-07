#!/usr/bin/env python3
"""Self-test for tools/check-apk.py: mutate one thing at a time and assert the verdict flips.

A verifier nobody tests is worse than no verifier: it prints `ok` while the APK is uninstallable.
An earlier version of check-apk.py shipped a hand-written reader of the APK's *binary*
AndroidManifest.xml that read every attribute one slot early and reported
"minSdkVersion — screenOrientation" — a wrong answer delivered confidently. That reader is gone;
what remains is zip-directory lookups, ELF header fields, and a real XML parse of the source
manifest. This file proves each of those flips the verdict when it should.

    tools/check-apk-selftest.py            run every case
    tools/check-apk-selftest.py -v         also print each verdict
Exit status 0 when every case matches its expected verdict.
"""

import argparse
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CHECKER = os.path.join(HERE, "check-apk.py")

PACKAGE = "com.github.qwqfranzfox.bettertimelapse"
NATIVE_LIB = "libbettertimelapse.so"

MANIFEST_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{package}" android:versionCode="{vcode}" android:versionName="{vname}">
    <uses-sdk android:minSdkVersion="{minsdk}" android:targetSdkVersion="{targetsdk}" />
    {permissions}
    <application android:label="x" />
</manifest>
"""


def manifest(**kw):
    # The source manifest holds the next target release, never a -dev suffix: the build derives
    # that itself (tools/version.sh), so the checker rejects a suffixed one.
    body = dict(package=PACKAGE, vcode="1000", vname="0.1.0", minsdk="10", targetsdk="10",
                permissions="")
    body.update(kw)
    return MANIFEST_TEMPLATE.format(**body)


def elf(machine=40):
    """A minimal ELF header with the given e_machine (40 = EM_ARM, 3 = EM_386, 62 = EM_X86_64)."""
    header = b"\x7fELF" + bytes([1, 1, 1, 0]) + b"\x00" * 8
    header += struct.pack("<HH", 3, machine)
    return header.ljust(64, b"\x00")


def build_apk(path, *, abi="armeabi", machine=40, v1=True, dex=True, lib=NATIVE_LIB, v2=False,
              manifest_bytes=b"<manifest/>"):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", manifest_bytes)
        if dex:
            z.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 64)
        if abi and lib:
            z.writestr(f"lib/{abi}/{lib}", elf(machine))
        z.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        if v1:
            z.writestr("META-INF/FAKE.SF", b"Signature-Version: 1.0\n")
            z.writestr("META-INF/FAKE.RSA", b"\x30\x82fake")
        if v2:
            z.writestr("APK Signing Block 42", b"\x00" * 16)


# name, manifest kwargs, apk kwargs, expected pass?, why
CASES = [
    ("good", {}, {}, True, "a well-formed source manifest and APK must pass"),
    ("min_sdk_11", {"minsdk": "11"}, {}, False, "the installer refuses above 10"),
    ("min_sdk_missing", {"minsdk": ""}, {}, False, "an undeclared minSdk cannot be checked"),
    ("version_suffixed", {"vname": "0.1.0-beta"}, {}, False,
     "the manifest must hold the next target release without a suffix"),
    ("version_not_xyz", {"vname": "1.0"}, {}, False, "the version must be X.Y.Z"),
    ("wrong_package", {"package": "com.example.other"}, {}, False,
     "an update must carry the same package"),
    ("internet_permission", {"permissions": '<uses-permission android:name="android.permission.INTERNET" />'},
     {}, True, "an unnecessary permission is a warning, not a failure"),
    ("abi_x86", {}, {"abi": "x86", "machine": 3}, False, "the bodies are armeabi only"),
    ("not_arm", {}, {"machine": 62}, False,
     "an x86_64 library would install and then fail to load"),
    ("no_lib", {}, {"lib": None}, False, "the settings-store driver must be present"),
    ("wrong_lib_name", {}, {"lib": "libother.so"}, False, "the library the Java side loads by name"),
    ("v2_only", {}, {"v1": False, "v2": True}, False,
     "the mistake that shipped a TimeLapse release nobody could install"),
    ("no_dex", {}, {"dex": False}, False, "the Java part must be present"),
    ("v2_also_present", {}, {"v2": True}, True, "a v2 block is not fatal; it is warned about"),
]


def run_case(name, mkwargs, akwargs, expect_pass, why, verbose, tmpdir):
    mpath = os.path.join(tmpdir, f"{name}.AndroidManifest.xml")
    with open(mpath, "w", encoding="utf-8") as f:
        f.write(manifest(**mkwargs))
    apk = os.path.join(tmpdir, f"{name}.apk")
    build_apk(apk, manifest_bytes=manifest(**mkwargs).encode("utf-8"), **akwargs)

    proc = subprocess.run(
        [sys.executable, CHECKER, apk, "--package", PACKAGE, "--native-lib", NATIVE_LIB,
         "--manifest", mpath],
        capture_output=True, text=True,
    )
    got_pass = proc.returncode == 0
    ok = got_pass == expect_pass
    print(f"  {'ok  ' if ok else 'FAIL'} {name:20} expected {'pass' if expect_pass else 'fail'}, "
          f"got {'pass' if got_pass else 'fail'}   — {why}")
    if verbose or not ok:
        for line in (proc.stdout + proc.stderr).strip().splitlines():
            print(f"        | {line}")
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    if not os.path.exists(CHECKER):
        print(f"selftest: {CHECKER} is absent", file=sys.stderr)
        return 2

    print(f"check-apk selftest: {len(CASES)} cases")
    tmpdir = tempfile.mkdtemp(prefix="check-apk-selftest-")
    failures = 0
    try:
        for name, mkwargs, akwargs, expect_pass, why in CASES:
            if not run_case(name, mkwargs, akwargs, expect_pass, why, args.verbose, tmpdir):
                failures += 1
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    print()
    if failures:
        print(f"{failures} case(s) did not match the expected verdict")
        return 1
    print(f"CHECK-APK SELFTEST OK ({len(CASES)} cases)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
