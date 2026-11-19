"""Write test vectors so the app's SearchKey can be checked against lib/textnorm.

Usage: tools/.venv/bin/python tools/tests/gen_search_key_vectors.py
Output: app/src/test/resources/search_key_vectors.tsv (input<TAB>expected)
"""
import random
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import paths  # noqa: E402
from lib.textnorm import search_key  # noqa: E402

HAND = ["タベル", "た・べる", "ＡＢＣ", "ｶﾀｶﾅ", "コンピューター", "ヴァイオリン", "懸（け）橋",
        "〈副〉", "全角　空白", "あい‐とう", "Ｗｉ－Ｆｉ", "ヶ月", "ヵ所", "①", "㍻"]

out = paths.ROOT / "app/src/test/resources/search_key_vectors.tsv"
conn = sqlite3.connect(paths.CORE_DB)
rows = [r[0] for r in conn.execute("SELECT kanji FROM form WHERE kanji IS NOT NULL")] + \
       [r[0] for r in conn.execute("SELECT reading FROM form")]
random.Random(0).shuffle(rows)
samples = HAND + rows[:3000]
with out.open("w", encoding="utf-8") as f:
    for s in samples:
        if "\t" not in s and "\n" not in s:
            f.write(f"{s}\t{search_key(s)}\n")
print(f"wrote {len(samples)} vectors to {out}")
