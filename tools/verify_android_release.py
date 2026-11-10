#!/usr/bin/env python3
"""Fail-fast verification for Android release artifacts.

The Android Gradle plugin can successfully create an unsigned release bundle.
That is useful for some local diagnostics but is not publishable. This gate
cryptographically verifies an AAB JAR signature or an APK v2+ signing block and
can additionally pin the expected Play upload certificate SHA-256 fingerprint.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
from pathlib import Path


def normalize_certificate_fingerprint(value: str) -> str:
    normalized = re.sub(r"[^0-9A-Fa-f]", "", value).upper()
    if len(normalized) != 64:
        raise ValueError("certificate SHA-256 fingerprint must contain exactly 64 hexadecimal characters")
    return normalized


def is_verified_jarsigner_output(output: str) -> bool:
    lowered = output.lower()
    # jarsigner can exit successfully and print "jar verified." even when an
    # entry was added after signing. Such a bundle is not fully authenticated.
    return (
        "jar verified." in lowered
        and "jar is unsigned" not in lowered
        and "unsigned entries" not in lowered
    )


def extract_certificate_fingerprint(output: str) -> str:
    match = re.search(r"SHA256:\s*([0-9A-Fa-f:]{64,95})", output)
    if not match:
        raise ValueError("keytool did not report a SHA-256 certificate fingerprint")
    return normalize_certificate_fingerprint(match.group(1))


def is_verified_apksigner_output(output: str) -> bool:
    lowered = output.lower()
    return (
        "verifies" in lowered
        and not re.search(r"number of signers:\s*0\b", lowered)
        and "does not verify" not in lowered
    )


def extract_apksigner_certificate_fingerprint(output: str) -> str:
    match = re.search(
        r"Signer\s+#1\s+certificate\s+SHA-256\s+digest:\s*([0-9A-Fa-f:]{64,95})",
        output,
        flags=re.IGNORECASE,
    )
    if not match:
        raise ValueError("apksigner did not report a SHA-256 certificate fingerprint")
    return normalize_certificate_fingerprint(match.group(1))


def find_apksigner(requested: str) -> str | None:
    resolved = shutil.which(requested)
    if resolved:
        return resolved
    if requested != "apksigner":
        return None
    sdk_root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_root:
        local_app_data = os.environ.get("LOCALAPPDATA")
        if local_app_data:
            sdk_root = str(Path(local_app_data) / "Android" / "Sdk")
    build_tools = Path(sdk_root or "") / "build-tools"
    if not build_tools.is_dir():
        return None
    executable_name = "apksigner.bat" if os.name == "nt" else "apksigner"
    candidates = [directory / executable_name for directory in build_tools.iterdir() if directory.is_dir()]
    candidates = [candidate for candidate in candidates if candidate.is_file()]
    return str(sorted(candidates, key=lambda item: item.parent.name, reverse=True)[0]) if candidates else None


def run_command(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, capture_output=True, text=True, check=False)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Android release signature and upload certificate.")
    parser.add_argument("artifact", help="Signed .aab or .apk release artifact")
    parser.add_argument(
        "--expected-cert-sha256",
        default=os.environ.get("NOVVIN_UPLOAD_CERT_SHA256", ""),
        help="Expected Play upload certificate SHA-256 fingerprint",
    )
    parser.add_argument("--jarsigner", default="jarsigner")
    parser.add_argument("--keytool", default="keytool")
    parser.add_argument("--apksigner", default=os.environ.get("APKSIGNER", "apksigner"))
    args = parser.parse_args()

    artifact = Path(args.artifact).resolve()
    if not artifact.is_file():
        print(f"Release artifact not found: {artifact}")
        return 1
    if artifact.suffix.lower() not in {".aab", ".apk"}:
        print(f"Unsupported release artifact: {artifact}")
        return 1
    if artifact.suffix.lower() == ".apk":
        apksigner = find_apksigner(args.apksigner)
        if not apksigner:
            print("Android SDK apksigner is required to verify APK signing blocks.")
            return 1
        verification = run_command([apksigner, "verify", "--verbose", "--print-certs", str(artifact)])
        verification_output = "\n".join(part for part in (verification.stdout, verification.stderr) if part)
        if verification.returncode != 0 or not is_verified_apksigner_output(verification_output):
            print("APK signature verification failed: the artifact is unsigned or has an invalid signing block.")
            return 1
        try:
            actual_fingerprint = extract_apksigner_certificate_fingerprint(verification_output)
        except ValueError as error:
            print(str(error))
            return 1
    else:
        if shutil.which(args.jarsigner) is None or shutil.which(args.keytool) is None:
            print("A JDK with jarsigner and keytool is required to verify Android App Bundles.")
            return 1
        verification = run_command([args.jarsigner, "-verify", "-verbose", "-certs", str(artifact)])
        verification_output = "\n".join(part for part in (verification.stdout, verification.stderr) if part)
        if verification.returncode != 0 or not is_verified_jarsigner_output(verification_output):
            print("AAB signature verification failed: the artifact is unsigned or has an invalid signature.")
            return 1
        certificate = run_command([args.keytool, "-printcert", "-jarfile", str(artifact)])
        certificate_output = "\n".join(part for part in (certificate.stdout, certificate.stderr) if part)
        if certificate.returncode != 0:
            print("Unable to read the release signing certificate.")
            return 1
        try:
            actual_fingerprint = extract_certificate_fingerprint(certificate_output)
        except ValueError as error:
            print(str(error))
            return 1

    if args.expected_cert_sha256:
        try:
            expected_fingerprint = normalize_certificate_fingerprint(args.expected_cert_sha256)
        except ValueError as error:
            print(str(error))
            return 1
        if actual_fingerprint != expected_fingerprint:
            print("Release certificate does not match the configured Play upload certificate.")
            print(f"Expected certificate SHA-256: {expected_fingerprint}")
            print(f"Actual certificate SHA-256:   {actual_fingerprint}")
            return 1

    print("Android release signature verified.")
    print(f"Artifact: {artifact}")
    print(f"Artifact SHA-256: {sha256_file(artifact)}")
    print(f"Certificate SHA-256: {actual_fingerprint}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

