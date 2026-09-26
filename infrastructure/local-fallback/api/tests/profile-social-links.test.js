const test = require('node:test')
const assert = require('node:assert/strict')

const { normalizeSocialLinks } = require('../src/data-routes')

test('normalizes supported profile social links', () => {
  assert.deepEqual(
    normalizeSocialLinks({
      instagram: 'https://www.instagram.com/novvin.app/',
      tiktok: 'https://tiktok.com/@novvin',
      facebook: 'https://facebook.com/novvin',
      x: 'https://x.com/novvin',
      whatsapp: '+57 300 123 4567',
      unknown: 'https://example.com',
    }),
    {
      instagram: 'https://www.instagram.com/novvin.app/',
      tiktok: 'https://tiktok.com/@novvin',
      facebook: 'https://facebook.com/novvin',
      x: 'https://x.com/novvin',
      whatsapp: '573001234567',
    },
  )
})

test('rejects look-alike, insecure and malformed links', () => {
  assert.deepEqual(
    normalizeSocialLinks({
      instagram: 'http://instagram.com/novvin',
      tiktok: 'https://tiktok.com.evil.example/@novvin',
      facebook: 'javascript:alert(1)',
      x: 'https://x.com.evil.example/novvin',
      whatsapp: '123',
    }),
    {},
  )
})
