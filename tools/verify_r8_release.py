#!/usr/bin/env python3
"""Verify that a minified NOVVIN release retained its runtime entry points."""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ANDROID_NS = "http://schemas.android.com/apk/res/android"
APP_PACKAGE_PREFIX = "conectate.com."

CRITICAL_CLASSES = {
    "conectate.com.data.model.Video$$serializer": False,
    "conectate.com.data.model.Moment$$serializer": False,
    "conectate.com.data.model.User$$serializer": False,
    "conectate.com.data.model.Message$$serializer": False,
    "conectate.com.data.local.AppDatabase": True,
    "org.jni_zero.JniInit": True,
    "org.webrtc.PeerConnection": True,
    "androidx.media3.exoplayer.ExoPlayer": False,
    "io.ktor.client.engine.okhttp.OkHttp": False,
}

FORBIDDEN_BLANKET_PREFIXES = (
    "androidx.compose",
    "androidx.media3",
    "coil",
    "okhttp3",
    "io.ktor",
    "io.github.jan.supabase",
    "io.socket",
    "conectate.com.core",
    "conectate.com.data",
    "conectate.com.di",
    "conectate.com.presentation",
    "conectate.com.util",
)


def parse_mapping(path: Path) -> dict[str, str]:
    mappings: dict[str, str] = {}
    with path.open(encoding="utf-8") as handle:
        for raw_line in handle:
            if raw_line[:1].isspace() or " -> " not in raw_line or not raw_line.rstrip().endswith(":"):
                continue
            original, obfuscated = raw_line.rstrip()[:-1].split(" -> ", 1)
            mappings[original] = obfuscated
    return mappings


def resolve_component_name(raw_name: str, package_name: str) -> str:
    if raw_name.startswith("."):
        return f"{package_name}{raw_name}"
    if "." not in raw_name:
        return f"{package_name}.{raw_name}"
    return raw_name


def application_component_classes(path: Path) -> set[str]:
    root = ET.parse(path).getroot()
    package_name = root.attrib.get("package", "conectate.com")
    application = root.find("application")
    if application is None:
        raise ValueError("merged manifest does not contain <application>")

    names: set[str] = set()
    application_name = application.attrib.get(f"{{{ANDROID_NS}}}name")
    if application_name:
        names.add(resolve_component_name(application_name, package_name))

    for tag in ("activity", "service", "receiver", "provider"):
        for node in application.findall(tag):
            raw_name = node.attrib.get(f"{{{ANDROID_NS}}}name")
            if raw_name:
                names.add(resolve_component_name(raw_name, package_name))
    for alias in application.findall("activity-alias"):
        target = alias.attrib.get(f"{{{ANDROID_NS}}}targetActivity")
        if target:
            names.add(resolve_component_name(target, package_name))
    return {name for name in names if name.startswith(APP_PACKAGE_PREFIX)}


def forbidden_blanket_keeps(configuration: str) -> list[str]:
    active = "\n".join(
        line for line in configuration.splitlines() if not line.lstrip().startswith("#")
    )
    violations: list[str] = []
    for prefix in FORBIDDEN_BLANKET_PREFIXES:
        pattern = re.compile(
            rf"(?m)^\s*-keep(?:,[^\s]+)?\s+class\s+{re.escape(prefix)}(?:\.\*\*|\.\*)"
        )
        if pattern.search(active):
            violations.append(prefix)
    return violations


def verify_release(mapping_path: Path, manifest_path: Path, configuration_path: Path) -> dict:
    mappings = parse_mapping(mapping_path)
    components = sorted(application_component_classes(manifest_path))
    missing_components = [name for name in components if name not in mappings]
    renamed_components = [
        {"class": name, "mappedTo": mappings[name]}
        for name in components
        if name in mappings and mappings[name] != name
    ]

    missing_critical: list[str] = []
    renamed_keep_name: list[dict[str, str]] = []
    for class_name, must_keep_name in CRITICAL_CLASSES.items():
        mapped_name = mappings.get(class_name)
        if mapped_name is None:
            missing_critical.append(class_name)
        elif must_keep_name and mapped_name != class_name:
            renamed_keep_name.append({"class": class_name, "mappedTo": mapped_name})

    broad_keeps = forbidden_blanket_keeps(configuration_path.read_text(encoding="utf-8"))
    passed = not (
        missing_components
        or renamed_components
        or missing_critical
        or renamed_keep_name
        or broad_keeps
    )
    return {
        "passed": passed,
        "mappingClassCount": len(mappings),
        "applicationComponentCount": len(components),
        "applicationComponents": components,
        "missingComponents": missing_components,
        "renamedComponents": renamed_components,
        "missingCriticalClasses": missing_critical,
        "renamedKeepNameClasses": renamed_keep_name,
        "forbiddenBlanketKeeps": broad_keeps,
    }


def markdown_report(result: dict) -> str:
    status = "PASS" if result["passed"] else "FAIL"
    lines = [
        "# NOVVIN R8 Release Verification",
        "",
        f"- Status: **{status}**",
        f"- Classes in mapping: {result['mappingClassCount']}",
        f"- NOVVIN manifest components checked: {result['applicationComponentCount']}",
        f"- Missing manifest components: {len(result['missingComponents'])}",
        f"- Renamed manifest components: {len(result['renamedComponents'])}",
        f"- Missing critical runtime classes: {len(result['missingCriticalClasses'])}",
        f"- Renamed keep-name classes: {len(result['renamedKeepNameClasses'])}",
        f"- Forbidden blanket keeps: {len(result['forbiddenBlanketKeeps'])}",
    ]
    for key in (
        "missingComponents",
        "renamedComponents",
        "missingCriticalClasses",
        "renamedKeepNameClasses",
        "forbiddenBlanketKeeps",
    ):
        if result[key]:
            lines.extend(("", f"## {key}", "", f"```json\n{json.dumps(result[key], indent=2)}\n```"))
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--mapping",
        type=Path,
        default=Path("app/build/outputs/mapping/release/mapping.txt"),
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(
            "app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
        ),
    )
    parser.add_argument(
        "--configuration",
        type=Path,
        default=Path("app/build/outputs/mapping/release/configuration.txt"),
    )
    parser.add_argument("--report", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()

    for required in (args.mapping, args.manifest, args.configuration):
        if not required.is_file():
            print(f"Required R8 artifact does not exist: {required}", file=sys.stderr)
            return 2

    result = verify_release(args.mapping, args.manifest, args.configuration)
    report = markdown_report(result)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report, encoding="utf-8")
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

    print(report, end="")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
