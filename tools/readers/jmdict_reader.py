"""Read JMdict XML (JMdict_e.gz) into a compact skeleton: spellings, readings,
part-of-speech codes and frequency rank. Glosses are not used."""
import gzip
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

_COMMON = {"news1", "ichi1", "spec1", "spec2", "gai1"}
UNCOMMON = 99


@dataclass
class KanjiForm:
    text: str
    freq: int
    search_only: bool = False  # ke_inf sK: findable by search, never shown as the spelling


@dataclass
class ReadingForm:
    text: str
    freq: int
    no_kanji: bool
    restr: list[str]  # kanji spellings this reading applies to; empty = all
    search_only: bool = False  # re_inf sk


@dataclass
class JmEntry:
    seq: int
    kanji: list[KanjiForm]
    readings: list[ReadingForm]
    pos: list[str] = field(default_factory=list)
    usually_kana: bool = False        # any sense is tagged `uk` (usually written in kana)
    usually_kana_first: bool = False  # the first (main) sense is tagged `uk`

    def pairs(self):
        """Valid (kanji or None, reading, freq) combinations that may be shown.

        Search-only spellings (JMdict sK/sk) are left out: 居らっしゃる is only a search
        spelling of いらっしゃる, so the word counts as written in kana.
        """
        kanji = [k for k in self.kanji if not k.search_only]
        readings = [r for r in self.readings if not r.search_only] or self.readings
        names = {k.text for k in kanji}
        out = []
        for r in readings:
            if not kanji or r.no_kanji or (r.restr and not names & set(r.restr)):
                out.append((None, r.text, r.freq))
                continue
            for k in kanji:
                if not r.restr or k.text in r.restr:
                    out.append((k.text, r.text, max(k.freq, r.freq)))
        return out

    def all_pairs(self):
        """Like pairs(), but over every spelling including search-only ones (for matching)."""
        out = []
        for r in self.readings:
            if not self.kanji or r.no_kanji:
                out.append((None, r.text, r.freq))
                continue
            for k in self.kanji:
                if not r.restr or k.text in r.restr:
                    out.append((k.text, r.text, max(k.freq, r.freq)))
        return out

    def search_only_texts(self):
        """(text, freq) of search-only spellings: searchable keys, never displayed."""
        best = min(r.freq for r in self.readings)
        return [(k.text, max(k.freq, best)) for k in self.kanji if k.search_only] + \
            [(r.text, r.freq) for r in self.readings if r.search_only]

    @property
    def freq(self) -> int:
        return min([f.freq for f in self.kanji] + [r.freq for r in self.readings])


def _freq(pri: list[str]) -> int:
    ranks = [int(p[2:]) for p in pri if p.startswith("nf")]
    if ranks:
        return min(ranks)
    if any(p in _COMMON for p in pri):
        return 48
    if pri:
        return 60
    return UNCOMMON


def read_jmdict(path: Path):
    raw = gzip.decompress(path.read_bytes()).decode("utf-8")
    raw = re.sub(r"<!DOCTYPE.*?\]>", "", raw, count=1, flags=re.S)
    # Keep entity names (e.g. &v5k;) as plain codes instead of expanded text.
    raw = re.sub(r"&(?!amp;|lt;|gt;|quot;|apos;)([\w-]+);", r"\1", raw)
    root = ET.fromstring(raw)
    for e in root.iter("entry"):
        kanji = [
            KanjiForm(k.findtext("keb"), _freq([p.text for p in k.findall("ke_pri")]),
                      any(i.text == "sK" for i in k.findall("ke_inf")))
            for k in e.findall("k_ele")
        ]
        readings = [
            ReadingForm(
                r.findtext("reb"),
                _freq([p.text for p in r.findall("re_pri")]),
                r.find("re_nokanji") is not None,
                [x.text for x in r.findall("re_restr")],
                any(i.text == "sk" for i in r.findall("re_inf")),
            )
            for r in e.findall("r_ele")
        ]
        pos = []
        uk = any(m.text == "uk" for m in e.iter("misc"))
        first_sense = e.find("sense")
        uk_first = first_sense is not None and any(m.text == "uk" for m in first_sense.findall("misc"))
        for s in e.findall("sense"):
            for p in s.findall("pos"):
                if p.text not in pos:
                    pos.append(p.text)
        yield JmEntry(int(e.findtext("ent_seq")), kanji, readings, pos, uk, uk_first)
