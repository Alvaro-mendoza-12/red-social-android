'use strict'

const net = require('node:net')
const MAX_BYTES = 4 * 1024 * 1024
const MAX_REPLY_BYTES = 4096
const CHUNK_BYTES = 64 * 1024

function unavailable() {
  return Object.assign(new Error('Antivirus no disponible. El PDF no se guardó.'), {
    status: 503, code: 'SURVEY_SCAN_UNAVAILABLE', expose: true,
  })
}

/** Send bytes only to the private daemon; never accept a scan without its final OK. */
function scanPdfWithClamd(bytes, options = {}) {
  if (!Buffer.isBuffer(bytes) || !bytes.length || bytes.length > MAX_BYTES) {
    return Promise.reject(Object.assign(new Error('Archivo vacío o superior a 4 MB.'), {
      status: 413, code: 'SURVEY_FILE_LIMIT', expose: true,
    }))
  }
  const host = options.host ?? process.env.SURVEY_CLAMD_HOST ?? 'survey-antivirus'
  const port = Number(options.port ?? process.env.SURVEY_CLAMD_PORT ?? 3310)
  const timeoutMs = options.timeoutMs ?? 10000
  if (typeof host !== 'string' || !host.trim() || !Number.isInteger(port) || port < 1 || port > 65535 ||
      !Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 10000) return Promise.reject(unavailable())

  return new Promise((resolve, reject) => {
    let socket, settled = false, commandSent = false, finishedWriting = false, offset = 0
    let reply = Buffer.alloc(0)
    const timer = setTimeout(() => finish(unavailable()), timeoutMs)
    function finish(error) {
      if (settled) return
      settled = true
      clearTimeout(timer)
      socket?.destroy()
      if (error) reject(error)
      else resolve()
    }
    function pump() {
      if (settled || finishedWriting) return
      try {
        if (!commandSent) {
          commandSent = true
          if (!socket.write(Buffer.from('zINSTREAM\0'))) return
        }
        while (offset < bytes.length) {
          const end = Math.min(offset + CHUNK_BYTES, bytes.length)
          const header = Buffer.alloc(4)
          header.writeUInt32BE(end - offset)
          const frame = Buffer.concat([header, bytes.subarray(offset, end)])
          offset = end
          if (!socket.write(frame)) return
        }
        finishedWriting = true
        socket.write(Buffer.alloc(4))
      } catch { finish(unavailable()) }
    }
    try {
      socket = (options.connect ?? net.createConnection)({ host, port })
      socket.on('error', () => finish(unavailable()))
      socket.on('end', () => { if (!settled) finish(unavailable()) })
      socket.on('close', () => { if (!settled) finish(unavailable()) })
      socket.on('connect', pump)
      socket.on('drain', pump)
      socket.on('data', data => {
        if (settled) return
        if (reply.length + data.length > MAX_REPLY_BYTES) return finish(unavailable())
        reply = Buffer.concat([reply, data])
        const delimiter = reply.indexOf(0)
        if (delimiter < 0) return
        // zINSTREAM uses a NUL terminator. An early or unrecognized reply is not approval.
        const result = reply.subarray(0, delimiter).toString('utf8')
        if (!finishedWriting) return finish(unavailable())
        if (result === 'stream: OK') return finish()
        if (/^stream: .+ FOUND$/.test(result)) return finish(Object.assign(new Error('El antivirus rechazó el PDF.'), {
          status: 400, code: 'SURVEY_FILE_REJECTED', expose: true,
        }))
        finish(unavailable())
      })
    } catch { finish(unavailable()) }
  })
}

module.exports = { scanPdfWithClamd, MAX_BYTES }
