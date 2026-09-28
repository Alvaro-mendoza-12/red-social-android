'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const { profileShareHtml, registerPublicProfileShareRoutes } = require('../src/public-profile-share')

test('shared profiles use readable metadata, escape untrusted text and omit private fields', () => {
  const html = profileShareHtml({ id: 'test-id', display_name: '<script>Hola</script>',
    username: 'mirella_vr07', bio: 'Mi perfil "público"', email: 'private@example.invalid',
    avatar_url: 'javascript:alert(1)' }, 'https://example.invalid')
  assert.match(html, /og:title/)
  assert.match(html, /@mirella_vr07/)
  assert.match(html, /&lt;script&gt;/)
  assert.match(html, /conectate:\/\/profile\/test-id/)
  assert.match(html, /https:\/\/example.invalid\/v1\/public\/profiles\/mirella_vr07/)
  assert.ok(!html.includes('private@example.invalid'))
  assert.ok(!html.includes('javascript:'))
  assert.ok(!html.includes('<script>'))
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
  } finally { await new Promise((resolve) => server.close(resolve)) }
})
