import tempfile
import unittest
from pathlib import Path

from tools.security_sql_audit import scan_effective_manifest


class EffectiveSqlSecurityAuditTest(unittest.TestCase):
    def _scan(self, migrations: list[str]):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = []
            for index, sql in enumerate(migrations):
                path = root / f"{index:02d}.sql"
                path.write_text(sql, encoding="utf-8")
                files.append(path)
            return scan_effective_manifest(root, files)

    def test_latest_definition_replaces_historical_function_by_signature(self):
        findings = self._scan(
            [
                """
                CREATE OR REPLACE FUNCTION public.lookup(p_id UUID)
                RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER
                SET search_path = public, pg_temp AS $$ BEGIN NULL; END; $$;
                """,
                """
                CREATE OR REPLACE FUNCTION public.lookup(p_id UUID)
                RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER
                SET search_path = public, pg_temp AS $$
                BEGIN
                    IF auth.uid() IS NULL THEN RAISE EXCEPTION 'auth'; END IF;
                END;
                $$;
                """,
            ]
        )
        self.assertEqual([], findings)

    def test_service_role_only_trigger_helper_does_not_require_jwt_guard(self):
        findings = self._scan(
            [
                """
                CREATE FUNCTION public.trigger_helper()
                RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
                SET search_path = public, pg_temp AS $$ BEGIN RETURN NEW; END; $$;
                REVOKE ALL ON FUNCTION public.trigger_helper()
                    FROM PUBLIC, anon, authenticated;
                GRANT EXECUTE ON FUNCTION public.trigger_helper() TO service_role;
                """
            ]
        )
        self.assertEqual([], findings)

    def test_public_overload_remains_independently_reported(self):
        findings = self._scan(
            [
                """
                CREATE OR REPLACE FUNCTION public.lookup(p_value UUID)
                RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER
                SET search_path = public, pg_temp AS $$ BEGIN NULL; END; $$;
                CREATE OR REPLACE FUNCTION public.lookup(p_value TEXT)
                RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER
                SET search_path = public, pg_temp AS $$ BEGIN NULL; END; $$;
                REVOKE ALL ON FUNCTION public.lookup(UUID) FROM PUBLIC;
                GRANT EXECUTE ON FUNCTION public.lookup(UUID) TO service_role;
                """
            ]
        )
        self.assertEqual(1, len(findings))
        self.assertEqual("SEC_DEF_NO_AUTH_GUARD", findings[0].rule)
        self.assertEqual("public.lookup(text)", findings[0].subject)

    def test_plain_create_and_missing_search_path_are_scanned(self):
        findings = self._scan(
            [
                """
                CREATE FUNCTION public.unsafe_rpc(p_actor UUID DEFAULT NULL)
                RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER
                AS $$ BEGIN NULL; END; $$;
                """
            ]
        )
        self.assertEqual(
            {"SEC_DEF_NO_SEARCH_PATH", "SEC_DEF_NO_AUTH_GUARD"},
            {finding.rule for finding in findings},
        )

    def test_dropped_legacy_signature_is_not_reported(self):
        findings = self._scan(
            [
                """
                CREATE FUNCTION public.legacy_rpc(p_actor UUID)
                RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER
                SET search_path = public, pg_temp AS $$ BEGIN NULL; END; $$;
                DROP FUNCTION IF EXISTS public.legacy_rpc(UUID);
                """
            ]
        )
        self.assertEqual([], findings)


if __name__ == "__main__":
    unittest.main()
