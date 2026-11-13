"""Check source traceability, not implementation or code-review completion."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from pathlib import Path


PACKAGE = Path("Novvin_Auditoria_Ecosistema")
SOURCES = (
    "README.md", "PROMPT_MAESTRO_NOVVIN.md", "PROMPT_MAESTRO_NOVVIN.docx",
    "INVESTIGACION_MERCADO_BENCHMARK.md", "PRD_NOVVIN_LIVE_LIVE_PLAY.md",
    "MATRIZ_ACEPTACION_QA.md", "FUENTES_Y_REFERENCIAS.md",
)
MATRIX = Path("docs/product/REQUIREMENT_TRACEABILITY.csv")


def source_fingerprint(path: Path) -> str:
    # Preserve the matrix's UTF-8/LF policy across Git CRLF worktree checkouts.
    content = path.read_bytes() if path.suffix == ".docx" else path.read_text(encoding="utf-8").encode("utf-8")
    return hashlib.sha256(content).hexdigest()


def compatible_fingerprints(path: Path) -> set[str]:
    fingerprints = {source_fingerprint(path)}
    if path.suffix != ".docx":
        # Historical rows were recorded from the CRLF main worktree. Accept
        # precisely that newline variant, never a semantically changed source.
        text = path.read_text(encoding="utf-8")
        fingerprints.add(hashlib.sha256(text.replace("\n", "\r\n").encode("utf-8")).hexdigest())
    return fingerprints


def normalized_text(value: str) -> str:
    return re.sub(r"^(?:[-*+]\s+|\d+[.)]\s+)", "", value.strip()).replace("`", "")


def source_lines(path: Path) -> list[dict]:
    """Keep narrative paragraphs as well as list items and table data."""
    lines = path.read_text(encoding="utf-8-sig").splitlines()
    result = []
    section = ""
    for number, raw in enumerate(lines, 1):
        line = raw.strip()
        if line.startswith("#"):
            section = line.lstrip("#").strip()
            continue
        if not line:
            continue
        if line.startswith("|"):
            if re.fullmatch(r"[|:\s-]+", line):
                continue
            if number < len(lines) and re.fullmatch(r"[|:\s-]+", lines[number].strip()):
                continue  # Table header, followed by its separator.
        result.append({"line": number, "section": section, "text": line})
    return result


def expected_text(line: str) -> str:
    if line.startswith("|"):
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        # The acceptance source has ID, area, scenario and criterion columns.
        if len(cells) == 4:
            return f"{cells[2]} — {cells[3]}"
    return normalized_text(line)


def audit(root: Path) -> dict:
    with (root / MATRIX).open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    errors = []
    ids = set()
    for row in rows:
        key = row.get("id", "").strip()
        if not key or key in ids:
            errors.append({"id": key, "problem": "missing_or_duplicate_id"})
        ids.add(key)
    missing = []
    coverage = []
    for name in SOURCES:
        relative = (PACKAGE / name).as_posix()
        source = root / relative
        if not source.is_file():
            errors.append({"document": relative, "problem": "source_missing"})
            continue
        fingerprints = compatible_fingerprints(source)
        records = [row for row in rows if row.get("documento") == relative]
        if not records:
            errors.append({"document": relative, "problem": "source_unregistered"})
        for row in records:
            if row.get("sha256_fuente") not in fingerprints:
                errors.append({"id": row["id"], "problem": "source_hash_changed"})
            if not row.get("requerimiento", "").strip() or not row.get("estado", "").strip():
                errors.append({"id": row["id"], "problem": "empty_text_or_status"})
        if source.suffix == ".docx":
            coverage.append({"document": relative, "kind": "binary_fingerprint_only"})
            continue  # Reading/parity of the Word mandate is separate evidence.
        substantive = source_lines(source)
        by_number = {line["line"]: line for line in substantive}
        covered = set()
        for row in records:
            try:
                number = int(row.get("linea", ""))
            except ValueError:
                number = -1
            if number not in by_number:
                errors.append({"id": row["id"], "problem": "invalid_source_line"})
                continue
            covered.add(number)
            expected = normalized_text(expected_text(by_number[number]["text"]))
            if normalized_text(row["requerimiento"]) != expected:
                errors.append({"id": row["id"], "problem": "source_text_mismatch", "line": number})
        for line in substantive:
            if line["line"] not in covered:
                missing.append({"document": relative, **line})
        coverage.append({"document": relative, "contentLines": len(substantive), "registeredLines": len(covered)})
    return {"ok": not errors and not missing, "rows": len(rows), "sourceCoverage": coverage,
            "errors": errors, "missing": missing,
            "hashPolicy": "SHA-256 of UTF-8/LF for new rows; historical CRLF equivalent accepted; Word uses original bytes.",
            "scope": "Source registration only; no implementation, code review or production-readiness claim."}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    result = audit(args.root)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
