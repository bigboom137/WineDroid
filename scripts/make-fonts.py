#!/usr/bin/env python3
"""Build Arial, Times New Roman and Courier New from the Liberation fonts.

usage: make-fonts.py <liberation-fonts-ttf dir> <output dir>

Liberation is metric-compatible with these fonts, and Proton ships it under
their names too: its wine.inf registers arial.ttf, times.ttf and cour.ttf,
and Windows programs (Chromium's default sans-serif font is Arial) expect
them. The OFL reserves the name "Liberation" for unmodified fonts, so the
renamed copies must drop it anyway. Needs fontTools.
"""
import os
import sys

from fontTools.ttLib import TTFont

# Liberation family, Windows family, file name stem, PostScript stem
FAMILIES = [
    ("Liberation Sans", "Arial", "arial", "Arial"),
    ("Liberation Serif", "Times New Roman", "times", "TimesNewRoman"),
    ("Liberation Mono", "Courier New", "cour", "CourierNew"),
]
STYLES = [("Regular", ""), ("Bold", "bd"), ("Italic", "i"), ("BoldItalic", "bi")]


def rename(src, dst, old, new, ps_new):
    font = TTFont(src)
    ps_old = old.replace(" ", "")
    for rec in font["name"].names:
        # keep copyright, license and URLs (0, 7, 13, 14) as they are
        if rec.nameID not in (1, 3, 4, 6, 16, 18, 21):
            continue
        text = rec.toUnicode()
        text = text.replace(old, new).replace(ps_old, ps_new)
        rec.string = text
    font.save(dst)


def main():
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    for old, new, stem, ps_new in FAMILIES:
        for style, suffix in STYLES:
            src = os.path.join(src_dir, "%s-%s.ttf" % (old.replace(" ", ""), style))
            dst = os.path.join(out_dir, stem + suffix + ".ttf")
            rename(src, dst, old, new, ps_new)
            print(dst)


main()
