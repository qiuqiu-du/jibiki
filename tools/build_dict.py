"""Build datapacks/dict_<id>.db for one dictionary.

Usage: tools/.venv/bin/python tools/build_dict.py daijisen|meikyo3|shinmeikai8|daijirin4
Requires datapacks/core.db (run build_core.py first) for JMdict alignment.
"""
import collections
import importlib
import random
import re
import sqlite3
import sys

import zstandard

import paths
from lib.markup import convert, plain_text, resolve_links
from lib.packdb import (BODY_FORMAT_VERSION, DICT_SCHEMA, DICT_SCHEMA_VERSION, ZSTD_LEVEL,
                        fresh_db, finish_db, train_zdict, write_meta)
from lib.textnorm import kata_to_hira, search_key
from readers.jmdict_reader import read_jmdict

ADAPTERS = {"daijisen": "dicts.daijisen", "meikyo3": "dicts.meikyo3", "shinmeikai8": "dicts.shinmeikai8",
            "daijirin4": "dicts.daijirin4"}


def load_core_keys():
    conn = sqlite3.connect(paths.CORE_DB)
    keys = collections.defaultdict(set)
    for key, seq in conn.execute("SELECT key, entry_id FROM search_key"):
        keys[key].add(seq)
    conn.close()
    return keys


def load_core_forms():
    """Alignment indexes, read from JMdict itself (not core.db's display forms).

    by_pair: (spelling, reading) -> entries, over every JMdict spelling including search-only
        ones (sK, e.g. 居らっしゃる), since a dictionary may print those in its headword.
    by_kana: kana -> entries written in kana: kana-only spellings (after dropping search-only
        kanji) and words JMdict marks as usually written in kana (uk).
    """
    by_pair = collections.defaultdict(set)
    by_kana = collections.defaultdict(set)
    for e in read_jmdict(paths.JMDICT):
        for kanji, reading, _ in e.all_pairs():
            if kanji:
                by_pair[(kanji, kata_to_hira(reading))].add(e.seq)
        for kanji, reading, _ in e.pairs():
            if not kanji or e.usually_kana:
                by_kana[kata_to_hira(reading)].add(e.seq)
    return by_pair, by_kana


def align(entry, by_pair, by_kana):
    seqs = set()
    reading = entry.reading
    for s in entry.spellings:
        seqs |= by_pair.get((s, reading), set())
    if not seqs and reading and not entry.spellings:
        seqs |= by_kana.get(reading, set())
    if not seqs and reading:
        # Katakana loanwords and kana words written with a kana "spelling".
        for s in entry.spellings:
            if kata_to_hira(s) == reading:
                seqs |= by_kana.get(reading, set())
    return seqs


def alias_keys(alias):
    """Raw alias like たべる【食べる】 -> searchable keys."""
    m = re.match(r"^(.*?)【(.*?)】", alias)
    parts = [m.group(1), *re.split(r"[・／]", m.group(2))] if m else [alias]
    return [search_key(p) for p in parts if p.strip()]


def main(dict_id):
    adapter = importlib.import_module(ADAPTERS[dict_id]).load()
    entries = adapter.entries
    print(f"{dict_id}: {len(entries)} entries", flush=True)

    # Keys -> entry ids (1-based, in source order)
    raw_index = {}
    keys = set()
    for i, e in enumerate(entries, 1):
        for raw in [e.src_id, e.title, *e.aliases]:
            raw_index.setdefault(raw, i)
        for k in [e.reading, *e.spellings]:
            if k:
                keys.add((search_key(k), i))
        for a in e.aliases:
            for k in alias_keys(a):
                if k:
                    keys.add((k, i))

    link_stats = collections.Counter()

    def resolve(href):
        target = adapter.link_target(href)
        if target is None:
            link_stats["dropped"] += 1
            return ""
        if target in raw_index:
            link_stats["resolved"] += 1
            return f"#{raw_index[target]}"
        link_stats["search"] += 1
        return "?" + search_key(re.sub(r"【.*", "", target))

    bodies, images = [], collections.Counter()
    for e in entries:
        r = convert(e.source_html, adapter.rule)
        body = resolve_links(r.body, r.links, resolve)
        body = body.replace('<a href="">', "<a>")
        if adapter.post:
            body = adapter.post(body)
        bodies.append(body)
        images.update(r.images)

    by_pair, by_kana = load_core_forms()
    links = []
    linked = collections.defaultdict(set)
    for i, e in enumerate(entries, 1):
        seqs = align(e, by_pair, by_kana)
        linked[i] = seqs
        links += [(i, s) for s in seqs]
    aligned = sum(1 for s in linked.values() if s)

    # Drop keys the app can already reach through core.db search_key -> dict_link.
    core_keys = load_core_keys()
    all_keys = len(keys)
    keys = {(k, i) for k, i in keys if not (core_keys.get(k, set()) & linked[i])}

    print("training zstd dictionary ...", flush=True)
    encoded = [(e.title + "\n" + b).encode("utf-8") for e, b in zip(entries, bodies)]
    rnd = random.Random(0)
    zdict = train_zdict(rnd.sample(encoded, min(len(encoded), 60000)))
    cctx = zstandard.ZstdCompressor(level=ZSTD_LEVEL, dict_data=zdict, write_content_size=True,
                                    write_checksum=False, write_dict_id=False)

    out = paths.DATAPACKS / f"dict_{dict_id}.db"
    conn = fresh_db(out, DICT_SCHEMA)
    conn.execute("INSERT INTO zdict VALUES (1, ?)", (zdict.as_bytes(),))
    conn.executemany(
        "INSERT INTO dict_entry VALUES (?,?)",
        [(i, cctx.compress(b)) for i, b in enumerate(encoded, 1)],
    )
    conn.executemany("INSERT OR IGNORE INTO dict_key VALUES (?,?)", sorted(keys))
    conn.executemany("INSERT OR IGNORE INTO dict_link VALUES (?,?)", links)
    media = adapter.gaiji.media() if adapter.gaiji else {}
    conn.executemany("INSERT INTO media VALUES (?,?)", sorted(media.items()))
    write_meta(conn, {
        "schema_version": DICT_SCHEMA_VERSION,
        "body_format": BODY_FORMAT_VERSION,
        "kind": "dictionary",
        "dict_id": dict_id,
        "title": adapter.title,
        "revision": adapter.revision,
        "entry_count": len(entries),
    })
    finish_db(conn, out)

    raw_mb = sum(len(b) for b in encoded) / 1e6
    print(f"aligned to JMdict: {aligned}/{len(entries)} = {aligned / len(entries):.1%}")
    print(f"links: {dict(link_stats)}; gaiji images: {sum(images.values())} ({len(images)} distinct, "
          f"{sum(map(len, media.values())) / 1e3:.1f} KB)")
    print(f"keys {len(keys)} (of {all_keys})  body markup {raw_mb:.1f} MB  pack {out.stat().st_size / 1e6:.1f} MB")
    sample = rnd.sample(range(len(entries)), 3)
    for i in sample:
        print("  sample:", entries[i].title, "|", entries[i].reading, entries[i].spellings[:3], "|",
              plain_text(bodies[i])[:80].replace("\n", " "))


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ADAPTERS:
        sys.exit(__doc__)
    main(sys.argv[1])
