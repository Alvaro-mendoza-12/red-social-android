import type { Socket } from 'socket.io-client'

/** Rewrites use HTTP for polling; start there to avoid a failed upgrade delaying admission. */
export const voiceTransportOptions = {
  transports: ['polling', 'websocket'] as ('polling' | 'websocket')[], tryAllTransports: true,
  // Keep healthy HTTP sessions off the platform's finite-lived WebSocket relay.
  // WebSocket remains a fallback when polling cannot connect.
  upgrade: false,
}

/** A connected socket can lose a membership response without losing its transport. */
export function voiceMembershipRecovery(socket: Pick<Socket, 'connected' | 'connect'>, retry: () => void,
  current: () => boolean, timeoutMs = 10_000) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let pending = false
  return {
    begin() {
      if (pending || !current() || !socket.connected) return false
      pending = true
      timer = setTimeout(() => { pending = false; timer = undefined; if (current()) retry() }, timeoutMs)
      return true
    },
    complete() { clearTimeout(timer); timer = undefined; pending = false },
    reconnect(reason: string) {
      clearTimeout(timer); timer = undefined; pending = false
      // Socket.IO deliberately does not auto-reconnect after server disconnect.
      if (reason === 'io server disconnect' && current()) socket.connect()
    },
  }
}
