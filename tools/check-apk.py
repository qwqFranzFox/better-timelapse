#!/usr/bin/env python3
"""Check a built APK against what a Sony PMCA body requires, with no Android tooling.

This is the "inspect the output" half of the build's red test. `apksigner verify` answers "is this
signature well formed"; nothing answers "would this install and run". Every rule below comes either
from a failure that has actually happened in this problem space, or from the platform's hard
requirements (docs/PLATFORM-FACTS.md §2, §3):

  ABI armeabi only            the bodies are ARMv7 with no other ABI
  the library is ARM code     a build that silently produced x86 would install and then fail to load
  a v1 signature              the camera ignores v2/v3; a v2-only APK installs nowhere. A TimeLapse
                              release shipped exactly that way and nobody could install it
  classes.dex present         the Java part
  the native library present  the settings-store driver
  no unexpected permissions   an app that asks for INTERNET on a camera deserves a second look

Two deliberate design choices, both learned the hard way:

  * Nothing here parses the APK's *binary* AndroidManifest.xml. A hand-written reader of that format
    is easy to get subtly wrong — a version of this file read every attribute one slot early and
    reported "minSdkVersion: screenOrientation" — and a verifier that is wrong while printing `ok` is
    worse than no verifier. Version and minSdk are checked where they are declared and readable:
    AndroidManifest.xml in the source tree, with a real XML parser.
  * Every check is either a zip-directory lookup or an ELF header field. Those are formats small
    enough to be read correctly in a few lines, and the selftest mutates each one.

Usage:
    tools/check-apk.py BetterTimelapse.apk
    tools/check-apk.py BetterTimelapse.apk --package com.github.qwqfranzfox.bettertimelapse --native-lib libbettertimelapse.so
Exit status is 0 when every check passes, 1 otherwise.
"""

import argparse
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
EM_ARM = 40
MAX_MIN_SDK = 10

# Permissions a camera app has no business asking for. Not fatal — a warning, because the reason
# might be legitimate one day — but the old app asked for INTERNET and Wi-Fi and used neither.
SUSPICIOUS_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.ACCESS_NETWORK_STATE",
}


class Result:
    def __init__(self):
        self.failed = 0
        self.warned = 0

    def ok(self, what, detail=""):
        print(f"  ok   {what}{'  — ' + detail if detail else ''}")

    def fail(self, what, detail=""):
        self.failed += 1
        print(f"  FAIL {what}{'  — ' + detail if detail else ''}")

    def warn(self, what, detail=""):
        self.warned += 1
        print(f"  warn {what}{'  — ' + detail if detail else ''}")


def elf_machine(blob):
    """The e_machine field of an ELF file, or None when it is not an ELF."""
    if len(blob) < 20 or blob[:4] != b"\x7fELF":
        return None
    endian = "<" if blob[5] == 1 else ">"
    return struct.unpack_from(f"{endian}H", blob, 18)[0]


def read_source_manifest(path):
    """Parse a source AndroidManifest.xml. Returns (attrs, permissions) or (None, None)."""
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        return None, None
    attrs = {
        "package": root.get("package"),
        "versionCode": root.get(f"{ANDROID_NS}versionCode"),
        "versionName": root.get(f"{ANDROID_NS}versionName"),
    }
    uses_sdk = root.find("uses-sdk")
    if uses_sdk is not None:
        attrs["minSdkVersion"] = uses_sdk.get(f"{ANDROID_NS}minSdkVersion")
        attrs["targetSdkVersion"] = uses_sdk.get(f"{ANDROID_NS}targetSdkVersion")
    perms = [p.get(f"{ANDROID_NS}name") for p in root.findall("uses-permission")]
    return attrs, [p for p in perms if p]


def check_source_manifest(r, manifest_path, want_package):
    """The declaration side: this is what a build injects into the APK."""
    print(f"source manifest: {manifest_path}")
    if not os.path.exists(manifest_path):
        r.fail("AndroidManifest.xml is present", manifest_path)
        return
    attrs, perms = read_source_manifest(manifest_path)
    if attrs is None:
        r.fail("AndroidManifest.xml parses as XML", manifest_path)
        return

    pkg = attrs.get("package")
    if want_package and pkg != want_package:
        r.fail("package name", f"manifest has {pkg}, expected {want_package}")
    elif pkg:
        r.ok("package name", pkg)
    else:
        r.fail("package name declared")

    vname, vcode = attrs.get("versionName"), attrs.get("versionCode")
    if vname and re.fullmatch(r"\d+\.\d+\.\d+", vname or ""):
        r.ok("versionName is X.Y.Z", f"{vname} (a build appends -dev.N)")
    else:
        r.fail("versionName is X.Y.Z", repr(vname))
    if vcode and str(vcode).isdigit():
        r.ok("versionCode declared", str(vcode))
    else:
        r.fail("versionCode declared", repr(vcode))

    min_sdk = attrs.get("minSdkVersion")
    if min_sdk is None:
        r.fail("minSdkVersion declared")
    elif int(min_sdk) <= MAX_MIN_SDK:
        r.ok("minSdkVersion", f"{min_sdk} (the installer refuses above {MAX_MIN_SDK})")
    else:
        r.fail("minSdkVersion", f"{min_sdk}, must be <= {MAX_MIN_SDK}")
    if attrs.get("targetSdkVersion"):
        r.ok("targetSdkVersion", attrs["targetSdkVersion"])

    for p in perms:
        if p in SUSPICIOUS_PERMISSIONS:
            r.warn("permission not needed by a camera app", p)
    if perms:
        r.ok("uses-permission", ", ".join(sorted(perms)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--package", default=None, help="the package name the manifest must carry")
    ap.add_argument("--native-lib", default=None, help="e.g. libbettertimelapse.so")
    ap.add_argument("--manifest", default=None, help="source manifest (default: ./AndroidManifest.xml)")
    ap.add_argument("--source-only", action="store_true", help="skip the APK checks")
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    manifest_path = args.manifest or os.path.join(os.path.dirname(here), "AndroidManifest.xml")

    r = Result()
    check_source_manifest(r, manifest_path, args.package)

    if not args.source_only:
        print(f"apk: {args.apk}")
        try:
            zf = zipfile.ZipFile(args.apk)
        except (OSError, zipfile.BadZipFile) as e:
            r.fail("APK is a readable zip", str(e))
            print()
            print(f"{r.failed} check(s) FAILED, {r.warned} warning(s)")
            return 1
        names = zf.namelist()

        if "AndroidManifest.xml" in names:
            r.ok("AndroidManifest.xml present")
        else:
            r.fail("AndroidManifest.xml present")

        if "classes.dex" in names:
            r.ok("classes.dex", f"{zf.getinfo('classes.dex').file_size} bytes")
        else:
            r.fail("classes.dex present")

        libs = [n for n in names if n.startswith("lib/")]
        if not libs:
            r.fail("native library present", "no lib/ entries")
        for lib in libs:
            parts = lib.split("/")
            if len(parts) != 3:
                r.warn("native library path", lib)
            abi = parts[1] if len(parts) > 2 else "?"
            if abi == "armeabi":
                r.ok("native library in armeabi", lib)
            else:
                r.fail("ABI is armeabi", f"{lib} is in {abi}; the bodies are ARMv7 + armeabi only")
            machine = elf_machine(zf.read(lib))
            if machine == EM_ARM:
                r.ok("native library is ARM code", f"e_machine={machine}")
            elif machine is None:
                r.fail("native library is an ELF", lib)
            else:
                r.fail("native library is ARM code", f"e_machine={machine}, expected {EM_ARM}")
            if args.native_lib:
                if lib.endswith(args.native_lib):
                    r.ok("expected native library name", args.native_lib)
                else:
                    r.fail("expected native library name", f"{args.native_lib} is not in the APK")

        v1 = [n for n in names if re.match(r"META-INF/.*\.(SF|RSA|DSA|EC)$", n)]
        if v1:
            r.ok("v1 signature present", ", ".join(sorted(v1)))
        else:
            r.fail("v1 signature present",
                   "the camera ignores v2/v3; without META-INF/*.SF+*.RSA it installs nowhere")

        try:
            with open(args.apk, "rb") as f:
                blob = f.read()
            if b"APK Sig Block 42" in blob:
                r.warn("APK Signing Block present", "v2/v3 signing was not disabled")
            else:
                r.ok("no v2/v3 signing block", "v1 only, as the camera requires")
        except OSError as e:
            r.warn("could not read the file for the signing-block check", str(e))

    print()
    if r.failed:
        print(f"{r.failed} check(s) FAILED, {r.warned} warning(s)")
        return 1
    print(f"APK OK ({r.warned} warning(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())
