#!/usr/bin/env python3
"""Release artifact performance budget checker.

Checks APK/AAB size, aggregate DEX size, and aggregate native .so size against
configured byte budgets. Supports individual artifact files or directories that
contain multiple split APKs. Generates markdown + JSON reports and fails on
budget violations.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import zipfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ArtifactMetrics:
    artifact: str
    artifact_type: str
    size_bytes: int
    dex_bytes: int
    native_so_bytes: int


@dataclass(frozen=True)
class MetricResult:
    artifact: str
    artifact_type: str
    key: str
    label: str
    measured: int
    budget: int

    @property
    def ok(self) -> bool:
        return self.measured <= self.budget


def format_mib(value: int) -> str:
    return f"{value / (1024 * 1024):.2f} MiB"


def load_budgets(path: Path) -> dict[str, int]:
    data = json.loads(path.read_text(encoding="utf-8"))
    required = ("max_apk_bytes", "max_bundle_bytes", "max_total_dex_bytes", "max_native_so_bytes")
    missing = [key for key in required if key not in data]
    if missing:
        raise ValueError(f"Missing budget keys in {path}: {', '.join(missing)}")
    return {key: int(data[key]) for key in required}


def discover_artifacts(entries: list[str]) -> list[Path]:
    discovered: list[Path] = []
    seen: set[Path] = set()
    for raw in entries:
        path = Path(raw)
        candidates: list[Path]
        if path.is_dir():
            candidates = sorted(
                child for child in path.rglob("*")
                if child.is_file() and child.suffix.lower() in {".apk", ".aab"}
            )
        else:
            candidates = [path]

        for candidate in candidates:
            resolved = candidate.resolve()
            if not resolved.exists():
                raise FileNotFoundError(f"Artifact not found: {candidate}")
            if resolved.suffix.lower() not in {".apk", ".aab"}:
                continue
            if resolved in seen:
                continue
            seen.add(resolved)
            discovered.append(resolved)
    return discovered


def collect_archive_metrics(artifact_path: Path) -> ArtifactMetrics:
    size_bytes = artifact_path.stat().st_size
    dex_total = 0
    native_by_group: dict[str, int] = {}

    with zipfile.ZipFile(artifact_path, "r") as archive:
        for entry in archive.infolist():
            name = entry.filename
            if name.endswith(".dex"):
                dex_total += entry.file_size
            elif "/lib/" in f"/{name}" and name.endswith(".so"):
                group = resolve_native_group(name)
                native_by_group[group] = native_by_group.get(group, 0) + entry.file_size

    return ArtifactMetrics(
        artifact=artifact_path.as_posix(),
        artifact_type=artifact_path.suffix.lower().lstrip("."),
        size_bytes=size_bytes,
        dex_bytes=dex_total,
        native_so_bytes=max(native_by_group.values(), default=0),
    )


def resolve_native_group(entry_name: str) -> str:
    parts = entry_name.split("/")
    for index, part in enumerate(parts[:-1]):
        if part == "lib" and index + 1 < len(parts):
            return parts[index + 1]
    return "unknown"


def evaluate_metrics(
    artifacts: list[ArtifactMetrics],
    budgets: dict[str, int],
) -> list[MetricResult]:
    results: list[MetricResult] = []
    for artifact in artifacts:
        size_budget_key = "max_apk_bytes" if artifact.artifact_type == "apk" else "max_bundle_bytes"
        size_label = "APK size" if artifact.artifact_type == "apk" else "AAB size"
        results.append(
            MetricResult(
                artifact=artifact.artifact,
                artifact_type=artifact.artifact_type,
                key=size_budget_key,
                label=size_label,
                measured=artifact.size_bytes,
                budget=budgets[size_budget_key],
            )
        )
        results.append(
            MetricResult(
                artifact=artifact.artifact,
                artifact_type=artifact.artifact_type,
                key="max_total_dex_bytes",
                label="Total DEX size",
                measured=artifact.dex_bytes,
                budget=budgets["max_total_dex_bytes"],
            )
        )
        results.append(
            MetricResult(
                artifact=artifact.artifact,
                artifact_type=artifact.artifact_type,
                key="max_native_so_bytes",
                label="Largest native .so ABI slice",
                measured=artifact.native_so_bytes,
                budget=budgets["max_native_so_bytes"],
            )
        )
    return results


def write_reports(results: list[MetricResult], report_md: Path, report_json: Path) -> None:
    report_md.parent.mkdir(parents=True, exist_ok=True)
    report_json.parent.mkdir(parents=True, exist_ok=True)

    lines = [
        "# Performance Budget Report",
        "",
        f"- Generated (UTC): {dt.datetime.now(dt.UTC).isoformat(timespec='seconds').replace('+00:00', 'Z')}",
        "",
        "| Artifact | Metric | Measured | Budget | Status |",
        "|---|---|---:|---:|---|",
    ]

    for result in results:
        status = "PASS" if result.ok else "FAIL"
        measured = f"{result.measured} ({format_mib(result.measured)})"
        budget = f"{result.budget} ({format_mib(result.budget)})"
        lines.append(f"| `{Path(result.artifact).name}` | {result.label} | {measured} | {budget} | {status} |")

    report_md.write_text("\n".join(lines), encoding="utf-8")
    report_json.write_text(
        json.dumps(
            [
                {
                    "artifact": result.artifact,
                    "artifact_type": result.artifact_type,
                    "key": result.key,
                    "label": result.label,
                    "measured": result.measured,
                    "budget": result.budget,
                    "ok": result.ok,
                }
                for result in results
            ],
            indent=2,
        ),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Check performance budgets for release artifacts.")
    parser.add_argument(
        "--artifacts",
        nargs="+",
        help="Artifact files or directories to scan (.apk/.aab).",
    )
    parser.add_argument(
        "--apk",
        help="Backwards-compatible single APK path. Prefer --artifacts.",
    )
    parser.add_argument("--budgets", default="tools/perf_budgets.json")
    parser.add_argument("--report", default="build/reports/perf/performance_budget_report.md")
    parser.add_argument("--json", default="build/reports/perf/performance_budget_report.json")
    args = parser.parse_args()

    artifact_entries = list(args.artifacts or [])
    if args.apk:
        artifact_entries.append(args.apk)
    if not artifact_entries:
        artifact_entries = [
            "app/build/outputs/apk/release",
            "app/build/outputs/bundle/release/app-release.aab",
        ]

    budgets_path = Path(args.budgets)
    report_md = Path(args.report)
    report_json = Path(args.json)

    if not budgets_path.exists():
        print(f"Budget config not found: {budgets_path}")
        return 1

    artifacts = discover_artifacts(artifact_entries)
    if not artifacts:
        print("No APK/AAB artifacts found to inspect.")
        return 1

    budgets = load_budgets(budgets_path)
    results = evaluate_metrics([collect_archive_metrics(path) for path in artifacts], budgets)
    write_reports(results, report_md, report_json)

    violations = [result for result in results if not result.ok]
    if violations:
        print("Performance budget violations detected:")
        for result in violations:
            print(
                f"- {Path(result.artifact).name} / {result.label}: "
                f"measured={result.measured} budget={result.budget} key={result.key}"
            )
        print(f"Report: {report_md}")
        return 1

    print("Performance budgets passed.")
    print(f"Artifacts checked: {len(artifacts)}")
    print(f"Report: {report_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
