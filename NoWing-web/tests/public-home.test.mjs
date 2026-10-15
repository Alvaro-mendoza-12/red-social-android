import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createHash } from 'node:crypto'
import path from 'node:path'

const root = path.resolve(import.meta.dirname, '..')
test('public home is readable without JavaScript and points to real access routes', () => {
  const html = fs.readFileSync(path.join(root, 'dist/index.html'), 'utf8')
  assert.match(html, /<h1>Novvin/)
  assert.match(html, /href="\/login"/)
  assert.match(html, /href="\/register"/)
  assert.match(html, /N-O-V-V-I-N/)
  assert.match(html, /Novvyn/)
  assert.match(html, /NoWin/)
  assert.match(html, /rel="canonical" href="https:\/\/red-social-android\.vercel\.app\/"/)
  assert.doesNotMatch(html, /name="keywords"/)
  assert.doesNotMatch(html, /accessToken|access_token/)
})
test('private SPA shell does not contain public home markup', () => {
  const html = fs.readFileSync(path.join(root, 'dist/app-shell.html'), 'utf8')
  assert.match(html, /<div id="root"><\/div>/)
  assert.doesNotMatch(html, /<h1>Novvin/)
})
test('only the public home and public guides are advertised in the sitemap', () => {
  const sitemap = fs.readFileSync(path.join(root, 'dist/sitemap.xml'), 'utf8')
  assert.match(sitemap, /<loc>https:\/\/red-social-android\.vercel\.app\/<\/loc>/)
  assert.equal((sitemap.match(/<loc>/g) || []).length, 6)
  assert.match(sitemap, /\/novvin-web<\/loc>/)
  assert.match(sitemap, /\/ayuda\/vincular-web<\/loc>/)
  assert.doesNotMatch(sitemap, /\/app\/|\/login|\/profile/)
  const config = JSON.parse(fs.readFileSync(path.join(root, 'vercel.json'), 'utf8'))
  assert.deepEqual(config.rewrites[0], { source: '/', destination: '/index.html' })
  assert.ok(
    config.headers.some(
      (rule) =>
        rule.source === '/app/:path*' &&
        rule.headers.some((h) => h.key === 'X-Robots-Tag' && h.value.includes('noindex')),
    ),
  )
})

for (const [route, file] of [['/novvin-web', 'novvin-web.html'], ['/ayuda/vincular-web', 'vincular-web.html'], ['/mensajes', 'mensajes.html'], ['/videos', 'videos.html'], ['/salas-de-voz', 'salas-de-voz.html']]) {
  test(`guide ${route} has readable HTML and its own canonical`, () => {
    const html = fs.readFileSync(path.join(root, 'dist', file), 'utf8')
    assert.match(html, /<h1>/)
    assert.match(html, /href="\/login"/)
    assert.ok(html.includes(`rel="canonical" href="https://red-social-android.vercel.app${route}"`))
    assert.doesNotMatch(html, /noindex|access_token|accessToken/)
    const config = JSON.parse(fs.readFileSync(path.join(root, 'vercel.json'), 'utf8'))
    assert.ok(config.rewrites.some(rule => rule.source === route && rule.destination === '/' + file))
  })
}

test('site name schema is valid and permitted by the content security policy', () => {
  const html = fs.readFileSync(path.join(root, 'dist/index.html'), 'utf8')
  const data = /<script type="application\/ld\+json">(.*?)<\/script>/.exec(html)?.[1]
  assert.ok(data)
  const schema = JSON.parse(data)
  assert.equal(schema.name, 'Novvin')
  assert.equal(schema.url, 'https://red-social-android.vercel.app/')
  assert.equal(schema.publisher.name, 'Novvin')
  assert.equal(schema.publisher.logo.url, 'https://red-social-android.vercel.app/novvin-logo.png')
  const sha = createHash('sha256').update(data).digest('base64')
  const config = JSON.parse(fs.readFileSync(path.join(root, 'vercel.json'), 'utf8'))
  assert.ok(
    config.headers[0].headers
      .find((h) => h.key === 'Content-Security-Policy')
      .value.includes(`'sha256-${sha}'`),
  )
})
