const { assert, readJsonCheck } = require('./verify-social-read-paths')

/** Read-only RPC used by the Android scheduled-message dialog. Never creates/cancels a job. */
async function verifyScheduledMessageRead({ baseUrl, token, chatId, maxMs, timeoutMs }) {
  const result = await readJsonCheck({
    baseUrl,
    pathname: '/rest/v1/rpc/secure_get_chat_scheduled_jobs',
    token,
    maxMs,
    timeoutMs,
    requireItems: false,
    name: 'scheduled-messages',
    requestBody: { p_scope_type: 'dm', p_target_id: chatId },
  })
  assert(Array.isArray(result.body), 'scheduled-messages returned an invalid list')
  return { ...result.evidence, items: result.body.length }
}

module.exports = { verifyScheduledMessageRead }
