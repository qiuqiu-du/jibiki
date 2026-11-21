"""Imports an .apkg exported by the app into a scratch Anki collection and prints its cards.

Needs Anki's Python library (pip install anki), which tools/ doesn't otherwise use, so run it
from a separate virtual environment:
    python tools/tests/check_anki.py path/to/export.apkg
Exits with code 1 if Anki can't import the file or finds no cards.
"""
import sys
import tempfile
from pathlib import Path

from anki.collection import Collection, ImportAnkiPackageOptions, ImportAnkiPackageRequest


def main(apkg: str) -> int:
    with tempfile.TemporaryDirectory() as tmp:
        col = Collection(str(Path(tmp) / "check.anki2"))
        try:
            col.import_anki_package(ImportAnkiPackageRequest(package_path=str(Path(apkg).resolve()), options=ImportAnkiPackageOptions()))
            ids = col.find_notes("")
            for nid in ids:
                note = col.get_note(nid)
                card = note.cards()[0]
                print(col.decks.name(card.did), "|", note.note_type()["name"], "|", " / ".join(note.fields))
                card.question(), card.answer()  # renders the templates
            print(f"{len(ids)} notes")
            return 0 if ids else 1
        finally:
            col.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
