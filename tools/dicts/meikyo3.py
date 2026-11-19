"""明鏡国語辞典 第三版 (MDX). Main records are keyed by numeric ids."""
import html
import re

import paths
from dicts.base import Adapter, SourceEntry
from dicts.daijisen import _attach_aliases
from lib.markup import DROP, UNWRAP, emit, text
from lib.textnorm import kata_to_hira, search_key, split_spellings
from readers.mdx_reader import read_mdx

_LABEL = {"fbox", "fbox0", "fbox1", "fbox2", "fbox3", "fbox4"}


def rule(tag, a):
    cls = a.get("class", "")
    if tag in ("head", "meta", "link", "entry-index", "script", "style"):
        return DROP
    if tag == "div" and cls == "head":
        return DROP
    # The 類語 heading only existed in the dropped per-entry index; restore it on the section.
    if tag == "div" and cls == "ruigo-items":
        return emit("p", _label="類語")
    if tag in ("div", "meaning", "column", "child-item", "tr", "ruigo-items"):
        return emit("p")
    if tag == "headword":
        return emit("b")
    if tag == "pos":  # HTMLParser lowercases <POS>/<Pos>
        return emit("span", **{"class": "pos"})
    if tag in ("bc", "pr"):
        return emit("span", **{"class": "sense"})
    if tag in ("example", "yourei"):
        return emit("span", **{"class": "ex"})
    if tag in _LABEL:
        return emit("span", **{"class": "label"})
    if tag == "ruby":
        return emit("ruby")
    if tag == "rt":
        return emit("rt")
    if tag == "a":
        href = a.get("href", "")
        return emit("a", href=href) if href.startswith("entry://") else UNWRAP
    if tag == "img":
        alt = a.get("alt") or re.sub(r"\d*\.svg$", "", a.get("src", "").split("/")[-1])
        return text(f"［{alt.strip('［］')}］") if alt else DROP
    if tag == "br":
        return emit("br")
    if tag in ("b", "em"):
        return emit("b")
    if tag in ("sup", "sub", "i"):
        return emit(tag)
    return UNWRAP


def link_target(href):
    return href[len("entry://"):] if href.startswith("entry://") else None


def parse_head(body):
    m = re.search(r'<div class="head">(.*?)</div>', body, re.S)
    head = m.group(1) if m else ""
    title = html.unescape(re.sub(r"<rt>.*?</rt>|<[^>]+>", "", head)).strip()
    kana = re.search(r'<headword class="カナ">(.*?)</headword>', head, re.S)
    notation = re.search(r'<headword class="表記">(.*?)</headword>', head, re.S)
    reading = ""
    if kana:
        reading = kata_to_hira(search_key(re.sub(r"<[^>]+>", "", kana.group(1))))
        reading = re.sub(r"[（(].*?[）)]", "", reading)
    spellings = split_spellings(re.sub(r"<[^>]+>", "", html.unescape(notation.group(1)))) if notation else []
    return title, reading, spellings


def load() -> Adapter:
    mdx = read_mdx(paths.MEIKYO3)
    entries = []
    for key, body in mdx.records:
        if not key.isdigit():
            continue  # stylesheet-like or appendix records
        title, reading, spellings = parse_head(body)
        entries.append(SourceEntry(key, title or key, reading, spellings, body))
    _attach_aliases(entries, mdx.links)
    return Adapter("meikyo3", "明鏡国語辞典 第三版", mdx.header.get("Title", ""), entries, rule, link_target)
