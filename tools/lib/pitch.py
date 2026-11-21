"""Pitch accents and importance marks that data packs carry (design-spec 3.6).

core.db (bundled with the app) only has open data: Kanjium accents. Copyrighted sources ship
in their own packs, each with a `pitch` table aligned to JMdict entries; the app merges them
into its copy of core.db when packs are imported, first by `pitch_priority` (lower first),
Kanjium last. Per (entry, reading) the first source with numbers wins, inside a pack too.

Shinmeikai's ＊＊/＊ marks also improve the search ranking: its pack has an `entry_score`
table with the improved score of the marked words (see score()).
"""
import collections

import paths
from lib.textnorm import is_kana, kata_to_hira, pron_key
from readers.jmdict_reader import read_jmdict

PACK_SCHEMA = """
CREATE TABLE pitch (
    entry_id INTEGER NOT NULL,              -- JMdict ent_seq
    reading  TEXT NOT NULL,                 -- the reading as JMdict spells it
    accent   TEXT NOT NULL,                 -- e.g. "2", "0,2", "1+1"
    PRIMARY KEY (entry_id, reading)
) WITHOUT ROWID;
CREATE TABLE entry_score (
    entry_id INTEGER PRIMARY KEY NOT NULL,
    score    INTEGER NOT NULL               -- replaces core.db's entry.score (lower = better)
);
"""

# Which source wins when several packs know a word (Kanjium in core.db comes after all).
PRIORITY = {"nhk2016": 10, "smk8": 20, "daijirin4": 30}


class JmdictIndex:
    """JMdict lookups for aligning accent sources: displayable (spelling, reading) pairs."""

    def __init__(self):
        self.by_pair = collections.defaultdict(set)   # (kanji, hira reading) -> seqs
        self.by_kana = collections.defaultdict(set)   # hira reading -> seqs (kana-only spellings)
        self.pairs = []                               # (seq, kanji, reading, freq)
        self.usually_kana = set()
        self.freq = {}
        self.entries = list(read_jmdict(paths.JMDICT))
        for e in self.entries:
            self.freq[e.seq] = e.freq
            if e.usually_kana:
                self.usually_kana.add(e.seq)
            for kanji, reading, freq in e.pairs():
                self.pairs.append((e.seq, kanji, reading, freq))
                if kanji:
                    self.by_pair[(kanji, kata_to_hira(reading))].add(e.seq)
                if not kanji or e.usually_kana:
                    self.by_kana[kata_to_hira(reading)].add(e.seq)
        self.reading_of = {}
        for seq, _k, reading, _f in self.pairs:
            self.reading_of.setdefault((seq, kata_to_hira(reading)), reading)

    def seqs(self, head, reading):
        hira = kata_to_hira(reading)
        if head and not is_kana(head):
            return self.by_pair.get((head, hira), ())
        return self.by_kana.get(kata_to_hira(head or reading), ())

    def jmdict_reading(self, seq, hira):
        return self.reading_of.get((seq, hira), hira)


class Pitch:
    """(entry, reading) -> accent; the first numbers put for a key win."""

    def __init__(self):
        self.rows: dict[tuple[int, str], str] = {}

    def put(self, seq, reading, nums):
        key = (seq, reading)
        if key not in self.rows and nums:
            self.rows[key] = ",".join(dict.fromkeys(nums))

    def apply(self, index: JmdictIndex, heads):
        """heads: (spelling, reading, [accent numbers]) as a dictionary prints them."""
        for head, reading, nums in heads:
            for seq in index.seqs(head, reading):
                self.put(seq, index.jmdict_reading(seq, kata_to_hira(reading)), nums)


def nhk2016(index: JmdictIndex, nhk) -> Pitch:
    """NHK 2016 recordings: one search word lists several words, inflected forms and words
    with particles (端 -> タン, ハシ, ハシオ), so match on spelling + reading."""
    pitch = Pitch()
    want_key = {}
    for seq, kanji, reading, _ in index.pairs:
        want = want_key.setdefault(reading, pron_key(reading))
        nums = []
        if kanji and kanji in nhk:
            nums = [r.accent for r in nhk[kanji] if pron_key(r.kana) == want]
        if not nums and (not kanji or seq in index.usually_kana):
            recs = nhk.get(reading) or nhk.get(kata_to_hira(reading)) or []
            nums = [r.accent for r in recs if pron_key(r.kana) == want]
            if len(set(nums)) > 1:
                continue  # kana key mixes homophones (はし: 箸/橋/端); let other sources decide
        pitch.put(seq, reading, nums)
    return pitch


def score(freq: int, importance: int) -> int:
    """Ranking score (lower = better). JMdict frequency alone is coarse: many basic
    verbs share 48 while news nouns get fine ranks, so 核 beat 書く for かく."""
    if importance == 2:
        return 1
    if importance == 1:
        return min(freq, 12)
    return freq


def write(conn, source: str, pitch: Pitch, scores: dict[int, int] | None = None):
    """Adds the pitch (and entry_score) tables and their meta to an open pack."""
    conn.executescript(PACK_SCHEMA)
    conn.executemany("INSERT INTO pitch VALUES (?,?,?)", [(s, r, a) for (s, r), a in pitch.rows.items()])
    conn.executemany("INSERT INTO entry_score VALUES (?,?)", sorted((scores or {}).items()))
    conn.executemany("INSERT OR REPLACE INTO meta VALUES (?,?)",
                     [("pitch_source", source), ("pitch_priority", str(PRIORITY[source]))])
    print(f"pitch rows {len(pitch.rows)} ({source}, priority {PRIORITY[source]}); improved scores {len(scores or {})}")
