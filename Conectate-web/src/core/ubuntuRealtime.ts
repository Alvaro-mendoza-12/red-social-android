import { isFallbackAuthConfigured, readFallbackSession } from './auth/fallbackAuth'

export type UbuntuRealtimeEvent = {
  type: 'event'
  channel: string
  payload: Record<string, unknown>
  ts?: string
}

type UbuntuRealtimeSubscription = {
  channels: string[]
  close: () => void
}

let sharedSocket: WebSocket | null = null
let sharedToken = ''
const channelHandlers = new Map<string, Set<(event: UbuntuRealtimeEvent) => void>>()
const pendingChannels = new Set<string>()
let reconnectTimer: number | null = null

function fallbackApiOrigin(): string {
  return (import.meta.env.VITE_FALLBACK_API_URL?.trim() || '').replace(/\/+$/, '')
}

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
  if (!sharedSocket || sharedSocket.readyState !== WebSocket.OPEN) return
  sharedSocket.send(JSON.stringify(payload))
}

function subscribeChannel(channel: string) {
  const clean = channel.trim()
  if (!clean) return
  pendingChannels.add(clean)
  sendSocketMessage({ type: 'subscribe', channel: clean })
}

function flushPendingSubscriptions() {
  pendingChannels.forEach((channel) => subscribeChannel(channel))
}

function ensureSocket() {
  const wsUrl = getUbuntuRealtimeWsUrl()
  const token = readFallbackSession()?.accessToken?.trim() || ''
  if (!isFallbackAuthConfigured || !wsUrl || !token) return null

  if (sharedSocket && sharedToken === token) {
    if (sharedSocket.readyState === WebSocket.OPEN) {
      flushPendingSubscriptions()
      return sharedSocket
    }
    if (sharedSocket.readyState === WebSocket.CONNECTING) return sharedSocket
  }

  sharedSocket?.close()
  sharedSocket = null
  sharedToken = token

  const socket = new WebSocket(`${wsUrl}?token=${encodeURIComponent(token)}`)
  sharedSocket = socket

  socket.addEventListener('open', () => {
    flushPendingSubscriptions()
  })

  socket.addEventListener('message', (raw) => {
    try {
      const parsed = JSON.parse(String(raw.data || '{}')) as UbuntuRealtimeEvent
      if (parsed?.type === 'event' && parsed.channel) {
        dispatchEvent(parsed)
      }
    } catch {
      // ignore malformed payloads
    }
  })

  socket.addEventListener('close', () => {
    if (sharedSocket !== socket) return
    if (reconnectTimer) window.clearTimeout(reconnectTimer)
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null
      if (channelHandlers.size > 0) ensureSocket()
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
  ensureSocket()

  return () => {
    const current = channelHandlers.get(channel)
    current?.delete(input.onEvent)
    if (current && current.size === 0) {
      channelHandlers.delete(channel)
      sendSocketMessage({ type: 'unsubscribe', channel })
    }
    if (channelHandlers.size === 0) {
      sharedSocket?.close()
      sharedSocket = null
      sharedToken = ''
    }
  }
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
