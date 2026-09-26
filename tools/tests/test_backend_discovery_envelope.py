import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
TOOL = ROOT / "tools" / "backend-discovery-envelope.mjs"


class BackendDiscoveryEnvelopeTests(unittest.TestCase):
    def run_tool(self, *args: str, expect_success: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            ["node", str(TOOL), *args],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        if expect_success and result.returncode != 0:
            self.fail(f"tool failed: {result.stderr}")
        if not expect_success and result.returncode == 0:
            self.fail(f"tool unexpectedly succeeded: {result.stdout}")
        return result

    def test_generates_signs_and_verifies_an_expiring_endpoint(self):
        with tempfile.TemporaryDirectory() as temp:
            private_key = pathlib.Path(temp) / "private.pem"
            public_key = pathlib.Path(temp) / "public.pem"
            generated = self.run_tool(
                "generate",
                "--private-out",
                str(private_key),
                "--public-out",
                str(public_key),
            )
            public_spki = generated.stdout.strip()
            self.assertTrue(private_key.exists())
            self.assertTrue(public_key.exists())
            self.assertGreater(len(public_spki), 80)
            derived_public_spki = self.run_tool(
                "public",
                "--private-key",
                str(private_key),
            ).stdout.strip()
            self.assertEqual(public_spki, derived_public_spki)

            signed = self.run_tool(
                "sign",
                "--private-key",
                str(private_key),
                "--url",
                "https://Api.Novvin.Example/",
                "--ttl-seconds",
                "600",
            )
            envelope = signed.stdout.strip()
            verified = self.run_tool(
                "verify",
                "--public-key-base64",
                public_spki,
                "--envelope",
                envelope,
            )
            self.assertEqual("https://api.novvin.example", verified.stdout.strip())

            funnel_envelope = self.run_tool(
                "sign", "--private-key", str(private_key),
                "--url", "https://Conectate.Tail9B267C.Ts.Net:8443/",
                "--ttl-seconds", "600",
            ).stdout.strip()
            funnel_verified = self.run_tool(
                "verify", "--public-key-base64", public_spki,
                "--envelope", funnel_envelope,
            )
            self.assertEqual("https://conectate.tail9b267c.ts.net:8443", funnel_verified.stdout.strip())

    def test_rejects_tampering_and_non_root_backend_urls(self):
        with tempfile.TemporaryDirectory() as temp:
            private_key = pathlib.Path(temp) / "private.pem"
            public_spki = self.run_tool(
                "generate",
                "--private-out",
                str(private_key),
            ).stdout.strip()
            envelope = self.run_tool(
                "sign",
                "--private-key",
                str(private_key),
                "--url",
                "https://api.novvin.example",
                "--ttl-seconds",
                "600",
            ).stdout.strip()
            parts = envelope.split(":")
            parts[-1] = ("A" if parts[-1][0] != "A" else "B") + parts[-1][1:]
            self.run_tool(
                "verify",
                "--public-key-base64",
                public_spki,
                "--envelope",
                ":".join(parts),
                expect_success=False,
            )
            self.run_tool(
                "sign",
                "--private-key",
                str(private_key),
                "--url",
                "https://api.novvin.example/unsafe",
                expect_success=False,
            )

    def test_refuses_to_overwrite_private_keys(self):
        with tempfile.TemporaryDirectory() as temp:
            private_key = pathlib.Path(temp) / "private.pem"
            self.run_tool("generate", "--private-out", str(private_key))
            self.run_tool(
                "generate",
                "--private-out",
                str(private_key),
                expect_success=False,
            )


if __name__ == "__main__":
    unittest.main()
