const http = require('http')
const { randomUUID } = require('crypto')
const path = require('path')
const { ConectateChatbot } = require('./chatbot')

const port = Number(process.env.PORT || 3010)
const dataDir = process.env.AI_DATA_DIR || path.resolve(__dirname, '..', 'data-runtime')
const intentsFile = process.env.AI_INTENTS_FILE || path.resolve(__dirname, '..', 'data', 'intents.json')
const guidesFile = process.env.AI_GUIDES_FILE || path.resolve(__dirname, '..', 'data', 'guides.json')
const knowledgeFile = process.env.AI_KNOWLEDGE_FILE || path.resolve(
  __dirname,
  '..',
  'data',
  'knowledge.json',
)
const modelFile = process.env.AI_MODEL_FILE || path.join(dataDir, 'model.json')
const feedbackFile = process.env.AI_FEEDBACK_FILE || path.join(dataDir, 'feedback.jsonl')
const MAX_BODY_BYTES = 32_000

const runtimeMetrics = {
  chatRequests: 0,
  feedbackRequests: 0,
  requestErrors: 0,
  lastErrorAt: null,
}

const chatbot = new ConectateChatbot({
  intentsFile,
  guidesFile,
  knowledgeFile,
  modelFile,
  feedbackFile,
  threshold: process.env.AI_CONFIDENCE_THRESHOLD || 0.34,
  hiddenSize: process.env.AI_HIDDEN_SIZE,
  epochs: process.env.AI_TRAIN_EPOCHS,
  learningRate: process.env.AI_LEARNING_RATE,
  feedbackMaxBytes: process.env.AI_FEEDBACK_MAX_BYTES,
})

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
  })
  res.end(body)
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = ''
    let bodyBytes = 0
    let settled = false
    req.on('data', (chunk) => {
      if (settled) return
      bodyBytes += Buffer.byteLength(chunk)
      if (bodyBytes > MAX_BODY_BYTES) {
        settled = true
        reject(Object.assign(new Error('PAYLOAD_TOO_LARGE'), {
          code: 'PAYLOAD_TOO_LARGE',
          status: 413,
        }))
        return
      }
      body += chunk
    })
    req.on('end', () => {
      if (settled) return
      settled = true
      if (!body.trim()) return resolve({})
      try {
        resolve(JSON.parse(body))
      } catch (error) {
        reject(Object.assign(error, { code: 'INVALID_JSON', status: 400 }))
      }
    })
    req.on('error', (error) => {
      if (settled) return
      settled = true
      reject(error)
    })
  })
}

function healthPayload() {
  const memory = process.memoryUsage()
  return {
    ok: true,
    service: 'conectate-ai-chatbot',
    uptimeSeconds: Math.round(process.uptime()),
    memory: {
      rssMb: Number((memory.rss / 1024 / 1024).toFixed(1)),
      heapUsedMb: Number((memory.heapUsed / 1024 / 1024).toFixed(1)),
    },
    requests: { ...runtimeMetrics },
    ...chatbot.status(),
  }
}

function logRequestResult(payload, level = 'log') {
  console[level](JSON.stringify(payload))
}

async function handle(req, res, requestContext) {
  const url = new URL(req.url || '/', 'http://127.0.0.1')

  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, 200, healthPayload())
  }

  if (req.method === 'GET' && url.pathname === '/ready') {
    const payload = healthPayload()
    return sendJson(res, payload.ready ? 200 : 503, payload)
  }

  if (req.method === 'POST' && url.pathname === '/v1/chat') {
    const body = await readBody(req)
    const message = String(body.message || '').trim()
    if (!message) return sendJson(res, 400, { error: 'MESSAGE_REQUIRED' })
    if (message.length > 1000) return sendJson(res, 413, { error: 'MESSAGE_TOO_LONG' })
    const context = body.context && typeof body.context === 'object' ? body.context : {}
    const answer = chatbot.answer(message, context)
    runtimeMetrics.chatRequests += 1
    logRequestResult({
      event: 'ai-chatbot-chat-complete',
      requestId: requestContext.requestId,
      durationMs: Date.now() - requestContext.startedAt,
      messageLength: message.length,
      intent: answer.intent,
      responseMode: answer.responseMode,
      source: answer.source,
    })
    return sendJson(res, 200, answer)
  }

  if (req.method === 'POST' && url.pathname === '/v1/feedback') {
    const body = await readBody(req)
    const result = chatbot.recordFeedback(body)
    runtimeMetrics.feedbackRequests += 1
    logRequestResult({
      event: 'ai-chatbot-feedback-queued',
      requestId: requestContext.requestId,
      durationMs: Date.now() - requestContext.startedAt,
      hasRating: Boolean(String(body.rating || '').trim()),
      hasComment: Boolean(String(body.comment || '').trim()),
    })
    return sendJson(res, 202, result)
  }

  return sendJson(res, 404, { error: 'NOT_FOUND' })
}

const startupStartedAt = Date.now()
const startupStatus = chatbot.loadOrTrain()

const server = http.createServer((req, res) => {
  const requestContext = {
    requestId: randomUUID(),
    startedAt: Date.now(),
  }
  res.setHeader('x-request-id', requestContext.requestId)
  handle(req, res, requestContext).catch((error) => {
    const status = Number(error.status) || 500
    const code = error.code || (status === 400 ? 'INVALID_JSON' : 'AI_CHATBOT_ERROR')
    runtimeMetrics.requestErrors += 1
    runtimeMetrics.lastErrorAt = new Date().toISOString()
    logRequestResult({
      event: 'ai-chatbot-request-failed',
      requestId: requestContext.requestId,
      method: req.method,
      path: String(req.url || '').split('?')[0].slice(0, 120),
      durationMs: Date.now() - requestContext.startedAt,
      status,
      code,
    }, 'error')
    if (!res.headersSent && !res.destroyed) {
      sendJson(res, status, { error: code })
    }
  })
})

server.on('clientError', (error, socket) => {
  runtimeMetrics.requestErrors += 1
  runtimeMetrics.lastErrorAt = new Date().toISOString()
  logRequestResult({
    event: 'ai-chatbot-client-error',
    code: String(error.code || 'CLIENT_ERROR').slice(0, 80),
  }, 'error')
  if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n')
})

server.listen(port, '0.0.0.0', () => {
  console.log(JSON.stringify({
    event: 'ai-chatbot-ready',
    port: server.address().port,
    startupMs: Date.now() - startupStartedAt,
    ...startupStatus,
  }))
})

function shutdown(signal) {
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(1), 5_000).unref()
  console.log(JSON.stringify({ event: 'ai-chatbot-shutdown', signal }))
}

process.once('SIGTERM', () => shutdown('SIGTERM'))
process.once('SIGINT', () => shutdown('SIGINT'))
