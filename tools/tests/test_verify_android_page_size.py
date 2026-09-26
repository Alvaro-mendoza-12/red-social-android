from __future__ import annotations

import unittest
from pathlib import Path

import sys

TOOLS_DIR = Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from verify_android_page_size import (  # noqa: E402
    parse_bundle_page_alignment,
    parse_load_alignments,
)


class VerifyAndroidPageSizeTest(unittest.TestCase):
    def test_extracts_bundle_alignment(self) -> None:
        output = '{"uncompressNativeLibraries":{"enabled":true,"alignment":"PAGE_ALIGNMENT_16K"}}'
        self.assertEqual("PAGE_ALIGNMENT_16K", parse_bundle_page_alignment(output))

    def test_returns_none_when_bundle_alignment_is_missing(self) -> None:
        self.assertIsNone(parse_bundle_page_alignment('{"enabled":true}'))

    def test_parses_llvm_readelf_load_alignments(self) -> None:
        output = """
          LOAD 0x000000 0x0 0x0 0x10 0x10 R E 0x4000
          LOAD 0x004000 0x0 0x0 0x10 0x10 RW  0x4000
        """
        self.assertEqual([16384, 16384], parse_load_alignments(output))

    def test_ignores_non_load_program_headers(self) -> None:
        output = "  PHDR 0x000040 0x0 0x0 0x10 0x10 R 0x8\n"
        self.assertEqual([], parse_load_alignments(output))


if __name__ == "__main__":
    unittest.main()
