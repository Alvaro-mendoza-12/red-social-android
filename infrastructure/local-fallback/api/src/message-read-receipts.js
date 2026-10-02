function createMessageReadReceiptPublisher({ pool, normalizeMessageRow, recipientIdsForChat, publishChatMessageEvent }) {
  return async function publishReadReceipts({ chatId, messageIds, chat, participantRows, identity }) {
    if (!messageIds.length) return
    const { rows } = await pool.query(
      `SELECT * FROM fallback_messages
       WHERE lower(chat_id) = $1 AND id = ANY($2::uuid[])
         AND status = 'read' AND deleted_for_all = FALSE`,
      [chatId, messageIds],
    )
    const recipientIds = recipientIdsForChat(chat, participantRows, identity)
    // Send full normalized messages. A receipt-only row would overwrite local
    // media/reply fields in Android's realtime update path.
    for (const row of rows) {
      await publishChatMessageEvent({
        chatId, message: normalizeMessageRow(row), recipientIds, action: 'updated',
      })
    }
  }
}

module.exports = { createMessageReadReceiptPublisher }
