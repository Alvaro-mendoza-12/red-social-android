from __future__ import annotations

import unittest
from pathlib import Path

import sys

TOOLS_DIR = Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from verify_android_release import (  # noqa: E402
    extract_apksigner_certificate_fingerprint,
    extract_certificate_fingerprint,
    is_verified_apksigner_output,
    is_verified_jarsigner_output,
    normalize_certificate_fingerprint,
)


class VerifyAndroidReleaseTest(unittest.TestCase):
    def test_normalizes_colon_delimited_fingerprint(self) -> None:
        raw = ":".join(["AB"] * 32)
        self.assertEqual("AB" * 32, normalize_certificate_fingerprint(raw))

    def test_rejects_invalid_fingerprint_length(self) -> None:
        with self.assertRaises(ValueError):
            normalize_certificate_fingerprint("AA:BB")

    def test_detects_verified_and_unsigned_outputs(self) -> None:
        self.assertTrue(is_verified_jarsigner_output("jar verified."))
        self.assertFalse(is_verified_jarsigner_output("jar is unsigned."))

    def test_rejects_verified_bundle_with_unsigned_entries(self) -> None:
        self.assertFalse(is_verified_jarsigner_output(
            "jar verified.\nWarning:\nThis jar contains unsigned entries which have not been integrity-checked."
        ))

    def test_allows_normal_self_signed_upload_certificate_warning(self) -> None:
        self.assertTrue(is_verified_jarsigner_output(
            "jar verified.\nWarning:\nThe signer's certificate is self-signed."
        ))

    def test_extracts_keytool_sha256(self) -> None:
        expected = "CD" * 32
        output = f"SHA1: 00:11\nSHA256: {':'.join(['CD'] * 32)}\n"
        self.assertEqual(expected, extract_certificate_fingerprint(output))

    def test_detects_verified_apksigner_output(self) -> None:
        output = "Verifies\nNumber of signers: 1\n"
        self.assertTrue(is_verified_apksigner_output(output))
        self.assertFalse(is_verified_apksigner_output("DOES NOT VERIFY\nNumber of signers: 0"))

    def test_extracts_apksigner_sha256(self) -> None:
        expected = "EF" * 32
        output = f"Signer #1 certificate SHA-256 digest: {expected.lower()}\n"
        self.assertEqual(expected, extract_apksigner_certificate_fingerprint(output))


if __name__ == "__main__":
    unittest.main()
