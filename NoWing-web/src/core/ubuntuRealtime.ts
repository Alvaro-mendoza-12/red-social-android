import { clearFallbackSession, ensureFallbackAccessToken, isFallbackAuthConfigured, onFallbackAuthChanged, readFallbackSession } from './auth/fallbackAuth'
import { getRuntimeBackendUrl, subscribeRuntimeBackend } from './runtimeBackend'
import { webSessionTransportWasRevoked } from './auth/webSessionRevocation'

export type UbuntuRealtimeEvent = {
  type: 'event'
  channel: string
  payload: Record<string, unknown>
  ts?: string
}

let sharedSocket: WebSocket | null = null
let sharedToken = ''
const channelHandlers = new Map<string, Set<(event: UbuntuRealtimeEvent) => void>>()
const pendingChannels = new Set<string>()
let reconnectTimer: number | null = null
let authenticated = false
let connectionGeneration = 0
const subscribedChannels = new Set<string>()

export function ubuntuRealtimeConnected(): boolean { return authenticated && sharedSocket?.readyState === WebSocket.OPEN }

onFallbackAuthChanged(() => {
  const nextToken = readFallbackSession()?.accessToken || ''
  if (!sharedToken || nextToken === sharedToken) return
  const old = sharedSocket
  connectionGeneration += 1
  authenticated = false; subscribedChannels.clear()
  sharedSocket = null
  sharedToken = ''
  if (reconnectTimer) { window.clearTimeout(reconnectTimer); reconnectTimer = null }
  old?.close()
  if (nextToken && channelHandlers.size) void ensureSocket()
})

function fallbackApiOrigin(): string {
  return getRuntimeBackendUrl()
}

subscribeRuntimeBackend(() => {
  const old = sharedSocket
  connectionGeneration += 1
  authenticated = false; subscribedChannels.clear()
  sharedSocket = null
  sharedToken = ''
  old?.close()
  if (channelHandlers.size) void ensureSocket()
})

export function getUbuntuRealtimeWsUrl(): string | null {
  const origin = fallbackApiOrigin()
  if (!origin) return null
  const wsOrigin = origin.replace(/^http/i, (scheme) => (scheme.toLowerCase() === 'https' ? 'wss' : 'ws'))
  return `${wsOrigin}/realtime/`
}

function dispatchEvent(event: UbuntuRealtimeEvent) {
  const handlers = channelHandlers.get(event.channel)
  if (!handlers) return
  handlers.forEach((handler) => {
    try {
      handler(event)
    } catch (error) {
      console.warn('[ubuntu-realtime] handler error', error)
    }
  })
}

function sendSocketMessage(payload: Record<string, unknown>) {
  if (!authenticated || !sharedSocket || sharedSocket.readyState !== WebSocket.OPEN) return
  sharedSocket.send(JSON.stringify(payload))
}

function subscribeChannel(channel: string) {
  const clean = channel.trim()
  if (!clean) return
  pendingChannels.add(clean)
  if (!authenticated || subscribedChannels.has(clean)) return
  sendSocketMessage({ type: 'subscribe', channel: clean })
  subscribedChannels.add(clean)
}

function flushPendingSubscriptions() {
  pendingChannels.forEach((channel) => subscribeChannel(channel))
}

async function ensureSocket() {
  const generation = connectionGeneration
  const wsUrl = getUbuntuRealtimeWsUrl()
  const token = await ensureFallbackAccessToken().catch(() => '')
  if (!isFallbackAuthConfigured || !wsUrl || !token || !channelHandlers.size || generation !== connectionGeneration || token !== readFallbackSession()?.accessToken) return null

  if (sharedSocket && sharedToken === token) {
    if (sharedSocket.readyState === WebSocket.OPEN) {
      flushPendingSubscriptions()
      return sharedSocket
    }
    if (sharedSocket.readyState === WebSocket.CONNECTING) return sharedSocket
  }

  sharedSocket?.close()
  authenticated = false; subscribedChannels.clear()
  sharedSocket = null
  sharedToken = token

  // Browser WebSocket APIs cannot set Authorization headers. Authenticate in
  // the first encrypted frame so bearer tokens never appear in URLs, proxy
  // access logs, analytics, or browser history.
  const socket = new WebSocket(wsUrl)
  sharedSocket = socket
  // A TCP-open socket is not ready until the server acknowledges authentication.
  const authenticationTimer = window.setTimeout(() => { if (sharedSocket === socket && !authenticated) socket.close() }, 12000)

  socket.addEventListener('open', () => {
    if (sharedSocket !== socket) { socket.close(); return }
    socket.send(JSON.stringify({ type: 'authenticate', token }))
  })

  socket.addEventListener('message', (raw) => {
    if (sharedSocket !== socket) return
    try {
      const parsed = JSON.parse(String(raw.data || '{}')) as {
        type?: string
        channel?: string
        payload?: Record<string, unknown>
        ts?: string
      }
      if (parsed?.type === 'connected') {
        authenticated = true
        window.clearTimeout(authenticationTimer)
        flushPendingSubscriptions()
        window.dispatchEvent(new Event('novvin:realtime-ready'))
        return
      }
      if (authenticated && parsed?.type === 'event' && parsed.channel) {
        dispatchEvent(parsed as UbuntuRealtimeEvent)
      }
    } catch {
      // ignore malformed payloads
    }
  })

  socket.addEventListener('close', event => {
    window.clearTimeout(authenticationTimer)
    if (sharedSocket !== socket) return
    authenticated = false; subscribedChannels.clear()
    // The transport may be revoked before its channel handler is mounted.
    // Expiry and transient network failures must still be allowed to recover.
    if (webSessionTransportWasRevoked(event, token, readFallbackSession()?.accessToken)) {
      clearFallbackSession()
      return
    }
    window.dispatchEvent(new Event('novvin:realtime-disconnected'))
    if (reconnectTimer) window.clearTimeout(reconnectTimer)
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null
      if (channelHandlers.size > 0) void ensureSocket()
    }, 2500)
  })

  socket.addEventListener('error', () => {
    socket.close()
  })

  return socket
}

export function subscribeUbuntuRealtime(input: {
  channel: string
  onEvent: (event: UbuntuRealtimeEvent) => void
}): () => void {
  const channel = input.channel.trim()
  if (!channel || !isFallbackAuthConfigured) return () => {}

  let handlers = channelHandlers.get(channel)
  if (!handlers) {
    handlers = new Set()
    channelHandlers.set(channel, handlers)
  }
  handlers.add(input.onEvent)
  pendingChannels.add(channel)
  void ensureSocket()

  return () => {
    const current = channelHandlers.get(channel)
    current?.delete(input.onEvent)
    if (current && current.size === 0) {
      channelHandlers.delete(channel)
      pendingChannels.delete(channel)
      subscribedChannels.delete(channel)
      sendSocketMessage({ type: 'unsubscribe', channel })
    }
    if (channelHandlers.size === 0) {
      const old = sharedSocket
      connectionGeneration += 1
      authenticated = false; subscribedChannels.clear()
      sharedSocket = null
      sharedToken = ''
      if (reconnectTimer) { window.clearTimeout(reconnectTimer); reconnectTimer = null }
      old?.close()
    }
  }
}

if (typeof window !== 'undefined') {
  const recover = () => { if (channelHandlers.size) void ensureSocket() }
  window.addEventListener('online', recover)
  document.addEventListener('visibilitychange', () => { if (!document.hidden) recover() })
}

export function subscribeUbuntuRealtimeMany(input: {
  channels: string[]
  onEvent: (event: UbuntuRealtimeEvent) => void
}): () => void {
  const cleanups = input.channels
    .map((channel) => channel.trim())
    .filter(Boolean)
    .map((channel) => subscribeUbuntuRealtime({ channel, onEvent: input.onEvent }))
  return () => cleanups.forEach((cleanup) => cleanup())
}
