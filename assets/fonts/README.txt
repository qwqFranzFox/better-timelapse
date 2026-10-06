The display font
================

    BetterTimelapseCJKsc-Regular.ttf    315 characters, about 55 KB

Why it is here
--------------
The camera's firmware font has Latin, Greek and Cyrillic but no CJK. Every Chinese label on the
panel therefore came out as a tofu box on the camera (confirmed on the body). Android 2.3 has no
font fallback worth relying on, so whatever is not in this file cannot be drawn at all.

What it is
----------
A subset of **Sarasa Gothic UI SC Regular** — Sarasa is a CJK programming font built from Iosevka and
Source Han Sans, licensed under the SIL Open Font License 1.1 (see OFL.txt). The UI cut is used
rather than the text cut: tighter spacing and a taller x-height, which is what a glanceable camera
panel wants. SC because the panel's language is Simplified Chinese; a Traditional Chinese table would
need its own subset, exactly as Recipe Lab ships one file per script.

Why a subset
------------
Sarasa Gothic Regular ships as a 20 MB collection covering Japanese, Korean and two Chinese
standards. The panel draws a few hundred characters. Cutting it down is the difference between a
55 KB app and a 20 MB one.

The file is renamed, as the OFL requires of a modified font, and the licence travels with it.

How to rebuild it
-----------------
    tools/subset-font.py              cut from the packaged Sarasa (pacman -S ttf-sarasa-gothic)
    tools/subset-font.py --check      report whether the built font covers what the source uses

**Run it after any change to a label.** The character set is derived from the Java sources, so a new
label is a new character, and a character the font lacks is a tofu box on a screen the user cannot
debug. The script verifies its own output by reading the finished file's cmap back and refusing to
report success if anything is missing, and `tools/test.sh` asserts the same thing, so a stale font
fails the build rather than the camera.

The subset is not byte-for-byte reproducible across fontTools versions. What is checked is what
matters: that every character the app draws is in the font.
