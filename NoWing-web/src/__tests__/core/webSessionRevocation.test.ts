import { webSessionTransportWasRevoked, webSessionWasRevoked } from '../../core/auth/webSessionRevocation'

test('an explicit revoked transport clears only its current credential, never expiry or another account',()=>{
 const event={code:4401,reason:'WEB_SESSION_REVOKED'}
 expect(webSessionTransportWasRevoked(event,'device-a','device-a')).toBe(true)
 expect(webSessionTransportWasRevoked(event,'device-a','device-b')).toBe(false)
 expect(webSessionTransportWasRevoked(event,'device-a',undefined)).toBe(false)
 expect(webSessionTransportWasRevoked({...event,reason:'SESSION_EXPIRED'},'device-a','device-a')).toBe(false)
 expect(webSessionTransportWasRevoked({...event,code:1006},'device-a','device-a')).toBe(false)
})
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
