import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SOURCE = Path(__file__).resolve().parents[1] / "configure_android_media_origin.py"
SPEC = importlib.util.spec_from_file_location("configure_android_media_origin", SOURCE)
media_origin = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(media_origin)
ORIGIN = "https://novvin-media-delivery.gamergamerff004.workers.dev"


class AndroidMediaOriginConfigTest(unittest.TestCase):
    def test_canonical_root_accepts_the_worker_and_a_custom_https_hostname(self):
        self.assertEqual(ORIGIN, media_origin.normalize_origin(ORIGIN))
        self.assertEqual("https://media.example.test", media_origin.normalize_origin("https://MEDIA.example.test:443/"))

    def test_invalid_origins_cannot_modify_existing_properties(self):
        invalid = ("", "http://media.example.test", "https://media.example.test/object", "https://media.example.test?token=x",
                   "https://media.example.test/#part", "https://member:secret@media.example.test", "https://media.example.test:8443",
                   "https://localhost", "https://127.0.0.1", "https://[::1]", "https://bad..example.test", "https://ſ.example.test",
                   "https://media.example.test\nFALLBACK_API_URL=https://other.example.test", " https://media.example.test")
        with tempfile.TemporaryDirectory(prefix="android-media-origin-") as directory:
            properties = Path(directory) / "secrets.properties"
            original = b"FALLBACK_API_URL=https://api.example.test\r\nUNCHANGED=test-value\r\n"
            for value in invalid:
                with self.subTest(origin=value):
                    properties.write_bytes(original)
                    with self.assertRaises(ValueError):
                        media_origin.configure(properties, value)
                    self.assertEqual(original, properties.read_bytes())

    def test_preserves_unrelated_bytes_and_replaces_duplicate_origin_properties(self):
        with tempfile.TemporaryDirectory(prefix="android-media-origin-") as directory:
            properties = Path(directory) / "secrets.properties"
            original = (b"# preserved\r\nFALLBACK_API_URL=https://api.example.test\r\n"
                        b"MEDIA_EDGE_ORIGIN=https://old.example.test\r\nOTHER=\xffbytes\r\n"
                        b" MEDIA_EDGE_ORIGIN : https://older.example.test\r\n")
            properties.write_bytes(original)
            media_origin.configure(properties, ORIGIN)
            self.assertEqual(b"# preserved\r\nFALLBACK_API_URL=https://api.example.test\r\nOTHER=\xffbytes\r\n"
                             + b"MEDIA_EDGE_ORIGIN=" + ORIGIN.encode() + b"\r\n", properties.read_bytes())

    def test_creates_properties_and_preserves_an_existing_unterminated_line(self):
        with tempfile.TemporaryDirectory(prefix="android-media-origin-") as directory:
            properties = Path(directory) / "secrets.properties"
            media_origin.configure(properties, ORIGIN)
            self.assertEqual(b"MEDIA_EDGE_ORIGIN=" + ORIGIN.encode() + b"\n", properties.read_bytes())
            properties.write_bytes(b"FALLBACK_API_URL=https://api.example.test")
            media_origin.configure(properties, ORIGIN)
            self.assertTrue(properties.read_bytes().startswith(b"FALLBACK_API_URL=https://api.example.test\nMEDIA_EDGE_ORIGIN="))

    def test_continued_values_and_comment_lines_do_not_become_other_properties(self):
        with tempfile.TemporaryDirectory(prefix="android-media-origin-") as directory:
            properties = Path(directory) / "secrets.properties"
            original = (b"OTHER=first\\\nMEDIA_EDGE_ORIGIN=part-of-other-value\n"
                        b"# comment ending in a backslash\\\nMEDIA_EDGE_ORIGIN=old\\\ncontinued-origin\n")
            properties.write_bytes(original)
            media_origin.configure(properties, ORIGIN)
            expected = (b"OTHER=first\\\nMEDIA_EDGE_ORIGIN=part-of-other-value\n"
                        b"# comment ending in a backslash\\\nMEDIA_EDGE_ORIGIN=" + ORIGIN.encode() + b"\n")
            self.assertEqual(expected, properties.read_bytes())

    def test_an_unfinished_continuation_is_not_allowed_to_consume_the_new_origin(self):
        with tempfile.TemporaryDirectory(prefix="android-media-origin-") as directory:
            properties = Path(directory) / "secrets.properties"
            for original in (b"OTHER=value\\", b"OTHER=value\\\n"):
                properties.write_bytes(original)
                with self.assertRaises(ValueError):
                    media_origin.configure(properties, ORIGIN)
                self.assertEqual(original, properties.read_bytes())

    def test_failed_atomic_publication_keeps_the_original_file(self):
        with tempfile.TemporaryDirectory(prefix="android-media-origin-") as directory:
            properties = Path(directory) / "secrets.properties"
            original = b"FALLBACK_API_URL=https://api.example.test\n"
            properties.write_bytes(original)
            with patch.object(media_origin.os, "replace", side_effect=OSError("Temporary disk failure")):
                with self.assertRaises(OSError):
                    media_origin.configure(properties, ORIGIN)
            self.assertEqual(original, properties.read_bytes())
            self.assertEqual([properties], list(Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
