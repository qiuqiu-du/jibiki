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


@dataclass
class ReadingForm:
    text: str
    freq: int
    no_kanji: bool
    restr: list[str]  # kanji spellings this reading applies to; empty = all


@dataclass
class JmEntry:
    seq: int
    kanji: list[KanjiForm]
    readings: list[ReadingForm]
    pos: list[str] = field(default_factory=list)
    usually_kana: bool = False        # any sense is tagged `uk` (usually written in kana)
    usually_kana_first: bool = False  # the first (main) sense is tagged `uk`

    def pairs(self):
        """Valid (kanji or None, reading, freq) combinations."""
        out = []
        for r in self.readings:
            if not self.kanji or r.no_kanji:
                out.append((None, r.text, r.freq))
                continue
            for k in self.kanji:
                if not r.restr or k.text in r.restr:
                    out.append((k.text, r.text, max(k.freq, r.freq)))
        return out

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
            KanjiForm(k.findtext("keb"), _freq([p.text for p in k.findall("ke_pri")]))
            for k in e.findall("k_ele")
        ]
        readings = [
            ReadingForm(
                r.findtext("reb"),
                _freq([p.text for p in r.findall("re_pri")]),
                r.find("re_nokanji") is not None,
                [x.text for x in r.findall("re_restr")],
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
