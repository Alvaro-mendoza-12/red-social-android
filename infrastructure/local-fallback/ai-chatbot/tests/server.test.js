const assert = require('assert')
const fs = require('fs')
const net = require('net')
const os = require('os')
const path = require('path')
const { spawn } = require('child_process')

function reservePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address()
      server.close((error) => error ? reject(error) : resolve(port))
    })
  })
}

async function waitForReady(url, child, logs) {
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    if (child.exitCode != null) {
      throw new Error(`AI test server exited with ${child.exitCode}: ${logs.join('')}`)
    }
    try {
      const response = await fetch(`${url}/ready`)
      if (response.ok) return response.json()
    } catch {
      // The first local training happens before the HTTP socket starts.
    }
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  throw new Error(`AI test server was not ready in time: ${logs.join('')}`)
}

async function run() {
  const port = await reservePort()
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'conectate-ai-server-'))
  const projectDir = path.resolve(__dirname, '..')
  const logs = []
  const child = spawn(process.execPath, ['src/server.js'], {
    cwd: projectDir,
    env: {
      ...process.env,
      PORT: String(port),
      AI_DATA_DIR: dataDir,
      AI_HIDDEN_SIZE: '32',
      AI_TRAIN_EPOCHS: '20',
      AI_KNOWLEDGE_HIDDEN_SIZE: '32',
      AI_KNOWLEDGE_EPOCHS: '30',
      AI_FEEDBACK_MAX_BYTES: '65536',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  child.stdout.on('data', (chunk) => logs.push(chunk.toString()))
  child.stderr.on('data', (chunk) => logs.push(chunk.toString()))

  const baseUrl = `http://127.0.0.1:${port}`
  try {
    const ready = await waitForReady(baseUrl, child, logs)
    assert.strictEqual(ready.ready, true)
    assert.strictEqual(ready.hiddenNeurons, 32)
    assert.ok(ready.memory.rssMb > 0)
    assert.strictEqual(ready.knowledge.enabled, true)
    assert.strictEqual(ready.knowledge.networkAccess, false)
    assert.strictEqual(ready.knowledge.pretrainedModel, false)

    const healthResponse = await fetch(`${baseUrl}/health`)
    assert.strictEqual(healthResponse.status, 200)

    const chatResponse = await fetch(`${baseUrl}/v1/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ message: 'como cambio mi foto de perfil' }),
    })
    assert.strictEqual(chatResponse.status, 200)
    const chat = await chatResponse.json()
    assert.strictEqual(chat.intent, 'perfil_foto')
    assert.ok(chatResponse.headers.get('x-request-id'))

    const chatLog = logs
      .join('')
      .split(/\r?\n/)
      .filter(Boolean)
      .map((line) => {
        try { return JSON.parse(line) } catch { return null }
      })
      .find((entry) => entry?.event === 'ai-chatbot-chat-complete')
    assert.strictEqual(chatLog.intent, 'perfil_foto')
    assert.strictEqual(chatLog.messageLength, 'como cambio mi foto de perfil'.length)
    assert.ok(chatLog.requestId)
    assert.ok(!JSON.stringify(chatLog).includes('como cambio mi foto de perfil'))

    const generalResponse = await fetch(`${baseUrl}/v1/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        message: 'por que el cielo es azul',
        context: { detailLevel: 'expanded', userId: 'must-not-affect-knowledge' },
      }),
    })
    assert.strictEqual(generalResponse.status, 200)
    const general = await generalResponse.json()
    assert.strictEqual(general.intent, 'general_knowledge')
    assert.strictEqual(general.knowledgeId, 'cielo_azul')
    assert.ok(!JSON.stringify(general).includes('must-not-affect-knowledge'))

    const diagnosticResponse = await fetch(`${baseUrl}/v1/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ message: 'Video no carga' }),
    })
    assert.strictEqual(diagnosticResponse.status, 200)
    assert.strictEqual((await diagnosticResponse.json()).intent, 'videos_subida_error')

    const semanticResponse = await fetch(`${baseUrl}/v1/semantic-rank`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: 'alimentación saludable',
        documents: [
          { id: 'recipe', title: 'Recetas nutritivas', text: 'Cocina para tu bienestar', tags: ['comida'] },
          { id: 'code', title: 'Desarrollo Android', text: 'Programación móvil', tags: ['software'] },
        ],
      }),
    })
    assert.strictEqual(semanticResponse.status, 200)
    const semantic = await semanticResponse.json()
    assert.strictEqual(semantic.model, 'novvin-semantic-local-v1')
    assert.strictEqual(semantic.results[0].id, 'recipe')

    const invalidResponse = await fetch(`${baseUrl}/v1/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ message: '' }),
    })
    assert.strictEqual(invalidResponse.status, 400)

    const oversizedResponse = await fetch(`${baseUrl}/v1/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ message: 'x'.repeat(33_000) }),
    })
    assert.strictEqual(oversizedResponse.status, 413)
    assert.deepStrictEqual(await oversizedResponse.json(), { error: 'PAYLOAD_TOO_LARGE' })

    const readyAfterRequests = await (await fetch(`${baseUrl}/ready`)).json()
    assert.ok(readyAfterRequests.requests.chatRequests >= 3)
    assert.ok(readyAfterRequests.requests.semanticSearchRequests >= 1)
    assert.ok(readyAfterRequests.requests.requestErrors >= 1)
    assert.ok(readyAfterRequests.requests.lastErrorAt)
  } finally {
    child.kill('SIGTERM')
    await Promise.race([
      new Promise((resolve) => child.once('exit', resolve)),
      new Promise((resolve) => setTimeout(resolve, 6_000)),
    ])
    if (child.exitCode == null) child.kill('SIGKILL')
    fs.rmSync(dataDir, { recursive: true, force: true })
  }
}

run()
  .then(() => console.log('conectate-ai server tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
