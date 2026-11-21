"""Estimate what a dictionary would take as a datapack, before writing its adapter.

Usage: tools/.venv/bin/python tools/measure_sources.py <name> [...]
       tools/.venv/bin/python tools/measure_sources.py --show <word> <name> [...]
Names: the folders in dictionaries/ (daijisen, kodansha-jc, ...), or 'all'.

For each source it prints the size of the entry bodies at each stage of build_dict.py:
source markup -> light markup (a generic conversion that keeps only what the app draws) ->
zstd with a trained dictionary (measured on a random sample). Also the share of plain text,
the mix of scripts in it (an English column shows up as Latin letters) and the number of
lookup keys, which become the dict_key index. Nothing is written; only numbers are printed.
"""
import html
import random
import re
import sys
import time
import unicodedata
from pathlib import Path

import zstandard

import paths
from lib.markup import DROP, UNWRAP, convert, emit, plain_text
from lib.packdb import ZSTD_LEVEL, train_zdict

SAMPLE = 20000
_KEEP = {"b": "b", "i": "i", "sup": "sup", "sub": "sub", "ruby": "ruby", "rt": "rt"}


def generic_rule(tag, a):
    """What any adapter keeps: emphasis, line breaks, links; no styling, scripts or pictures."""
    if tag in ("script", "style", "link", "img", "figure", "audio", "video", "head", "title", "meta"):
        return DROP
    if tag == "a":
        return emit("a", href=a.get("href", "")) if a.get("href") else UNWRAP
    if tag == "br":
        return emit("br")
    if tag in ("p", "div", "li", "tr"):
        return emit("p")
    if tag in _KEEP:
        return emit(_KEEP[tag])
    return UNWRAP


def script_mix(text):
    counts = {"kana": 0, "han": 0, "latin": 0, "other": 0}
    for ch in text:
        o = ord(ch)
        if 0x3040 <= o <= 0x30FF:
            counts["kana"] += 1
        elif 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF:
            counts["han"] += 1
        elif ch.isascii() and ch.isalpha():
            counts["latin"] += 1
        elif not ch.isspace():
            counts["other"] += 1
    total = sum(counts.values()) or 1
    return " ".join(f"{k} {v / total:.0%}" for k, v in counts.items())


def mdx_bodies(path):
    from readers.mdx_reader import read_mdx
    mdx = read_mdx(path)
    keys = {k for k, _ in mdx.records} | set(mdx.links)
    return [b for _, b in mdx.records], len(mdx.links), keys


def yomitan_bodies(path):
    from dicts.shinmeikai8 import to_html
    from readers.yomitan_reader import iter_terms
    seen, bodies, keys = set(), [], set()
    for t in iter_terms(path):
        keys.add(t.term)
        keys.add(t.reading)
        if t.sequence not in seen:
            seen.add(t.sequence)
            bodies.append(to_html(t.content))
    return bodies, 0, keys


def coverage(raw_keys):
    """Share of JMdict words with frequency data (the common ones) that have an entry here:
    a kanji spelling, or the reading of a word written in kana, matches one of the keys."""
    import sqlite3
    from lib.textnorm import search_key
    keys = set()
    for raw in raw_keys:
        m = re.match(r"^(.*?)【(.*?)】", raw)
        parts = [m.group(1), *re.split(r"[・／]", m.group(2))] if m else [raw]
        keys.update(search_key(re.sub(r"[‐・▽▼×△〈〉]", "", p)) for p in parts if p.strip())
    db = sqlite3.connect(paths.CORE_DB)
    words = {}
    for eid, kanji, reading, kana_word in db.execute(
            "SELECT f.entry_id, f.kanji, f.reading, (e.flags & 1) FROM form f JOIN entry e ON e.id = f.entry_id WHERE e.freq < 99"):
        w = words.setdefault(eid, set())
        if kanji:
            w.add(search_key(kanji))
        if not kanji or kana_word:
            w.add(search_key(reading))
    hit = sum(1 for w in words.values() if w & keys)
    return hit, len(words)


def sources(name):
    folder = paths.DICTIONARIES / name
    files = sorted(folder.glob("*.mdx")) + sorted(folder.glob("*.zip"))
    return files


def measure(path):
    t0 = time.time()
    bodies, links, keys = yomitan_bodies(path) if path.suffix == ".zip" else mdx_bodies(path)
    n = len(bodies)
    src = sum(len(b.encode()) for b in bodies)
    rnd = random.Random(0)
    idx = rnd.sample(range(n), min(n, SAMPLE))
    light = [convert(bodies[i], generic_rule).body for i in idx]
    scale = n / len(idx)
    light_bytes = [s.encode() for s in light]
    light_total = sum(map(len, light_bytes)) * scale
    text = [html.unescape(plain_text(s)) for s in light]
    text_total = sum(len(t.encode()) for t in text) * scale
    half = len(light_bytes) // 2
    zdict = train_zdict(light_bytes[:half] or light_bytes)
    cctx = zstandard.ZstdCompressor(level=ZSTD_LEVEL, dict_data=zdict, write_content_size=True,
                                    write_checksum=False, write_dict_id=False)
    test = light_bytes[half:] or light_bytes
    ratio = sum(len(cctx.compress(b)) for b in test) / max(1, sum(map(len, test)))
    mb = lambda x: f"{x / 1e6:7.1f} MB"
    print(f"== {path.relative_to(paths.ROOT)}  ({time.time() - t0:.0f} s)")
    hit, common = coverage(keys)
    print(f"   entries {n:,}  link records {links:,}  lookup keys {len(keys):,}  common words covered {hit / common:.1%}")
    print(f"   source markup {mb(src)}  -> light markup {mb(light_total)}  -> zstd {mb(light_total * ratio)}"
          f"  (x{ratio:.2f})")
    print(f"   plain text {mb(text_total)} ({text_total / max(1, light_total):.0%} of light markup);"
          f"  scripts: {script_mix(''.join(text))}")
    sys.stdout.flush()


def show(word, names):
    """A short plain-text look at one word's entry in each source (a few lines only)."""
    from readers.mdx_reader import read_mdx
    for name in names:
        for f in sources(name):
            if f.suffix != ".mdx":
                continue
            mdx = read_mdx(f)
            body = next((b for k, b in mdx.records if k == word or k.startswith(word + "【") or f"【{word}】" in k), None)
            if body is None:
                target = next((t[0] for k, t in mdx.links.items() if k == word), None)
                body = next((b for k, b in mdx.records if k == target), "") if target else ""
            text = re.sub(r"\s+", " ", html.unescape(plain_text(convert(body, generic_rule).body)))
            print(f"== {name}: {text[:160]}")


def main(names):
    if names and names[0] == "--show":
        return show(names[1], names[2:])
    if names == ["all"]:
        names = sorted(p.name for p in paths.DICTIONARIES.iterdir() if p.is_dir())
    for name in names:
        for f in sources(name):
            measure(f)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
