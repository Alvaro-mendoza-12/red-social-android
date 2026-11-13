import subprocess
import re
import shutil
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "audit-mini-pc-health.sh"
SCRIPT_ARGUMENT = "scripts/audit-mini-pc-health.sh"
# On Windows, CreateProcess can prefer the System32 WSL shim over PATH.
# Resolve the selected shell before launching it; Linux uses its usual bash.
BASH = shutil.which("bash") or "bash"


class MiniPcHealthAuditTest(unittest.TestCase):
    def test_shell_syntax(self) -> None:
        completed = subprocess.run(
            [BASH, "-n", SCRIPT_ARGUMENT],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)

    def test_threshold_self_test(self) -> None:
        completed = subprocess.run(
            [BASH, SCRIPT_ARGUMENT, "--self-test"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("SELF_TEST_OK", completed.stdout)

    def test_script_has_no_mutating_host_operations(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        forbidden_patterns = (
            r"(?m)^\s*sudo\b",
            r"(?m)^\s*rm\b",
            r"(?m)^\s*mkfs\b",
            r"(?m)^\s*fsck\b",
            r"(?m)^\s*mount\b",
            r"(?m)^\s*umount\b",
            r"(?m)^\s*docker\s+restart\b",
            r"(?m)^\s*docker\s+compose\s+(?:up|down)\b",
            r"(?m)^\s*systemctl\s+restart\b",
            r"(?m)^\s*ethtool\s+-s\b",
        )
        present = [pattern for pattern in forbidden_patterns if re.search(pattern, source)]
        self.assertEqual([], present, f"host audit must remain read-only: {present}")


if __name__ == "__main__":
    unittest.main()
