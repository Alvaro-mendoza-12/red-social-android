import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { auditBuiltSeo, robotsAllows } from '../scripts/check-built-seo.mjs'
import { refreshSitemapState, renderPublicSitemap, validateDate } from '../scripts/public-sitemap.mjs'

const root = path.resolve(import.meta.dirname, '..')
const read = file => fs.readFileSync(path.join(root, 'dist', file), 'utf8')
const configs = () => ['vercel.json', '../vercel.json'].map(file => JSON.parse(fs.readFileSync(path.join(root, file), 'utf8')))
const audit = (replace = (_, content) => content, config = configs()) => auditBuiltSeo({ read: file => replace(file, read(file)), exists: file => fs.existsSync(path.join(root, 'dist', file)), configs: config })

test('the deployable artifact passes the same offline gate used during build', () => assert.equal(audit().passed, true))
test('an accidental global HTTP noindex fails before deployment', () => {
  const config = configs()
  config[0].headers.push({ source: '/(.*)', headers: [{ key: 'X-Robots-Tag', value: 'noindex' }] })
  assert.throws(() => audit(undefined, config), /HTTP header is noindex/)
})
test('HTML noindex, wrong canonical, blocked crawling and missing routes are detected', () => {
  assert.throws(() => audit((file, content) => file === 'index.html' ? content.replace('index, follow, max-image', 'noindex, follow, max-image') : content), /HTML is noindex/)
  assert.throws(() => audit((file, content) => file === 'index.html' ? content.replace('rel="canonical" href="https://red-social-android.vercel.app/', 'rel="canonical" href="https://wrong.invalid/') : content), /Canonical mismatch/)
  assert.throws(() => audit((file, content) => file === 'robots.txt' ? 'User-agent: *\nDisallow: /\n' + content.replace('Allow: /', '') : content), /Robots blocks/)
  const config = configs(); config[0].rewrites = config[0].rewrites.filter(rule => rule.source !== '/mensajes')
  assert.throws(() => audit(undefined, config), /rewrite missing/)
})
test('private URLs or broken app navigation cannot silently pass the SEO gate', () => {
  assert.throws(() => audit((file, content) => file === 'sitemap.xml' ? content.replace('/mensajes</loc>', '/app/messages</loc>') : content), /private or noncanonical/)
  assert.throws(() => audit((file, content) => file === 'app-shell.html' ? content.replace(/type="module"/g, 'type="text/plain"') : content), /app shell/)
  assert.throws(() => audit((file, content) => file === '404.html' ? content.replace('noindex, follow', 'index, follow') : content), /404 is indexable/)
})
test('robots rules honour specific crawlers, longest rules and Allow ties', () => {
  const text = 'User-agent: *\nDisallow: /\nAllow: /images/\nUser-agent: Googlebot\nDisallow: /videos\nAllow: /videos/help\n'
  assert.equal(robotsAllows(text, '/videos', 'Googlebot'), false)
  assert.equal(robotsAllows(text, '/videos/help', 'Googlebot'), true)
  assert.equal(robotsAllows(text, '/images/a.png', 'bingbot'), true)
  assert.equal(robotsAllows('User-agent: *\nDisallow: /\nAllow: /', '/', 'bingbot'), true)
})
const page = { route: '/', file: 'index.html', url: 'https://red-social-android.vercel.app/', images: [], contentHash: 'first' }
test('lastmod stays stable on rebuilds and changes only with meaningful content', () => {
  const initial = refreshSitemapState([page], null, { date: '2026-10-26' })
  const unchanged = refreshSitemapState([page], initial, { date: '2026-10-27' })
  assert.deepEqual(initial, unchanged)
  assert.equal(refreshSitemapState([{ ...page, contentHash: 'second' }], initial, { date: '2026-10-27' }).routes['/'].lastmod, '2026-10-27')
  assert.throws(() => renderPublicSitemap([{ ...page, contentHash: 'second' }], initial), /content changed/)
})
test('stale, duplicate, noncanonical, future and oversized sitemap data fails closed', () => {
  const state = refreshSitemapState([page], null, { date: '2026-10-26' })
  assert.throws(() => renderPublicSitemap([page, page], state), /duplicate/)
  assert.throws(() => renderPublicSitemap([{ ...page, url: page.url + '?token=secret' }], state), /Noncanonical/)
  assert.throws(() => renderPublicSitemap([page], state, { maximumBytes: 10 }), /size/)
  assert.throws(() => validateDate('2026-02-30'), /Invalid/)
  assert.throws(() => validateDate('2099-01-01'), /future/)
})
