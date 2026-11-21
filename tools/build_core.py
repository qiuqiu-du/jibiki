"""Build datapacks/core.db: JMdict skeleton index + open pitch accents (Kanjium) + ranking.

core.db ships inside the app (as core.db.zst), so it holds open data only: JMdict,
Kanjium and Jiten's word frequency (all CC BY-SA 4.0). Ranking: JMdict's coarse frequency,
raised for words Jiten finds common (see jiten_band). Accents and importance marks from copyrighted
dictionaries go into their own packs (lib/pitch.py) and are merged on the phone.
Usage: tools/.venv/bin/python tools/build_core.py
"""
import collections
import csv
import math
import sys

import zstandard

import paths
from lib.packdb import fresh_db, finish_db, write_meta
from lib.pitch import JmdictIndex, Pitch
from lib.textnorm import kata_to_hira, search_key
from readers.kanjium_reader import read_kanjium

CORE_SCHEMA_VERSION = 5

SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE entry (
    id       INTEGER PRIMARY KEY NOT NULL,  -- JMdict ent_seq
    pos      TEXT NOT NULL,                 -- comma-separated JMdict codes, e.g. v1,vt
    freq     INTEGER NOT NULL,              -- lower = more common, 99 = no frequency data
    flags    INTEGER NOT NULL,              -- bit 0: main sense usually written in kana (JMdict `uk`); bit 1: any sense
    score    INTEGER NOT NULL               -- ranking: freq raised by Jiten; packs' entry_score may raise it more (lower = better)
);
CREATE TABLE form (
    entry_id INTEGER NOT NULL,
    ord      INTEGER NOT NULL,              -- JMdict order; ord 0 is the display headword
    kanji    TEXT,                          -- NULL for kana-only spellings
    reading  TEXT NOT NULL,
    freq     INTEGER NOT NULL,
    PRIMARY KEY (entry_id, ord)
) WITHOUT ROWID;
CREATE TABLE search_key (
    key      TEXT NOT NULL,                 -- lib/textnorm.search_key()
    entry_id INTEGER NOT NULL,
    rank     INTEGER NOT NULL,              -- entry.score * 100 + frequency of this spelling (lower = better)
    PRIMARY KEY (key, entry_id)
) WITHOUT ROWID;
CREATE TABLE pitch (
    entry_id INTEGER NOT NULL,
    reading  TEXT NOT NULL,
    accent   TEXT NOT NULL,                 -- e.g. "2", "0,2", "1+1"
    source   TEXT NOT NULL,                 -- kanjium here; the app adds pack sources (nhk2016, smk8, ...)
    PRIMARY KEY (entry_id, reading)
) WITHOUT ROWID;
"""
INDEXES = ""


def jiten_band(index: JmdictIndex) -> dict[int, int]:
    """JMdict entry -> Jiten frequency on JMdict's own scale: 500 words per step (rank 1-500 -> 1,
    like JMdict's nf01), at most 98. Jiten lists words by JMdict spelling and reading."""
    if not paths.JITEN.exists():
        sys.exit(f"{paths.JITEN} is missing: download the Global CSV from https://jiten.moe/frequency-dictionaries")
    by_pair = collections.defaultdict(set)
    for seq, kanji, reading, _ in index.pairs:
        by_pair[(kanji or reading, kata_to_hira(reading))].add(seq)
    band = {}
    with open(paths.JITEN, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            b = min(math.ceil(int(row["Rank"]) / 500), 98)
            for seq in by_pair.get((row["Word"], kata_to_hira(row["Form"])), ()):
                band[seq] = min(b, band.get(seq, 99))
    return band


def main():
    print("reading JMdict ...", flush=True)
    index = JmdictIndex()
    entries, pairs = index.entries, index.pairs

    print("Jiten frequency ...", flush=True)
    jiten = jiten_band(index)

    print("Kanjium ...", flush=True)
    pitch = Pitch()
    pitch.apply(index, read_kanjium(paths.KANJIUM))

    print("writing core.db ...", flush=True)
    conn = fresh_db(paths.CORE_DB, SCHEMA)
    rows_entry, keys = [], {}
    for e in entries:
        flags = (1 if e.usually_kana_first else 0) | (2 if e.usually_kana else 0)
        # Raised only: Jiten never pushes a word below where JMdict puts it.
        rows_entry.append((e.seq, ",".join(e.pos), e.freq, flags, min(e.freq, jiten.get(e.seq, 99))))
    conn.executemany("INSERT INTO entry VALUES (?,?,?,?,?)", rows_entry)
    ords = collections.Counter()
    form_rows = []
    for seq, kanji, reading, freq in pairs:
        form_rows.append((seq, ords[seq], kanji, reading, freq))
        ords[seq] += 1
    conn.executemany("INSERT INTO form VALUES (?,?,?,?,?)", form_rows)
    for seq, kanji, reading, freq in pairs:
        for text in (kanji, reading):
            if text:
                k = (search_key(text), seq)
                keys[k] = min(freq, keys.get(k, 999))
    # Search-only spellings (JMdict sK/sk) can be typed, but rank below shown spellings.
    for e in entries:
        for text, freq in e.search_only_texts():
            k = (search_key(text), e.seq)
            keys.setdefault(k, max(freq, 98))
    # Rank is precomputed so prefix search is a single-table range scan (no joins or grouping),
    # which keeps one-kana queries (tens of thousands of matches) fast on the phone. The app
    # rewrites it for the words a pack's entry_score improves.
    score = {seq: s for seq, _p, _f, _fl, s in rows_entry}
    conn.executemany("INSERT INTO search_key VALUES (?,?,?)",
                     [(k, s, score[s] * 100 + min(f, 99)) for (k, s), f in keys.items()])
    conn.executemany("INSERT INTO pitch VALUES (?,?,?,?)", [(s, r, a, "kanjium") for (s, r), a in pitch.rows.items()])
    conn.executescript(INDEXES)
    write_meta(conn, {
        "schema_version": CORE_SCHEMA_VERSION,
        "kind": "core",
        "jmdict_entries": len(entries),
        "pitch_rows": len(pitch.rows),
    })
    finish_db(conn, paths.CORE_DB)

    # The copy bundled with the app (app/build.gradle.kts picks it up).
    data = paths.CORE_DB.read_bytes()
    packed = zstandard.ZstdCompressor(level=19, threads=-1).compress(data)
    paths.CORE_ZST.write_bytes(packed)

    common = {(s, r) for s, _k, r, f in pairs if f < 99}
    covered = sum(1 for k in common if k in pitch.rows)
    print(f"entries {len(entries)}  forms {len(pairs)}  search keys {len(keys)}  raised by Jiten {sum(1 for s, _p, f, _fl, sc in rows_entry if sc < f)}")
    print(f"pitch rows {len(pitch.rows)} (Kanjium); common (entry, reading) with pitch: {covered}/{len(common)} = {covered / len(common):.1%}")
    print(f"size {len(data) / 1e6:.1f} MB, bundled {len(packed) / 1e6:.1f} MB")


if __name__ == "__main__":
    sys.exit(main())
