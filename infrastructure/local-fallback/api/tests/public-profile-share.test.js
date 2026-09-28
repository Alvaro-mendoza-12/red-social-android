'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const { profileShareHtml, publicProfileCard, publicProfilePosts, registerPublicProfileShareRoutes } = require('../src/public-profile-share')

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
  assert.match(html, /rel="icon"/)
  assert.match(html, /\/v1\/public\/profiles\/mirella_vr07\/card.jpg/)
  assert.ok(!html.includes('private@example.invalid'))
  assert.ok(!html.includes('javascript:'))
  assert.ok(!html.includes('<script>'))
  assert.match(html, /Ver en la web/)
  assert.match(html, /Descargar Novvin en Google Play/)
  assert.match(html, /Estás viendo la versión web como invitado/)
  assert.match(html, /Iniciar sesión/)
  assert.match(html, /Crear cuenta/)
})

test('guest web view includes only public and non-sensitive profile posts', async () => {
  const statements = []
  const pool = { async query(sql) { statements.push(sql); if (sql.includes('to_regclass')) return { rows: [{ name: 'supabase_import.posts' }] }
    if (sql.includes('supabase_import.posts p')) return { rows: [{ row: {
      id: 'imported-post', content: 'Cinco fotos', media_type: 'image',
      media_urls: Array.from({ length: 5 }, (_, index) => ({
        url: `https://example.invalid/foto-${index + 1}.jpg`, media_type: 'image',
      })),
      created_at: new Date('2026-09-29T10:00:00Z'),
    } }] }
    return { rows: [{
    content: 'Hola desde Novvin', media_type: 'image',
    media_url: 'https://example.invalid/photo.jpg',
    media_urls: ['https://example.invalid/photo.jpg', 'https://example.invalid/photo-2.jpg'],
    created_at: new Date('2026-09-28T10:00:00Z'),
  }] } } }
  const profile = { id: '550e8400-e29b-41d4-a716-446655440000',
    display_name: 'Mirella', username: 'mirella_vr07' }
  const posts = await publicProfilePosts(pool, profile)
  assert.equal(posts.length, 2)
  assert.match(statements[0], /p\.visibility = 'public' AND p\.is_sensitive = FALSE/)
  const html = profileShareHtml(profile, 'https://example.invalid', posts)
  assert.match(html, /Hola desde Novvin/)
  assert.match(html, /photo\.jpg/)
  assert.match(html, /photo-2\.jpg/)
  assert.match(html, /foto-5\.jpg/)
  assert.match(html, /Desliza a la izquierda para ver las 2 fotos/)
  assert.match(html, /Desliza a la izquierda para ver las 5 fotos/)
  assert.equal((html.match(/alt="Imagen [12] de 2 de la publicación"/g) || []).length, 2)
  assert.match(html, /2026-09-28/)
  assert.deepEqual(await publicProfilePosts(pool, { ...profile, is_private: true }), [])
  assert.equal(statements.length, 3)
  assert.doesNotMatch(profileShareHtml({ ...profile, is_private: true }, 'https://example.invalid', posts),
    /Hola desde Novvin/)
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
