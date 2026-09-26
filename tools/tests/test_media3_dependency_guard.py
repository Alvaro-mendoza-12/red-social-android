import re
import tomllib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG_PATH = ROOT / "gradle" / "libs.versions.toml"
APP_BUILD_PATH = ROOT / "app" / "build.gradle.kts"
APP_SOURCES = ROOT / "app" / "src" / "main" / "java"


def version_tuple(value: str) -> tuple[int, ...]:
    return tuple(int(part) for part in value.split("."))


class Media3DependencyGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = tomllib.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        cls.kotlin_sources = "\n".join(
            path.read_text(encoding="utf-8", errors="replace")
            for path in APP_SOURCES.rglob("*.kt")
        )

    def test_media3_modules_share_the_supported_version(self) -> None:
        versions = self.catalog["versions"]
        self.assertGreaterEqual(version_tuple(versions["media3"]), (1, 11, 0))

        media3_libraries = {
            name: dependency
            for name, dependency in self.catalog["libraries"].items()
            if dependency.get("group") == "androidx.media3"
        }
        self.assertTrue(media3_libraries, "the catalog must declare Media3 modules")
        mismatches = [
            name
            for name, dependency in media3_libraries.items()
            if dependency.get("version", {}).get("ref") != "media3"
        ]
        self.assertEqual([], mismatches, f"Media3 modules are not version-aligned: {mismatches}")

    def test_kotlin_ksp_room_and_hilt_remain_compatible(self) -> None:
        versions = self.catalog["versions"]
        self.assertEqual("2.2.10", versions["kotlin"])
        self.assertTrue(versions["ksp"].startswith(f'{versions["kotlin"]}-'))
        self.assertGreaterEqual(version_tuple(versions["room"]), (2, 7, 0))
        self.assertGreaterEqual(version_tuple(versions["hilt"]), (2, 57, 1))

    def test_compose_bom_stays_on_the_api_36_compatible_line(self) -> None:
        # Compose BOM 2026.08 / Compose 1.12 requires compileSdk 37 and AGP 9.1.2.
        self.assertEqual("2026.06.00", self.catalog["versions"]["composeBom"])

    def test_audit_release_disables_crashlytics_mapping_upload(self) -> None:
        build_script = APP_BUILD_PATH.read_text(encoding="utf-8")
        self.assertIn('environmentNonBlank("NOVVIN_AUDIT_BUILD")', build_script)
        self.assertRegex(
            build_script,
            r'tasks\.matching \{ it\.name\.startsWith\("uploadCrashlyticsMappingFile"\) \}'
            r"\.configureEach \{\s*onlyIf\([^)]*\) \{\s*!isAuditBuild",
        )

    def test_removed_media3_transformer_apis_do_not_return(self) -> None:
        forbidden = (
            r"\bSpeedChangeEffect\b",
            r"createExperimentalSpeedChangingEffect\s*\(",
            r"experimentalSetForceAudioTrack\s*\(",
            r"EditedMediaItemSequence\s*\(",
        )
        violations = [pattern for pattern in forbidden if re.search(pattern, self.kotlin_sources)]
        self.assertEqual([], violations, f"deprecated Media3 APIs reintroduced: {violations}")

    def test_exporters_declare_track_types_and_use_synchronized_speed(self) -> None:
        editor = (
            APP_SOURCES
            / "conectate"
            / "com"
            / "presentation"
            / "videos"
            / "VideoEditorViewModel.kt"
        ).read_text(encoding="utf-8")
        moments = (
            APP_SOURCES
            / "conectate"
            / "com"
            / "data"
            / "music"
            / "MomentMusicCompositionExporter.kt"
        ).read_text(encoding="utf-8")

        self.assertIn("EditedMediaItemSequence.withAudioAndVideoFrom(items)", editor)
        self.assertIn("speedProvider?.let(editedItemBuilder::setSpeed)", editor)
        self.assertIn("EditedMediaItemSequence.withAudioFrom(listOf(editedMusic))", editor)
        self.assertIn("EditedMediaItemSequence.withAudioAndVideoFrom(listOf(videoItem))", moments)
        self.assertIn("EditedMediaItemSequence.withAudioFrom(listOf(musicItem))", moments)


if __name__ == "__main__":
    unittest.main()
