"""Show how a dictionary source is marked up, before writing its adapter.

Usage: tools/.venv/bin/python tools/inspect_source.py <file under dictionaries/> [word ...]

Prints how often each tag / class / data-name occurs (Yomitan structured content is serialized
the way dicts/shinmeikai8.to_html does), the Yomitan index and tag banks, and, for each word
given, a short piece of its raw markup. Only counts and short fragments are printed.
"""
import collections
import json
import re
import sys
import zipfile

import paths


def markup_stats(bodies):
    tags, attrs = collections.Counter(), collections.Counter()
    for b in bodies:
        for m in re.finditer(r"<([a-zA-Z0-9]+)([^>]*)>", b):
            tags[m.group(1)] += 1
            for k, v in re.findall(r'(class|data-name)="([^"]*)"', m.group(2)):
                attrs[f"{m.group(1)}.{k}={v}"] += 1
    print("tags:", tags.most_common(25))
    print("classes / data-names:")
    for k, n in attrs.most_common(80):
        print(f"   {n:8d}  {k}")


def main(path, words):
    path = paths.DICTIONARIES / path
    if path.suffix == ".zip":
        from dicts.shinmeikai8 import to_html
        from readers.yomitan_reader import iter_terms
        with zipfile.ZipFile(path) as z:
            print("index:", json.loads(z.read("index.json")))
            print("files:", collections.Counter(re.sub(r"\d+", "N", n.split("/")[0]) for n in z.namelist()).most_common(10))
            for n in z.namelist():
                if n.startswith("tag_bank"):
                    print("tags bank:", json.loads(z.read(n))[:40])
        seen, bodies, by_term, rows = set(), [], {}, 0
        rules = collections.Counter()
        for t in iter_terms(path):
            rows += 1
            rules[t.rules] += 1
            if t.sequence not in seen:
                seen.add(t.sequence)
                bodies.append(to_html(t.content))
                if t.term in words:
                    by_term.setdefault(t.term, bodies[-1])
        print(f"rows {rows}, sequences {len(seen)}, deinflection rules {rules.most_common(8)}")
    else:
        from readers.mdx_reader import read_mdx
        mdx = read_mdx(path)
        bodies = [b for _, b in mdx.records]
        by_term = {k: b for k, b in mdx.records if any(k == w or k.startswith(w + "【") or f"【{w}】" in k for w in words)}
        print("header:", {k: v[:80] for k, v in mdx.header.items()})
        print(f"records {len(bodies)}, links {len(mdx.links)}; sample keys:", [k for k, _ in mdx.records[1000:1006]])
    markup_stats(bodies)
    for w, b in by_term.items():
        print(f"== {w}: {b[:700]}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2:])
