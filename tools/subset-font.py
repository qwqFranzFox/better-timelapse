#!/usr/bin/env python3
"""Build the bundled display font: Sarasa Gothic, cut down to the characters this app draws.

Why the app needs its own font at all: the camera's firmware font has Latin, Greek and Cyrillic but
no CJK, so every Chinese label on the panel comes out as a tofu box. Confirmed on the camera.

Why a *subset* rather than the font: Sarasa Gothic Regular ships as a 20 MB collection covering
Japanese, Korean and two Chinese standards. The panel draws a couple of hundred characters, so the
file it actually needs is tens of kilobytes. Android 2.3 has no font fallback worth relying on, so
whatever is not in this file cannot be drawn at all — which is why the character set is derived from
the source rather than guessed, and why the coverage is asserted (tools/test.sh, HudTest).

    tools/subset-font.py                 rebuild assets/fonts/*.ttf
    tools/subset-font.py --check         only report the characters needed and whether the built
                                         font covers them; write nothing

The source font is taken from the system (Sarasa Gothic is packaged as ttf-sarasa-gothic); pass
--source to point at a different file. The output is renamed, as the OFL requires of a modified
font, and the licence travels with it.
"""

import argparse
import glob
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC_DIR = os.path.join(ROOT, "src")
FONT_DIR = os.path.join(ROOT, "assets", "fonts")

# The family inside the collection. "UI" is Sarasa's UI-optimised cut: tighter spacing and a taller
# x-height than the text cut, which is what a glanceable camera panel wants. SC because the panel's
# language is Simplified Chinese; a Traditional table would need its own file, exactly as Recipe Lab
# ships one per script.
FAMILY = "Sarasa UI SC"
STYLE = "Regular"

OUT_NAME = "BetterTimelapseCjkSC-Regular.ttf"
DEFAULT_SOURCES = [
    "/usr/share/fonts/sarasa-gothic/Sarasa-Regular.ttc",
    "/usr/share/fonts/sarasa-gothic/SarasaGothic-Regular.ttf",
    "/usr/share/fonts/TTF/Sarasa-Regular.ttc",
]

# Characters to carry beyond what the source happens to use today. Cheap insurance: the panel will
# grow labels, and a missing glyph is a tofu box on a screen the user cannot debug. Everything here
# is a few hundred bytes of font data.
EXTRA = (
    "0123456789"                                    # every duration and count
    ":-+/.%()[]<>|=*#!?,'\"\u00b7\u2026\u2013\u2014"     # punctuation the panel uses or may use
    # Arrows and blocks only. U+25B8 and friends are deliberately absent: Sarasa has no such
    # glyph, and the panel's selection marker is drawn as a triangle instead (PanelView).
    "\u00b1\u2190\u2192\u2191\u2193\u25cf\u25a0\u25a1\u2588\u2591"  # arrows, bars, markers
    "\u3001\u3002\uff0c\uff1a\uff1b\uff01\uff1f\uff08\uff09\u3010\u3011\u300a\u300b\u201c\u201d"
    "\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341\u767e\u5343\u4e07"  # 一二三四五六七八九十百千万
    "\u79d2\u5206\u5c0f\u65f6\u5929\u5468\u6708\u5e74"                                  # 秒分小时天周月年
    "\u5f00\u5173\u662f\u5426\u6709\u65e0\u53ef\u4e0d\u5728\u4e3a\u4e86\u7684\u5df2\u672a"
    "\u6b63\u5728\u5b8c\u6210\u505c\u6b62\u7ee7\u7eed\u91cd\u65b0\u8bbe\u7f6e\u4fdd\u5b58"
    "\u8bfb\u53d6\u5199\u5165\u68c0\u67e5\u6d4b\u8bd5\u5931\u8d25\u6210\u529f\u9519\u8bef"
    "\u76f8\u673a\u955c\u5934\u5b58\u50a8\u5361\u7535\u91cf\u4f20\u611f\u5668\u66f4\u65b0"
    "\u7248\u672c\u8bed\u8a00\u7b80\u4f53\u7e41\u4f53\u4e2d\u6587\u82f1\u6587\u81ea\u52a8"
    "\u9884\u89c8\u6d4f\u89c8\u4e3b\u9875\u83dc\u5355\u9000\u51fa\u8fd4\u56de\u9009\u62e9"
    "\u8c03\u6574\u5207\u6362\u6d88\u9664\u91cd\u7f6e\u6062\u590d\u9ed8\u8ba4\u63a8\u8350"
)


def collect_characters():
    """Every character the app can put on screen, from the Java string literals."""
    chars = set()
    # \uXXXX escapes: the source is ASCII, so this is the whole of the on-screen text.
    for path in glob.glob(os.path.join(SRC_DIR, "**", "*.java"), recursive=True):
        text = open(path, encoding="utf-8").read()
        for m in re.finditer(r"\\u([0-9a-fA-F]{4})", text):
            chars.add(chr(int(m.group(1), 16)))
        # Any literal non-ASCII characters too, in case a label is written directly rather than
        # escaped. Cheap to include, and missing one is a tofu box.
        for ch in text:
            if ord(ch) > 0x7F and ch not in "\u2018\u2019":
                chars.add(ch)
    chars.update(EXTRA)
    # ASCII printable, always: durations, hex ids, model names.
    chars.update(chr(c) for c in range(0x20, 0x7F))
    return chars


def name_of(font, name_id):
    for rec in font["name"].names:
        if rec.nameID == name_id:
            try:
                return rec.toUnicode()
            except Exception:
                pass
    return ""


def open_source(path):
    """Pick the family/style wanted out of a .ttf or a .ttc."""
    from fontTools.ttLib import TTFont, TTCollection
    if path.lower().endswith(".ttc"):
        collection = TTCollection(path, lazy=False)
        for font in collection.fonts:
            if name_of(font, 1) == FAMILY and name_of(font, 2) == STYLE:
                return font
        families = sorted({name_of(f, 1) for f in collection.fonts})
        raise SystemExit(f"subset-font: {FAMILY} {STYLE} is not in {path}\n"
                         f"  families present: {', '.join(families[:12])}…")
    return TTFont(path)


def find_source(explicit):
    if explicit:
        if not os.path.exists(explicit):
            raise SystemExit(f"subset-font: --source {explicit} does not exist")
        return explicit
    for p in DEFAULT_SOURCES:
        if os.path.exists(p):
            return p
    for p in glob.glob("/usr/share/fonts/**/*[Ss]arasa*.tt[cf]", recursive=True):
        return p
    raise SystemExit(
        "subset-font: no Sarasa Gothic font found.\n"
        "  Install it (Arch: pacman -S ttf-sarasa-gothic; Debian/Ubuntu: apt install fonts-sarasa-gothic)\n"
        "  or pass --source /path/to/Sarasa-Regular.ttc"
    )


def rename(font, name):
    """Rename per the OFL, which requires a modified font to carry a different name."""
    full = name.replace("-", " ").replace(".ttf", "")
    for rec in font["name"].names:
        if rec.nameID in (1, 3, 4, 6, 16, 17):
            rec.string = full
        elif rec.nameID == 2:
            rec.string = "Regular"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", help="the Sarasa file to cut (default: the packaged one)")
    ap.add_argument("--check", action="store_true", help="report coverage, write nothing")
    ap.add_argument("--out", default=os.path.join(FONT_DIR, OUT_NAME))
    ap.add_argument("--conservative", action="store_true",
                    help="also drop the OpenType layout and vertical-writing tables")
    args = ap.parse_args()

    needed = collect_characters()
    cjk = sorted(c for c in needed if ord(c) > 0x2E7F)
    print(f"characters needed: {len(needed)}  (of which CJK: {len(cjk)})")

    if args.check:
        if not os.path.exists(args.out):
            print(f"  {args.out} does not exist — build it with tools/subset-font.py")
            return 1
        from fontTools.ttLib import TTFont
        cmap = TTFont(args.out, lazy=True).getBestCmap()
        missing = sorted(c for c in needed if ord(c) not in cmap)
        if missing:
            print(f"  MISSING {len(missing)}: {''.join(missing[:40])}")
            return 1
        print(f"  {os.path.basename(args.out)} covers all {len(needed)} characters "
              f"({os.path.getsize(args.out)} bytes)")
        return 0

    source = find_source(args.source)
    print(f"source: {source}")
    font = open_source(source)
    print(f"  family: {name_of(font, 1)} / {name_of(font, 2)}   upm={font['head'].unitsPerEm}")

    from fontTools import subset
    options = subset.Options()
    # Android 2.3 shapes nothing and reads static tables only, so drop what it cannot use: the
    # layout features, the hinting, and the name records other than the ones a font needs.
    options.layout_features = []
    options.hinting = False
    options.glyph_names = False
    options.notdef_outline = True
    options.recalc_bounds = True
    options.drop_tables += ["DSIG"]
    # A conservative table set, on top of the above. `layout_features = []` empties the OpenType
    # layout tables without removing them, and a subset that still carries GSUB/GPOS/GDEF/VORG/vhea/
    # vmtx is a lot of machinery for an engine that shapes nothing and lays text out horizontally
    # only. Recipe Lab ships those tables and its font works, so this is not known to be the cause —
    # it is the cheapest difference to remove while the real error is still unidentified.
    if args.conservative:
        options.drop_tables += ["GDEF", "GPOS", "GSUB", "VORG", "vhea", "vmtx",
                                "BASE", "STAT", "gasp", "kern", "morx", "mort"]
    options.name_IDs = ["*"]
    options.name_legacy = True
    options.name_languages = ["*"]

    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=[ord(c) for c in sorted(needed)])
    subsetter.subset(font)
    rename(font, OUT_NAME)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    font.save(args.out)
    size = os.path.getsize(args.out)
    print(f"wrote {args.out}  ({size} bytes)")

    # Prove the output, not the intent: read the file back and check every character is in its cmap.
    from fontTools.ttLib import TTFont
    cmap = TTFont(args.out, lazy=True).getBestCmap()
    missing = sorted(c for c in needed if ord(c) not in cmap)
    if missing:
        print(f"  FAIL: {len(missing)} characters missing: {''.join(missing[:40])}")
        return 1
    print(f"  verified: all {len(needed)} characters present, "
          f"{len(cmap)} code points in the cmap, upm={TTFont(args.out, lazy=True)['head'].unitsPerEm}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
