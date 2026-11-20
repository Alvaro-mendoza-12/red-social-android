#!/usr/bin/env python3
"""Check the archived storage volume against the producer's PostgreSQL snapshot."""

import argparse
import csv
import gzip
import hashlib
import re
import sqlite3
import sys
import tarfile
import tempfile
from contextlib import closing
from pathlib import Path


def catalog_rows(path):
    with path.open(encoding="utf-8", newline="") as source:
        for object_id, storage_path, size, sha256, row_version in csv.reader(source):
            if not storage_path.startswith("/data/storage/"):
                raise ValueError("catalog path outside the storage volume")
            name = storage_path[len("/data/storage/"):]
            if any(part in ("", ".", "..") for part in name.split("/")):
                raise ValueError("noncanonical catalog path")
            if not size.isdecimal() or not row_version.isdecimal():
                raise ValueError("invalid storage size or row version")
            if sha256 and not re.fullmatch("[0-9a-f]{64}", sha256):
                raise ValueError("invalid catalog digest")
            yield object_id, name, int(size), sha256 or None, row_version


def file_digest(source):
    digest = hashlib.sha256()
    for block in iter(lambda: source.read(1024 * 1024), b""):
        digest.update(block)
    return digest.hexdigest()


def record_files(db, archive):
    # Store member identities on disk. A hard link refers to a preceding tar
    # entry, which may be outside the SQL catalog after a migration.
    need_digests = db.execute("SELECT 1 FROM expected WHERE digest IS NOT NULL LIMIT 1").fetchone()
    with gzip.open(archive, "rb") as stream:
        with tarfile.open(fileobj=stream, mode="r|") as entries:
            for entry in entries:
                name = entry.name.removeprefix("./")
                if entry.isfile():
                    digest = None
                    if need_digests:
                        with entries.extractfile(entry) as source:
                            digest = file_digest(source)
                    db.execute("INSERT INTO files VALUES (?, ?, ?)", (name, entry.size, digest))
                elif entry.islnk():
                    target = entry.linkname.removeprefix("./")
                    cursor = db.execute(
                        "INSERT INTO files SELECT ?, size, digest FROM files WHERE name = ?",
                        (name, target),
                    )
                    if cursor.rowcount != 1:
                        raise ValueError("unresolved archive hard link")
                entries.members.clear()
        # Read through the gzip trailer, including when tar encountered its end marker.
        while stream.read(1024 * 1024):
            pass


def verify(expected, current, archive):
    with tempfile.TemporaryDirectory(dir=expected.parent) as work:
        with closing(sqlite3.connect(Path(work) / "catalog.sqlite")) as db:
            db.executescript("""
                CREATE TABLE expected (id TEXT PRIMARY KEY, name TEXT UNIQUE, size INTEGER,
                                       digest TEXT, version TEXT);
                CREATE TABLE current (id TEXT PRIMARY KEY, version TEXT);
                CREATE TABLE files (name TEXT PRIMARY KEY, size INTEGER, digest TEXT);
            """)
            db.executemany("INSERT INTO expected VALUES (?, ?, ?, ?, ?)", catalog_rows(expected))
            with current.open(encoding="utf-8", newline="") as source:
                db.executemany("INSERT INTO current VALUES (?, ?)", csv.reader(source))
            record_files(db, archive)
            missing, wrong_size, wrong_digest, changed = db.execute("""
                SELECT count(*) FILTER (WHERE f.name IS NULL),
                       count(*) FILTER (WHERE e.size <> f.size),
                       count(*) FILTER (WHERE e.digest <> f.digest),
                       count(*) FILTER (WHERE e.digest IS NULL AND e.version IS NOT c.version)
                FROM expected e
                LEFT JOIN files f ON e.name = f.name
                LEFT JOIN current c ON e.id = c.id
            """).fetchone()
            if any((missing, wrong_size, wrong_digest, changed)):
                raise ValueError(f"storage mismatch: missing={missing} size={wrong_size} "
                                 f"digest={wrong_digest} changed_unhashed={changed}")
            total, hashed = db.execute("SELECT count(*), count(digest) FROM expected").fetchone()
            print(f"STORAGE_CATALOG_VERIFIED={total} SHA256_FROM_DATABASE={hashed}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ("expected", "current", "archive"):
        parser.add_argument(field, type=Path)
    args = parser.parse_args()
    try:
        verify(args.expected, args.current, args.archive)
    except (OSError, EOFError, ValueError, csv.Error, sqlite3.Error, tarfile.TarError) as error:
        sys.exit(f"ERROR: {error}")
