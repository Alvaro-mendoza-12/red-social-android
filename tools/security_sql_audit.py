#!/usr/bin/env python3
"""Static SQL security audit with baseline support.

Scans *.sql files and reports risky patterns. Fails only on new high/critical
findings that are not present in the baseline. When a release manifest is
provided, function checks are evaluated against the effective state produced
by that ordered migration set (latest definition + final EXECUTE privileges).
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

SEVERITY_ORDER = {
    "LOW": 1,
    "MEDIUM": 2,
    "HIGH": 3,
    "CRITICAL": 4,
}

SKIP_DIR_NAMES = {
    ".git",
    ".gradle",
    ".idea",
    ".kilo",
    ".agent",
    "build",
    "node_modules",
}

DISABLE_RLS_RE = re.compile(
    r"alter\s+table\s+([a-zA-Z0-9_.\"]+)\s+disable\s+row\s+level\s+security",
    re.IGNORECASE,
)
GRANT_ALL_PUBLIC_RE = re.compile(
    r"grant\s+all(?:\s+privileges)?\s+on\s+([a-zA-Z0-9_.\"]+)\s+to\s+(public|authenticated)",
    re.IGNORECASE,
)
FUNCTION_START_RE = re.compile(
    r"create\s+(?:(or)\s+replace\s+)?function\s+([a-zA-Z0-9_.\"]+)\s*\(",
    re.IGNORECASE,
)
GRANT_EXECUTE_AUTH_RE = re.compile(
    r"grant\s+execute\s+on\s+function\s+([a-zA-Z0-9_.\"(),\s]+)\s+to\s+authenticated",
    re.IGNORECASE,
)
FUNCTION_PRIVILEGE_RE = re.compile(
    r"\b(grant\s+execute|revoke\s+all(?:\s+privileges)?)\s+on\s+function\s+"
    r"([a-zA-Z0-9_.\"]+)\s*\(",
    re.IGNORECASE,
)
DROP_FUNCTION_RE = re.compile(
    r"\bdrop\s+function\s+(?:if\s+exists\s+)?([a-zA-Z0-9_.\"]+)\s*\(",
    re.IGNORECASE,
)
BROAD_EXECUTE_ROLES = frozenset({"public", "anon", "authenticated"})


@dataclass(frozen=True)
class Finding:
    id: str
    severity: str
    rule: str
    file: str
    subject: str
    message: str


@dataclass(frozen=True)
class FunctionDefinition:
    identity: str
    name: str
    file: str
    block: str


def _matching_parenthesis(text: str, opening_index: int) -> int:
    """Return the matching ')' while respecting SQL string/identifier quotes."""
    depth = 0
    quote: str | None = None
    index = opening_index
    while index < len(text):
        char = text[index]
        if quote:
            if char == quote:
                if index + 1 < len(text) and text[index + 1] == quote:
                    index += 2
                    continue
                quote = None
            index += 1
            continue
        if char in ("'", '"'):
            quote = char
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index
        index += 1
    raise ValueError("Unbalanced function signature")


def _split_top_level_csv(value: str) -> list[str]:
    parts: list[str] = []
    start = 0
    depth = 0
    quote: str | None = None
    for index, char in enumerate(value):
        if quote:
            if char == quote:
                quote = None
            continue
        if char in ("'", '"'):
            quote = char
        elif char in "([":
            depth += 1
        elif char in ")]":
            depth = max(depth - 1, 0)
        elif char == "," and depth == 0:
            parts.append(value[start:index].strip())
            start = index + 1
    tail = value[start:].strip()
    if tail:
        parts.append(tail)
    return parts


def _strip_default(argument: str) -> str:
    match = re.search(r"\s+(?:default\b|=)", argument, re.IGNORECASE)
    return argument[: match.start()].strip() if match else argument.strip()


def _normalize_type(value: str) -> str:
    normalized = re.sub(r"\s+", " ", value.strip().lower())
    normalized = re.sub(r"\s*\[\s*\]", "[]", normalized)
    aliases = {
        "int": "integer",
        "int4": "integer",
        "int8": "bigint",
        "bool": "boolean",
        "decimal": "numeric",
        "varchar": "character varying",
        "timestamptz": "timestamp with time zone",
    }
    return aliases.get(normalized, normalized)


def _normalize_name(value: str) -> str:
    parts = [part.strip().strip('"').lower() for part in value.split(".")]
    if len(parts) == 1:
        parts.insert(0, "public")
    return ".".join(parts)


def _create_argument_types(arguments: str) -> list[str]:
    types: list[str] = []
    for raw_argument in _split_top_level_csv(arguments):
        argument = _strip_default(raw_argument)
        mode_match = re.match(r"^(inout|in|out|variadic)\s+", argument, re.IGNORECASE)
        mode = mode_match.group(1).lower() if mode_match else "in"
        if mode_match:
            argument = argument[mode_match.end() :].strip()
        if mode == "out":
            continue
        pieces = argument.split(None, 1)
        if len(pieces) < 2:
            # Unnamed input parameter, valid in PostgreSQL.
            type_name = pieces[0]
        else:
            # Project migrations consistently name input parameters. Quoted
            # and unquoted names are both handled by discarding token one.
            type_name = pieces[1]
        types.append(_normalize_type(type_name))
    return types


def _reference_argument_types(arguments: str) -> list[str]:
    return [_normalize_type(item) for item in _split_top_level_csv(arguments)]


def _function_identity(name: str, argument_types: list[str]) -> str:
    return f"{_normalize_name(name)}({','.join(argument_types)})"


def _function_block(sql_text: str, start: int, signature_end: int) -> str:
    """Extract one definition without leaking checks from later statements."""
    next_function = FUNCTION_START_RE.search(sql_text, signature_end + 1)
    fallback_end = next_function.start() if next_function else len(sql_text)
    opening = re.search(r"\bas\s+(\$[a-zA-Z0-9_]*\$)", sql_text[signature_end:fallback_end], re.IGNORECASE)
    if not opening:
        return sql_text[start:fallback_end]
    tag = opening.group(1)
    body_start = signature_end + opening.end()
    body_end = sql_text.find(tag, body_start)
    if body_end < 0:
        return sql_text[start:fallback_end]
    statement_end = sql_text.find(";", body_end + len(tag), fallback_end)
    if statement_end < 0:
        statement_end = fallback_end - 1
    return sql_text[start : statement_end + 1]


def parse_function_definitions(sql_text: str, rel: str) -> list[tuple[int, bool, FunctionDefinition]]:
    definitions: list[tuple[int, bool, FunctionDefinition]] = []
    for match in FUNCTION_START_RE.finditer(sql_text):
        opening = match.end() - 1
        closing = _matching_parenthesis(sql_text, opening)
        name = match.group(2).strip()
        argument_types = _create_argument_types(sql_text[opening + 1 : closing])
        identity = _function_identity(name, argument_types)
        definitions.append(
            (
                match.start(),
                bool(match.group(1)),
                FunctionDefinition(
                    identity=identity,
                    name=_normalize_name(name),
                    file=rel,
                    block=_function_block(sql_text, match.start(), closing),
                ),
            )
        )
    return definitions


def parse_function_references(pattern: re.Pattern[str], sql_text: str) -> list[tuple[int, re.Match[str], str, str]]:
    references: list[tuple[int, re.Match[str], str, str]] = []
    for match in pattern.finditer(sql_text):
        opening = match.end() - 1
        closing = _matching_parenthesis(sql_text, opening)
        identity = _function_identity(
            match.group(2) if pattern is FUNCTION_PRIVILEGE_RE else match.group(1),
            _reference_argument_types(sql_text[opening + 1 : closing]),
        )
        statement_end = sql_text.find(";", closing)
        if statement_end < 0:
            statement_end = len(sql_text)
        references.append((match.start(), match, identity, sql_text[closing + 1 : statement_end]))
    return references


def finding_id(rule: str, file_path: str, subject: str) -> str:
    payload = f"{rule}|{file_path}|{subject}".encode("utf-8")
    return hashlib.sha1(payload).hexdigest()[:16]


def load_manifest(root: Path, manifest_path: Path) -> list[Path]:
    if not manifest_path.exists():
        raise FileNotFoundError(f"Manifest not found: {manifest_path}")

    files: list[Path] = []
    seen: set[Path] = set()
    for raw_line in manifest_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue

        candidate = (root / line).resolve()
        try:
            candidate.relative_to(root)
        except ValueError as exc:
            raise ValueError(f"Manifest entry escapes repository root: {line}") from exc

        if candidate.suffix.lower() != ".sql":
            raise ValueError(f"Manifest entry is not a SQL file: {line}")
        if not candidate.exists():
            raise FileNotFoundError(f"Manifest entry not found: {line}")
        if candidate in seen:
            continue
        seen.add(candidate)
        files.append(candidate)

    return files


def iter_sql_files(root: Path, manifest_files: list[Path] | None = None) -> Iterable[Path]:
    if manifest_files is not None:
        yield from manifest_files
        return
    for path in root.rglob("*.sql"):
        if any(part in SKIP_DIR_NAMES for part in path.parts):
            continue
        if not path.is_file():
            continue
        yield path


def split_function_blocks(sql_text: str) -> list[tuple[str, str]]:
    return [
        (definition.name, definition.block)
        for _, _, definition in parse_function_definitions(sql_text, "")
    ]


def _definition_findings(definition: FunctionDefinition, broad_execute: bool) -> list[Finding]:
    findings: list[Finding] = []
    block_lower = definition.block.lower()
    if "security definer" not in block_lower:
        return findings
    if "set search_path" not in block_lower:
        findings.append(
            Finding(
                id=finding_id("SEC_DEF_NO_SEARCH_PATH", definition.file, definition.identity),
                severity="HIGH",
                rule="SEC_DEF_NO_SEARCH_PATH",
                file=definition.file,
                subject=definition.identity,
                message="SECURITY DEFINER function missing SET search_path hardening.",
            )
        )
    has_auth_guard = (
        "auth.uid()" in block_lower
        or "auth.role()" in block_lower
        or "current_setting('request.jwt.claims'" in block_lower
    )
    if broad_execute and not has_auth_guard:
        findings.append(
            Finding(
                id=finding_id("SEC_DEF_NO_AUTH_GUARD", definition.file, definition.identity),
                severity="MEDIUM",
                rule="SEC_DEF_NO_AUTH_GUARD",
                file=definition.file,
                subject=definition.identity,
                message=(
                    "SECURITY DEFINER function is executable by PUBLIC/anon/authenticated "
                    "and appears to lack an explicit JWT identity/role guard."
                ),
            )
        )
    return findings


def scan_effective_manifest(root: Path, manifest_files: list[Path]) -> list[Finding]:
    """Reconstruct deploy-order function definitions and EXECUTE privileges."""
    findings: list[Finding] = []
    definitions: dict[str, FunctionDefinition] = {}
    execute_roles: dict[str, set[str]] = {}

    for sql_path in manifest_files:
        rel = sql_path.relative_to(root).as_posix()
        raw = sql_path.read_text(encoding="utf-8", errors="ignore")

        # Table-level destructive patterns are actions, not final-state
        # declarations, so every occurrence remains auditable.
        for match in DISABLE_RLS_RE.finditer(raw):
            subject = match.group(1)
            findings.append(
                Finding(
                    id=finding_id("DISABLE_RLS", rel, subject),
                    severity="CRITICAL",
                    rule="DISABLE_RLS",
                    file=rel,
                    subject=subject,
                    message="Table disables RLS; verify this is strictly temporary and controlled.",
                )
            )
        for match in GRANT_ALL_PUBLIC_RE.finditer(raw):
            subject = f"{match.group(1)}->{match.group(2)}"
            findings.append(
                Finding(
                    id=finding_id("GRANT_ALL_TO_BROAD_ROLE", rel, subject),
                    severity="HIGH",
                    rule="GRANT_ALL_TO_BROAD_ROLE",
                    file=rel,
                    subject=subject,
                    message="Broad GRANT ALL to public/authenticated detected.",
                )
            )

        events: list[tuple[int, str, object]] = []
        for position, is_replace, definition in parse_function_definitions(raw, rel):
            events.append((position, "create", (is_replace, definition)))
        for position, _match, identity, _tail in parse_function_references(DROP_FUNCTION_RE, raw):
            events.append((position, "drop", identity))
        for position, match, identity, tail in parse_function_references(FUNCTION_PRIVILEGE_RE, raw):
            operation = "grant" if match.group(1).lower().startswith("grant") else "revoke"
            events.append((position, operation, (identity, tail)))

        for _, operation, payload in sorted(events, key=lambda event: event[0]):
            if operation == "create":
                is_replace, definition = payload
                assert isinstance(definition, FunctionDefinition)
                if not is_replace or definition.identity not in definitions:
                    # PostgreSQL grants EXECUTE to PUBLIC for newly created
                    # functions until an explicit REVOKE changes that state.
                    execute_roles[definition.identity] = {"public"}
                definitions[definition.identity] = definition
            elif operation == "drop":
                identity = str(payload)
                definitions.pop(identity, None)
                execute_roles.pop(identity, None)
            else:
                identity, tail = payload
                roles_match = re.search(r"\b(?:to|from)\s+(.+)$", tail, re.IGNORECASE | re.DOTALL)
                if not roles_match:
                    continue
                roles = {
                    role.strip().strip('"').lower()
                    for role in roles_match.group(1).split(",")
                    if role.strip()
                }
                current = execute_roles.setdefault(identity, {"public"})
                if operation == "grant":
                    current.update(roles)
                else:
                    current.difference_update(roles)

    for identity, definition in definitions.items():
        broad_execute = bool(execute_roles.get(identity, {"public"}) & BROAD_EXECUTE_ROLES)
        findings.extend(_definition_findings(definition, broad_execute))
    return findings


def scan_sql_file(root: Path, sql_path: Path) -> list[Finding]:
    findings: list[Finding] = []
    rel = sql_path.relative_to(root).as_posix()
    raw = sql_path.read_text(encoding="utf-8", errors="ignore")
    lowered = raw.lower()

    for m in DISABLE_RLS_RE.finditer(raw):
        subject = m.group(1)
        findings.append(
            Finding(
                id=finding_id("DISABLE_RLS", rel, subject),
                severity="CRITICAL",
                rule="DISABLE_RLS",
                file=rel,
                subject=subject,
                message="Table disables RLS; verify this is strictly temporary and controlled.",
            )
        )

    for m in GRANT_ALL_PUBLIC_RE.finditer(raw):
        subject = f"{m.group(1)}->{m.group(2)}"
        findings.append(
            Finding(
                id=finding_id("GRANT_ALL_TO_BROAD_ROLE", rel, subject),
                severity="HIGH",
                rule="GRANT_ALL_TO_BROAD_ROLE",
                file=rel,
                subject=subject,
                message="Broad GRANT ALL to public/authenticated detected.",
            )
        )

    for _, _, definition in parse_function_definitions(raw, rel):
        findings.extend(_definition_findings(definition, broad_execute=True))

    for m in GRANT_EXECUTE_AUTH_RE.finditer(raw):
        subject = m.group(1).strip()
        if "security definer" in lowered and "auth.uid()" not in lowered:
            findings.append(
                Finding(
                    id=finding_id("AUTH_GRANT_WITHOUT_UID_CHECK", rel, subject),
                    severity="MEDIUM",
                    rule="AUTH_GRANT_WITHOUT_UID_CHECK",
                    file=rel,
                    subject=subject,
                    message="EXECUTE granted to authenticated with no obvious auth.uid() guard in file.",
                )
            )

    return findings


def load_baseline(path: Path) -> set[str]:
    if not path.exists():
        return set()
    data = json.loads(path.read_text(encoding="utf-8"))
    return set(data.get("allowed_ids", []))


def write_baseline(path: Path, finding_ids: set[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "generated_at_utc": dt.datetime.now(dt.UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "allowed_ids": sorted(finding_ids),
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def write_reports(
    findings: list[Finding],
    report_md: Path,
    report_json: Path,
    scan_mode: str = "repository files",
) -> None:
    report_md.parent.mkdir(parents=True, exist_ok=True)
    report_json.parent.mkdir(parents=True, exist_ok=True)

    grouped: dict[str, list[Finding]] = {}
    for finding in findings:
        grouped.setdefault(finding.severity, []).append(finding)

    lines = [
        "# SQL Security Audit Report",
        "",
        f"- Generated (UTC): {dt.datetime.now(dt.UTC).isoformat(timespec='seconds').replace('+00:00', 'Z')}",
        f"- Scan mode: {scan_mode}",
        f"- Total findings: {len(findings)}",
        "",
    ]
    for severity in ("CRITICAL", "HIGH", "MEDIUM", "LOW"):
        items = grouped.get(severity, [])
        lines.append(f"## {severity} ({len(items)})")
        lines.append("")
        if not items:
            lines.append("- None")
            lines.append("")
            continue
        for item in items:
            lines.append(f"- `{item.rule}` in `{item.file}` ({item.subject}): {item.message} [id={item.id}]")
        lines.append("")

    report_md.write_text("\n".join(lines), encoding="utf-8")
    report_json.write_text(
        json.dumps([finding.__dict__ for finding in findings], indent=2),
        encoding="utf-8",
    )


def severity_at_or_above(severity: str, threshold: str) -> bool:
    return SEVERITY_ORDER[severity] >= SEVERITY_ORDER[threshold]


def main() -> int:
    parser = argparse.ArgumentParser(description="Run static SQL security audit.")
    parser.add_argument("--root", default=".", help="Repository root")
    parser.add_argument("--baseline", default="tools/security_audit_baseline.json")
    parser.add_argument("--report", default="build/reports/security/sql_security_audit.md")
    parser.add_argument("--json", default="build/reports/security/sql_security_audit.json")
    parser.add_argument("--fail-threshold", default="HIGH", choices=list(SEVERITY_ORDER.keys()))
    parser.add_argument("--manifest", help="Optional newline-delimited manifest of deployable SQL files")
    parser.add_argument("--update-baseline", action="store_true")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    baseline_path = Path(args.baseline)
    report_md = Path(args.report)
    report_json = Path(args.json)
    fail_threshold = args.fail_threshold
    manifest_files = None
    if args.manifest:
        manifest_path = Path(args.manifest)
        if not manifest_path.is_absolute():
            manifest_path = (root / manifest_path).resolve()
        manifest_files = load_manifest(root, manifest_path)

    if manifest_files is not None:
        findings = scan_effective_manifest(root, manifest_files)
        scan_mode = "release manifest effective state"
    else:
        findings = []
        for sql_file in iter_sql_files(root):
            findings.extend(scan_sql_file(root, sql_file))
        scan_mode = "repository files (historical definitions included)"

    findings.sort(key=lambda f: (-SEVERITY_ORDER[f.severity], f.file, f.rule, f.subject))
    write_reports(findings, report_md, report_json, scan_mode=scan_mode)

    fail_candidates = {
        f.id
        for f in findings
        if severity_at_or_above(f.severity, fail_threshold)
    }

    if args.update_baseline:
        write_baseline(baseline_path, fail_candidates)
        print(f"Baseline updated at {baseline_path} with {len(fail_candidates)} entries.")
        return 0

    baseline = load_baseline(baseline_path)
    new_violations = [f for f in findings if f.id in fail_candidates and f.id not in baseline]
    if new_violations:
        print("New SQL security violations detected (not in baseline):")
        for item in new_violations:
            print(f"- [{item.severity}] {item.rule} {item.file} ({item.subject}) id={item.id}")
        print(f"Report: {report_md}")
        return 1

    print(f"SQL security audit passed. Findings={len(findings)}. New violations=0.")
    print(f"Report: {report_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
