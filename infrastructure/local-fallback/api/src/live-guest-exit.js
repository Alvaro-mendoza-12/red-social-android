/** Explicit departure only: transport reconnects must retain the invitation. */
async function leaveLiveGuest(pool, liveId, identity) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`live-guests:${liveId}`])
    const { rows: invitations } = await client.query(
      `UPDATE fallback_live_guest_invitations
       SET status='canceled', responded_at=COALESCE(responded_at,NOW()), updated_at=NOW()
       WHERE live_id=$1::uuid AND lower(invitee_user_id)=ANY($2::text[]) AND status IN ('pending','accepted')
       RETURNING id::text, live_id::text, host_user_id, invitee_user_id, initiated_by, status,
                 request_nonce, expires_at, responded_at, created_at, updated_at`, [liveId, identity.values])
    const { rows: participants } = await client.query(
      `UPDATE fallback_live_participants SET role='viewer', state='disconnected', left_at=NOW(), updated_at=NOW()
       WHERE live_id=$1::uuid AND lower(user_id)=ANY($2::text[]) AND role='guest'
       RETURNING user_id, role, state`, [liveId, identity.values])
    await client.query(
      `UPDATE fallback_live_battles SET status='canceled', ended_at=NOW(), updated_at=NOW()
       WHERE live_id=$1::uuid AND opponent_live_id IS NULL AND status='active'
         AND (lower(left_user_id)=ANY($2::text[]) OR lower(right_user_id)=ANY($2::text[]))`, [liveId, identity.values])
    await client.query('COMMIT')
    return { invitations, participant: participants[0] || null }
  } catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error }
  finally { client.release() }
}
module.exports = { leaveLiveGuest }
