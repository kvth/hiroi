#!/usr/bin/env python3
"""Builds app/src/main/assets/example-dictionary.zip: the Jitendex entries for
the words in the app's example section (and on the Japanese Android Settings
screen, for scripts/emulator.sh), plus a little pitch and frequency data and a
few kanji, so the example works without importing a full dictionary.

Usage: scripts/make-example-dictionary.py <jitendex-yomitan.zip> [out.zip]
Jitendex: https://jitendex.org (CC BY-SA 4.0, see the attribution below)
"""
import json
import sys
import zipfile
from pathlib import Path

TITLE = "Hiroi example dictionary"  # MainActivity.EXAMPLE_DICTIONARY must match

# Every word in the example sentences (res/values/strings.xml, example_sentences)
EXAMPLE_WORDS = [
    "昨日", "友達", "新しい", "アプリ", "教える", "貰う", "早速", "使う", "見る", "設定", "変える",
    "通知", "届く", "毎日", "日本語", "勉強", "子供", "頃", "野菜", "食べる",
]
# Text on the Japanese Settings app's home screen
SETTINGS_WORDS = [
    "検索", "最近", "デフォルト", "接続", "履歴", "会話", "表示", "音", "使用", "済み",
    "ネットワーク", "インターネット", "バッテリー", "ストレージ", "空き", "容量",
]

# Not from Jitendex: downstep positions and rough frequency ranks, just so the
# example shows what pitch and frequency dictionaries look like.
PITCH = {
    "使う": ("つかう", 0), "設定": ("せってい", 0), "昨日": ("きのう", 2), "友達": ("ともだち", 0),
    "新しい": ("あたらしい", 4), "勉強": ("べんきょう", 0), "食べる": ("たべる", 2), "日本語": ("にほんご", 0),
    "毎日": ("まいにち", 1), "野菜": ("やさい", 0), "子供": ("こども", 0), "通知": ("つうち", 0),
}
FREQUENCY = {"使う": 120, "見る": 60, "新しい": 250, "友達": 480, "昨日": 520, "毎日": 600, "設定": 850}
KANJI = [
    ["使", "シ", "つか.う", "", ["use", "employ"], {}],
    ["設", "セツ", "もう.ける", "", ["establish", "set up"], {}],
    ["食", "ショク ジキ", "く.う た.べる", "", ["eat", "food"], {}],
    ["勉", "ベン", "つと.める", "", ["exertion", "endeavour"], {}],
]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    source = zipfile.ZipFile(sys.argv[1])
    out = Path(sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/example-dictionary.zip")
    jitendex = json.loads(source.read("index.json"))

    wanted = set(EXAMPLE_WORDS + SETTINGS_WORDS)
    terms = []
    for name in sorted(n for n in source.namelist() if n.startswith("term_bank_")):
        terms += [row for row in json.loads(source.read(name)) if row[0] in wanted]
    missing = wanted - {row[0] for row in terms}
    if missing:
        sys.exit(f"not in Jitendex: {', '.join(sorted(missing))}")

    meta = [[word, "pitch", {"reading": reading, "pitches": [{"position": position}]}]
            for word, (reading, position) in PITCH.items()]
    meta += [[word, "freq", rank] for word, rank in FREQUENCY.items()]

    index = {
        "title": TITLE,
        "revision": jitendex["revision"],
        "format": 3,
        "description": "The words of Hiroi's example sentences. Definitions from " + jitendex["title"]
        + "; pitch, frequency and kanji are small hand-written samples.",
        "attribution": jitendex["attribution"],
        "url": jitendex["url"],
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("index.json", json.dumps(index, ensure_ascii=False, indent=1))
        z.writestr("term_bank_1.json", json.dumps(terms, ensure_ascii=False))
        z.writestr("term_meta_bank_1.json", json.dumps(meta, ensure_ascii=False))
        z.writestr("kanji_bank_1.json", json.dumps(KANJI, ensure_ascii=False))
    print(f"{out}: {len(terms)} Jitendex entries for {len(wanted)} words, {out.stat().st_size // 1024} KB")


main()
