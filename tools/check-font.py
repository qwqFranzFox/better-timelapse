#!/usr/bin/env python3
"""Assert the bundled font can draw every character the app puts on screen.

The camera font has no CJK, so the APK carries its own subset. Android 2.3 has no fallback worth
relying on, which makes this check a hard gate rather than a nicety: a character missing from the
font is a tofu box on a screen the user has no way to debug.

    tools/check-font.py
"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from importlib.machinery import SourceFileLoader
sf = SourceFileLoader("subset_font", os.path.join(HERE, "subset-font.py")).load_module()

ROOT = os.path.dirname(HERE)
FONT = os.path.join(ROOT, "assets", "fonts", "BetterTimelapseCjkSC-Regular.ttf")

def main():
    try:
        from fontTools.ttLib import TTFont
    except ImportError:
        # A missing optional tool skips with a reason instead of failing the build (docs/DECISIONS.md A16).
        # This check is a hard gate when it runs -- a character absent from the bundled subset is a tofu box
        # on a camera screen -- so the skip is loud, and the container image ships python3-fonttools so that
        # the build this project actually releases from always runs it.
        print("      SKIPPED: no fontTools (apt install python3-fonttools, or pip install fonttools)")
        return 0
    needed = sf.collect_characters()
    if not os.path.exists(FONT):
        print(f"  FAIL font absent: {FONT}")
        return 1
    cmap = TTFont(FONT, lazy=True).getBestCmap()
    missing = sorted(c for c in needed if ord(c) not in cmap)
    if missing:
        print(f"  FAIL font lacks {len(missing)} needed characters: {''.join(missing[:40])}")
        print("       run tools/subset-font.py")
        return 1
    print(f"  ok   font covers all {len(needed)} characters "
          f"({os.path.getsize(FONT)} bytes, {len(cmap)} code points)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
