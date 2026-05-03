# Security Audit Checklist (2026)

## Scope
This checklist is the release-blocking baseline for deployable Supabase/Postgres SQL listed in `sql/release-manifest.txt`.

## Automated Gate
- Script: `tools/security_sql_audit.py`
- Release manifest: `sql/release-manifest.txt`
- Baseline file: `tools/security_audit_baseline.json`
- Report outputs:
  - `build/reports/security/sql_security_audit.md`
  - `build/reports/security/sql_security_audit.json`

Run locally:

```bash
python tools/security_sql_audit.py --root . --manifest sql/release-manifest.txt --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH
```

Gate policy:
- Fails on new `HIGH`/`CRITICAL` findings not present in baseline.
- Applies only to files inside the release manifest.
- Historical SQL outside the manifest is tracked separately as hygiene debt until remediated or archived.

## Baseline Maintenance
Only update baseline after security review and explicit sign-off.

```bash
python tools/security_sql_audit.py --root . --manifest sql/release-manifest.txt --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --update-baseline
```

Required for baseline updates:
- Link to reviewed PR.
- Security owner approval.
- Justification per new finding ID.

## Historical Inventory
Use the whole-tree scan only to prioritize cleanup of legacy or archived SQL, not to block releases directly.

```bash
python tools/security_sql_audit.py --root . --report build/reports/security/sql_security_inventory.md --json build/reports/security/sql_security_inventory.json --fail-threshold HIGH
```

## Manual Review Checklist
- Confirm `SECURITY DEFINER` functions define `SET search_path`.
- Verify every privileged RPC has explicit auth guard (`auth.uid()`/role checks).
- Verify no table keeps `DISABLE ROW LEVEL SECURITY` unintentionally.
- Verify no broad grants (`GRANT ALL`) to `public` or over-broad roles without justification.
- Verify rate-limit and abuse-sensitive functions include constraints.
- Verify secrets are not hardcoded in SQL files or migration scripts.

## Release Sign-off
- `sql_security_audit.md` attached to release artifacts.
- New `HIGH`/`CRITICAL` findings: `0`.
- Baseline changes (if any): approved and documented.
