#!/usr/bin/env python3
"""Writes the launcher icon's foreground: the kanji 拾 from Noto Sans CJK JP Bold
(SIL Open Font License) as an Android vector drawable.

Usage: scripts/make-icon.py [path/to/NotoSansCJK-Bold.ttc]
"""
import sys
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTCollection

FONT = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
OUT = "app/src/main/res/drawable/ic_launcher_foreground.xml"
CHAR = "拾"
SIZE = 108  # adaptive icon viewport; the middle 66 units are always visible
GLYPH_HEIGHT = 50

font = next(f for f in TTCollection(FONT).fonts if "JP" in f["name"].getDebugName(1))
glyphs = font.getGlyphSet()
name = font.getBestCmap()[ord(CHAR)]

bounds = BoundsPen(glyphs)
glyphs[name].draw(bounds)
x_min, y_min, x_max, y_max = bounds.bounds
scale = GLYPH_HEIGHT / (y_max - y_min)
# Font units have y going up; the vector's goes down. Centre the glyph in the viewport.
dx = SIZE / 2 - (x_min + x_max) / 2 * scale
dy = SIZE / 2 + (y_min + y_max) / 2 * scale
svg = SVGPathPen(glyphs, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
glyphs[name].draw(TransformPen(svg, (scale, 0, 0, -scale, dx, dy)))

with open(OUT, "w") as f:
    f.write(f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {CHAR} from Noto Sans CJK JP Bold (SIL Open Font License), made by scripts/make-icon.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="{SIZE}"
    android:viewportHeight="{SIZE}">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="{svg.getCommands()}" />
</vector>
""")
print(f"{OUT}: {CHAR} ({name}), {len(svg.getCommands())} characters of path data")
