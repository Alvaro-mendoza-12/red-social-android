"""The candidate manifest must never publish unrelated working-tree fingerprints."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "audit/update_review_coverage.py"


class ReviewCoverageIndexTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="novvin-coverage-index-")
        self.root = Path(self.directory.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Coverage QA")
        self.git("config", "user.email", "coverage@example.invalid")
        self.git("config", "core.autocrlf", "false")
        (self.root / "source.kt").write_text("committed\n", encoding="utf-8")
        self.git("add", "source.kt")
        self.git("commit", "-qm", "fixture")

    def tearDown(self):
        self.directory.cleanup()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True).strip()

    def update(self, *args, succeeds=True):
        process = subprocess.run([sys.executable, str(SCRIPT), *args], cwd=self.root, text=True, capture_output=True)
        if not succeeds:
            self.assertNotEqual(process.returncode, 0)
            return process
        self.assertEqual(process.returncode, 0, process.stderr)
        return json.loads((self.root / "docs/oficial/review-coverage.json").read_text(encoding="utf-8"))

    def test_candidate_uses_staged_bytes_and_preserves_the_foreign_working_edit(self):
        file = self.root / "source.kt"
        file.write_text("candidate\n", encoding="utf-8")
        self.git("add", "source.kt")
        candidate = self.git("rev-parse", ":source.kt")
        file.write_text("foreign uncommitted change\n", encoding="utf-8")
        manifest = self.update("--from-index")
        self.assertEqual(manifest["files"][0]["gitBlobId"], candidate)
        self.assertEqual(file.read_text(encoding="utf-8"), "foreign uncommitted change\n")
        self.assertEqual(manifest["files"][0]["reviewStatus"], "pending")

    def test_default_mode_still_fingerprints_the_actual_working_file(self):
        (self.root / "source.kt").write_text("current work\n", encoding="utf-8")
        expected = self.git("hash-object", "source.kt")
        manifest = self.update()
        self.assertEqual(manifest["files"][0]["gitBlobId"], expected)

    def test_staged_new_file_is_included_even_when_the_working_copy_is_missing(self):
        file = self.root / "new.kt"
        file.write_text("staged new source\n", encoding="utf-8")
        self.git("add", "new.kt")
        expected = self.git("rev-parse", ":new.kt")
        file.unlink()
        manifest = self.update("--from-index")
        entry = next(row for row in manifest["files"] if row["file"] == "new.kt")
        self.assertEqual(entry["gitBlobId"], expected)

    def test_unstaged_review_target_is_rejected_instead_of_publishing_its_hash(self):
        (self.root / "foreign.kt").write_text("not staged\n", encoding="utf-8")
        (self.root / "read.json").write_text(json.dumps(["foreign.kt"]), encoding="utf-8")
        result = self.update("--from-index", "--mark-read-list", "read.json", succeeds=False)
        self.assertIn("absent from the candidate index", result.stderr)
        self.assertFalse((self.root / "docs/oficial/review-coverage.json").exists())


if __name__ == "__main__":
    unittest.main()
