"""Maintain evidence of actual file review; inventory and tests never imply code review."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()


def phase(path: str) -> str:
    if path.startswith(("app/", "baselineprofile/")) or path in {
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    } or path.startswith("gradle/"):
        return "android"
    if path.startswith("NoWing-web/"):
        return "web"
    if path.startswith(("infrastructure/", "server/", "sql/")):
        return "server"
    if path.startswith("docs/") or path.endswith((".md", ".txt", ".rst")):
        return "documentation"
    return "tooling-and-other"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_list(path: str | None) -> list[str]:
    return json.loads(Path(path).read_text(encoding="utf-8")) if path else []


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="docs/oficial/review-coverage.json")
    parser.add_argument("--import-mobile-catalog")
    parser.add_argument("--mark-read-list", help="JSON paths actually read, never a search or test result")
    parser.add_argument("--mark-partial-list", help="JSON paths with only partial code review")
    parser.add_argument("--evidence", default="docs/oficial/101-mobile-inicio-cuentas-y-comentarios-2026-10-31.md")
    args = parser.parse_args()
    root = Path(git("rev-parse", "--show-toplevel")).resolve()
    output = root / args.output
    previous = json.loads(output.read_text(encoding="utf-8")) if output.exists() else {}
    existing = {entry["file"]: entry for entry in previous.get("files", [])}
    source_catalog: dict[str, dict] = {}
    if args.import_mobile_catalog:
        source_catalog = {entry["file"]: entry for entry in json.loads(
            Path(args.import_mobile_catalog).read_text(encoding="utf-8"))["files"]}
        for path, entry in source_catalog.items():
            existing.setdefault(path, entry)
    reviewed = set(read_list(args.mark_read_list))
    partial = set(read_list(args.mark_partial_list))
    paths = set(subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode("utf-8").split("\0"))
    paths.update(reviewed | partial)
    paths.discard("")
    paths.discard(args.output)  # The generated manifest cannot include its own content hash.
    files: list[tuple[str, Path]] = []
    for path in sorted(paths):
        absolute = (root / path).resolve()
        if not absolute.is_relative_to(root):
            raise ValueError(f"Review target outside repository: {path}")
        if absolute.is_file():
            files.append((path, absolute))
    # Let Git apply its own text/binary attributes and clean filters. Raw Windows
    # CRLF hashes would invalidate the same source after checkout in another worktree.
    hashes = subprocess.run(
        ["git", "hash-object", "--stdin-paths"], cwd=root,
        input="".join(path + "\n" for path, _ in files), text=True, encoding="utf-8",
        capture_output=True, check=True,
    ).stdout.splitlines()
    if len(hashes) != len(files):
        raise ValueError("Incomplete Git file-hash inventory")
    entries: list[dict] = []
    for (path, absolute), current_hash in zip(files, hashes):
        old = existing.get(path, {})
        status = old.get("reviewStatus", "pending")
        same_content = old.get("gitBlobId") == current_hash
        if not same_content and "gitBlobId" not in old and old.get("sha256"):
            # Import only a legacy review whose actual working bytes still match.
            same_content = old["sha256"] == sha256(absolute)
        if not same_content:
            status = "pending"
        if path in reviewed:
            status = "code-read"
        if path in partial:
            status = "partial"
        if status not in {"pending", "partial", "code-read"}:
            raise ValueError(f"Unknown review state: {path}: {status}")
        entry = {"file": path, "phase": phase(path), "gitBlobId": current_hash, "reviewStatus": status}
        if status != "pending":
            entry["evidence"] = args.evidence if path in reviewed or path in partial else old.get("evidence", args.evidence)
            entry["note"] = "Code read is not complete flow validation or a production-readiness certificate."
        entries.append(entry)
    by_phase: dict[str, Counter] = defaultdict(Counter)
    for entry in entries:
        by_phase[entry["phase"]][entry["reviewStatus"]] += 1
    manifest = {
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "baseCommit": git("rev-parse", "HEAD"),
        "hashPolicy": "Git clean-filtered blob identity; equivalent LF/CRLF checkouts have the same review hash.",
        "scope": "Tracked repository working tree, plus explicitly reviewed new files; ignored backups and temporary files excluded.",
        "workflow": "Android first, then web and shared backend; no phase is closed by inventory generation.",
        "definitions": {
            "pending": "Not fully read at this exact file hash.",
            "partial": "Only part of this file or its relevant implementation was read.",
            "code-read": "Full source read at this hash; follow-ups, integration and runtime validation may still be open.",
        },
        "counts": dict(Counter(entry["reviewStatus"] for entry in entries)),
        "countsByPhase": {key: dict(value) for key, value in sorted(by_phase.items())},
        "files": entries,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"files": len(entries), "counts": manifest["counts"], "countsByPhase": manifest["countsByPhase"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
