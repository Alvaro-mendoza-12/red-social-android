import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "sql" / "release-manifest.txt"
HARDENING = (
    ROOT
    / "sql"
    / "migrations"
    / "20260824_security_definer_surface_hardening.sql"
)
BASE_SCHEMA = ROOT / "sql" / "migrations" / "database_schema.sql"


class SqlSecurityOverlayTest(unittest.TestCase):
    def test_hardening_runs_after_legacy_security_definer_definitions(self):
        entries = [
            line.strip()
            for line in MANIFEST.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
        overlay = "sql/migrations/20260824_security_definer_surface_hardening.sql"
        self.assertIn(overlay, entries)
        self.assertGreater(
            entries.index(overlay),
            entries.index("sql/migrations/20260425_hashtag_rpcs_2026.sql"),
        )
        self.assertGreater(
            entries.index(overlay),
            entries.index("sql/migrations/20260802_wallet_privilege_hardening_v4.sql"),
        )

    def test_overlay_binds_identity_privacy_and_bounded_queries(self):
        sql = HARDENING.read_text(encoding="utf-8").lower()
        for required in (
            "auth.uid()",
            "auth.role()",
            "set search_path = public, pg_temp",
            "revoke all on function public.increment_followers_count",
            "revoke all on function public.get_posts_by_hashtag",
            "p.visibility = 'followers'",
            "coalesce(v.is_public, true)",
            "least(greatest(coalesce(p_limit",
        ):
            self.assertIn(required, sql)

    def test_canonical_video_schema_uses_api_column_names(self):
        sql = BASE_SCHEMA.read_text(encoding="utf-8")
        for required in (
            "user_id UUID NOT NULL",
            "video_url TEXT NOT NULL",
            "thumbnail_url TEXT",
            "is_public BOOLEAN",
        ):
            self.assertIn(required, sql)
        for obsolete in ("userId ", "videoUrl ", "thumbnailUrl ", "isPublic "):
            self.assertNotIn(obsolete, sql)


if __name__ == "__main__":
    unittest.main()
