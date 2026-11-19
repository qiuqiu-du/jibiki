"""Read the NHK 2016 accent index (headword -> recordings -> pitch)."""
import json
from dataclasses import dataclass
from pathlib import Path


@dataclass
class NhkRecording:
    kana: str      # katakana as pronounced, long vowels written ー
    pattern: str   # e.g. タベ＼ル
    accent: str    # e.g. "2", "0", "1+1"


def read_nhk2016(path: Path) -> dict[str, list[NhkRecording]]:
    data = json.loads(path.read_text("utf-8"))
    files = data["files"]
    out = {}
    for head, names in data["headwords"].items():
        recs = []
        for n in names:
            f = files.get(n)
            if f:
                recs.append(NhkRecording(f["kana_reading"], f["pitch_pattern"], f["pitch_number"]))
        if recs:
            out[head] = recs
    return out
