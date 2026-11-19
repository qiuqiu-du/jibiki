"""Read Yomitan dictionary zips (format 3): term banks grouped by sequence."""
import json
import zipfile
from dataclasses import dataclass
from pathlib import Path


@dataclass
class YomitanTerm:
    term: str
    reading: str
    rules: str        # deinflection class: v1, v5, adj-i, vs, vk, vz or ''
    content: list     # structured-content glossary list
    sequence: int


def read_index(path: Path) -> dict:
    with zipfile.ZipFile(path) as zf:
        return json.loads(zf.read("index.json"))


def iter_terms(path: Path):
    with zipfile.ZipFile(path) as zf:
        names = [n for n in zf.namelist() if n.startswith("term_bank_")]
        names.sort(key=lambda n: int(n.split("_")[-1].split(".")[0]))
        for name in names:
            for row in json.loads(zf.read(name)):
                yield YomitanTerm(row[0], row[1] or row[0], row[3], row[5], row[6])


def read_grouped(path: Path) -> list[list[YomitanTerm]]:
    """Terms sharing a sequence number describe the same entry."""
    groups: dict[int, list[YomitanTerm]] = {}
    for t in iter_terms(path):
        groups.setdefault(t.sequence, []).append(t)
    return list(groups.values())
