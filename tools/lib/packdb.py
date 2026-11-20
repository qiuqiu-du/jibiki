"""Writers for the SQLite data packs consumed by the app (Architecture.md §9)."""
import sqlite3
import time
from pathlib import Path

import zstandard

DICT_SCHEMA_VERSION = 2  # 2: media table (gaiji pictures)
BODY_FORMAT_VERSION = 1
ZDICT_SIZE = 1024 * 1024  # 1 MB: measured ~16% smaller than 128 KB on Daijisen
ZSTD_LEVEL = 19

DICT_SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE zdict (id INTEGER PRIMARY KEY NOT NULL, data BLOB NOT NULL);
CREATE TABLE dict_entry (
    id       INTEGER PRIMARY KEY NOT NULL,
    body     BLOB NOT NULL           -- zstd(title, newline, body markup) with zdict 1
);
-- Lookup keys NOT already covered by core.db search_key + dict_link.
CREATE TABLE dict_key (key TEXT NOT NULL, entry_id INTEGER NOT NULL, PRIMARY KEY (key, entry_id)) WITHOUT ROWID;
CREATE TABLE dict_link (entry_id INTEGER NOT NULL, core_id INTEGER NOT NULL, PRIMARY KEY (core_id, entry_id)) WITHOUT ROWID;
-- Pictures referenced by <img src="NAME"> in bodies (gaiji): PNG or GIF bytes.
CREATE TABLE media (name TEXT PRIMARY KEY NOT NULL, data BLOB NOT NULL) WITHOUT ROWID;
"""


def fresh_db(path: Path, schema: str) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.unlink(missing_ok=True)
    conn = sqlite3.connect(tmp)
    conn.execute("PRAGMA journal_mode=OFF")
    conn.execute("PRAGMA synchronous=OFF")
    conn.execute("PRAGMA page_size=4096")
    conn.executescript(schema)
    return conn


def finish_db(conn: sqlite3.Connection, path: Path) -> None:
    conn.commit()
    conn.execute("VACUUM")
    conn.close()
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.replace(path)


def write_meta(conn: sqlite3.Connection, items: dict) -> None:
    base = {"built_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    conn.executemany(
        "INSERT INTO meta(key, value) VALUES (?, ?)",
        [(k, str(v)) for k, v in {**base, **items}.items()],
    )


def train_zdict(samples: list[bytes]) -> zstandard.ZstdCompressionDict:
    return zstandard.train_dictionary(ZDICT_SIZE, samples, level=ZSTD_LEVEL)
