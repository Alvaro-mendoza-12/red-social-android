import { voiceSocketAuth } from '../../../features/voice/voiceSocketRecovery'

test('each reconnect reads refreshed credentials instead of the initial token', async () => {
  const read = jest.fn().mockResolvedValueOnce('first').mockResolvedValueOnce('renewed')
  const auth = voiceSocketAuth(read, () => true)
  expect(await new Promise(resolve => auth(resolve))).toEqual({ token: 'first' })
  expect(await new Promise(resolve => auth(resolve))).toEqual({ token: 'renewed' })
})
test('leaving during renewal never sends credentials from the previous room', async () => {
  let current = true, complete!: (value: string) => void
  const auth = voiceSocketAuth(() => new Promise(resolve => { complete = resolve }), () => current)
  const result = new Promise(resolve => auth(resolve))
  await Promise.resolve(); current = false; complete('old-account-token')
  expect(await result).toEqual({ token: '' })
})
test('an unavailable session completes the handshake callback without an unhandled rejection', async () => {
  const auth = voiceSocketAuth(async () => { throw Error('offline') }, () => true)
  expect(await new Promise(resolve => auth(resolve))).toEqual({ token: '' })
})
