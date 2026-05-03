# Runtime Security Audit - 2026-04-19

## What Was Hardened

### Signaling Server

- HTTP endpoints now validate Supabase bearer tokens where required.
- Socket namespaces now require authenticated handshakes:
  - root namespace
  - `/call`
  - `/live`
- Client-provided `userId` is no longer blindly trusted; authenticated socket identity wins.
- CORS is now origin-aware through `SIGNALING_ALLOWED_ORIGINS`.
- HTTP and socket handshake rate limits were added.
- Cross-room/cross-call/cross-live event injection was reduced by requiring:
  - joined-scope membership
  - shared target scope before forwarding WebRTC signaling payloads

### Android App

- Secure session persistence continues through encrypted shared preferences.
- Backup/data extraction rules only allow reviewed UX prefs:
  - `theme_prefs.xml`
  - `messages_preferences.xml`
  - `app_prefs.xml`
- Sensitive auth/account caches are not part of backup/device transfer allowlists.

### Repo Hygiene

- `signaling-server/.env.example` now documents:
  - `SIGNALING_ALLOWED_ORIGINS`
  - `HTTP_RATE_LIMIT_PER_MINUTE`
  - `SOCKET_RATE_LIMIT_PER_MINUTE`

## Audits Executed

Runtime audit:

```bash
python tools/runtime_security_audit.py --root . --report build/reports/security/runtime_security_audit.md --json build/reports/security/runtime_security_audit.json --fail-threshold HIGH
```

Result:

- `0` runtime findings

SQL audit:

```bash
python tools/security_sql_audit.py --root . --manifest sql/release-manifest.txt --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH
```

Result:

- runtime/server hardening passed
- deployable SQL manifest passed with `0` new blocking findings

Historical SQL inventory:

```bash
python tools/security_sql_audit.py --root . --report build/reports/security/sql_security_inventory.md --json build/reports/security/sql_security_inventory.json --fail-threshold HIGH
```

Result:

- repo-wide historical inventory still reports legacy high-severity debt outside the release manifest

## Historical SQL Risks Still Open

The full-tree SQL inventory still reports high-severity legacy issues in historical or one-off artifacts, especially:

- `DISABLE_RLS`
- `SEC_DEF_NO_SEARCH_PATH`
- `GRANT_ALL_TO_BROAD_ROLE`

Examples found in this run:

- `sql/hotfixes/fix_chat_insert_definitive.sql`
- `sql/security/fix_chat_standard_rls.sql`
- `sql/security/fix_chats_rls_secure.sql`
- multiple historical files under `sql/archive`, `sql/hotfixes`, `sql/migrations` and `sql/security`

This means the application runtime is now better protected and the release path is gated correctly, but the database layer still contains historical debt that should keep being reduced before calling the platform fully hardened.

## Priority Remediation Order

1. Remove or archive any SQL artifact that disables RLS outside controlled emergency procedures.
2. Add `SET search_path` to every `SECURITY DEFINER` function that remains valid.
3. Replace broad `GRANT ALL` statements with least-privilege grants.
4. Keep `sql/release-manifest.txt` tight so historical one-off hotfixes do not re-enter the release gate.

## Outcome

This pass materially improved runtime security for calls, lives and room signaling, and it leaves a reproducible audit trail. The main remaining red zone is now historical Supabase SQL/RLS hygiene, not the Android client, release SQL gate, or signaling authentication path.
