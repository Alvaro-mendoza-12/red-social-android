import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EDGE_FUNCTION = ROOT / "supabase" / "functions" / "push-notification" / "index.ts"


class PushNotificationEdgeGuardTest(unittest.TestCase):
    def test_webhook_is_fail_closed_and_has_no_private_server_address(self):
        source = EDGE_FUNCTION.read_text(encoding="utf-8")
        for required in (
            "PUSH_WEBHOOK_SECRET",
            "constantTimeEquals",
            "SUPABASE_SERVICE_ROLE_KEY",
            "persistSession: false",
            "PAYLOAD_TOO_LARGE",
        ):
            self.assertIn(required, source)
        for forbidden in (
            "192.168.",
            "Notification Payload Received",
            "record:",
            "slice(0, 12)",
        ):
            self.assertNotIn(forbidden, source)

    def test_provider_response_is_reduced_to_a_safe_code(self):
        source = EDGE_FUNCTION.read_text(encoding="utf-8")
        self.assertIn("safeFcmErrorCode(responseText)", source)
        self.assertNotIn("body: responseText", source)


if __name__ == "__main__":
    unittest.main()
