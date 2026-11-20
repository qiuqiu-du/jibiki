"""Build datapacks/core.db: JMdict skeleton index + pitch accent numbers.

Pitch sources, first match wins per (entry, reading):
  1. NHK 2016 accent index  2. Shinmeikai 8 headword accents  3. Kanjium
Usage: tools/.venv/bin/python tools/build_core.py
"""
import collections
import re
import sys

import paths
from lib.packdb import fresh_db, finish_db, write_meta
from lib.textnorm import circled_to_numbers, is_kana, kata_to_hira, pron_key, search_key
from readers.jmdict_reader import read_jmdict
from readers.kanjium_reader import read_kanjium
from readers.nhk2016_reader import read_nhk2016
from readers.yomitan_reader import read_grouped

CORE_SCHEMA_VERSION = 4

SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE entry (
    id       INTEGER PRIMARY KEY NOT NULL,  -- JMdict ent_seq
    pos      TEXT NOT NULL,                 -- comma-separated JMdict codes, e.g. v1,vt
    freq     INTEGER NOT NULL,              -- lower = more common, 99 = no frequency data
    flags    INTEGER NOT NULL,              -- bit 0: main sense usually written in kana (JMdict `uk`); bit 1: any sense
    score    INTEGER NOT NULL               -- ranking: freq, improved by Shinmeikai 8 importance marks (lower = better)
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
    source   TEXT NOT NULL,                 -- nhk2016 | smk8 | kanjium
    PRIMARY KEY (entry_id, reading)
) WITHOUT ROWID;
"""
INDEXES = ""


def smk8_heads():
    """(spelling, hiragana reading, [accent numbers], importance) from Shinmeikai 8 headwords.
    importance: 2 = ＊＊ (most basic words), 1 = ＊ (basic), 0 = unmarked.

    Rows of one entry share a sequence number; when the entry has a kanji
    spelling we only emit kanji rows, so a kana row cannot hit a homophone."""
    def head_accents(node, inside=False):
        found = []
        if isinstance(node, list):
            for n in node:
                found += head_accents(n, inside)
        elif isinstance(node, dict):
            name = (node.get("data") or {}).get("name", "")
            if name in ("子見出部", "解説部", "子解説部"):
                return found
            if inside and re.fullmatch(r"accent\d*", name or ""):
                found += circled_to_numbers(_text(node))
                return found
            found += head_accents(node.get("content"), inside or name == "見出部")
        return found

    for group in read_grouped(paths.SHINMEIKAI8):
        nums = list(dict.fromkeys(head_accents(group[0].content)))
        head = _find_name(group[0].content, "見出部")
        mark = _text(head) if head else ""
        importance = 2 if mark.startswith("＊＊") else 1 if mark.startswith("＊") else 0
        kanji_terms = [t for t in group if not is_kana(t.term) and t.term != t.reading]
        for t in kanji_terms or group[:1]:
            yield t.term, kata_to_hira(t.reading), nums, importance


def _find_name(node, name):
    if isinstance(node, list):
        for n in node:
            r = _find_name(n, name)
            if r is not None:
                return r
    elif isinstance(node, dict):
        if (node.get("data") or {}).get("name") == name:
            return node
        return _find_name(node.get("content"), name)
    return None


def score(freq: int, importance: int) -> int:
    """Ranking score (lower = better). JMdict frequency alone is coarse: many basic
    verbs share 48 while news nouns get fine ranks, so 核 beat 書く for かく."""
    if importance == 2:
        return 1
    if importance == 1:
        return min(freq, 12)
    return freq


def _text(node):
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return "".join(_text(n) for n in node)
    if isinstance(node, dict):
        return _text(node.get("content", ""))
    return ""


def main():
    print("reading JMdict ...", flush=True)
    entries = list(read_jmdict(paths.JMDICT))

    by_pair = collections.defaultdict(set)   # (kanji, hira reading) -> seqs
    by_kana = collections.defaultdict(set)   # hira reading -> seqs (kana-only spellings)
    pairs = []                               # (seq, kanji, reading, freq)
    usually_kana = set()
    for e in entries:
        if e.usually_kana:
            usually_kana.add(e.seq)
        for kanji, reading, freq in e.pairs():
            pairs.append((e.seq, kanji, reading, freq))
            if kanji:
                by_pair[(kanji, kata_to_hira(reading))].add(e.seq)
            if not kanji or e.usually_kana:
                by_kana[kata_to_hira(reading)].add(e.seq)

    pitch: dict[tuple[int, str], tuple[str, str]] = {}

    def put(seq, reading, nums, source):
        key = (seq, reading)
        if key not in pitch and nums:
            pitch[key] = (",".join(dict.fromkeys(nums)), source)

    print("NHK 2016 ...", flush=True)
    nhk = read_nhk2016(paths.NHK2016)
    want_key = {}
    for seq, kanji, reading, _ in pairs:
        want = want_key.setdefault(reading, pron_key(reading))
        nums = []
        if kanji and kanji in nhk:
            nums = [r.accent for r in nhk[kanji] if pron_key(r.kana) == want]
        if not nums and (not kanji or seq in usually_kana):
            recs = nhk.get(reading) or nhk.get(kata_to_hira(reading)) or []
            nums = [r.accent for r in recs if pron_key(r.kana) == want]
            if len(set(nums)) > 1:
                continue  # kana key mixes homophones (はし: 箸/橋/端); let other sources decide
        put(seq, reading, nums, "nhk2016")

    def apply(source, rows):
        for head, reading, nums in rows:
            for seq in lookup_seqs(head, reading):
                put(seq, _orig_reading(seq, kata_to_hira(reading)), nums, source)

    def lookup_seqs(head, reading):
        hira = kata_to_hira(reading)
        if head and not is_kana(head):
            return by_pair.get((head, hira), ())
        return by_kana.get(kata_to_hira(head or reading), ())

    reading_of = {}
    for seq, _k, reading, _f in pairs:
        reading_of.setdefault((seq, kata_to_hira(reading)), reading)

    def _orig_reading(seq, hira):
        return reading_of.get((seq, hira), hira)

    print("Shinmeikai 8 accents and importance marks ...", flush=True)
    smk8 = list(smk8_heads())
    apply("smk8", [(h, r, n) for h, r, n, _ in smk8 if n])
    importance = collections.Counter()
    for h, r, _n, imp in smk8:
        for seq in lookup_seqs(h, r):
            importance[seq] = max(importance[seq], imp)
    print("Kanjium ...", flush=True)
    apply("kanjium", read_kanjium(paths.KANJIUM))

    print("writing core.db ...", flush=True)
    conn = fresh_db(paths.CORE_DB, SCHEMA)
    rows_entry, keys = [], {}
    entry_score = {}
    for e in entries:
        flags = (1 if e.usually_kana_first else 0) | (2 if e.usually_kana else 0)
        entry_score[e.seq] = score(e.freq, importance[e.seq])
        rows_entry.append((e.seq, ",".join(e.pos), e.freq, flags, entry_score[e.seq]))
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
    # which keeps one-kana queries (tens of thousands of matches) fast on the phone.
    conn.executemany("INSERT INTO search_key VALUES (?,?,?)",
                     [(k, s, entry_score[s] * 100 + min(f, 99)) for (k, s), f in keys.items()])
    conn.executemany(
        "INSERT INTO pitch VALUES (?,?,?,?)", [(s, r, a, src) for (s, r), (a, src) in pitch.items()]
    )
    conn.executescript(INDEXES)
    write_meta(conn, {
        "schema_version": CORE_SCHEMA_VERSION,
        "kind": "core",
        "jmdict_entries": len(entries),
        "pitch_rows": len(pitch),
    })
    finish_db(conn, paths.CORE_DB)

    # Report
    src_count = collections.Counter(src for _, src in pitch.values())
    common = {(s, r) for s, _k, r, f in pairs if f < 99}
    covered = sum(1 for k in common if k in pitch)
    print(f"entries {len(entries)}  forms {len(pairs)}  search keys {len(keys)}")
    print(f"pitch rows {len(pitch)} by source {dict(src_count)}")
    print(f"common (entry, reading) with pitch: {covered}/{len(common)} = {covered / len(common):.1%}")
    print(f"size {paths.CORE_DB.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    sys.exit(main())
