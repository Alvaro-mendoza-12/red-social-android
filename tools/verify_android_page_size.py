#!/usr/bin/env python3
"""Verify that an Android App Bundle is ready for 16 KiB page-size devices."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


REQUIRED_ALIGNMENT_BYTES = 16 * 1024
REQUIRED_BUNDLE_ALIGNMENT = "PAGE_ALIGNMENT_16K"
TARGET_64_BIT_ABIS = {"arm64-v8a", "x86_64"}
NATIVE_ENTRY_PATTERN = re.compile(
    r"(?:^|/)lib/(?P<abi>arm64-v8a|x86_64)/(?P<name>[^/]+\.so)$"
)


def parse_bundle_page_alignment(output: str) -> str | None:
    match = re.search(r'"alignment"\s*:\s*"(PAGE_ALIGNMENT_[0-9A-Z]+)"', output)
    return match.group(1) if match else None


def parse_load_alignments(output: str) -> list[int]:
    alignments: list[int] = []
    for line in output.splitlines():
        if re.match(r"^\s*LOAD\s", line) is None:
            continue
        match = re.search(r"(0x[0-9A-Fa-f]+)\s*$", line)
        if match:
            alignments.append(int(match.group(1), 16))
    return alignments


def find_readelf(explicit: str) -> str | None:
    if explicit:
        explicit_path = Path(explicit).expanduser()
        if explicit_path.is_file():
            return str(explicit_path.resolve())
        return shutil.which(explicit)

    for command in ("llvm-readelf", "readelf"):
        resolved = shutil.which(command)
        if resolved:
            return resolved

    ndk_roots: list[Path] = []
    for variable in ("ANDROID_NDK_ROOT", "ANDROID_NDK_HOME"):
        if os.environ.get(variable):
            ndk_roots.append(Path(os.environ[variable]))

    sdk_roots: list[Path] = []
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(variable):
            sdk_roots.append(Path(os.environ[variable]))
    if os.name == "nt":
        local_app_data = os.environ.get("LOCALAPPDATA")
        if local_app_data:
            sdk_roots.append(Path(local_app_data) / "Android" / "Sdk")
    else:
        sdk_roots.append(Path.home() / "Android" / "Sdk")

    for sdk_root in sdk_roots:
        ndk_directory = sdk_root / "ndk"
        if ndk_directory.is_dir():
            ndk_roots.extend(sorted((path for path in ndk_directory.iterdir() if path.is_dir()), reverse=True))

    executable = "llvm-readelf.exe" if os.name == "nt" else "llvm-readelf"
    for ndk_root in ndk_roots:
        prebuilt = ndk_root / "toolchains" / "llvm" / "prebuilt"
        if not prebuilt.is_dir():
            continue
        for host in prebuilt.iterdir():
            candidate = host / "bin" / executable
            if candidate.is_file():
                return str(candidate.resolve())
    return None


def run_command(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, capture_output=True, text=True, check=False)


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify 16 KiB page-size compatibility of an Android AAB.")
    parser.add_argument("artifact", help="Release .aab artifact")
    parser.add_argument("--bundletool", default="tools/bundletool/bundletool-all.jar")
    parser.add_argument("--java", default="java")
    parser.add_argument("--readelf", default="")
    args = parser.parse_args()

    artifact = Path(args.artifact).resolve()
    bundletool = Path(args.bundletool).resolve()
    if not artifact.is_file() or artifact.suffix.lower() != ".aab":
        print(f"Android App Bundle not found: {artifact}")
        return 1
    if not bundletool.is_file():
        print(f"bundletool not found: {bundletool}")
        return 1
    if shutil.which(args.java) is None:
        print("Java is required to inspect the App Bundle configuration.")
        return 1

    bundle_config = run_command(
        [args.java, "-jar", str(bundletool), "dump", "config", f"--bundle={artifact}"]
    )
    config_output = "\n".join(part for part in (bundle_config.stdout, bundle_config.stderr) if part)
    actual_bundle_alignment = parse_bundle_page_alignment(config_output)
    if bundle_config.returncode != 0 or actual_bundle_alignment != REQUIRED_BUNDLE_ALIGNMENT:
        print("The App Bundle does not request 16 KiB ZIP alignment.")
        print(f"Detected alignment: {actual_bundle_alignment or 'missing'}")
        return 1

    readelf = find_readelf(args.readelf)
    if readelf is None:
        print("llvm-readelf or readelf is required to inspect native ELF segments.")
        return 1

    failures: list[str] = []
    checked = 0
    with zipfile.ZipFile(artifact) as archive, tempfile.TemporaryDirectory(prefix="novvin-page-size-") as temp:
        temp_root = Path(temp)
        for entry in archive.infolist():
            match = NATIVE_ENTRY_PATTERN.search(entry.filename)
            if not match:
                continue
            abi = match.group("abi")
            if abi not in TARGET_64_BIT_ABIS:
                continue
            output_path = temp_root / abi / match.group("name")
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_bytes(archive.read(entry))

            inspection = run_command([readelf, "-lW", str(output_path)])
            alignments = parse_load_alignments(inspection.stdout)
            checked += 1
            minimum = min(alignments) if alignments else 0
            status = "PASS" if inspection.returncode == 0 and minimum >= REQUIRED_ALIGNMENT_BYTES else "FAIL"
            print(f"{status} min_load_alignment={minimum} {entry.filename}")
            if status == "FAIL":
                failures.append(entry.filename)

    if failures:
        print("64-bit native libraries incompatible with 16 KiB pages:")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print("Android 16 KiB page-size verification passed.")
    print(f"Bundle ZIP alignment: {actual_bundle_alignment}")
    print(f"64-bit native libraries checked: {checked}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
