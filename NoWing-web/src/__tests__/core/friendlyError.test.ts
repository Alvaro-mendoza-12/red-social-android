import { friendlyError } from '../../core/utils'

test('technical network details are not shown to users', () => {
  const visible = friendlyError(new Error('Request timeout https://private.example/v1/data request_timeout=25000'))
  expect(visible).not.toMatch(/https|request_timeout|25000/)
  expect(friendlyError(new Error('ROOM_ACCESS_DENIED'), 'No tienes acceso.')).toBe('No tienes acceso.')
})
