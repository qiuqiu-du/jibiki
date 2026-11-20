"""Read single files from an MDict .mdd resource pack without unpacking the whole pack."""
from pathlib import Path

from mdict_utils.base.readmdict import MDD
from mdict_utils.reader import get_record


def read_folder(path: Path, folder: str) -> dict[str, bytes]:
    """Files directly under `folder` (e.g. "gaiji"), keyed by file name."""
    md = MDD(str(path))
    prefix = "\\" + folder + "\\"
    keys = md._key_list
    out = {}
    for i, (offset, key) in enumerate(keys):
        name = key.decode("utf-8")
        if not name.startswith(prefix):
            continue
        length = keys[i + 1][0] - offset if i + 1 < len(keys) else -1
        out[name[len(prefix):]] = get_record(md, key, offset, length)
    return out
