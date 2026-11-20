from pathlib import Path
import unittest

import yaml


class ServiceEnvironmentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[2]
        path = root / "infrastructure/local-fallback/docker-compose.yml"
        cls.services = yaml.safe_load(path.read_text(encoding="utf-8"))["services"]

    def test_bootstrap_password_is_scoped_to_postgres(self):
        self.assertIn("POSTGRES_PASSWORD", self.services["db"]["environment"])
        api = self.services["api"]["environment"]
        self.assertEqual(api["POSTGRES_PASSWORD"], "")
        self.assertIn("FALLBACK_API_DATABASE_URL", api["DATABASE_URL"])

    def test_socket_services_have_their_own_optional_environment_file(self):
        for name, filename in (("signaling", "signaling.env"), ("realtime-gateway", "realtime.env")):
            with self.subTest(service=name):
                service = self.services[name]
                self.assertEqual(service["env_file"], [{"path": "./secrets/" + filename, "required": False}])
                for key in ("POSTGRES_PASSWORD", "FALLBACK_API_DATABASE_URL", "DATABASE_URL", "ADMIN_SYNC_TOKEN"):
                    self.assertNotIn(key, service["environment"])

    def test_socket_services_keep_their_authentication_and_tuning_settings(self):
        signaling = self.services["signaling"]["environment"]
        realtime = self.services["realtime-gateway"]["environment"]
        for key in ("TURN_SECRET", "TURN_STATIC_URLS", "SOCKET_EVENT_RATE_LIMIT_PER_MINUTE", "VOICE_PRESENCE_HEARTBEAT_MS"):
            self.assertIn(key, signaling)
        for key in ("JWT_SECRET", "REALTIME_INTERNAL_SECRET", "REALTIME_REDIS_STREAM_MAXLEN", "REALTIME_MAX_CONNECTIONS"):
            self.assertIn(key, realtime)
