"""Build a pitch-only pack: datapacks/pitch_<source>.db (accents aligned to JMdict, no bodies).

Sources without definitions live here; dictionaries that print accents in their headwords
(新明解, 大辞林) carry them in their own pack instead (build_dict.add_pitch). See lib/pitch.py.
Usage: tools/.venv/bin/python tools/build_pitch.py nhk2016
"""
import sys

import paths
from lib import pitch
from lib.packdb import DICT_SCHEMA_VERSION, fresh_db, finish_db, write_meta
from readers.nhk2016_reader import read_nhk2016

SOURCES = {
    "nhk2016": ("NHK日本語発音アクセント新辞典", lambda index: pitch.nhk2016(index, read_nhk2016(paths.NHK2016))),
}


def main(source):
    title, build = SOURCES[source]
    print("reading JMdict ...", flush=True)
    index = pitch.JmdictIndex()
    print(f"{source} ...", flush=True)
    accents = build(index)
    out = paths.DATAPACKS / f"pitch_{source}.db"
    conn = fresh_db(out, "CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);")
    write_meta(conn, {"schema_version": DICT_SCHEMA_VERSION, "kind": "pitch", "title": title})
    pitch.write(conn, source, accents)
    finish_db(conn, out)
    print(f"size {out.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in SOURCES:
        sys.exit(f"usage: build_pitch.py {{{'|'.join(SOURCES)}}}")
    main(sys.argv[1])
