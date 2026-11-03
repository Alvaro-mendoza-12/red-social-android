"""Put the public R2 Worker origin in Android build properties; never accept URL credentials."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit


def normalize_origin(value: str) -> str:
    try:
        parsed = urlsplit(value)
        host = parsed.hostname or ""
        labels = host.split(".")
        valid_host = len(labels) >= 2 and len(host) <= 253 and all(
            re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label, re.I | re.ASCII) for label in labels
        ) and not labels[-1].isdigit()
        valid = (not any(char.isspace() for char in value) and parsed.scheme == "https" and valid_host
                 and parsed.username is None and parsed.password is None and parsed.port in (None, 443)
                 and parsed.path in ("", "/") and not parsed.query and not parsed.fragment)
    except ValueError:
        valid = False
    if not valid:
        raise ValueError("Android media origin must be a root HTTPS hostname, without credentials, query or fragment.")
    return f"https://{host.lower()}"


def configure(properties: Path, origin: str) -> None:
    normalized = normalize_origin(origin)
    if properties.is_symlink():
        raise ValueError("Android properties must be a regular file, not a symbolic link.")
    existing = properties.read_bytes() if properties.exists() else b""
    newline = b"\r\n" if b"\r\n" in existing else b"\n"
    # Preserve all other bytes, including comments and unrelated secret properties.
    kept_lines: list[bytes] = []
    continuing = dropping = False
    for line in existing.splitlines(keepends=True):
        new_property = not continuing
        if new_property:
            dropping = bool(re.match(rb"[ \t]*MEDIA_EDGE_ORIGIN(?:[ \t]*[=:]|[ \t]+|$)", line.rstrip(b"\r\n")))
        if not dropping:
            kept_lines.append(line)
        body = line.rstrip(b"\r\n")
        comment = new_property and body.lstrip(b" \t\f").startswith((b"#", b"!"))
        continuing = not comment and (len(body) - len(body.rstrip(b"\\"))) % 2 == 1
    if continuing:
        raise ValueError("Android properties end in an unfinished continuation; the file was not changed.")
    kept = b"".join(kept_lines)
    if kept and not kept.endswith((b"\n", b"\r")):
        kept += newline
    data = kept + b"MEDIA_EDGE_ORIGIN=" + normalized.encode("ascii") + newline
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(dir=properties.parent, prefix=".android-media-origin-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, properties)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", type=Path, default=Path("secrets.properties"))
    parser.add_argument("--origin", required=True)
    args = parser.parse_args()
    try:
        configure(args.properties, args.origin)
    except (ValueError, OSError) as error:
        parser.exit(2, f"Could not configure Android media origin: {error}\n")
    print("Android media origin configured; no credentials printed.")


if __name__ == "__main__":
    main()
