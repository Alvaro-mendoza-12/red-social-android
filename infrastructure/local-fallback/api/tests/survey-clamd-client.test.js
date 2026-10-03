'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const net = require('node:net')
const { scanPdfWithClamd, MAX_BYTES } = require('../src/surveys/clamd-client')

async function endpoint(t, handler) {
  const sockets = new Set()
  const server = net.createServer(socket => {
    sockets.add(socket)
    socket.on('error', () => {})
    socket.on('close', () => sockets.delete(socket))
    handler(socket)
  })
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  t.after(async () => {
    for (const socket of sockets) socket.destroy()
    await new Promise(resolve => server.close(resolve))
  })
  return { host: '127.0.0.1', port: server.address().port, timeoutMs: 1500 }
}

function readStream(socket, done) {
  let buffer = Buffer.alloc(0), command = false, chunks = [], length = 0
  socket.on('data', data => {
    buffer = Buffer.concat([buffer, data])
    if (!command) {
      if (buffer.length < 10) return
      assert.equal(buffer.subarray(0, 10).toString(), 'zINSTREAM\0')
      buffer = buffer.subarray(10)
      command = true
    }
    while (buffer.length >= 4) {
      const n = buffer.readUInt32BE()
      assert.ok(n <= 64 * 1024)
      if (buffer.length < n + 4) return
      buffer = buffer.subarray(4)
      if (n === 0) {
        assert.equal(buffer.length, 0)
        done(Buffer.concat(chunks, length))
        return
      }
      chunks.push(buffer.subarray(0, n))
      length += n
      assert.ok(length <= MAX_BYTES)
      buffer = buffer.subarray(n)
    }
  })
}

test('envía framing INSTREAM exacto y espera el OK fragmentado hasta el terminador', async t => {
  const input = Buffer.from('%PDF-1.4\narchivo de prueba')
  let received = false
  const options = await endpoint(t, socket => readStream(socket, bytes => {
    assert.deepEqual(bytes, input)
    received = true
    socket.write('stream: O')
    setTimeout(() => socket.write('K\0'), 15)
  }))
  await scanPdfWithClamd(input, options)
  assert.ok(received)
})

test('envía exactamente 4 MiB en fragmentos acotados respetando backpressure', async t => {
  const input = Buffer.alloc(MAX_BYTES, 7)
  let received = 0
  const options = await endpoint(t, socket => readStream(socket, bytes => {
    assert.deepEqual(bytes, input)
    received = bytes.length
    socket.write('stream: OK\0')
  }))
  await scanPdfWithClamd(input, options)
  assert.equal(received, MAX_BYTES)
})

test('rechaza malware sin exponer el nombre o contenido devuelto por el daemon', async t => {
  const options = await endpoint(t, socket => readStream(socket, () => socket.write('stream: Eicar-Signature FOUND\0')))
  await assert.rejects(scanPdfWithClamd(Buffer.from('prueba'), options), e =>
    e.status === 400 && e.code === 'SURVEY_FILE_REJECTED' && !e.message.includes('Eicar'))
})

test('una respuesta ERROR o no reconocida nunca aprueba el adjunto', async t => {
  for (const response of ['stream: size limit exceeded ERROR\0', 'OK\0', 'stream: OK ERROR\0']) {
    const options = await endpoint(t, socket => readStream(socket, () => socket.write(response)))
    await assert.rejects(scanPdfWithClamd(Buffer.from('prueba'), options), e => e.status === 503)
  }
})

test('no acepta un OK sin terminador y cierra al agotarse el plazo absoluto', async t => {
  const options = await endpoint(t, socket => readStream(socket, () => socket.write('stream: OK')))
  const started = performance.now()
  await assert.rejects(scanPdfWithClamd(Buffer.from('prueba'), { ...options, timeoutMs: 50 }), e => e.code === 'SURVEY_SCAN_UNAVAILABLE')
  assert.ok(performance.now() - started < 1000)
})

test('rechaza desconexión prematura y error de conexión', async t => {
  const options = await endpoint(t, socket => readStream(socket, () => socket.end()))
  await assert.rejects(scanPdfWithClamd(Buffer.from('prueba'), options), e => e.status === 503)
  await assert.rejects(scanPdfWithClamd(Buffer.from('prueba'), { connect: () => { throw new Error('privado') } }), e =>
    e.status === 503 && !e.message.includes('privado'))
})

test('limita respuestas del daemon sin terminador', async t => {
  const options = await endpoint(t, socket => readStream(socket, () => socket.write(Buffer.alloc(4097, 65))))
  await assert.rejects(scanPdfWithClamd(Buffer.from('prueba'), options), e => e.status === 503)
})

test('rechaza archivos vacíos o mayores de 4 MiB antes de conectar', async () => {
  let connections = 0
  const options = { connect: () => { connections++; throw new Error('no debe conectar') } }
  for (const bytes of [Buffer.alloc(0), Buffer.alloc(MAX_BYTES + 1), 'cadena']) {
    await assert.rejects(scanPdfWithClamd(bytes, options), e => e.status === 413)
  }
  assert.equal(connections, 0)
})

test('configuración inválida falla sin ejecutar otro escáner', async () => {
  for (const options of [{ host: '' }, { port: 0 }, { port: 'abc' }, { timeoutMs: 10001 }]) {
    await assert.rejects(scanPdfWithClamd(Buffer.from('prueba'), options), e => e.status === 503)
  }
})
