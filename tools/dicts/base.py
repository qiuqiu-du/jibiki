"""Shared shape for dictionary adapters used by build_dict.py."""
from dataclasses import dataclass, field

from lib.markup import Rule


@dataclass
class SourceEntry:
    src_id: str              # id/key of the record in the source file
    title: str               # headline as printed, e.g. た・べる【食べる】
    reading: str             # hiragana reading ('' if unknown)
    spellings: list[str]     # kanji/kana spellings from the notation, cleaned
    source_html: str         # body in the source's HTML dialect
    aliases: list[str] = field(default_factory=list)  # extra raw lookup keys


@dataclass
class Adapter:
    dict_id: str
    title: str
    revision: str
    entries: list[SourceEntry]
    rule: Rule
    link_target: callable    # raw href -> raw key in the source, or None to unwrap
    gaiji: object = None     # lib.gaiji.Gaiji: pictures kept as images go into the pack
    post: callable = None    # body markup -> body markup, applied after conversion
