"""新世纪日汉双解大辞典 (MDX): every sense gives a Japanese definition, then its Chinese.

Source shape: <div class="wrap-switch"> per headword (several per record for homographs), the
headline in <h1><span class="word">reading</span><span>【spellings】</span></h1>, senses as
  <li><span>Japanese definition</span><span class="divide-imgs"><img (divider)></span>
      <span>Chinese: translation words。explanation。</span> examples…</li>
Marked for the app (design-spec「一行释义」):
  <span class="gloss">  the Chinese up to its first 「。」: the translation words, for the gloss line
  <span class="zh">     the rest of the Chinese, example translations, idiom translations
Pictures are inlined as base64 data: URIs (the divider on almost every sense, which is why the
source is 429 MB); the divider is dropped, the few others are kept as gaiji images.
"""
import base64
import hashlib
import html
import re

import paths
from dicts.base import Adapter, SourceEntry
from lib.gaiji import Gaiji
from lib.markup import DROP, UNWRAP, emit, raw
from lib.textnorm import has_japanese, is_kana, kata_to_hira, search_key, split_spellings
from readers.mdx_reader import read_mdx

DIVIDER = "b1a86398"  # md5 prefix of the Japanese/Chinese divider picture
_pictures: dict[str, bytes] = {}
GAIJI = Gaiji("shinseiki_jc", lambda: _pictures)

_DATA_IMG = re.compile(r'<img[^>]*src="data:image/png;base64,([^"]*)"[^>]*>')
_CHINESE_AFTER_DIVIDER = re.compile(r'<span class="divide-imgs">.*?</span>\s*<span>((?:(?!</span>).)*)</span>', re.S)


def _picture(m) -> str:
    data = m.group(1)
    digest = hashlib.md5(data.encode()).hexdigest()
    if digest.startswith(DIVIDER):
        return ""
    name = f"sj{digest[:12]}.png"
    _pictures.setdefault(name, base64.b64decode(data))
    return f'<img src="{name}">'


def _split_gloss(chinese: str) -> str:
    """「会，逢，会见。与别人见面。」 -> gloss 会，逢，会见 + the rest as plain Chinese."""
    m = re.match(r"(.*?。)(.*)", chinese, re.S)
    head, rest = (m.group(1), m.group(2)) if m else (chinese, "")
    out = f"<gloss>{head}</gloss>"
    return out + (f"<zh>{rest}</zh>" if rest.strip() else "")


def mark(block: str) -> str:
    """One headword's markup with its Chinese marked (as tags the rule turns into spans)."""
    block = re.sub(r"<!--.*?-->", "", block, flags=re.S)
    # An export bug half-escaped 44 superscript tags: 「10<sub><</sub>sup>-3</sup>」 is 10<sup>-3</sup>.
    block = re.sub(r"<sub><</sub>(/?)(sup|sub)>", r"<\1\2>", block)
    # Idioms (phrase-wrap): their Chinese is not the headword's meaning.
    block = re.sub(r'<p class="phrase-wrap">.*?</p>', lambda m: _CHINESE_AFTER_DIVIDER.sub(r" <zh>\1</zh>", m.group(0)), block, flags=re.S)
    block = _CHINESE_AFTER_DIVIDER.sub(lambda m: " " + _split_gloss(m.group(1)), block)
    # Examples: Japanese, then its Chinese translation.
    block = re.sub(r'<span class="example-source">((?:(?!</span>).)*)</span>\s*<span>((?:(?!</span>).)*)</span>',
                   lambda m: f"<jae>{m.group(1).replace('·', '・')}</jae> <zh>{m.group(2)}</zh>", block, flags=re.S)
    # 「⇒ example」 on one line (the source puts the example in a paragraph of its own).
    block = re.sub(r'<strong class="dot">⇒</strong>\s*<p>(.*?)</p>', r"⇒ \1", block, flags=re.S)
    # Cross-references name the target word in the link text.
    block = re.sub(r'<a class="link" href="javascript:;">((?:(?!</a>).)*)</a>',
                   lambda m: f'<a href="entry://{html.escape(_link_word(m.group(1)))}">{m.group(1)}</a>', block)
    # Sub-sense numbers are pictures missing from the .mdd: drawn by the app as circled numbers.
    block = re.sub(r'<img src="file://img/ic_number_japanese_(\d+)\.png"/?>', r'<num>\1</num>', block)
    return _DATA_IMG.sub(_picture, block)


def _link_word(text: str) -> str:
    t = html.unescape(re.sub(r"<[^>]+>", "", text))
    return re.sub(r"[（(][^）)]*[）)]|[。．\s]", "", t)


def rule(tag, a):
    cls = a.get("class", "")
    if tag in ("link", "script", "style"):
        return DROP
    if tag == "gloss":
        return emit("span", **{"class": "gloss"})
    if tag == "zh":
        return emit("span", **{"class": "zh"})
    if tag == "jae":
        return emit("span", **{"class": "ex"})
    if tag == "num":
        return emit("span", **{"class": "num-c"})
    if tag == "span" and cls == "ncdata-sense-id":
        return emit("span", **{"class": "sense"})
    if tag == "span" and cls == "divide-imgs":
        return DROP
    if tag == "p" and cls in ("sense-cat", "cat-cat", "cat-unit"):
        return emit("span", **{"class": "pos"})
    if tag == "h1":  # idioms inside the body
        return emit("b")
    if tag == "strong" and cls == "dot":
        return UNWRAP
    if tag in ("ul", "li"):  # one list per sense: its number stays on the definition's line
        return UNWRAP
    if tag in ("p", "div", "tr"):
        return emit("p")
    if tag == "a":
        return emit("a", href=a.get("href", "")) if a.get("href") else UNWRAP
    if tag == "img":
        return GAIJI.action(a.get("src", "")) if a.get("src", "").startswith("sj") else DROP
    if tag == "br":
        return emit("br")
    if tag in ("b", "i", "sup", "sub", "em"):
        return emit("i" if tag == "em" else tag)
    return UNWRAP


def link_target(href):
    return href[len("entry://"):] if href.startswith("entry://") else None


def _head(h1: str):
    """Headline -> (title, reading, spellings)."""
    word_m = re.search(r'<span class="word">(.*?)</span>', h1, re.S)
    word = html.unescape(re.sub(r"<[^>]+>", "", word_m.group(1))).strip() if word_m else ""
    rest = html.unescape(re.sub(r"<[^>]+>", "", h1[word_m.end():] if word_m else h1)).strip()
    notation = ""
    m = re.match(r"【(.*?)】", rest)
    if m:
        notation = m.group(1)
    # The source writes the Japanese middle dot as Latin 「·」 (た·べる); the other dictionaries use 「・」.
    title = (word + rest).strip().replace("·", "・")
    plain = word.replace("·", "").replace("‐", "")
    reading = kata_to_hira(search_key(plain)) if plain and is_kana(plain) else ""
    spellings = split_spellings(notation.replace("·", "・")) if notation and has_japanese(notation) else []
    if not reading and not spellings and has_japanese(plain):
        spellings = [plain]
    if is_kana(plain) and not notation and plain != reading:
        spellings = [plain]  # katakana loanwords: ビットマップ
    return title, reading, spellings


def load() -> Adapter:
    mdx = read_mdx(paths.SHINSEIKI_JC)
    entries = []
    for key, record in mdx.records:
        blocks = [b for b in re.split(r'(?=<div class="wrap-switch">)', record) if "ncdata-header" in b]
        for i, block in enumerate(blocks):
            h1 = re.search(r'<div class="ncdata-header">\s*<h1>(.*?)</h1>', block, re.S)
            title, reading, spellings = _head(h1.group(1)) if h1 else (key, "", [])
            if not any(c.isalnum() for c in title):  # 「⇒」: a headword lost to the source's encoding (Å)
                spelled = re.search(r'<span class="unabbrev">(.*?)</span>', block)
                title = f"［{re.sub(r'<[^>]+>', '', spelled.group(1))}］" if spelled else key
            body = re.sub(r'<div class="ncdata-header">.*?</div>', "", block, count=1, flags=re.S)
            entries.append(SourceEntry(key if i == 0 else f"{key}#{i}", title or key, reading, spellings, mark(body)))
    _attach_aliases(entries, mdx.links)
    return Adapter("shinseiki_jc", "新世纪日汉双解大辞典", mdx.header.get("Title", ""), entries, rule, link_target,
                   GAIJI, lang="ja-zh")


def _attach_aliases(entries, links):
    """Alias records (会う -> あ·う【会う·逢う】) become lookup keys and link targets."""
    by_key = {}
    for e in entries:
        by_key.setdefault(e.src_id, []).append(e)
    for alias, targets in links.items():
        for t in targets:
            for e in by_key.get(t, []):
                e.aliases.append(alias)
