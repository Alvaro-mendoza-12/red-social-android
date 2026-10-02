// Authentication responses carry the same permanent handle as the profile.
// Login addresses must never become a public handle.
function publicUsername(value) {
  const username = typeof value === 'string' ? value.trim() : ''
  return username && !username.includes('@') && username.length <= 24 ? username : null
}

async function hydrateAuthPublicUsername(pool, row) {
  if (!row) return null
  const { rows } = await pool.query(
    `SELECT profile->>'username' AS username FROM fallback_profile_overrides WHERE user_id = $1::uuid`,
    [row.id],
  )
  return { ...row, username: publicUsername(rows[0]?.username) }
}

module.exports = { publicUsername, hydrateAuthPublicUsername }
