import { webSessionWasRevoked } from '../../core/auth/webSessionRevocation'
const token = (sid: string) => `header.${btoa(JSON.stringify({sid}))}.signature`
test('revokes exactly the device named by the server, keeping other sessions open', () => {
  const event = { event: 'web_sessions_revoked', sessionIds: ['browser-a'] }
  expect(webSessionWasRevoked(event, token('browser-a'))).toBe(true)
  expect(webSessionWasRevoked(event, token('browser-b'))).toBe(false)
})
test('ignores ordinary events, malformed payloads and missing session claims', () => {
  expect(webSessionWasRevoked({event:'message',sessionIds:['browser-a']},token('browser-a'))).toBe(false)
  expect(webSessionWasRevoked({event:'web_sessions_revoked',sessionIds:'browser-a'},token('browser-a'))).toBe(false)
  expect(webSessionWasRevoked({event:'web_sessions_revoked',sessionIds:['browser-a']},'invalid')).toBe(false)
  expect(webSessionWasRevoked({event:'web_sessions_revoked',sessionIds:['']},token(''))).toBe(false)
})
