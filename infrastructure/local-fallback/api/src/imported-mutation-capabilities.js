'use strict';

// Only administrator-installed, table-specific database capabilities may
// modify imports. Endpoint ownership/admin checks remain mandatory above this
// boundary; these functions do not authenticate end users.
async function patchImportedRow(db, table, id, patch) {
  const result = await db.query(
    'SELECT novvin_import_mutation.patch_row($1::text,$2::text,$3::jsonb) AS row',
    [table, id, JSON.stringify(patch)],
  )
  const row = result.rows[0]?.row || null
  return { rows: row ? [row] : [], rowCount: row ? 1 : 0 }
}

async function deleteImportedRow(db, table, id) {
  const result = await db.query(
    'SELECT novvin_import_mutation.delete_row($1::text,$2::text) AS affected',
    [table, id],
  )
  return { rows: [], rowCount: Number(result.rows[0]?.affected) || 0 }
}

async function deleteImportedPost(db, id) {
  const result = await db.query(
    'SELECT novvin_import_mutation.delete_post($1::text) AS affected', [id],
  )
  return { rows: [], rowCount: Number(result.rows[0]?.affected) || 0 }
}

async function deleteImportedPollVotes(db, parameters) {
  const result = await db.query(
    'SELECT novvin_import_mutation.delete_poll_votes($1::text,$2::text[],$3::boolean,$4::text) AS affected',
    parameters,
  )
  return Number(result.rows[0]?.affected) || 0
}

async function purgeImportedRows(db, values, mode) {
  const result = await db.query(
    'SELECT novvin_import_mutation.purge_rows($1::text[],$2::text) AS affected',
    [values, mode],
  )
  return Number(result.rows[0]?.affected) || 0
}

module.exports = { patchImportedRow, deleteImportedRow, deleteImportedPost,
  deleteImportedPollVotes, purgeImportedRows }
