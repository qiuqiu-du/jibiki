"""大辞林 第四版 (Yomitan structured content, same family of markup as 新明解 第八版)."""
import re
import urllib.parse
import zipfile

import paths
from dicts.base import Adapter, SourceEntry
from dicts.shinmeikai8 import _find, to_html
from lib.gaiji import Gaiji
from lib.markup import DROP, UNWRAP, emit, text
from lib.textnorm import has_japanese, is_kana, kata_to_hira
from readers.yomitan_reader import read_grouped, read_index


def _svg_files():
    with zipfile.ZipFile(paths.DAIJIRIN4) as z:
        return {n.split("/")[-1]: z.read(n) for n in z.namelist() if n.endswith(".svg")}


GAIJI = Gaiji("daijirin4", _svg_files)
_SENSE = {"語義Gnum", "副義num", "中語義num"}
_POS = {"品詞G"}
_EX = {"用例", "熟語例G"}  # 熟語例G: compounds under a kanji entry (亜綱・亜流)
CIRCLED = "⓪①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"


def accent_text(s: str) -> str:
    """[2] -> ② (how the other dictionaries print pitch accents in their headlines)."""
    return re.sub(r"\[(\d+)\]", lambda m: CIRCLED[int(m.group(1))] if int(m.group(1)) < len(CIRCLED) else m.group(0), s)


def _title(node) -> str:
    """Headline text: reading, accent, notation; not the kanji readings list (漢字音G)."""
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return "".join(_title(n) for n in node)
    if isinstance(node, dict):
        if node.get("tag") == "rt" or (node.get("data") or {}).get("name") == "漢字音G":
            return ""
        if node.get("tag") == "img":
            return node.get("title") or "〓"
        if (node.get("data") or {}).get("name") == "accent":
            return accent_text(_title(node.get("content", "")))
        return _title(node.get("content", ""))
    return ""


def rule(tag, a):
    name = a.get("data-name", "")
    if name == "見出部":
        return DROP
    if name in _POS:
        return emit("span", **{"class": "pos"})
    if name in _SENSE:
        return emit("span", **{"class": "sense"})
    if name in _EX:
        return emit("span", **{"class": "ex"})
    if tag in ("div", "li"):
        return emit("p")
    if tag == "ruby":
        return emit("ruby")
    if tag == "rt":
        return emit("rt")
    if tag == "a":
        return emit("a", href=a.get("href", ""))
    if tag == "img":
        t = a.get("title", "")
        src = a.get("src", "")
        if src.split("/")[-1] in GAIJI.table or not t or t == "〓":
            return GAIJI.action(src)
        return text(f"［{t}］")  # labels: 文 (文語形), 季 (季語), 漢・呉・唐 (kanji readings), 可能 …
    if tag == "br":
        return emit("br")
    if a.get("data-bold"):
        return emit("b")
    if a.get("data-sup"):
        return emit("sup")
    return UNWRAP


_BLACK_CIRCLED = {c: str(i) for i, c in enumerate("❶❷❸❹❺❻❼❽❾❿⓫⓬⓭⓮⓯⓰⓱⓲⓳⓴", 1)}


def post(body):
    """Black circled sense numbers (❶, 中語義 in a few long entries) are drawn by the app, as in 明鏡."""
    return re.sub("[\u2776-\u277f\u24eb-\u24f4]", lambda m: f'<span class="num-c">{_BLACK_CIRCLED[m.group(0)]}</span>', body)


def link_target(href):
    if not href.startswith("?"):
        return None
    q = urllib.parse.parse_qs(href[1:]).get("query")
    return q[0].strip() if q else None


def load() -> Adapter:
    index = read_index(paths.DAIJIRIN4)
    entries = []
    for group in read_grouped(paths.DAIJIRIN4):
        first = group[0]
        head = _find(first.content, "見出部")
        title = _title(head).strip() if head else first.term
        reading = kata_to_hira(first.reading)
        spellings = []
        for t in group:
            if t.term != t.reading and not is_kana(t.term) and has_japanese(t.term) and t.term not in spellings:
                spellings.append(t.term)
        # Kanji entries list their on/kun readings inside the headline, which the body drops.
        readings = _find(head, "漢字音G") if head else None
        body = (f"<div>{to_html(readings)}</div>" if readings else "") + to_html(first.content)
        aliases = list(dict.fromkeys(t.term for t in group))
        entries.append(SourceEntry(str(first.sequence), title, reading, spellings, body, aliases))
    return Adapter("daijirin4", index.get("title", "").replace("　", " "), index.get("revision", ""),
                   entries, rule, link_target, GAIJI, post)


# Headword accents for the pack's pitch table (build_dict.add_pitch); no importance marks.
PITCH_SOURCE = "daijirin4"


def pitch_heads():
    """(spelling, hiragana reading, [accent numbers], 0) from the headwords' accent nodes
    ([0], [2]); like Shinmeikai, kanji rows only when the entry has a kanji spelling."""
    def accents(node, inside=False):
        found = []
        if isinstance(node, list):
            for n in node:
                found += accents(n, inside)
        elif isinstance(node, dict):
            name = (node.get("data") or {}).get("name", "")
            if inside and name == "accent":
                return found + re.findall(r"\[(\d+)\]", _title(node.get("content", "")))
            found += accents(node.get("content"), inside or name == "見出部")
        return found

    for group in read_grouped(paths.DAIJIRIN4):
        nums = list(dict.fromkeys(accents(group[0].content)))
        kanji_terms = [t for t in group if not is_kana(t.term) and t.term != t.reading]
        for t in kanji_terms or group[:1]:
            yield t.term, kata_to_hira(t.reading), nums, 0
