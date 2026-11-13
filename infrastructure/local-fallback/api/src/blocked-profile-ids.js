'use strict'

/** Both directions matter to the viewer. Match the returned IDs against all identity aliases. */
async function blockedProfileIds(db, identity) {
  const { rows } = await db.query(
    `SELECT lower(blocker_id::text) AS blocker_id, lower(blocked_id::text) AS blocked_id
       FROM fallback_user_blocks
      WHERE lower(blocker_id::text) = ANY($1::text[])
         OR lower(blocked_id::text) = ANY($1::text[])`,
    [Array.from(identity.ids)],
  )
  const blocked = new Set()
  for (const { blocker_id, blocked_id } of rows) {
    if (identity.ids.has(blocker_id)) blocked.add(blocked_id)
    if (identity.ids.has(blocked_id)) blocked.add(blocker_id)
  }
  return blocked
}

module.exports = { blockedProfileIds }
