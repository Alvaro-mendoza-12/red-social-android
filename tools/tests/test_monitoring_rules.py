import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
ALERTS = ROOT / "infrastructure" / "monitoring" / "prometheus" / "rules" / "conectate-alerts.yml"


class MonitoringRulesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.document = yaml.safe_load(ALERTS.read_text(encoding="utf-8"))
        cls.rules = [
            rule
            for group in cls.document.get("groups", [])
            for rule in group.get("rules", [])
        ]

    def test_alert_names_are_unique(self) -> None:
        names = [rule.get("alert") for rule in self.rules]
        self.assertEqual(len(names), len(set(names)))

    def test_host_streaming_alerts_are_present(self) -> None:
        by_name = {rule.get("alert"): rule for rule in self.rules}
        expected = {
            "NetworkPacketDropsHigh",
            "NetworkPacketDropsCritical",
            "NetworkInterfaceErrors",
            "ServerEthernetBelowGigabit",
            "DiskIOSaturationHigh",
            "DiskIOSaturationCritical",
        }
        self.assertEqual(set(), expected - set(by_name))
        self.assertEqual("warning", by_name["NetworkPacketDropsHigh"]["labels"]["severity"])
        self.assertEqual("critical", by_name["NetworkPacketDropsCritical"]["labels"]["severity"])
        self.assertIn("clamp_min", by_name["NetworkPacketDropsHigh"]["expr"])
        self.assertIn("node_network_speed_bytes", by_name["ServerEthernetBelowGigabit"]["expr"])

    def test_every_alert_has_operational_metadata(self) -> None:
        incomplete = []
        for rule in self.rules:
            annotations = rule.get("annotations", {})
            labels = rule.get("labels", {})
            if not (
                rule.get("alert")
                and rule.get("expr") is not None
                and labels.get("severity")
                and annotations.get("summary")
                and annotations.get("description")
            ):
                incomplete.append(rule.get("alert", "<unnamed>"))
        self.assertEqual([], incomplete)


if __name__ == "__main__":
    unittest.main()
