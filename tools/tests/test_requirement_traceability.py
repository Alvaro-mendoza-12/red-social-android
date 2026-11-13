from __future__ import annotations

import csv
import importlib.util
from pathlib import Path
import tempfile
import unittest


MODULE = Path(__file__).resolve().parents[1] / "audit/check_requirement_traceability.py"
SPEC = importlib.util.spec_from_file_location("requirement_traceability", MODULE)
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)


class RequirementTraceabilityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.rows = []
        for name in checker.SOURCES:
            path = self.root / checker.PACKAGE / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"word fixture" if path.suffix == ".docx" else b"# Scope\n- A requirement\n")
            self.rows.append({"id": name, "documento": (checker.PACKAGE / name).as_posix(),
                              "linea": "" if path.suffix == ".docx" else "2",
                              "apartado": "Scope", "requerimiento": "Word mandate" if path.suffix == ".docx" else "A requirement",
                              "tipo": "requisito", "estado": "PENDIENTE", "evidencia": "",
                              "sha256_fuente": checker.source_fingerprint(path)})
        self.matrix = self.root / checker.MATRIX
        self.matrix.parent.mkdir(parents=True)

    def write_matrix(self):
        with self.matrix.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(self.rows[0]))
            writer.writeheader()
            writer.writerows(self.rows)

    def replace_source(self, text):
        row = self.rows[0]
        path = self.root / row["documento"]
        path.write_text(text, encoding="utf-8")
        row["sha256_fuente"] = checker.source_fingerprint(path)

    def test_registered_source_is_not_reported_as_implemented(self):
        self.write_matrix()
        result = checker.audit(self.root)
        self.assertTrue(result["ok"])
        self.assertTrue(all(row["estado"] == "PENDIENTE" for row in self.rows))
        self.assertIn("no implementation", result["scope"])

    def test_narrative_requirement_is_detected_even_when_every_bullet_is_registered(self):
        self.replace_source("# Scope\n- A requirement\n\n# Security\nRevoke access after an account change.\n")
        self.write_matrix()
        result = checker.audit(self.root)
        self.assertFalse(result["ok"])
        self.assertEqual([5], [item["line"] for item in result["missing"]])
        self.assertEqual("Security", result["missing"][0]["section"])

    def test_changed_source_hash_cannot_keep_old_evidence(self):
        self.write_matrix()
        (self.root / self.rows[0]["documento"]).write_text("# Scope\n- Changed requirement\n", encoding="utf-8")
        result = checker.audit(self.root)
        self.assertIn("source_hash_changed", [error["problem"] for error in result["errors"]])
        self.assertIn("source_text_mismatch", [error["problem"] for error in result["errors"]])

    def test_crlf_checkout_keeps_the_same_text_fingerprint(self):
        self.write_matrix()
        path = self.root / self.rows[0]["documento"]
        path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
        self.assertTrue(checker.audit(self.root)["ok"])

    def test_legacy_crlf_fingerprint_is_accepted_only_for_the_same_content(self):
        path = self.root / self.rows[0]["documento"]
        legacy = checker.compatible_fingerprints(path) - {checker.source_fingerprint(path)}
        self.rows[0]["sha256_fuente"] = legacy.pop()
        self.write_matrix()
        self.assertTrue(checker.audit(self.root)["ok"])
        path.write_text("# Scope\n- Different requirement\n", encoding="utf-8")
        self.assertFalse(checker.audit(self.root)["ok"])

    def test_wrong_line_and_duplicate_id_are_rejected(self):
        self.rows[0]["linea"] = "99"
        self.rows[1]["id"] = self.rows[0]["id"]
        self.write_matrix()
        result = checker.audit(self.root)
        problems = {error["problem"] for error in result["errors"]}
        self.assertTrue({"invalid_source_line", "missing_or_duplicate_id"} <= problems)

    def test_acceptance_table_header_and_separator_are_not_requirements(self):
        self.replace_source("# Scope\n| ID | Area | Scenario | Criterion |\n|---|---|---|---|\n| X | Auth | Sign out | Access revoked |\n")
        self.rows[0].update(linea="4", requerimiento="Sign out — Access revoked")
        self.write_matrix()
        self.assertTrue(checker.audit(self.root)["ok"])

    def test_unregistered_document_and_empty_status_are_rejected(self):
        self.rows.pop()
        self.rows[0]["estado"] = ""
        self.write_matrix()
        result = checker.audit(self.root)
        problems = {error["problem"] for error in result["errors"]}
        self.assertTrue({"source_unregistered", "empty_text_or_status"} <= problems)


if __name__ == "__main__":
    unittest.main()
