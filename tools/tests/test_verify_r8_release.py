import tempfile
import unittest
from pathlib import Path

from tools.verify_r8_release import (
    application_component_classes,
    forbidden_blanket_keeps,
    parse_mapping,
    verify_release,
)


MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="conectate.com">
  <application android:name=".SocialApp">
    <activity android:name=".MainActivity" />
    <service android:name="conectate.com.services.LiveForegroundService" />
    <receiver android:name="third.party.Receiver" />
  </application>
</manifest>
"""


class VerifyR8ReleaseTest(unittest.TestCase):
    def test_mapping_parser_ignores_member_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mapping.txt"
            path.write_text("a.Source -> b:\n    int value -> a\n", encoding="utf-8")
            self.assertEqual({"a.Source": "b"}, parse_mapping(path))

    def test_manifest_collects_only_application_components(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text(MANIFEST, encoding="utf-8")
            self.assertEqual(
                {
                    "conectate.com.SocialApp",
                    "conectate.com.MainActivity",
                    "conectate.com.services.LiveForegroundService",
                },
                application_component_classes(path),
            )

    def test_detects_blanket_dependency_keep(self) -> None:
        self.assertEqual(
            ["androidx.compose"],
            forbidden_blanket_keeps("-keep class androidx.compose.** { *; }\n"),
        )
        self.assertEqual([], forbidden_blanket_keeps("# -keep class androidx.compose.** { *; }\n"))

    def test_fixture_passes_when_components_and_critical_classes_survive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "AndroidManifest.xml"
            mapping = root / "mapping.txt"
            config = root / "configuration.txt"
            manifest.write_text(MANIFEST, encoding="utf-8")
            lines = [
                "conectate.com.SocialApp -> conectate.com.SocialApp:",
                "conectate.com.MainActivity -> conectate.com.MainActivity:",
                "conectate.com.services.LiveForegroundService -> conectate.com.services.LiveForegroundService:",
            ]
            for class_name, must_keep_name in __import__(
                "tools.verify_r8_release", fromlist=["CRITICAL_CLASSES"]
            ).CRITICAL_CLASSES.items():
                mapped = class_name if must_keep_name else "minified." + str(len(lines))
                lines.append(f"{class_name} -> {mapped}:")
            mapping.write_text("\n".join(lines) + "\n", encoding="utf-8")
            config.write_text("-keep class org.jni_zero.** { *; }\n", encoding="utf-8")

            result = verify_release(mapping, manifest, config)
            self.assertTrue(result["passed"], result)

    def test_fails_when_manifest_component_is_removed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "AndroidManifest.xml"
            mapping = root / "mapping.txt"
            config = root / "configuration.txt"
            manifest.write_text(MANIFEST, encoding="utf-8")
            mapping.write_text("conectate.com.SocialApp -> conectate.com.SocialApp:\n", encoding="utf-8")
            config.write_text("", encoding="utf-8")
            result = verify_release(mapping, manifest, config)
            self.assertFalse(result["passed"])
            self.assertIn("conectate.com.MainActivity", result["missingComponents"])


if __name__ == "__main__":
    unittest.main()
