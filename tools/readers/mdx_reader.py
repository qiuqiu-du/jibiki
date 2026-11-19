"""Read MDict .mdx files: main records plus @@@LINK= alias records."""
from dataclasses import dataclass, field
from pathlib import Path

from mdict_utils.base.readmdict import MDX

LINK_PREFIX = "@@@LINK="


@dataclass
class MdxData:
    records: list[tuple[str, str]]                       # (key, html) main records, file order
    links: dict[str, list[str]] = field(default_factory=dict)  # alias key -> target keys
    header: dict = field(default_factory=dict)


def read_mdx(path: Path) -> MdxData:
    mdx = MDX(str(path))
    data = MdxData([], {}, {k.decode(): v.decode() for k, v in mdx.header.items()})
    for k, v in mdx.items():
        key = k.decode("utf-8").strip()
        val = v.decode("utf-8").strip("\x00").strip()
        if val.startswith(LINK_PREFIX):
            target = val[len(LINK_PREFIX):].strip()
            data.links.setdefault(key, [])
            if target not in data.links[key]:
                data.links[key].append(target)
        else:
            data.records.append((key, val))
    return data
