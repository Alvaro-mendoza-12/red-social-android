#!/usr/bin/env python3
"""Runtime security audit for Android + signaling server assets.

Checks for:
- tracked secret-like files committed to git
- secret ignore coverage in .gitignore
- accidental exposure of privileged keys in Android BuildConfig/example files
- sensitive shared prefs leaking through Android backup/data extraction rules
- signaling-server auth/cors/rate-limit hardening markers
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
import xml.etree.ElementTree as ET


SEVERITY_ORDER = {
    "LOW": 1,
    "MEDIUM": 2,
    "HIGH": 3,
    "CRITICAL": 4,
}

EXPECTED_GITIGNORE_PATTERNS = {
    "secrets.properties",
    "keystore.properties",
    "*.env",
    "google-services.json",
    "app/google-services.json",
}

SENSITIVE_PREFS = {
    "auth_cache.xml",
    "account_switch_secure_prefs.xml",
    "notification_repository_cache.xml",
    "privacy_policy_cache.xml",
    "user_cache.xml",
}

SAFE_PREF_ALLOWLIST = {
    "theme_prefs.xml",
    "messages_preferences.xml",
    "app_prefs.xml",
}


@dataclass(frozen=True)
class Finding:
    id: str
    severity: str
    rule: str
    subject: str
    message: str
    file: str | None = None


def finding_id(rule: str, subject: str, file_path: str | None = None) -> str:
    payload = f"{rule}|{file_path or ''}|{subject}".encode("utf-8")
    return hashlib.sha1(payload).hexdigest()[:16]


def make_finding(
    severity: str,
    rule: str,
    subject: str,
    message: str,
    file_path: str | None = None,
) -> Finding:
    return Finding(
        id=finding_id(rule, subject, file_path),
        severity=severity,
        rule=rule,
        subject=subject,
        message=message,
        file=file_path,
    )


def git_tracked_files(root: Path) -> list[str]:
    try:
        completed = subprocess.run(
            ["git", "ls-files"],
            cwd=root,
            capture_output=True,
            text=True,
            check=True,
        )
    except Exception:
        return []
    return [line.strip() for line in completed.stdout.splitlines() if line.strip()]


def is_sensitive_tracked_path(path_text: str) -> bool:
    normalized = path_text.replace("\\", "/").lower()
    name = Path(normalized).name

    if name.endswith(".example") or name.endswith(".sample"):
        return False

    exact_names = {
        "secrets.properties",
        "keystore.properties",
        ".env",
        "google-services.json",
    }
    if name in exact_names:
        return True

    suffixes = (".jks", ".keystore", ".p12")
    if normalized.endswith(suffixes):
        return True

    if "firebase-adminsdk" in normalized and normalized.endswith(".json"):
        return True

    if "service-role" in normalized or "service_role" in normalized:
        return True

    return False


def parse_pref_paths(xml_path: Path) -> tuple[list[str], list[str]]:
    if not xml_path.exists():
        raise FileNotFoundError(str(xml_path))

    root = ET.fromstring(xml_path.read_text(encoding="utf-8"))
    includes: list[str] = []
    excludes: list[str] = []
    for element in root.iter():
        path = (element.attrib.get("path") or "").strip()
        if not path:
            continue
        if element.tag.endswith("include"):
            includes.append(path)
        elif element.tag.endswith("exclude"):
            excludes.append(path)
    return includes, excludes


def scan_runtime_security(root: Path) -> list[Finding]:
    findings: list[Finding] = []

    tracked_files = git_tracked_files(root)
    for tracked in tracked_files:
        if is_sensitive_tracked_path(tracked):
            findings.append(
                make_finding(
                    severity="CRITICAL",
                    rule="TRACKED_SECRET_FILE",
                    subject=tracked,
                    message="Sensitive secret-like file is tracked in git.",
                    file_path=tracked,
                )
            )

    gitignore_path = root / ".gitignore"
    gitignore_text = gitignore_path.read_text(encoding="utf-8", errors="ignore") if gitignore_path.exists() else ""
    for pattern in sorted(EXPECTED_GITIGNORE_PATTERNS):
        if pattern not in gitignore_text:
            findings.append(
                make_finding(
                    severity="MEDIUM",
                    rule="MISSING_GITIGNORE_SECRET_PATTERN",
                    subject=pattern,
                    message="Expected secret-ignore pattern is missing from .gitignore.",
                    file_path=".gitignore",
                )
            )

    build_gradle = root / "app/build.gradle.kts"
    build_text = build_gradle.read_text(encoding="utf-8", errors="ignore") if build_gradle.exists() else ""
    for marker in ("SUPABASE_SERVICE_ROLE_KEY", "SERVICE_ROLE_KEY", "PRIVATE_KEY"):
        if marker in build_text:
            findings.append(
                make_finding(
                    severity="HIGH",
                    rule="ANDROID_PRIVILEGED_KEY_EXPOSED",
                    subject=marker,
                    message="Privileged key marker appears in Android build config script.",
                    file_path="app/build.gradle.kts",
                )
            )

    example_secrets = root / "secrets.properties.example"
    if example_secrets.exists():
        example_text = example_secrets.read_text(encoding="utf-8", errors="ignore")
        for marker in ("SUPABASE_SERVICE_ROLE_KEY", "SERVICE_ROLE_KEY", "PRIVATE_KEY"):
            if marker in example_text:
                findings.append(
                    make_finding(
                        severity="HIGH",
                        rule="EXAMPLE_FILE_PRIVILEGED_KEY",
                        subject=marker,
                        message="Example secrets file references a privileged key that should stay server-side.",
                        file_path="secrets.properties.example",
                    )
                )

    backup_targets = [
        root / "app/src/main/res/xml/backup_rules.xml",
        root / "app/src/main/res/xml/data_extraction_rules.xml",
    ]
    for xml_file in backup_targets:
        rel = xml_file.relative_to(root).as_posix()
        try:
            includes, excludes = parse_pref_paths(xml_file)
        except FileNotFoundError:
            findings.append(
                make_finding(
                    severity="HIGH",
                    rule="MISSING_BACKUP_RULES_FILE",
                    subject=rel,
                    message="Backup/data extraction rules file is missing.",
                    file_path=rel,
                )
            )
            continue

        for path in includes:
            if path in SENSITIVE_PREFS:
                findings.append(
                    make_finding(
                        severity="HIGH",
                        rule="SENSITIVE_PREF_INCLUDED_IN_BACKUP",
                        subject=path,
                        message="Sensitive shared preferences are included in backup/device-transfer rules.",
                        file_path=rel,
                    )
                )
            elif path not in SAFE_PREF_ALLOWLIST:
                findings.append(
                    make_finding(
                        severity="MEDIUM",
                        rule="UNREVIEWED_PREF_INCLUDED_IN_BACKUP",
                        subject=path,
                        message="Backup rules include a shared prefs file outside the reviewed allowlist.",
                        file_path=rel,
                    )
                )

        for blocked in sorted(SENSITIVE_PREFS):
            if blocked not in excludes and blocked in includes:
                continue

    signaling_index = root / "signaling-server/index.js"
    signaling_text = signaling_index.read_text(encoding="utf-8", errors="ignore") if signaling_index.exists() else ""
    signaling_expectations = [
        ("SIGNALING_SOCKET_AUTH_ROOT", "io.use(authenticateSocketConnection);"),
        ("SIGNALING_SOCKET_AUTH_CALL", "callIo.use(authenticateSocketConnection);"),
        ("SIGNALING_SOCKET_AUTH_LIVE", "liveIo.use(authenticateSocketConnection);"),
        ("SIGNALING_REQUIRE_SOCKET_USER", "function requireSocketUser"),
        ("SIGNALING_REQUIRE_JOINED_SCOPE", "function requireJoinedScope"),
        ("SIGNALING_SHARED_TARGET_GUARD", "function sharesScopeWithTarget"),
        ("SIGNALING_HTTP_RATE_LIMIT", "function enforceHttpRateLimit"),
        ("SIGNALING_ALLOWED_ORIGINS", "function parseAllowedOrigins"),
    ]
    for rule, marker in signaling_expectations:
        if marker not in signaling_text:
            findings.append(
                make_finding(
                    severity="HIGH",
                    rule=rule,
                    subject=marker,
                    message="Expected signaling-server hardening marker is missing.",
                    file_path="signaling-server/index.js",
                )
            )

    if "Access-Control-Allow-Origin', '*'" in signaling_text or 'Access-Control-Allow-Origin", "*"' in signaling_text:
        findings.append(
            make_finding(
                severity="HIGH",
                rule="SIGNALING_WILDCARD_CORS_LITERAL",
                subject="Access-Control-Allow-Origin=*",
                message="Signaling server sets a literal wildcard CORS header.",
                file_path="signaling-server/index.js",
            )
        )

    env_example = root / "signaling-server/.env.example"
    env_example_text = env_example.read_text(encoding="utf-8", errors="ignore") if env_example.exists() else ""
    for required_env in (
        "SUPABASE_SERVICE_ROLE_KEY=",
        "SIGNALING_ALLOWED_ORIGINS=",
        "HTTP_RATE_LIMIT_PER_MINUTE=",
        "SOCKET_RATE_LIMIT_PER_MINUTE=",
        "TURN_SECRET=",
    ):
        if required_env not in env_example_text:
            findings.append(
                make_finding(
                    severity="MEDIUM",
                    rule="SIGNALING_ENV_EXAMPLE_MISSING_KEY",
                    subject=required_env.rstrip("="),
                    message="signaling-server/.env.example is missing a hardening-related variable.",
                    file_path="signaling-server/.env.example",
                )
            )

    return findings


def write_reports(findings: list[Finding], report_md: Path, report_json: Path) -> None:
    report_md.parent.mkdir(parents=True, exist_ok=True)
    report_json.parent.mkdir(parents=True, exist_ok=True)

    grouped: dict[str, list[Finding]] = {}
    for finding in findings:
        grouped.setdefault(finding.severity, []).append(finding)

    lines = [
        "# Runtime Security Audit Report",
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
            location = f" `{item.file}`" if item.file else ""
            lines.append(
                f"- `{item.rule}`{location} ({item.subject}): {item.message} [id={item.id}]"
            )
        lines.append("")

    report_md.write_text("\n".join(lines), encoding="utf-8")
    report_json.write_text(json.dumps([finding.__dict__ for finding in findings], indent=2), encoding="utf-8")


def severity_at_or_above(severity: str, threshold: str) -> bool:
    return SEVERITY_ORDER[severity] >= SEVERITY_ORDER[threshold]


def load_baseline(path: Path) -> set[str]:
    if not path.exists():
        return set()
    data = json.loads(path.read_text(encoding="utf-8"))
    return set(data.get("allowed_ids", []))


def write_baseline(path: Path, finding_ids: Iterable[str]) -> None:
    payload = {
        "generated_at_utc": dt.datetime.now(dt.UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "allowed_ids": sorted(set(finding_ids)),
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run runtime security audit.")
    parser.add_argument("--root", default=".")
    parser.add_argument("--baseline", default="tools/runtime_security_audit_baseline.json")
    parser.add_argument("--report", default="build/reports/security/runtime_security_audit.md")
    parser.add_argument("--json", default="build/reports/security/runtime_security_audit.json")
    parser.add_argument("--fail-threshold", default="HIGH", choices=list(SEVERITY_ORDER.keys()))
    parser.add_argument("--update-baseline", action="store_true")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    findings = scan_runtime_security(root)
    findings.sort(key=lambda item: (-SEVERITY_ORDER[item.severity], item.file or "", item.rule, item.subject))

    report_md = Path(args.report)
    report_json = Path(args.json)
    write_reports(findings, report_md, report_json)

    fail_candidates = {
        item.id for item in findings if severity_at_or_above(item.severity, args.fail_threshold)
    }

    baseline_path = Path(args.baseline)
    if args.update_baseline:
        write_baseline(baseline_path, fail_candidates)
        print(f"Baseline updated at {baseline_path} with {len(fail_candidates)} entries.")
        return 0

    baseline = load_baseline(baseline_path)
    new_violations = [item for item in findings if item.id in fail_candidates and item.id not in baseline]
    if new_violations:
        print("New runtime security violations detected (not in baseline):")
        for item in new_violations:
            print(f"- [{item.severity}] {item.rule} {item.file or '-'} ({item.subject}) id={item.id}")
        print(f"Report: {report_md}")
        return 1

    print(f"Runtime security audit passed. Findings={len(findings)}. New violations=0.")
    print(f"Report: {report_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
