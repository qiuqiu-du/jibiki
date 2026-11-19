"""新明解国語辞典 第八版 (Yomitan structured content)."""
import html
import json
import urllib.parse

import paths
from dicts.base import Adapter, SourceEntry
from lib.markup import DROP, UNWRAP, emit, text
from lib.textnorm import has_japanese, is_kana, kata_to_hira
from readers.yomitan_reader import read_grouped, read_index

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
        return text(f"［{t}］") if t else DROP
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
    return Adapter("shinmeikai8", index.get("title", ""), index.get("revision", ""), entries, rule, link_target)
