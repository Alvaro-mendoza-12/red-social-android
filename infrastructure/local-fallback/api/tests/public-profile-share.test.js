'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const { profileShareHtml, publicProfileCard, registerPublicProfileShareRoutes } = require('../src/public-profile-share')

test('shared profiles use readable metadata, escape untrusted text and omit private fields', () => {
  const html = profileShareHtml({ id: 'test-id', display_name: '<script>Hola</script>',
    username: 'mirella_vr07', bio: 'Mi perfil "público"', email: 'private@example.invalid',
    avatar_url: 'javascript:alert(1)' }, 'https://example.invalid')
  assert.match(html, /og:title/)
  assert.match(html, /@mirella_vr07/)
  assert.match(html, /&lt;script&gt;/)
  assert.match(html, /conectate:\/\/profile\/test-id/)
  assert.match(html, /https:\/\/example.invalid\/v1\/public\/profiles\/mirella_vr07/)
  assert.match(html, /og:image:width" content="1200"/)
  assert.match(html, /\/v1\/public\/profiles\/mirella_vr07\/card.jpg/)
  assert.ok(!html.includes('private@example.invalid'))
  assert.ok(!html.includes('javascript:'))
  assert.ok(!html.includes('<script>'))
})

test('profile card is a readable JPEG and escapes profile content', async () => {
  const image = await publicProfileCard({ id: 'test-id', display_name: '<script>Mirella</script>',
    username: 'mirella_vr07', bio: 'Hola & bienvenidos', avatar_url: 'https://other.invalid/a.jpg' },
  'https://example.invalid')
  assert.equal(image.subarray(0, 2).toString('hex'), 'ffd8')
  assert.ok(image.length > 5_000)
})

test('public profile preview does not reveal unavailable profiles', async () => {
  const app = express()
  const sql = []
  const pool = { async query(statement) { sql.push(statement); return { rows: [] } } }
  registerPublicProfileShareRoutes(app, { pool, publicBaseUrl: 'https://example.invalid' })
  const server = app.listen(0, '127.0.0.1')
  try {
    await new Promise((resolve) => server.once('listening', resolve))
    const base = `http://127.0.0.1:${server.address().port}`
    assert.equal((await fetch(`${base}/v1/public/profiles/mirella_vr07`)).status, 404)
    assert.ok(sql.some((statement) => statement.includes("profile_visibility', 'everyone') = 'everyone'")))
    assert.equal((await fetch(`${base}/v1/public/profiles/invalid%3Cscript%3E`)).status, 404)
    assert.equal((await fetch(`${base}/v1/public/profiles/mirella_vr07/card.jpg`)).status, 404)
  } finally { await new Promise((resolve) => server.close(resolve)) }
})
