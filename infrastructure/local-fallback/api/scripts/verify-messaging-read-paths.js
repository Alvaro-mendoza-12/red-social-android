const crypto = require('crypto')
const { verifyScheduledMessageRead } = require('./verify-scheduled-message-read')
const {
  assert,
  cleanBaseUrl,
  withReadPathToken,
  positiveInteger,
  readJsonCheck,
  selectImageUrls,
  truthy,
  verifyImage,
} = require('./verify-social-read-paths')

const defaultBaseUrl = 'http://127.0.0.1:3001'
const defaultTimeoutMs = 15_000
const defaultMaxJsonMs = 3_000
const defaultMaxImageMs = 4_000

function opaqueRef(value) {
  const normalized = String(value || '').trim()
  return normalized
    ? crypto.createHash('sha256').update(normalized).digest('hex').slice(0, 16)
    : 'missing'
}

function selectChatItem(inbox) {
  return Array.isArray(inbox?.items)
    ? inbox.items.find((item) => item?.kind === 'chat' && String(item?.id || '').trim()) || null
    : null
}

async function main() {
  const baseUrl = cleanBaseUrl(process.env.SMOKE_API_BASE_URL || defaultBaseUrl)
  const timeoutMs = positiveInteger(process.env.SMOKE_TIMEOUT_MS, defaultTimeoutMs)
  const maxJsonMs = positiveInteger(process.env.SMOKE_MAX_JSON_MS, defaultMaxJsonMs)
  const maxImageMs = positiveInteger(process.env.SMOKE_MAX_IMAGE_MS, defaultMaxImageMs)
  const requireContent = truthy(process.env.SMOKE_REQUIRE_CONTENT, true)
  return withReadPathToken(async (token) => {
  const checks = []

  for (const [name, pathname] of [['health', '/health'], ['ready', '/ready']]) {
    const result = await readJsonCheck({
      baseUrl,
      pathname,
      token: '',
      maxMs: maxJsonMs,
      timeoutMs,
      requireItems: false,
      name,
    })
    assert(result.body?.ok === true, `${name} did not report ok=true`)
    checks.push(result.evidence)
  }

  const currentProfile = await readJsonCheck({
    baseUrl,
    pathname: '/v1/data/profile/me',
    token,
    maxMs: maxJsonMs,
    timeoutMs,
    requireItems: false,
    name: 'current-profile',
  })
  checks.push(currentProfile.evidence)

  const savedMessages = await readJsonCheck({
    baseUrl,
    pathname: '/v1/data/messages/saved',
    token,
    maxMs: maxJsonMs,
    timeoutMs,
    requireItems: false,
    name: 'saved-messages',
  })
  assert(Array.isArray(savedMessages.body?.saved), 'saved messages did not return a saved list')
  checks.push({ ...savedMessages.evidence, items: savedMessages.body.saved.length })

  const inboxes = {}
  for (const tab of ['chats', 'groups', 'communities']) {
    const result = await readJsonCheck({
      baseUrl,
      pathname: `/v1/data/messages/inbox?tab=${tab}`,
      token,
      maxMs: maxJsonMs,
      timeoutMs,
      requireItems: false,
      name: `${tab}-inbox`,
    })
    inboxes[tab] = result.body
    checks.push({
      ...result.evidence,
      items: Array.isArray(result.body?.items) ? result.body.items.length : 0,
    })
  }

  const chat = selectChatItem(inboxes.chats)
  if (requireContent) assert(chat, 'chat inbox returned no conversation to verify')
  let chatEvidence = null
  let peerProfile = null
  if (chat) {
    const history = await readJsonCheck({
      baseUrl,
      pathname: `/v1/data/messages/chats/${encodeURIComponent(chat.id)}`,
      token,
      maxMs: maxJsonMs,
      timeoutMs,
      requireItems: false,
      name: 'chat-history',
    })
    const messageCount = Array.isArray(history.body?.messages) ? history.body.messages.length : 0
    if (requireContent) assert(messageCount > 0, 'selected conversation returned no message history')
    chatEvidence = {
      ref: opaqueRef(chat.id),
      messages: messageCount,
      ...history.evidence,
    }
    const markers = await readJsonCheck({
      baseUrl,
      pathname: `/v1/data/messages/markers/chat/${encodeURIComponent(chat.id)}`,
      token,
      maxMs: maxJsonMs,
      timeoutMs,
      requireItems: false,
      name: 'chat-markers',
    })
    assert(Array.isArray(markers.body?.pins), 'chat markers did not return pins')
    assert(Array.isArray(markers.body?.saved), 'chat markers did not return saved messages')
    checks.push(markers.evidence)
    checks.push(await verifyScheduledMessageRead({ baseUrl, token, chatId: chat.id, maxMs: maxJsonMs, timeoutMs }))

    const profileId = String(chat.profileId || '').trim()
    if (profileId) {
      peerProfile = await readJsonCheck({
        baseUrl,
        pathname: `/v1/data/profiles/${encodeURIComponent(profileId)}`,
        token,
        maxMs: maxJsonMs,
        timeoutMs,
        requireItems: false,
        name: 'conversation-profile',
      })
      checks.push(peerProfile.evidence)
    }
  }

  const imageUrls = selectImageUrls([currentProfile.body, inboxes.chats, peerProfile?.body], 2)
  if (requireContent) assert(imageUrls.length > 0, 'messaging/profile paths returned no avatar URL to verify')
  const images = []
  for (const sourceUrl of imageUrls) {
    images.push(await verifyImage({ sourceUrl, timeoutMs, maxMs: maxImageMs }))
  }

  console.log(JSON.stringify({
    ok: true,
    target: new URL(baseUrl).host,
    requireContent,
    thresholdsMs: { json: maxJsonMs, image: maxImageMs },
    checks,
    chat: chatEvidence,
    images,
  }, null, 2))
  })
}

if (require.main === module || truthy(process.env.SMOKE_FORCE_RUN)) {
  main().catch((error) => {
    console.error(JSON.stringify({ ok: false, error: error.message }, null, 2))
    process.exitCode = 1
  })
}

module.exports = { opaqueRef, selectChatItem }
