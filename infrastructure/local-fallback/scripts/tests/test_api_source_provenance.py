import importlib.util
import pathlib
import tempfile
import unittest

script = pathlib.Path(__file__).resolve().parents[1] / "verify-api-source-provenance.py"
spec = importlib.util.spec_from_file_location("api_provenance", script)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SourceProvenanceTest(unittest.TestCase):
    def test_line_endings_are_normalized_and_non_source_files_are_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "one.js").write_bytes(b"hello\r\n")
            (root / "two.js").write_bytes(b"hello\n")
            (root / ".env").write_text("must not read")
            hashes = module.source_hashes(root)
            self.assertEqual({"one.js", "two.js"}, set(hashes))
            self.assertEqual(hashes["one.js"], hashes["two.js"])

    def test_missing_changed_and_extra_files_fail(self):
        result = module.comparison({"one.js": "a", "two.js": "b"},
                                   {"one.js": "changed", "extra.js": "c"}, True)
        self.assertFalse(result["ok"])
        self.assertEqual(["one.js", "two.js"], result["different"])
        self.assertEqual(["extra.js"], result["extra"])

    def test_empty_source_or_unready_server_cannot_pass(self):
        self.assertFalse(module.comparison({}, {}, True)["ok"])
        self.assertFalse(module.comparison({"one.js": "a"}, {"one.js": "a"}, False)["ok"])

    def test_identical_source_and_ready_server_pass(self):
        self.assertTrue(module.comparison({"one.js": "a"}, {"one.js": "a"}, True)["ok"])


if __name__ == "__main__":
    unittest.main()
