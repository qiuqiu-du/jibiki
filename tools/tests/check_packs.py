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

# (dictionary, headword, reading, JMdict ent_seq that must be linked)
ALIGN = [
    ("daijisen", "食べる", "たべる", 1358280), ("meikyo3", "食べる", "たべる", 1358280),
    ("shinmeikai8", "食べる", "たべる", 1358280), ("daijisen", "哀悼", "あいとう", None),
    ("daijisen", "橋", "はし", 1237410), ("meikyo3", "橋", "はし", 1237410),
    ("shinmeikai8", "橋", "はし", 1237410),
    ("daijirin4", "食べる", "たべる", 1358280), ("daijirin4", "橋", "はし", 1237410),
    ("shogakukan_jc3", "食べる", "たべる", 1358280), ("shogakukan_jc3", "手間", "てま", None),
    ("shinseiki_jc", "食べる", "たべる", 1358280), ("shinseiki_jc", "手間", "てま", None),
]

ALLOWED_TAGS = {"p", "br", "b", "i", "sup", "sub", "ruby", "rt", "span", "a", "img"}
ALLOWED_SPAN = {"pos", "sense", "ex", "label", "note", "mark", "box-b", "box-w", "num-c", "gloss", "zh"}

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
    """core.db is open data only (it ships with the app): JMdict + Kanjium accents. The merged
    accents (NHK, 新明解, 大辞林 packs) are checked by the app's CoreComposerTest."""
    sources = dict(core.execute("SELECT source, count(*) FROM pitch GROUP BY source"))
    if set(sources) != {"kanjium"}:
        fail(f"core.db must only hold open accents (kanjium), got {sources}")
    for p in sorted(paths.DATAPACKS.glob("pitch_*.db")) + sorted(paths.DATAPACKS.glob("dict_*.db")):
        conn = sqlite3.connect(p)
        meta = dict(conn.execute("SELECT key, value FROM meta"))
        if "pitch_priority" in meta:
            n = conn.execute("SELECT count(*) FROM pitch").fetchone()[0]
            s = conn.execute("SELECT count(*) FROM entry_score").fetchone()[0]
            print(f"{p.name}: pitch {n} rows ({meta['pitch_source']}, priority {meta['pitch_priority']}), scores {s}")
        conn.close()
    print(f"core.db pitch: {sources}")


def open_pack(dict_id):
    conn = sqlite3.connect(paths.DATAPACKS / f"dict_{dict_id}.db")
    zd = zstandard.ZstdCompressionDict(conn.execute("SELECT data FROM zdict WHERE id = 1").fetchone()[0])
    return conn, zstandard.ZstdDecompressor(dict_data=zd)


def check_pack(core, dict_id):
    conn, dctx = open_pack(dict_id)
    n = conn.execute("SELECT count(*) FROM dict_entry").fetchone()[0]
    ids = {r[0] for r in conn.execute("SELECT id FROM dict_entry")}
    # Ids of items stored once under an earlier id (build_dict.py) still open: links may use them.
    if conn.execute("SELECT 1 FROM sqlite_master WHERE name = 'dict_same'").fetchone():
        ids |= {r[0] for r in conn.execute("SELECT id FROM dict_same")}

    # Markup vocabulary and link integrity over every entry.
    bad_tags, bad_links = {}, 0
    media = dict(conn.execute("SELECT name, data FROM media"))
    missing_media, lost_gaiji, bad_badges = set(), 0, set()
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
        # Gaiji: every picture is in the pack; none was turned into a placeholder label.
        missing_media.update(n for n in re.findall(r'<img src="([^"]+)">', text) if n not in media)
        lost_gaiji += text.count("［〓］") + text.count("［3分の2］")
        # Number badges hold one label: 一-二十 in boxes, 1-99 in circles.
        bad_badges.update(re.findall(r'<span class="(?:box-b|box-w|num-c)">((?:(?!</span>).)*)</span>', text))
    per_entry_us = (time.perf_counter() - t0) / n * 1e6
    if bad_tags:
        fail(f"{dict_id}: unexpected markup {dict(list(bad_tags.items())[:10])}")
    if bad_links:
        fail(f"{dict_id}: {bad_links} links to missing entries")
    if missing_media:
        fail(f"{dict_id}: pictures missing from the media table: {sorted(missing_media)[:10]}")
    bad_badges = {b for b in bad_badges if not re.fullmatch(r"[1-9][0-9]?|[一二三四五六七八九十]{1,3}", b)}
    if bad_badges:
        fail(f"{dict_id}: number badges with unexpected labels: {sorted(bad_badges)[:10]}")
    if lost_gaiji:
        fail(f"{dict_id}: {lost_gaiji} gaiji shown as a placeholder label")
    bad_images = [n for n, b in media.items() if not (b.startswith(b"\x89PNG") or b.startswith(b"GIF8"))]
    if bad_images:
        fail(f"{dict_id}: media that is not PNG or GIF: {bad_images[:10]}")

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
    lang = dict(conn.execute("SELECT key, value FROM meta")).get("lang", "ja")
    if lang == "ja-zh":
        # The app's Chinese one-line meaning comes from <span class="gloss">: most common words need one.
        glossed = sum(1 for (seq,) in core.execute("SELECT id FROM entry WHERE freq < 99")
                      if any('class="gloss"' in dctx.decompress(conn.execute("SELECT body FROM dict_entry WHERE id = ?", (eid,)).fetchone()[0]).decode()
                             for (eid,) in conn.execute("SELECT entry_id FROM dict_link WHERE core_id = ? LIMIT 1", (seq,))))
        print(f"{dict_id}: common words with a Chinese gloss {glossed}/{common} = {glossed / common:.1%}")
        if glossed < covered * 0.95:
            fail(f"{dict_id}: only {glossed} of {covered} linked common words have a gloss")
    size = (paths.DATAPACKS / f"dict_{dict_id}.db").stat().st_size / 1e6
    print(f"{dict_id}: gaiji pictures {len(media)} ({sum(map(len, media.values())) / 1e3:.1f} KB)")
    print(f"{dict_id}: {n} entries, {size:.1f} MB, common JMdict words covered "
          f"{covered}/{common} = {covered / common:.1%}, decompress+scan {per_entry_us:.0f} µs/entry")


def main():
    core = sqlite3.connect(paths.CORE_DB)
    check_core(core)
    for d in ("daijisen", "meikyo3", "shinmeikai8", "daijirin4", "shogakukan_jc3", "shinseiki_jc"):
        check_pack(core, d)
    total = sum(p.stat().st_size for p in paths.DATAPACKS.glob("*.db")) / 1e6
    print(f"total data packs: {total:.1f} MB")
    print("OK" if not failures else f"{len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
