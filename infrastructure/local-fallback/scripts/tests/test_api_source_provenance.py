import importlib.util
import pathlib
import subprocess
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
        self.assertEqual(["one.js"], result["changed"])
        self.assertEqual(["two.js"], result["missing"])
        self.assertEqual(["extra.js"], result["extra"])

    def test_empty_source_or_unready_server_cannot_pass(self):
        self.assertFalse(module.comparison({}, {}, True)["ok"])
        self.assertFalse(module.comparison({"one.js": "a"}, {"one.js": "a"}, False)["ok"])

    def test_identical_source_and_ready_server_pass(self):
        self.assertTrue(module.comparison({"one.js": "a"}, {"one.js": "a"}, True)["ok"])

    def test_commit_source_ignores_untracked_files_and_uncommitted_edits(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / module.API_SOURCE
            source.mkdir(parents=True)
            committed = source / "committed.js"
            committed.write_bytes(b"const value = 1;\n")
            (source / "ignored.json").write_text("{}")
            git = ["git", "-C", str(root)]
            subprocess.run([*git, "init", "-q"], check=True)
            subprocess.run([*git, "add", "--", module.API_SOURCE], check=True)
            subprocess.run([*git, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                            "-c", "commit.gpgsign=false", "commit", "-qm", "Fixture"], check=True)
            committed.write_text("const value = 2;")
            (source / "local.js").write_text("local();")
            revision, result = module.git_source(root, "HEAD")
            self.assertRegex(revision, r"^[a-f0-9]{40}$")
            self.assertEqual({"committed.js": module.source_digest(b"const value = 1;\n")}, result)

    def test_strict_comparison_requires_a_matching_release_marker(self):
        sources = {"one.js": "a"}
        commit = "d" * 40
        marker = {"release": "deployed", "commit": commit, "sourceDigest": "1" * 64}
        self.assertTrue(module.comparison(sources, sources, True, marker, commit)["ok"])
        stale = {**marker, "commit": "b" * 40}
        self.assertFalse(module.comparison(sources, sources, True, stale, commit)["ok"])
        self.assertFalse(module.comparison(sources, sources, True, {"commit": commit}, commit)["ok"])


if __name__ == "__main__":
    unittest.main()
