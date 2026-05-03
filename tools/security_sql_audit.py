#!/usr/bin/env python3
"""Static SQL security audit with baseline support.

Scans *.sql files and reports risky patterns. Fails only on new high/critical
findings that are not present in the baseline.
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
    r"create\s+or\s+replace\s+function\s+([a-zA-Z0-9_.\"]+)\s*\(",
    re.IGNORECASE,
)
GRANT_EXECUTE_AUTH_RE = re.compile(
    r"grant\s+execute\s+on\s+function\s+([a-zA-Z0-9_.\"(),\s]+)\s+to\s+authenticated",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Finding:
    id: str
    severity: str
    rule: str
    file: str
    subject: str
    message: str


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
    starts = list(FUNCTION_START_RE.finditer(sql_text))
    if not starts:
        return []
    blocks: list[tuple[str, str]] = []
    for idx, match in enumerate(starts):
        name = match.group(1).strip()
        block_start = match.start()
        block_end = starts[idx + 1].start() if idx + 1 < len(starts) else len(sql_text)
        blocks.append((name, sql_text[block_start:block_end]))
    return blocks


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

    for func_name, block in split_function_blocks(raw):
        block_lower = block.lower()
        if "security definer" in block_lower and "set search_path" not in block_lower:
            findings.append(
                Finding(
                    id=finding_id("SEC_DEF_NO_SEARCH_PATH", rel, func_name),
                    severity="HIGH",
                    rule="SEC_DEF_NO_SEARCH_PATH",
                    file=rel,
                    subject=func_name,
                    message="SECURITY DEFINER function missing SET search_path hardening.",
                )
            )

        if "security definer" in block_lower:
            has_auth_guard = (
                "auth.uid()" in block_lower
                or "auth.role()" in block_lower
                or "current_setting('request.jwt.claims'" in block_lower
            )
            if not has_auth_guard:
                findings.append(
                    Finding(
                        id=finding_id("SEC_DEF_NO_AUTH_GUARD", rel, func_name),
                        severity="MEDIUM",
                        rule="SEC_DEF_NO_AUTH_GUARD",
                        file=rel,
                        subject=func_name,
                        message="SECURITY DEFINER function appears to lack explicit auth guard.",
                    )
                )

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


def write_reports(findings: list[Finding], report_md: Path, report_json: Path) -> None:
    report_md.parent.mkdir(parents=True, exist_ok=True)
    report_json.parent.mkdir(parents=True, exist_ok=True)

    grouped: dict[str, list[Finding]] = {}
    for finding in findings:
        grouped.setdefault(finding.severity, []).append(finding)

    lines = [
        "# SQL Security Audit Report",
        "",
        f"- Generated (UTC): {dt.datetime.now(dt.UTC).isoformat(timespec='seconds').replace('+00:00', 'Z')}",
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

    findings: list[Finding] = []
    for sql_file in iter_sql_files(root, manifest_files):
        findings.extend(scan_sql_file(root, sql_file))

    findings.sort(key=lambda f: (-SEVERITY_ORDER[f.severity], f.file, f.rule, f.subject))
    write_reports(findings, report_md, report_json)

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
