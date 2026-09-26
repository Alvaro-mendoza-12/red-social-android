from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

import sys

TOOLS_DIR = Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from performance_budget_check import (  # noqa: E402
    ArtifactMetrics,
    collect_archive_metrics,
    evaluate_metrics,
    parse_bundletool_size_csv,
)


class PerformanceBudgetCheckTest(unittest.TestCase):
    def test_parse_bundletool_size_csv_returns_largest_configuration(self) -> None:
        output = """SDK,ABI,SCREEN_DENSITY,LANGUAGE,MIN,MAX
24-28,arm64-v8a,XXXHDPI,,70000000,71000000
29-,x86_64,XXXHDPI,,80000000,82000000
"""

        self.assertEqual(82_000_000, parse_bundletool_size_csv(output))

    def test_collect_apk_metrics_uses_compressed_payload_and_largest_abi(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "sample.apk"
            with zipfile.ZipFile(artifact, "w", compression=zipfile.ZIP_STORED) as archive:
                archive.writestr("classes.dex", b"d" * 11)
                archive.writestr("lib/arm64-v8a/one.so", b"a" * 17)
                archive.writestr("lib/arm64-v8a/two.so", b"b" * 5)
                archive.writestr("lib/x86_64/one.so", b"c" * 19)

            metrics = collect_archive_metrics(artifact, Path("unused-bundletool.jar"))

            self.assertEqual(11, metrics.dex_bytes)
            self.assertEqual(22, metrics.native_so_bytes)
            self.assertEqual(metrics.size_bytes, metrics.device_download_bytes)

    def test_aab_evaluation_includes_device_download_budget(self) -> None:
        metrics = ArtifactMetrics(
            artifact="sample.aab",
            artifact_type="aab",
            size_bytes=100,
            dex_bytes=20,
            native_so_bytes=30,
            device_download_bytes=80,
        )
        budgets = {
            "max_apk_bytes": 100,
            "max_bundle_bytes": 100,
            "max_device_download_bytes": 79,
            "max_total_dex_bytes": 20,
            "max_native_so_bytes": 30,
        }

        results = evaluate_metrics([metrics], budgets)

        device_result = next(result for result in results if result.key == "max_device_download_bytes")
        self.assertFalse(device_result.ok)


if __name__ == "__main__":
    unittest.main()
