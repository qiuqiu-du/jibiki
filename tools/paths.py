"""Filesystem locations shared by all build scripts."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DICTIONARIES = ROOT / "dictionaries"
SOURCES = ROOT / "sources"
DATAPACKS = ROOT / "datapacks"

JMDICT = SOURCES / "jmdict" / "JMdict_e.gz"
KANJIUM = SOURCES / "kanjium" / "accents.txt"
NHK2016 = DICTIONARIES / "nhk-accent-2016" / "nhk-accent-2016.json"
DAIJISEN = DICTIONARIES / "daijisen" / "daijisen.mdx"
MEIKYO3 = DICTIONARIES / "meikyo3" / "meikyo3.mdx"
SHINMEIKAI8 = DICTIONARIES / "shinmeikai8" / "shinmeikai8.zip"
DAIJIRIN4 = DICTIONARIES / "daijirin4" / "daijirin4.zip"

CORE_DB = DATAPACKS / "core.db"
