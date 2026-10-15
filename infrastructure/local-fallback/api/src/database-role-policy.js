'use strict';

// Bootstrap owns extensions. The runtime role may own application objects while
// the current startup still performs idempotent CREATE/ALTER statements.
const UNSAFE_ROLE_QUERY = `WITH RECURSIVE accessible_roles(oid) AS (
  SELECT oid FROM pg_roles WHERE rolname=current_user
  UNION
  SELECT m.roleid FROM pg_auth_members m JOIN accessible_roles a ON a.oid=m.member
)
SELECT COALESCE(bool_or(r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR
  r.rolreplication OR r.rolbypassrls OR r.rolname IN (
    'pg_read_server_files','pg_write_server_files','pg_execute_server_program'
  )),true) AS unsafe_role_exists
FROM pg_roles r JOIN accessible_roles a ON a.oid=r.oid`;

async function assertSafeRuntimeDatabaseRole(executor, enforce = true) {
  if (!enforce) return;
  const result = await executor.query(UNSAFE_ROLE_QUERY);
  if (result.rows.length !== 1 || result.rows[0].unsafe_role_exists !== false) {
    const error = new Error('Runtime database role has administrative capabilities');
    error.code = 'RUNTIME_DATABASE_ROLE_UNSAFE';
    throw error;
  }
}

module.exports = { UNSAFE_ROLE_QUERY, assertSafeRuntimeDatabaseRole };
