jest.mock('../../core/auth/fallbackAuth', () => ({
  clearFallbackSession: jest.fn(),
  ensureFallbackAccessToken: jest.fn(async () => 'synthetic-token'),
  isFallbackAuthConfigured: true,
  onFallbackAuthChanged: jest.fn(),
  readFallbackSession: jest.fn(() => ({ accessToken: 'synthetic-token' })),
}))
jest.mock('../../core/runtimeBackend', () => ({ getRuntimeBackendUrl: () => 'https://api.example.invalid', subscribeRuntimeBackend: jest.fn() }))
jest.mock('../../core/auth/webSessionRevocation', () => ({ webSessionTransportWasRevoked: () => false }))

class SocketStub extends EventTarget {
  static OPEN = 1
  static CONNECTING = 0
  static instances: SocketStub[] = []
  readyState = 0
  send = jest.fn()
  constructor(readonly url: string) { super(); SocketStub.instances.push(this) }
  open() { this.readyState = 1; this.dispatchEvent(new Event('open')) }
  receive(data: object) { this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(data) })) }
  close() { this.readyState = 3; this.dispatchEvent(new Event('close')) }
}
const tick = async () => { await Promise.resolve(); await Promise.resolve() }
beforeEach(() => {
  jest.resetModules(); jest.useFakeTimers(); SocketStub.instances = []
  Object.defineProperty(globalThis, 'WebSocket', { configurable: true, value: SocketStub })
})
afterEach(() => { jest.clearAllTimers(); jest.useRealTimers() })

test('subscriptions wait for authentication and are sent once per channel', async () => {
  const { subscribeUbuntuRealtime, ubuntuRealtimeConnected } = await import('../../core/ubuntuRealtime')
  const first = subscribeUbuntuRealtime({ channel: 'chat:one', onEvent: jest.fn() })
  await tick(); const socket = SocketStub.instances[0]; socket.open()
  const second = subscribeUbuntuRealtime({ channel: 'chat:two', onEvent: jest.fn() })
  await tick()
  expect(ubuntuRealtimeConnected()).toBe(false)
  expect(socket.send.mock.calls.map(([value]) => JSON.parse(value).type)).toEqual(['authenticate'])
  socket.receive({ type: 'connected' })
  expect(ubuntuRealtimeConnected()).toBe(true)
  expect(socket.send.mock.calls.map(([value]) => JSON.parse(value))).toEqual([
    { type: 'authenticate', token: 'synthetic-token' },
    { type: 'subscribe', channel: 'chat:one' },
    { type: 'subscribe', channel: 'chat:two' },
  ])
  const duplicate = subscribeUbuntuRealtime({ channel: 'chat:one', onEvent: jest.fn() })
  await tick(); expect(socket.send).toHaveBeenCalledTimes(3)
  duplicate(); first(); second()
})

test('room channels queue behind acknowledgments and a denial does not starve the next room', async () => {
  const { subscribeUbuntuRealtime } = await import('../../core/ubuntuRealtime')
  const rooms = Array.from({ length: 6 }, (_, index) => `chat:room:group:r${index}`)
  const close = rooms.map(channel => subscribeUbuntuRealtime({ channel, onEvent: jest.fn() }))
  await tick()
  const socket = SocketStub.instances[0]
  socket.open()
  socket.receive({ type: 'connected' })
  const subscriptions = () => socket.send.mock.calls.map(([value]) => JSON.parse(value)).filter(item => item.type === 'subscribe').map(item => item.channel)
  expect(subscriptions()).toEqual(rooms.slice(0, 3))
  socket.receive({ type: 'subscribed', channel: rooms[0] })
  socket.receive({ type: 'error', channel: rooms[1], error: 'FORBIDDEN_CHANNEL' })
  socket.receive({ type: 'subscribed', channel: rooms[2] })
  expect(subscriptions()).toEqual(rooms)
  close.forEach(stop => stop())
})

test('an account change discards delayed events from the replaced socket', async () => {
  const auth = await import('../../core/auth/fallbackAuth')
  const { subscribeUbuntuRealtime } = await import('../../core/ubuntuRealtime')
  const onEvent = jest.fn(), unsubscribe = subscribeUbuntuRealtime({ channel: 'chat:one', onEvent })
  await tick(); const old = SocketStub.instances[0]; old.open(); old.receive({ type: 'connected' })
  ;(auth.readFallbackSession as jest.Mock).mockReturnValue({ accessToken: 'next-token' })
  ;(auth.ensureFallbackAccessToken as jest.Mock).mockResolvedValue('next-token')
  const changed = (auth.onFallbackAuthChanged as jest.Mock).mock.calls[0][0]
  changed(); await tick()
  old.receive({ type: 'event', channel: 'chat:one', payload: { message: 'stale' } })
  expect(onEvent).not.toHaveBeenCalled()
  unsubscribe()
})

test('returning online reconnects immediately instead of waiting for the retry timer', async () => {
  const { subscribeUbuntuRealtime } = await import('../../core/ubuntuRealtime')
  const unsubscribe = subscribeUbuntuRealtime({ channel: 'chat:one', onEvent: jest.fn() })
  await tick(); SocketStub.instances[0].close()
  window.dispatchEvent(new Event('online')); await tick()
  expect(SocketStub.instances).toHaveLength(2)
  unsubscribe()
})
