#!/usr/bin/env python3
"""Release artifact performance budget checker.

Checks APK size, aggregate DEX size, and aggregate native .so size against
configured byte budgets. Generates markdown + JSON reports and fails on
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
class Metric:
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
    required = ("max_apk_bytes", "max_total_dex_bytes", "max_native_so_bytes")
    missing = [key for key in required if key not in data]
    if missing:
        raise ValueError(f"Missing budget keys in {path}: {', '.join(missing)}")
    return {key: int(data[key]) for key in required}


def collect_apk_metrics(apk_path: Path, budgets: dict[str, int]) -> list[Metric]:
    apk_size = apk_path.stat().st_size
    dex_total = 0
    native_total = 0

    with zipfile.ZipFile(apk_path, "r") as apk_zip:
        for entry in apk_zip.infolist():
            name = entry.filename
            if name.endswith(".dex"):
                dex_total += entry.file_size
            elif name.startswith("lib/") and name.endswith(".so"):
                native_total += entry.file_size

    return [
        Metric(
            key="max_apk_bytes",
            label="APK size",
            measured=apk_size,
            budget=budgets["max_apk_bytes"],
        ),
        Metric(
            key="max_total_dex_bytes",
            label="Total DEX size",
            measured=dex_total,
            budget=budgets["max_total_dex_bytes"],
        ),
        Metric(
            key="max_native_so_bytes",
            label="Total native .so size",
            measured=native_total,
            budget=budgets["max_native_so_bytes"],
        ),
    ]


def write_reports(metrics: list[Metric], report_md: Path, report_json: Path) -> None:
    report_md.parent.mkdir(parents=True, exist_ok=True)
    report_json.parent.mkdir(parents=True, exist_ok=True)

    lines = [
        "# Performance Budget Report",
        "",
        f"- Generated (UTC): {dt.datetime.now(dt.UTC).isoformat(timespec='seconds').replace('+00:00', 'Z')}",
        "",
        "| Metric | Measured | Budget | Status |",
        "|---|---:|---:|---|",
    ]

    for metric in metrics:
        status = "PASS" if metric.ok else "FAIL"
        measured = f"{metric.measured} ({format_mib(metric.measured)})"
        budget = f"{metric.budget} ({format_mib(metric.budget)})"
        lines.append(f"| {metric.label} | {measured} | {budget} | {status} |")

    report_md.write_text("\n".join(lines), encoding="utf-8")
    report_json.write_text(
        json.dumps(
            [
                {
                    "key": metric.key,
                    "label": metric.label,
                    "measured": metric.measured,
                    "budget": metric.budget,
                    "ok": metric.ok,
                }
                for metric in metrics
            ],
            indent=2,
        ),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Check performance budgets for a release APK.")
    parser.add_argument(
        "--apk",
        default="app/build/outputs/apk/release/app-release.apk",
        help="Path to release APK",
    )
    parser.add_argument("--budgets", default="tools/perf_budgets.json")
    parser.add_argument("--report", default="build/reports/perf/performance_budget_report.md")
    parser.add_argument("--json", default="build/reports/perf/performance_budget_report.json")
    args = parser.parse_args()

    apk_path = Path(args.apk)
    budgets_path = Path(args.budgets)
    report_md = Path(args.report)
    report_json = Path(args.json)

    if not apk_path.exists():
        print(f"APK not found: {apk_path}")
        return 1
    if not budgets_path.exists():
        print(f"Budget config not found: {budgets_path}")
        return 1

    budgets = load_budgets(budgets_path)
    metrics = collect_apk_metrics(apk_path, budgets)
    write_reports(metrics, report_md, report_json)

    violations = [metric for metric in metrics if not metric.ok]
    if violations:
        print("Performance budget violations detected:")
        for metric in violations:
            print(
                f"- {metric.label}: measured={metric.measured} "
                f"budget={metric.budget} key={metric.key}"
            )
        print(f"Report: {report_md}")
        return 1

    print("Performance budgets passed.")
    print(f"Report: {report_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
