import { attachmentFailureCode, attachmentFailureText } from '../../core/media/attachmentFailure'

test.each([
  ['MEDIA_READ_401', 'Revisa tu sesión'],
  ['MEDIA_READ_403', 'Archivo protegido'],
  ['MEDIA_READ_404', 'Archivo no disponible'],
  ['MEDIA_READ_410', 'Archivo no disponible'],
  ['MEDIA_READ_429', 'Demasiadas solicitudes'],
  ['MEDIA_READ_500', 'Servicio temporalmente no disponible'],
  ['MEDIA_READ_502', 'Servicio temporalmente no disponible'],
  ['MEDIA_READ_503', 'Servicio temporalmente no disponible'],
  ['MEDIA_READ_504', 'Servicio temporalmente no disponible'],
])('gives a useful safe explanation for %s', (code, title) => {
  const normalized = attachmentFailureCode(new Error(code), true)
  expect(attachmentFailureText(normalized, 'image', true).title).toBe(title)
})

test('distinguishes offline connections and expired read deadlines', () => {
  expect(attachmentFailureCode(new TypeError('Failed to fetch'), false)).toBe('MEDIA_OFFLINE')
  expect(attachmentFailureCode(new DOMException('Aborted', 'AbortError'), true)).toBe('MEDIA_TIMEOUT')
})

test('does not expose private transport details or untrusted server messages', () => {
  const code = attachmentFailureCode(new Error('Bearer private-token https://private.test/object?token=secret'), true)
  expect(code).toBe('MEDIA_UNAVAILABLE')
  const text = attachmentFailureText(code, 'image', true)
  expect(text.title).toBe('No pudimos cargar el sticker')
  expect(JSON.stringify(text)).not.toMatch(/Bearer|private|secret/)
})
