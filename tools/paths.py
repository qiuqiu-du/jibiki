"""Filesystem locations shared by all build scripts."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DICTIONARIES = ROOT / "dictionaries"
SOURCES = ROOT / "sources"
DATAPACKS = ROOT / "datapacks"

JMDICT = SOURCES / "jmdict" / "JMdict_e.gz"
KANJIUM = SOURCES / "kanjium" / "accents.txt"
# Jiten word frequency (CC BY-SA 4.0): download "Global" CSV by hand from
# https://jiten.moe/frequency-dictionaries (the site refuses scripts).
JITEN = SOURCES / "jiten" / "frequency_list_global.csv"
NHK2016 = DICTIONARIES / "nhk-accent-2016" / "nhk-accent-2016.json"
DAIJISEN = DICTIONARIES / "daijisen" / "daijisen.mdx"
MEIKYO3 = DICTIONARIES / "meikyo3" / "meikyo3.mdx"
SHINMEIKAI8 = DICTIONARIES / "shinmeikai8" / "shinmeikai8.zip"
DAIJIRIN4 = DICTIONARIES / "daijirin4" / "daijirin4.zip"
SHOGAKUKAN_JC3 = DICTIONARIES / "shogakukan-jc3" / "shogakukan-jc3.mdx"
SHINSEIKI_JC = DICTIONARIES / "shinseiki-jc" / "shinseiki-jc.mdx"

CORE_DB = DATAPACKS / "core.db"
CORE_ZST = DATAPACKS / "core.db.zst"   # bundled with the app
