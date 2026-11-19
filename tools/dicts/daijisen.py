"""デジタル大辞泉 (MDX)."""
import html
import re

import paths
from dicts.base import Adapter, SourceEntry
from lib.markup import DROP, UNWRAP, emit
from lib.textnorm import kata_to_hira, search_key, split_spellings, has_japanese
from readers.mdx_reader import read_mdx

_KEEP = {"b": "b", "i": "i", "sup": "sup", "sub": "sub"}


def rule(tag, a):
    cls = a.get("class", "")
    if tag in ("link", "h3", "figure", "script", "style"):
        return DROP
    if tag == "span" and "hinshi" in cls:
        return emit("span", **{"class": "pos"})
    if tag == "v":
        return emit("span", **{"class": "sense"})
    if tag == "k":
        return emit("span", **{"class": "ex"}) if a.get("v") == "k" else UNWRAP
    if tag == "a":
        return emit("a", href=a.get("href", "")) if a.get("href") else UNWRAP
    if tag == "img":
        if "gaiji" in cls:
            return emit("img", src="daijisen/" + a.get("src", "").split("/")[-1])
        return DROP
    if tag == "br" or tag == "tr":
        return emit("br") if tag == "br" else emit("p")
    if tag in _KEEP:
        return emit(_KEEP[tag])
    return UNWRAP


def link_target(href):
    return href[len("entry://"):] if href.startswith("entry://") else None


def parse_title(title):
    m = re.match(r"^(.*?)【(.*?)】", title)
    if m:
        reading_part, notation = m.group(1), m.group(2)
    else:
        reading_part, notation = title, ""
    reading_part = re.sub(r"[（(〔].*?[）)〕]", "", reading_part)  # 〔‐タウ〕 = historical kana
    # Category labels (ひかり［列車］, が［接］, う［助動］) and the ◦ joint mark are not part of the reading.
    reading_part = re.sub(r"［[^］]*］|◦", "", reading_part)
    reading = kata_to_hira(search_key(reading_part))
    if not reading or not all(0x3041 <= ord(c) <= 0x309F or c in "ー" for c in reading):
        reading = ""
    spellings = split_spellings(notation) if notation else []
    if not reading and not spellings and has_japanese(title):
        spellings = [search_key(title)]
    return reading, spellings


def load() -> Adapter:
    mdx = read_mdx(paths.DAIJISEN)
    entries = []
    for key, body in mdx.records:
        m = re.search(r"<h3>(.*?)</h3>", body, re.S)
        title = html.unescape(re.sub(r"<[^>]+>", "", m.group(1))).strip() if m else key
        # The record key (あいとう【哀悼】) is cleaner than the printed title.
        reading, spellings = parse_title(key if "【" in key else title)
        if not reading:
            reading = parse_title(title)[0]
        entries.append(SourceEntry(key, title, reading, spellings, body))
    _attach_aliases(entries, mdx.links)
    return Adapter("daijisen", "デジタル大辞泉", mdx.header.get("Title", ""), entries, rule, link_target)


def _attach_aliases(entries, links):
    """Give each main entry the alias keys that point to it (following chains)."""
    by_key = {}
    for e in entries:
        by_key.setdefault(e.src_id, []).append(e)

    def targets(key, depth=0):
        if key in by_key:
            return by_key[key]
        if depth > 5 or key not in links:
            return []
        out = []
        for t in links[key]:
            out += targets(t, depth + 1)
        return out

    for alias in links:
        for e in targets(alias):
            e.aliases.append(alias)
