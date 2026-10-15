import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APP_SOURCES = ROOT / "app" / "src" / "main" / "java"


class AndroidMaintenanceGuardTest(unittest.TestCase):
    def test_file_provider_does_not_expose_shared_external_storage_root(self):
        paths = (ROOT / "app/src/main/res/xml/file_paths.xml").read_text(encoding="utf-8")
        self.assertNotIn("<external-path", paths)
        # The provider was narrowed to isolated staging directories. Requiring
        # external-files-path would force us to broaden access just for a test.
        entries = list(ET.fromstring(paths))
        self.assertTrue(entries)
        allowed = {
            ("cache-path", "file-provider/chat-captures/"),
            ("cache-path", "file-provider/recovery-evidence/"),
            ("cache-path", "file-provider/media-exports/"),
            ("cache-path", "file-provider/crops/"),
            ("cache-path", "file-provider/challenges/"),
            ("cache-path", "file-provider/moment-captures/"),
            ("external-cache-path", "file-provider/moment-captures/"),
        }
        self.assertEqual(allowed, {(entry.tag, entry.attrib.get("path")) for entry in entries})

    def test_recovery_deep_links_are_origin_bound_and_never_logged_whole(self):
        router = (
            ROOT / "app/src/main/java/conectate/com/presentation/navigation/IntentRouter.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("isTrustedRecoveryOrigin(uri.scheme, uri.host)", router)
        self.assertNotIn('"Handling Deep Link: $uri"', router)
        self.assertNotRegex(router, r"uri\.getQueryParameter\(\"type\"\)\s*==\s*\"recovery\"")
        self.assertNotRegex(router, r"fragmentData\.contains\(\"type=recovery\"\)")

    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = {
            path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8", errors="replace")
            for path in APP_SOURCES.rglob("*.kt")
        }
        cls.all_sources = "\n".join(cls.sources.values())

    def test_deprecated_memory_pressure_levels_do_not_return(self) -> None:
        legacy_levels = re.findall(
            r"ComponentCallbacks2\.TRIM_MEMORY_(?:RUNNING_[A-Z_]+|COMPLETE|MODERATE)",
            self.all_sources,
        )
        self.assertEqual([], legacy_levels, f"deprecated memory levels reintroduced: {legacy_levels}")

    def test_platform_movie_gif_decoder_does_not_return(self) -> None:
        self.assertNotIn("android.graphics.Movie", self.all_sources)
        user_repository = next(
            text for path, text in self.sources.items() if path.endswith("UserRepository.kt")
        )
        self.assertIn("gifSingleLoopDurationMs(bytes)", user_repository)

    def test_room_downgrade_policy_is_explicit(self) -> None:
        self.assertNotRegex(
            self.all_sources,
            r"fallbackToDestructiveMigrationOnDowngrade\s*\(\s*\)",
        )
        self.assertIn(
            "fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)",
            self.all_sources,
        )

    def test_resource_access_uses_compose_aware_local(self) -> None:
        self.assertNotRegex(
            self.all_sources,
            r"LocalContext\.current\.resources\b",
        )

    def test_lifecycle_and_progress_use_current_compose_apis(self) -> None:
        self.assertNotIn("androidx.compose.ui.platform.LocalLifecycleOwner", self.all_sources)
        self.assertNotIn("LocalClipboardManager", self.all_sources)
        self.assertNotRegex(
            self.all_sources,
            r"LinearProgressIndicator\s*\(\s*progress\s*=\s*([^\s\{])",
        )
        self.assertNotRegex(
            self.all_sources,
            r"CircularProgressIndicator\s*\(\s*progress\s*=\s*([^\s\{])",
        )

    def test_removed_material3_and_gesture_apis_do_not_return(self) -> None:
        self.assertNotRegex(
            self.all_sources,
            r"(?<![A-Za-z0-9_.])Divider\s*\(",
        )
        self.assertNotRegex(
            self.all_sources,
            r"rememberTransformableState\s*\{\s*[^,\n]+,\s*[^,\n]+,\s*[^,\n]+\s*->",
        )
        self.assertNotRegex(self.all_sources, r"\bconfirmValueChange\s*=")
        self.assertNotIn("androidx.compose.foundation.text.ClickableText", self.all_sources)
        self.assertNotRegex(
            self.all_sources,
            r"(?<![A-Za-z0-9_])(?:TabRow|ScrollableTabRow)\s*\(",
        )
        self.assertNotIn("quadraticBezierTo(", self.all_sources)

        mirrored_names = (
            "ArrowBack|Send|VolumeUp|ExitToApp|Message|Chat|OpenInNew|HelpOutline|"
            "InsertDriveFile|VolumeOff|ReceiptLong|Forward|ArrowForward|Logout|Notes|"
            "Comment|Reply|ScreenShare|VolumeDown|Undo|RotateRight|Article|CallMade|"
            "CallMissedOutgoing|KeyboardReturn|Help|Redo|StopScreenShare"
        )
        self.assertNotRegex(
            self.all_sources,
            rf"Icons\.(?:Default|Filled|Rounded)\.(?:{mirrored_names})\b",
        )

    def test_constructor_annotations_keep_explicit_use_site_targets(self) -> None:
        forbidden = (
            r"(?<!@param:)@ApplicationContext\s+(?:(?:private|internal|public|protected)\s+)?val\b",
            r"(?<!@param:)@dagger\.hilt\.android\.qualifiers\.ApplicationContext\s+"
            r"(?:(?:private|internal|public|protected)\s+)?val\b",
            r"(?<!@param:)@DrawableRes\s+(?:(?:private|internal|public|protected)\s+)?val\b",
        )
        violations = [pattern for pattern in forbidden if re.search(pattern, self.all_sources)]
        self.assertEqual([], violations, f"implicit annotation targets reintroduced: {violations}")

    def test_caught_failures_do_not_use_raw_stacktrace_printing(self) -> None:
        offenders = [
            path
            for path, text in self.sources.items()
            if ".printStackTrace()" in text
        ]
        self.assertEqual(
            [],
            offenders,
            f"caught failures must use structured logging/metrics: {offenders}",
        )

    def test_gradle_rejects_unsupported_java_runtimes_early(self) -> None:
        settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("buildJava <= JavaVersion.VERSION_21", settings)
        self.assertIn("requires JDK 17-21", settings)

    def test_minified_release_removes_direct_device_logs(self) -> None:
        rules = (ROOT / "app/proguard-rules.pro").read_text(encoding="utf-8")
        self.assertIn("-assumenosideeffects class android.util.Log", rules)
        for level in ("v", "d", "i", "w", "e", "wtf"):
            self.assertRegex(
                rules,
                rf"public static int {level}\(java\.lang\.String, java\.lang\.String",
            )


if __name__ == "__main__":
    unittest.main()
