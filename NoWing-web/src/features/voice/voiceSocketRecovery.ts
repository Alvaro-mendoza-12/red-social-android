/** Refresh credentials for each handshake; never reuse an old account after leaving. */
export function voiceSocketAuth(readToken: () => Promise<string | undefined>, isCurrent: () => boolean) {
  return (done: (auth: { token: string }) => void) => {
    void Promise.resolve().then(() => isCurrent() ? readToken() : '').then(token => {
      done({ token: isCurrent() ? token?.trim() || '' : '' })
    }).catch(() => done({ token: '' }))
  }
}

// Stop is still explicit socket.disconnect() when leaving the room.
export const voiceReconnectOptions = { reconnection: true, reconnectionAttempts: Infinity, reconnectionDelay: 700, reconnectionDelayMax: 10_000, randomizationFactor: 0.5 }
