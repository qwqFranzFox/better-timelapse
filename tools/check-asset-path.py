#!/usr/bin/env python3
"""Assert the asset path the code asks for is the asset path the APK carries.

Android's AssetManager is case sensitive, and a mismatch fails the way it failed here: the font
never loaded, every Chinese label was a box, and the only clue was a native error whose message is
`native typeface cannot be made: file: Filenotfound` — which reads like a corrupt font, not like a
typo. The path was written by hand in one file and produced by another, and nothing compared them.

So this compares them. It reads the constant out of the source (not a copy of it) and checks the APK
for that exact string.

    tools/check-asset-path.py                 # ./BetterTimelapse.apk
    tools/check-asset-path.py some.apk
    tools/check-asset-path.py --pre-build     # only check the path against the tree on disk
"""
import argparse
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
UIFONT = os.path.join(ROOT, "src", "com", "voxivoid", "bettertimelapse", "UiFont.java")


def asset_from_source():
    """The path UiFont.java actually asks for, read out of the file rather than copied from it."""
    try:
        text = open(UIFONT, encoding="utf-8").read()
    except OSError as e:
        return None, f"cannot read {UIFONT}: {e}"
    m = re.search(r'String\s+ASSET\s*=\s*"([^"]+)"', text)
    if not m:
        return None, f"no ASSET constant in {UIFONT}"
    return m.group(1), None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", nargs="?", default=os.path.join(ROOT, "BetterTimelapse.apk"))
    ap.add_argument("--pre-build", action="store_true",
                    help="check the path against assets/ on disk instead of inside an APK")
    args = ap.parse_args()

    want, err = asset_from_source()
    if err:
        print(f"  FAIL {err}")
        return 1

    if args.pre_build:
        on_disk = os.path.join(ROOT, "assets", want)
        if os.path.isfile(on_disk):
            print(f"  ok   asset path matches the tree: assets/{want}")
            return 0
        print(f"  FAIL no such file: assets/{want}")
        listing = sorted(
            os.path.relpath(os.path.join(d, f), os.path.join(ROOT, "assets"))
            for d, _dirs, files in os.walk(os.path.join(ROOT, "assets")) for f in files
        )
        print(f"       assets/ holds: {', '.join(listing) if listing else '(nothing)'}")
        return 1

    if not os.path.isfile(args.apk):
        print(f"  FAIL no APK at {args.apk} (build first)")
        return 1
    names = zipfile.ZipFile(args.apk).namelist()
    packed = f"assets/{want}"
    if packed in names:
        print(f"  ok   asset path matches the APK: {packed}")
        return 0

    print(f"  FAIL the code asks for {packed!r}, which is not in the APK")
    near = [n for n in names if n.startswith("assets/")]
    if near:
        print("       the APK carries:")
        for n in sorted(near):
            same_letters = n.lower() == packed.lower()
            print(f"         {n}{'   <-- same letters, different case' if same_letters else ''}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
