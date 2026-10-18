import { claimInteractiveMediaSession, getInteractiveMediaSession, releaseInteractiveMediaSession } from '../../core/interactiveMediaSession'
const session = (kind: 'live' | 'voice', accountId = 'a') => ({ kind, key: kind + ':' + accountId, accountId, title: kind, path: '/app/' + kind, close: jest.fn(async () => {}) })
afterEach(() => { const active = getInteractiveMediaSession(); if (active) releaseInteractiveMediaSession(active.key) })
test('live and voice cannot claim the same account microphone together', () => {
  expect(claimInteractiveMediaSession(session('live'))).toBe(true)
  expect(claimInteractiveMediaSession(session('voice'))).toBe(false)
  expect(getInteractiveMediaSession()?.kind).toBe('live')
  releaseInteractiveMediaSession('live:a')
  expect(claimInteractiveMediaSession(session('voice'))).toBe(true)
})
test('old cleanup does not release a new account session', () => {
  claimInteractiveMediaSession(session('live'))
  claimInteractiveMediaSession(session('voice', 'b'))
  releaseInteractiveMediaSession('live:a')
  expect(getInteractiveMediaSession()?.accountId).toBe('b')
})
