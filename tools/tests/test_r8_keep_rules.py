import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RULES_PATH = ROOT / "app" / "proguard-rules.pro"


class R8KeepRulesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.rules = RULES_PATH.read_text(encoding="utf-8")

    def test_dependency_and_application_packages_are_not_blanket_kept(self) -> None:
        forbidden_prefixes = (
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
        active_rules = "\n".join(
            line for line in self.rules.splitlines() if not line.lstrip().startswith("#")
        )
        violations = []
        for prefix in forbidden_prefixes:
            pattern = re.compile(
                rf"(?m)^\s*-keep(?:,[^\s]+)?\s+class\s+{re.escape(prefix)}(?:\.\*\*|\.\*|\.\*\*\s+\{{)"
            )
            if pattern.search(active_rules):
                violations.append(prefix)
        self.assertEqual([], violations, f"blanket R8 keeps defeat shrinking: {violations}")

    def test_native_webrtc_entry_points_remain_protected(self) -> None:
        required_fragments = (
            "-keep class org.jni_zero.** { *; }",
            "-keepnames class org.jni_zero.**",
            "native <methods>;",
            "@org.webrtc.CalledByNative <methods>;",
            "@org.webrtc.CalledByNativeUnchecked <methods>;",
        )
        missing = [fragment for fragment in required_fragments if fragment not in self.rules]
        self.assertEqual([], missing, f"missing WebRTC JNI safeguards: {missing}")

    def test_manifest_runtime_factories_remain_protected(self) -> None:
        required_fragments = (
            "-keep class * extends androidx.work.ListenableWorker { *; }",
            "-keep class * extends com.google.firebase.messaging.FirebaseMessagingService { *; }",
            "@dagger.hilt.android.lifecycle.HiltViewModel",
            "-keep class * extends androidx.room.RoomDatabase { *; }",
        )
        missing = [fragment for fragment in required_fragments if fragment not in self.rules]
        self.assertEqual([], missing, f"missing runtime factory safeguards: {missing}")


if __name__ == "__main__":
    unittest.main()
