'use strict'

const net = require('node:net')
const tls = require('node:tls')
const fs = require('node:fs')
const { EventEmitter } = require('node:events')

const CRLF = Buffer.from('\r\n')

function encodeRespCommand(args) {
  const parts = [Buffer.from(`*${args.length}\r\n`, 'utf8')]
  for (let i = 0; i < args.length; i += 1) {
    const raw = args[i]
    const buf = Buffer.isBuffer(raw) ? raw : Buffer.from(String(raw), 'utf8')
    parts.push(Buffer.from(`$${buf.length}\r\n`, 'utf8'), buf, CRLF)
  }
  return Buffer.concat(parts)
}

function parseRespValue(buffer, offset) {
  if (offset >= buffer.length) return null
  const prefix = buffer[offset]
  const lineEnd = buffer.indexOf(CRLF, offset + 1)
  if (lineEnd === -1) return null

  if (prefix === 43) { // '+' Simple String
    return { value: buffer.toString('utf8', offset + 1, lineEnd), nextOffset: lineEnd + 2 }
  }
  if (prefix === 45) { // '-' Error
    const message = buffer.toString('utf8', offset + 1, lineEnd)
    const err = new Error(message)
    err.code = 'REDIS_REPLY_ERROR'
    return { error: err, nextOffset: lineEnd + 2 }
  }
  if (prefix === 58) { // ':' Integer
    return { value: Number(buffer.toString('ascii', offset + 1, lineEnd)), nextOffset: lineEnd + 2 }
  }
  if (prefix === 36) { // '$' Bulk String
    const length = Number.parseInt(buffer.toString('ascii', offset + 1, lineEnd), 10)
    if (length === -1) return { value: null, nextOffset: lineEnd + 2 }
    if (!Number.isFinite(length) || length < 0) {
      throw new Error(`Invalid RESP bulk length: ${length}`)
    }
    const dataStart = lineEnd + 2
    const dataEnd = dataStart + length
    if (buffer.length < dataEnd + 2) return null
    return { value: buffer.toString('utf8', dataStart, dataEnd), nextOffset: dataEnd + 2 }
  }
  if (prefix === 42) { // '*' Array
    const count = Number.parseInt(buffer.toString('ascii', offset + 1, lineEnd), 10)
    if (count === -1) return { value: null, nextOffset: lineEnd + 2 }
    if (!Number.isFinite(count) || count < 0) {
      throw new Error(`Invalid RESP array length: ${count}`)
    }
    const items = new Array(count)
    let cursor = lineEnd + 2
    for (let i = 0; i < count; i += 1) {
      const parsed = parseRespValue(buffer, cursor)
      if (!parsed) return null
      if (parsed.error) items[i] = parsed.error
      else items[i] = parsed.value
      cursor = parsed.nextOffset
    }
    return { value: items, nextOffset: cursor }
  }
  throw new Error(`Unsupported RESP prefix byte: ${prefix}`)
}

function parseRedisConnectionConfig({
  url,
  password,
  passwordFile,
  connectTimeoutMs = 3_000,
  commandTimeoutMs = 250,
} = {}) {
  const rawUrl = String(url || '').trim()
  if (!rawUrl) return null
  const parsed = new URL(rawUrl)
  if (!['redis:', 'rediss:'].includes(parsed.protocol)) {
    throw new Error('Redis URL must use redis:// or rediss://')
  }
  const useTls = parsed.protocol === 'rediss:'
  const host = parsed.hostname || '127.0.0.1'
  const port = parsed.port ? Number.parseInt(parsed.port, 10) : 6379
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    throw new Error('Redis port is invalid')
  }
  let resolvedPassword = String(password || '').trim()
  const resolvedPasswordFile = String(passwordFile || '').trim()
  if (!resolvedPassword && resolvedPasswordFile) {
    try {
      resolvedPassword = fs.readFileSync(resolvedPasswordFile, 'utf8').trim()
    } catch {
      // Handled gracefully when connecting or via explicit secret config
    }
  }
  if (!resolvedPassword && parsed.password) {
    resolvedPassword = decodeURIComponent(parsed.password)
  }
  const username = parsed.username ? decodeURIComponent(parsed.username) : ''
  const pathDb = parsed.pathname.replace(/^\/+/, '').trim()
  const db = pathDb ? Number.parseInt(pathDb, 10) : 0
  return {
    host,
    port,
    useTls,
    username,
    password: resolvedPassword,
    db: Number.isInteger(db) && db >= 0 && db <= 15 ? db : 0,
    connectTimeoutMs: Math.max(250, Math.min(Number(connectTimeoutMs) || 3_000, 30_000)),
    commandTimeoutMs: Math.max(50, Math.min(Number(commandTimeoutMs) || 250, 30_000)),
  }
}

class RedisRespClient extends EventEmitter {
  constructor(config) {
    super()
    this.config = config
    this.socket = null
    this.buffer = Buffer.alloc(0)
    this.pending = []
    this.ready = false
    this.closed = false
    this.connecting = false
    this.reconnectAttempts = 0
    this.reconnectTimer = null
    this.lastErrorCode = null
  }

  isReady() {
    return this.ready && !this.closed && Boolean(this.socket) && !this.socket.destroyed
  }

  getStatus() {
    return {
      configured: true,
      ready: this.isReady(),
      host: this.config.host,
      port: this.config.port,
      tls: this.config.useTls,
      lastErrorCode: this.lastErrorCode,
      pendingCommands: this.pending.length,
    }
  }

  connect() {
    if (this.closed || this.connecting || this.isReady()) return Promise.resolve(this.isReady())
    this.connecting = true
    return new Promise((resolve) => {
      let settled = false
      const done = (ok) => {
        if (settled) return
        settled = true
        this.connecting = false
        resolve(ok)
      }

      const socket = this.config.useTls
        ? tls.connect({
            host: this.config.host,
            port: this.config.port,
            servername: this.config.host,
          })
        : net.createConnection({
            host: this.config.host,
            port: this.config.port,
          })

      this.socket = socket
      this.buffer = Buffer.alloc(0)
      socket.setNoDelay(true)
      socket.setKeepAlive(true, 15_000)

      const connectTimer = setTimeout(() => {
        this.lastErrorCode = 'REDIS_CONNECT_TIMEOUT'
        socket.destroy(new Error('REDIS_CONNECT_TIMEOUT'))
        done(false)
      }, this.config.connectTimeoutMs)
      connectTimer.unref?.()

      socket.on('data', (chunk) => this._onData(chunk))

      socket.on('error', (error) => {
        this.lastErrorCode = String(error?.code || error?.message || 'REDIS_SOCKET_ERROR').slice(0, 80)
        this.emit('error', error)
      })

      socket.on('close', () => {
        clearTimeout(connectTimer)
        const wasReady = this.ready
        this.ready = false
        if (this.socket === socket) this.socket = null
        this._rejectAllPending(new Error('REDIS_CONNECTION_CLOSED'))
        done(false)
        if (wasReady) this.emit('disconnect')
        if (!this.closed) this._scheduleReconnect()
      })

      const onConnected = async () => {
        clearTimeout(connectTimer)
        try {
          if (this.config.password) {
            const authArgs = this.config.username
              ? ['AUTH', this.config.username, this.config.password]
              : ['AUTH', this.config.password]
            await this._writeRawCommand(socket, authArgs, this.config.connectTimeoutMs)
          }
          if (this.config.db > 0) {
            await this._writeRawCommand(socket, ['SELECT', String(this.config.db)], this.config.connectTimeoutMs)
          }
          const pong = await this._writeRawCommand(socket, ['PING'], this.config.connectTimeoutMs)
          if (pong !== 'PONG') throw new Error(`Unexpected PING reply: ${pong}`)
          this.ready = true
          this.reconnectAttempts = 0
          this.lastErrorCode = null
          this.emit('ready')
          done(true)
        } catch (error) {
          this.lastErrorCode = String(error?.code || error?.message || 'REDIS_HANDSHAKE_FAILED').slice(0, 80)
          socket.destroy(error)
          done(false)
        }
      }

      socket.once(this.config.useTls ? 'secureConnect' : 'connect', () => {
        void onConnected()
      })
    })
  }

  _scheduleReconnect() {
    if (this.closed || this.reconnectTimer) return
    const delayMs = Math.min(200 * (2 ** Math.min(this.reconnectAttempts, 5)), 5_000)
    this.reconnectAttempts += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      if (!this.closed) void this.connect()
    }, delayMs)
    this.reconnectTimer.unref?.()
  }

  _rejectAllPending(error) {
    while (this.pending.length > 0) {
      const entry = this.pending.shift()
      clearTimeout(entry.timer)
      entry.reject(error)
    }
  }

  _onData(chunk) {
    this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk])
    let offset = 0
    while (offset < this.buffer.length) {
      let parsed
      try {
        parsed = parseRespValue(this.buffer, offset)
      } catch (error) {
        this.lastErrorCode = 'REDIS_PROTOCOL_ERROR'
        this.socket?.destroy(error)
        return
      }
      if (!parsed) break
      offset = parsed.nextOffset
      const entry = this.pending.shift()
      if (!entry) continue
      clearTimeout(entry.timer)
      if (parsed.error) entry.reject(parsed.error)
      else entry.resolve(parsed.value)
    }
    this.buffer = offset === this.buffer.length ? Buffer.alloc(0) : this.buffer.subarray(offset)
  }

  _writeRawCommand(socket, args, timeoutMs) {
    return new Promise((resolve, reject) => {
      if (!socket || socket.destroyed) {
        const err = new Error('REDIS_NOT_CONNECTED')
        err.code = 'REDIS_NOT_CONNECTED'
        return reject(err)
      }
      const entry = { resolve, reject, timer: null }
      entry.timer = setTimeout(() => {
        const idx = this.pending.indexOf(entry)
        if (idx !== -1) this.pending.splice(idx, 1)
        const err = new Error('REDIS_COMMAND_TIMEOUT')
        err.code = 'REDIS_COMMAND_TIMEOUT'
        this.lastErrorCode = err.code
        // Stream framing would be out of sync after a timed-out reply; recycle socket cleanly.
        socket.destroy(err)
        reject(err)
      }, timeoutMs)
      entry.timer.unref?.()
      this.pending.push(entry)
      socket.write(encodeRespCommand(args))
    })
  }

  sendCommand(args, { timeoutMs = this.config.commandTimeoutMs } = {}) {
    if (!this.isReady()) {
      const err = new Error('REDIS_NOT_READY')
      err.code = 'REDIS_NOT_READY'
      return Promise.reject(err)
    }
    return this._writeRawCommand(this.socket, args, timeoutMs)
  }

  async close() {
    this.closed = true
    this.ready = false
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    const socket = this.socket
    this.socket = null
    this._rejectAllPending(new Error('REDIS_CLIENT_CLOSED'))
    if (socket && !socket.destroyed) {
      socket.end()
      socket.destroy()
    }
  }
}

function createRedisRespClient(options) {
  const config = parseRedisConnectionConfig(options)
  if (!config) return null
  return new RedisRespClient(config)
}

module.exports = {
  RedisRespClient,
  createRedisRespClient,
  encodeRespCommand,
  parseRedisConnectionConfig,
  parseRespValue,
}
