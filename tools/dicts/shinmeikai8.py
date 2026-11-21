"""新明解国語辞典 第八版 (Yomitan structured content)."""
import html
import json
import re
import urllib.parse
import zipfile

import paths
from dicts.base import Adapter, SourceEntry
from lib.gaiji import Gaiji
from lib.markup import DROP, UNWRAP, emit, text
from lib.textnorm import circled_to_numbers, has_japanese, is_kana, kata_to_hira
from readers.yomitan_reader import read_grouped, read_index

def _svg_files():
    with zipfile.ZipFile(paths.SHINMEIKAI8) as z:
        return {n.split("/")[-1]: z.read(n) for n in z.namelist() if n.endswith(".svg")}


GAIJI = Gaiji("shinmeikai8", _svg_files)
_SENSE = {"語義番号", "大語義番号"}
_POS = {"品詞M", "品詞G", "品詞用法"}
_DROP = {"見出部", "表外字マーク", "表外音訓マーク"}


def to_html(node) -> str:
    """Serialize structured content to HTML, keeping data-name and simple styles."""
    if isinstance(node, str):
        return html.escape(node, quote=False)
    if isinstance(node, list):
        return "".join(to_html(n) for n in node)
    if not isinstance(node, dict):
        return ""
    if node.get("type") == "structured-content":
        return to_html(node.get("content"))
    if node.get("type") == "text":
        return html.escape(node.get("text", ""), quote=False)
    tag = node.get("tag") or "span"
    attrs = {}
    data = node.get("data") or {}
    if data.get("name"):
        attrs["data-name"] = data["name"]
    style = node.get("style") or {}
    if style.get("fontWeight") == "bold":
        attrs["data-bold"] = "1"
    if style.get("verticalAlign") == "super":
        attrs["data-sup"] = "1"
    if tag == "a":
        attrs["href"] = node.get("href", "")
    if tag == "img":
        attrs["title"] = node.get("title", "")
        attrs["src"] = node.get("path", "")
        return "<img" + "".join(f' {k}="{html.escape(v)}"' for k, v in attrs.items()) + ">"
    if tag == "br":
        return "<br>"
    a = "".join(f' {k}="{html.escape(v)}"' for k, v in attrs.items())
    return f"<{tag}{a}>{to_html(node.get('content'))}</{tag}>"


def _text(node) -> str:
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return "".join(_text(n) for n in node)
    if isinstance(node, dict):
        if node.get("tag") == "rt":
            return ""
        if node.get("tag") == "img":  # a gaiji in the headword
            return node.get("title") or "〓"
        return _text(node.get("content", ""))
    return ""


def _find(node, name):
    if isinstance(node, list):
        for n in node:
            r = _find(n, name)
            if r is not None:
                return r
    elif isinstance(node, dict):
        if (node.get("data") or {}).get("name") == name:
            return node
        return _find(node.get("content"), name)
    return None


def rule(tag, a):
    name = a.get("data-name", "")
    if name in _DROP:
        return DROP
    if name == "子見出部":
        return emit("b")
    if name in _POS:
        return emit("span", **{"class": "pos"})
    if name in _SENSE:
        return emit("span", **{"class": "sense"})
    if name == "用例":
        return emit("span", **{"class": "ex"})
    if tag == "div":
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
        # Pictures in gaiji/ are characters (抛, √2), the others labels (表記, かぞえ方).
        return text(t if "/gaiji/" in src else f"［{t}］")
    if tag == "br":
        return emit("br")
    if a.get("data-bold"):
        return emit("b")
    if a.get("data-sup"):
        return emit("sup")
    return UNWRAP


def link_target(href):
    if not href.startswith("?"):
        return None
    q = urllib.parse.parse_qs(href[1:]).get("query")
    return q[0] if q else None


def load() -> Adapter:
    index = read_index(paths.SHINMEIKAI8)
    entries = []
    for group in read_grouped(paths.SHINMEIKAI8):
        first = group[0]
        head = _find(first.content, "見出部")
        title = _text(head).strip() if head else first.term
        reading = kata_to_hira(first.reading)
        spellings = []
        for t in group:
            if t.term != t.reading and not is_kana(t.term) and has_japanese(t.term) and t.term not in spellings:
                spellings.append(t.term)
        body = to_html(first.content)
        aliases = list(dict.fromkeys(t.term for t in group))
        entries.append(SourceEntry(str(first.sequence), title, reading, spellings, body, aliases))
    return Adapter("shinmeikai8", index.get("title", ""), index.get("revision", ""), entries, rule, link_target, GAIJI)


# Headword accents and ＊＊/＊ marks for the pack's pitch tables (build_dict.add_pitch).
PITCH_SOURCE = "smk8"


def pitch_heads():
    """(spelling, hiragana reading, [accent numbers], importance) from the headwords.
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
                found += circled_to_numbers(_head_text(node))
                return found
            found += head_accents(node.get("content"), inside or name == "見出部")
        return found

    for group in read_grouped(paths.SHINMEIKAI8):
        nums = list(dict.fromkeys(head_accents(group[0].content)))
        head = _find_name(group[0].content, "見出部")
        mark = _head_text(head) if head else ""
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


def _head_text(node):
    """Plain text of a headword node, as the accents were always read (ruby and pictures kept)."""
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return "".join(_head_text(n) for n in node)
    if isinstance(node, dict):
        return _head_text(node.get("content", ""))
    return ""
