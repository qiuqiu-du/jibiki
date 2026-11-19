"""Read Kanjium accents.txt: `headword<TAB>reading<TAB>accents`.
Accents may carry part-of-speech labels, e.g. `(副)0,(名)3`."""
import re
from pathlib import Path


def read_kanjium(path: Path):
    for line in path.read_text("utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        head, reading, accents = parts
        nums = []
        for n in re.sub(r"\([^)]*\)", "", accents).split(","):
            n = n.strip()
            if n.isdigit() and n not in nums:
                nums.append(n)
        if nums:
            yield head, reading or head, nums
