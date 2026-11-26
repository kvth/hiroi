#!/usr/bin/env python3
"""Builds app/src/main/assets/kanji-parts.txt: what each kanji is built from
(働: ⿰亻動), from the cjkvi-ids ids.txt, for the kanji cards' "Parts" and
"Used in" and the {kanji-parts} card marker.

Usage: scripts/make-kanji-parts.py <ids.txt> [out.txt]
ids.txt: https://github.com/cjkvi/cjkvi-ids (based on the CHISE IDS Database,
https://www.chise.org/ids/, GPL-2.0-or-later)

Kept: the kanji Japanese uses (JIS X 0208, 0212 and 0213) and every part they're
built from, each with its Japanese description where ids.txt has several
([J]), and how common it is by the JIS level it's in (1, 2, else 3), so that
"Used in" lists the common kanji first. Kanji ids.txt has no parts for are
left out. A part outside Unicode's first plane that no Japanese character set
has (強's 𧈧) is written as its own parts (厶 over 虫): phones' Japanese fonts
don't have it. One that has no parts (𠂉) becomes ①, a part without a character
(as ids.txt writes those), which Hiroi shows as "…".
"""
import re
import sys
from pathlib import Path

HEADER = """\
# What each kanji is built from: character, its Ideographic Description Sequence, its JIS level
# (1, 2: JIS X 0208's; 3: JIS X 0212's and 0213's; none: only a part of others).
# Made by scripts/make-kanji-parts.py from cjkvi-ids ids.txt (https://github.com/cjkvi/cjkvi-ids):
# Copyright (c) 2014-2017 CJKVI Database, based on the CHISE IDS Database
# (https://www.chise.org/ids/, MORIOKA Tomohiko and others), GPL-2.0-or-later.
"""

TAG = re.compile(r"\[([A-Z]+)\]$")

# Ideographic Description Characters (⿰ ⿱ … ⿻, and the newer ⿼ … ⿿ and ㇯)
IDC = set(chr(c) for c in range(0x2FF0, 0x3000)) | {"㇯"}


def jis_level(c):
    """1 or 2 for JIS X 0208's levels, 3 for JIS X 0212's and 0213's other kanji, else None."""
    try:
        sjis = c.encode("shift_jis")
        if len(sjis) == 2:
            code = sjis[0] << 8 | sjis[1]
            if 0x889F <= code <= 0x9872:
                return 1
            if 0x989F <= code <= 0xEAA4:
                return 2
    except UnicodeEncodeError:
        pass
    for codec in ("euc_jis_2004", "euc_jp"):
        try:
            if len(c.encode(codec)) >= 2:
                return 3
        except UnicodeEncodeError:
            pass
    return None


def japanese(descriptions):
    """The description Japan uses ([J], or one without regions), without its regions."""
    for d in descriptions:
        m = TAG.search(d)
        if m and "J" in m.group(1):
            return TAG.sub("", d)
    for d in descriptions:
        if not TAG.search(d):
            return d
    return TAG.sub("", descriptions[0])


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    source = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parent.parent / "app/src/main/assets/kanji-parts.txt"

    ids = {}
    for line in source.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) < 3:
            continue
        character, description = fields[1], japanese(fields[2:])
        if description != character:
            ids[character] = description

    def displayable(part):
        """Whether Japanese fonts have [part]: in the first plane, or in a Japanese character set"""
        return ord(part) <= 0xFFFF or jis_level(part) is not None

    def expand(description, depth=0):
        """[description] with its parts fonts don't have written as their own parts, where known"""
        out = []
        for part in description:
            if part not in IDC and not displayable(part) and part in ids and depth < 5:
                out.append(expand(ids[part], depth + 1))
            elif part not in IDC and not displayable(part):
                out.append("\u2460")
            else:
                out.append(part)
        return "".join(out)

    ids = {c: expand(d) for c, d in ids.items()}

    # The kanji Japanese uses, then the parts they're built from, and theirs
    kept = {c for c in ids if len(c) == 1 and jis_level(c)}
    todo = list(kept)
    while todo:
        for part in ids[todo.pop()]:
            if part not in IDC and part in ids and part not in kept:
                kept.add(part)
                todo.append(part)

    lines = []
    for c in sorted(kept):
        level = jis_level(c) if len(c) == 1 else None
        lines.append(f"{c}\t{ids[c]}\t{level or ''}")
    out.write_text(HEADER + "\n".join(lines) + "\n", encoding="utf-8")
    print(f"{out}: {len(lines)} kanji, {out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
