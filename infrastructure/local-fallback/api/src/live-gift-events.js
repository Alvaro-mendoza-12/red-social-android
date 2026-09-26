'use strict'

// Receipts are durable; Socket.IO is only the fast notification path.
function giftEvent(transaction, liveId, identity) {
  if (transaction.live_stream_id !== liveId || transaction.status !== 'committed') return null
  if (transaction.is_public !== true &&
      !identity.values.includes(transaction.sender_id) && !identity.values.includes(transaction.receiver_id)) return null
  const createdAtMs = Date.parse(transaction.created_at)
  const amount = Number(transaction.amount_diamonds)
  if (!transaction.id || !Number.isFinite(createdAtMs) || !Number.isSafeInteger(amount) || amount <= 0) return null
  return {
    transactionId: transaction.id,
    liveStreamId: liveId,
    senderId: transaction.sender_id,
    senderName: transaction.sender_name || 'Usuario',
    receiverId: transaction.receiver_id,
    createdAtMs,
    gift: {
      id: transaction.gift_id,
      name: transaction.gift_name || 'Regalo Novvin',
      imageUrl: transaction.gift_image_url || null,
      animationUrl: transaction.gift_animation_url || null,
      priceDiamonds: amount,
    },
  }
}

function registerLiveGiftRoutes(app, { pool, authRequired, getCurrentUser, userIdentity, validUuid, streamById, canViewStream, ownsStream }) {
  app.get('/v1/data/live/:liveId/gifts', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      const identity = userIdentity(user)
      if (!stream || (!stream.is_active && !ownsStream(stream, identity)) ||
          !(await canViewStream(pool, stream, identity))) {
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      const { rows } = await pool.query(
        `SELECT row FROM fallback_compat_rows
          WHERE table_name = 'gift_transactions'
            AND row->>'live_stream_id' = $1
            AND row->>'status' = 'committed'
            AND (row->>'is_public' = 'true' OR row->>'sender_id' = ANY($2::text[])
                 OR row->>'receiver_id' = ANY($2::text[]))
          ORDER BY row->>'created_at' DESC, row_id DESC LIMIT 256`,
        [liveId, identity.values],
      )
      const items = rows.map(({ row }) => giftEvent(row, liveId, identity)).filter(Boolean)
        .sort((a, b) => a.createdAtMs - b.createdAtMs || a.transactionId.localeCompare(b.transactionId))
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ items })
    } catch (error) { next(error) }
  })
}

module.exports = { registerLiveGiftRoutes, giftEvent }
