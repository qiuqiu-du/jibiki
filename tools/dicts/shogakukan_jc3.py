"""小学館 日中辞典 第三版 (MDX, Kotobank source): Japanese headwords, Chinese translations.

Marks the Chinese in the body so the app can show it with Chinese glyphs and take a one-line
Chinese meaning from it (design-spec, M5):
  <span class="gloss">  the translation words of a sense (bold in the source): the gloss line
  <span class="zh">     other Chinese: example translations, compound-word meanings
A record is either one or more main entries (<h3>headword<span class="pinyin_h">reading</span>)
or a compound word (複合語) of some main entry, kept as an entry of its own whose whole meaning
is its gloss.
"""
import html
import re

import paths
from dicts.base import Adapter, SourceEntry
from lib.gaiji import Gaiji
from lib.markup import DROP, UNWRAP, emit
from lib.textnorm import has_japanese, is_kana, kata_to_hira, search_key
from readers.mdd_reader import read_folder
from readers.mdx_reader import read_mdx

def _pictures():
    mdd = paths.SHOGAKUKAN_JC3.with_suffix(".mdd")
    out = {}
    for folder in ("image\\dictionary\\jc03\\gaiji", "image\\dictionary\\jc03\\entity"):
        out.update(read_folder(mdd, folder))
    return out


GAIJI = Gaiji("shogakukan_jc3", _pictures)

_MEANING = re.compile(r'<p data-orgtag="meaning"(?![^>]*subhw_meaning)([^>]*)>(.*?)</p>', re.S)
_SUB_MEANING = re.compile(r'<p data-orgtag="meaning" class="subhw_meaning">(.*?)</p>', re.S)


def mark(body: str, whole_gloss: bool) -> str:
    """Source markup with the Chinese marked (as <gloss>/<zh> tags the rule turns into spans)."""
    def meaning(m):
        inner = re.sub(r"^\s*<b>(\d+)</b>", r"<sense>\1</sense>", m.group(2))
        inner = re.sub(r"<b>(.*?)</b>", r"<gloss>\1</gloss>", inner, flags=re.S)
        return f'<p data-orgtag="meaning"{m.group(1)}>{inner}</p>'
    body = _MEANING.sub(meaning, body)
    tag = "gloss" if whole_gloss else "zh"
    body = _SUB_MEANING.sub(lambda m: f"<p><{tag}>{m.group(1)}</{tag}></p>", body)
    return body.replace("<ja_cn>", "<zh>").replace("</ja_cn>", "</zh>")


def rule(tag, a):
    if tag in ("link", "h3", "script", "style"):
        return DROP
    if tag == "gloss":
        return emit("span", **{"class": "gloss"})
    if tag == "zh":
        return emit("span", **{"class": "zh"})
    if tag == "sense":
        return emit("span", **{"class": "sense"})
    if tag == "jae":
        return emit("span", **{"class": "ex"})
    if tag == "div" and a.get("data-orgtag") == "subheadword":
        return emit("b")
    if tag in ("p", "div", "tr"):
        return emit("p")
    if tag == "a":
        return emit("a", href=a.get("href", "")) if a.get("href") else UNWRAP
    if tag == "img":
        src = a.get("src", "")
        # gaiji/ and entity/ hold characters and marks; pictures right under jc03/ are
        # illustrations (人体, 自動車の構造), which the app doesn't use.
        return GAIJI.action(src) if "/gaiji/" in src or "/entity/" in src else DROP
    if tag == "br":
        return emit("br")
    if tag in ("b", "i", "sup", "sub"):
        return emit(tag)
    return UNWRAP


def link_target(href):
    return href[len("entry://"):].strip() if href.startswith("entry://") else None


def _head(h3: str):
    """<h3> content -> (title, reading, spellings)."""
    m = re.match(r"(.*?)<span class=\"pinyin_h\">(.*?)</span>", h3, re.S)
    word = html.unescape(re.sub(r"<[^>]+>", "", m.group(1) if m else h3)).strip()
    note = html.unescape(re.sub(r"<[^>]+>", "", m.group(2))).strip() if m else ""
    kana_note = note if note and is_kana(note.replace("・", "").replace("‐", "")) else ""
    reading = kata_to_hira(search_key(kana_note)) if kana_note else ""
    if is_kana(word):
        reading = reading or kata_to_hira(search_key(word))
        title = word + (f"（{note}）" if note and not kana_note else "")
        return title, reading, ([word] if word != reading else [])
    title = f"{kana_note}【{word}】" if kana_note else word
    return title, reading, [word] if has_japanese(word) else []


def load() -> Adapter:
    mdx = read_mdx(paths.SHOGAKUKAN_JC3)
    entries = []
    for key, record in mdx.records:
        parts = re.split(r"(?=<h3>)", record)
        if len(parts) == 1:  # a compound word of a main entry
            m = re.search(r'<div data-orgtag="subheadword"[^>]*>(.*?)</div>', record, re.S)
            word = html.unescape(re.sub(r"<[^>]+>", "", m.group(1))).strip() if m else key
            spellings = [word] if has_japanese(word) and not is_kana(word) else []
            reading = kata_to_hira(search_key(word)) if is_kana(word) else ""
            # "参见：<a>parent</a>" stays: it leads to the main entry.
            entries.append(SourceEntry(key, word, reading, spellings, mark(record, whole_gloss=True)))
            continue
        for i, part in enumerate(parts[1:]):
            h3 = re.search(r"<h3>(.*?)</h3>", part, re.S).group(1)
            title, reading, spellings = _head(h3)
            entries.append(SourceEntry(key if i == 0 else f"{key}#{i}", title, reading, spellings,
                                       mark(part, whole_gloss=False), aliases=[key]))
    return Adapter("shogakukan_jc3", "小学館 日中辞典 第三版", mdx.header.get("Title", ""), entries, rule,
                   link_target, GAIJI, lang="ja-zh")
