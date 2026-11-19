"""Spot checks for core.db and dictionary packs. Exit code 1 on any failure.

Usage: tools/.venv/bin/python tools/tests/check_packs.py
"""
import re
import sqlite3
import sys
import time
from pathlib import Path

import zstandard

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import paths  # noqa: E402

# (headword, reading, expected accent) — verified against NHK / standard references.
PITCH = [
    ("食べる", "たべる", "2"), ("橋", "はし", "2"), ("箸", "はし", "1"), ("端", "はし", "0"),
    ("雨", "あめ", "1"), ("飴", "あめ", "0"), ("生物", "せいぶつ", "1"), ("昨日", "きのう", "2"),
    ("日本", "にほん", "2"), ("行く", "いく", "0"), ("読む", "よむ", "1"), ("先生", "せんせい", "3"),
    ("学校", "がっこう", "0"), ("東京", "とうきょう", "0"), ("綺麗", "きれい", "1"),
    ("大丈夫", "だいじょうぶ", "3"), ("花", "はな", "2"), ("鼻", "はな", "0"), ("箱", "はこ", "0"),
    ("犬", "いぬ", "2"), ("猫", "ねこ", "1"), ("山", "やま", "2"), ("川", "かわ", "2"),
    ("水", "みず", "0"), ("友達", "ともだち", "0"), ("勉強", "べんきょう", "0"),
    ("始める", "はじめる", "0"), ("見る", "みる", "1"), ("来る", "くる", "1"), ("為る", "する", "0"),
    ("言う", "いう", "0"), ("話す", "はなす", "2"), ("書く", "かく", "1"), ("赤い", "あかい", "0"),
    ("白い", "しろい", "2"), ("高い", "たかい", "2"), ("新しい", "あたらしい", "4"),
    ("電話", "でんわ", "0"), ("時計", "とけい", "0"), ("朝", "あさ", "1"), ("昼", "ひる", "2"),
    ("夜", "よる", "1"), ("今日", "きょう", "1"), ("明日", "あした", "3"), ("箸", "はし", "1"),
    ("柿", "かき", "0"), ("牡蠣", "かき", "1"), ("神", "かみ", "1"), ("紙", "かみ", "2"),
    ("髪", "かみ", "2"),
]

# (dictionary, headword, reading, JMdict ent_seq that must be linked)
ALIGN = [
    ("daijisen", "食べる", "たべる", 1358280), ("meikyo3", "食べる", "たべる", 1358280),
    ("shinmeikai8", "食べる", "たべる", 1358280), ("daijisen", "哀悼", "あいとう", None),
    ("daijisen", "橋", "はし", 1237410), ("meikyo3", "橋", "はし", 1237410),
    ("shinmeikai8", "橋", "はし", 1237410),
]

ALLOWED_TAGS = {"p", "br", "b", "i", "sup", "sub", "ruby", "rt", "span", "a", "img"}
ALLOWED_SPAN = {"pos", "sense", "ex", "label", "note", "mark"}

failures = []


def fail(msg):
    failures.append(msg)
    print("FAIL", msg)


def seq_of(core, head, reading):
    row = core.execute(
        "SELECT f.entry_id FROM form f JOIN entry e ON e.id = f.entry_id "
        "WHERE (f.kanji = ? OR f.reading = ?) AND f.reading = ? "
        "ORDER BY f.kanji = ? DESC, e.freq LIMIT 1", (head, head, reading, head)).fetchone()
    return row[0] if row else None


def check_core(core):
    for head, reading, want in PITCH:
        seq = seq_of(core, head, reading)
        row = core.execute("SELECT accent, source FROM pitch WHERE entry_id = ? AND reading = ?",
                           (seq, reading)).fetchone() if seq else None
        got = row[0].split(",")[0] if row else None
        if got != want:
            fail(f"pitch {head}（{reading}）: want {want}, got {row}")
    print(f"pitch: {len(PITCH) - sum('pitch' in f for f in failures)}/{len(PITCH)} correct")


def open_pack(dict_id):
    conn = sqlite3.connect(paths.DATAPACKS / f"dict_{dict_id}.db")
    zd = zstandard.ZstdCompressionDict(conn.execute("SELECT data FROM zdict WHERE id = 1").fetchone()[0])
    return conn, zstandard.ZstdDecompressor(dict_data=zd)


def check_pack(core, dict_id):
    conn, dctx = open_pack(dict_id)
    n = conn.execute("SELECT count(*) FROM dict_entry").fetchone()[0]
    ids = {r[0] for r in conn.execute("SELECT id FROM dict_entry")}

    # Markup vocabulary and link integrity over every entry.
    bad_tags, bad_links = {}, 0
    t0 = time.perf_counter()
    for eid, blob in conn.execute("SELECT id, body FROM dict_entry"):
        text = dctx.decompress(blob).decode("utf-8")
        for tag in re.findall(r"</?([a-zA-Z0-9-]+)", text):
            if tag not in ALLOWED_TAGS:
                bad_tags[tag] = eid
        for cls in re.findall(r'<span class="([^"]*)"', text):
            if cls not in ALLOWED_SPAN:
                bad_tags["span." + cls] = eid
        for href in re.findall(r'<a href="#(\d+)"', text):
            if int(href) not in ids:
                bad_links += 1
    per_entry_us = (time.perf_counter() - t0) / n * 1e6
    if bad_tags:
        fail(f"{dict_id}: unexpected markup {dict(list(bad_tags.items())[:10])}")
    if bad_links:
        fail(f"{dict_id}: {bad_links} links to missing entries")

    for d, head, reading, want in ALIGN:
        if d != dict_id:
            continue
        seq = want or seq_of(core, head, reading)
        hit = conn.execute("SELECT entry_id FROM dict_link WHERE core_id = ?", (seq,)).fetchall()
        if not hit:
            fail(f"{dict_id}: {head}（{reading}） not linked to JMdict {seq}")
            continue
        body = dctx.decompress(conn.execute("SELECT body FROM dict_entry WHERE id = ?", (hit[0][0],)).fetchone()[0]).decode()
        title, _, rest = body.partition("\n")
        print(f"  {dict_id} {head}: {title} | {re.sub('<rt>.*?</rt>|<[^>]+>', '', rest)[:50]}")

    common = core.execute("SELECT count(*) FROM entry WHERE freq < 99").fetchone()[0]
    conn.execute(f"ATTACH '{paths.CORE_DB}' AS core")
    covered = conn.execute(
        "SELECT count(*) FROM core.entry e WHERE e.freq < 99 AND EXISTS "
        "(SELECT 1 FROM dict_link l WHERE l.core_id = e.id)").fetchone()[0]
    size = (paths.DATAPACKS / f"dict_{dict_id}.db").stat().st_size / 1e6
    print(f"{dict_id}: {n} entries, {size:.1f} MB, common JMdict words covered "
          f"{covered}/{common} = {covered / common:.1%}, decompress+scan {per_entry_us:.0f} µs/entry")


def main():
    core = sqlite3.connect(paths.CORE_DB)
    check_core(core)
    for d in ("daijisen", "meikyo3", "shinmeikai8"):
        check_pack(core, d)
    total = sum(p.stat().st_size for p in paths.DATAPACKS.glob("*.db")) / 1e6
    print(f"total data packs: {total:.1f} MB")
    print("OK" if not failures else f"{len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
