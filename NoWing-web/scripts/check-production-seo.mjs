// Read-only checks against the deployed site. This does not assert search ranking.
import { createHash } from 'node:crypto'
import { publicRoutes, site } from './public-seo.mjs'

const checks = []
async function request(path) {
  return fetch(site + path, { redirect: 'manual', headers: { 'User-Agent': 'Novvin-owned-production-SEO-audit/9.0.40', 'Cache-Control': 'no-cache' }, signal: AbortSignal.timeout(20000) })
}
async function check(name, run) {
  try { await run(); checks.push({ name, passed: true }) }
  catch (error) { checks.push({ name, passed: false, error: String(error.message).slice(0, 300) }) }
}
function assert(value, message) { if (!value) throw new Error(message) }
const metas = (html, name) => new RegExp('<meta (?:name|property)="' + name + '" content="([^"]*)"').exec(html)?.[1]
const titles = new Set(), descriptions = new Set()
for (const [route, , label] of publicRoutes) {
  await check('Public HTML, canonical, schema and security: ' + route, async () => {
    const response = await request(route), html = await response.text(), canonical = site + route
    assert(response.status === 200, 'Expected HTTP 200')
    assert(response.headers.get('content-type')?.startsWith('text/html'), 'Expected HTML')
    assert(!response.headers.get('x-robots-tag')?.includes('noindex'), 'Public response is noindex')
    assert(metas(html, 'robots')?.startsWith('index, follow'), 'Missing crawl instructions')
    assert(html.includes('rel="canonical" href="' + canonical + '"'), 'Canonical mismatch')
    assert(metas(html, 'og:url') === canonical, 'Open Graph URL mismatch')
    assert((html.match(/<h1>/g) || []).length === 1, 'Expected one primary heading')
    assert(!/<script[^>]*(?:type="module"|src=)|rel="modulepreload"/.test(html), 'Public page loads application JavaScript')
    assert(html.includes('href="/login"') && html.includes('href="/mensajes"'), 'Missing useful internal links')
    const title = /<title>(.*?)<\/title>/.exec(html)?.[1], description = metas(html, 'description')
    assert(title && description && !titles.has(title) && !descriptions.has(description), 'Missing or duplicate metadata')
    titles.add(title); descriptions.add(description)
    const raw = /<script type="application\/ld\+json">(.*?)<\/script>/.exec(html)?.[1]
    assert(raw, 'Missing structured data')
    const graph = JSON.parse(raw)['@graph'], page = graph.find(node => node['@type'] === 'WebPage')
    assert(page?.url === canonical && page?.name === title, 'Page schema mismatch')
    const website = graph.find(node => node['@type'] === 'WebSite')
    assert(website?.name === 'Novvin' && metas(html, 'og:site_name') === 'Novvin', 'Site name mismatch')
    assert(JSON.stringify(website.alternateName) === JSON.stringify(['Novvin Web', new URL(site).hostname]), 'Site name fallback mismatch')
    if (route !== '/') assert(graph.find(node => node['@type'] === 'BreadcrumbList')?.itemListElement.at(-1)?.name === label, 'Missing breadcrumbs')
    const csp = response.headers.get('content-security-policy') || ''
    assert(csp.includes("'sha256-" + createHash('sha256').update(raw).digest('base64') + "'"), 'Structured data blocked by CSP')
    assert(!/script-src[^;]*'unsafe-inline'/.test(csp), 'Unsafe inline script policy')
    for (const header of ['x-content-type-options', 'strict-transport-security', 'referrer-policy', 'x-frame-options']) assert(response.headers.get(header), 'Missing ' + header)
    const css = /<link rel="stylesheet" href="([^"]+)"/.exec(html)?.[1]
    assert(css?.startsWith('/css/public-'), 'Public CSS missing')
    const style = await request(css)
    assert(style.status === 200 && style.headers.get('content-type')?.includes('text/css'), 'Invalid public stylesheet')
    assert((await style.arrayBuffer()).byteLength < 15000, 'Public stylesheet budget exceeded')
  })
}
await check('Robots and canonical sitemap', async () => {
  const robots = await request('/robots.txt'), sitemap = await request('/sitemap.xml')
  assert(robots.status === 200 && (await robots.text()).includes('Sitemap: ' + site + '/sitemap.xml'), 'Robots sitemap missing')
  const urls = [...(await sitemap.text()).matchAll(/<loc>(.*?)<\/loc>/g)].map(item => item[1])
  assert(sitemap.status === 200 && JSON.stringify(urls) === JSON.stringify(publicRoutes.map(([route]) => site + route)), 'Sitemap contains missing, private or noncanonical URLs')
})
for (const path of ['/no-existe-produccion-9038', '/js/no-existe-9038.js', '/icons/no-existe-9038.png']) {
  await check('Unknown URL returns 404: ' + path, async () => assert((await request(path)).status === 404, 'Missing URL did not return HTTP 404'))
}
for (const [route, file] of publicRoutes) {
  await check('Canonical redirect: /' + file, async () => {
    const response = await request('/' + file)
    assert([301, 308].includes(response.status), 'Expected permanent redirect')
    assert(new URL(response.headers.get('location'), site).href === site + route, 'Incorrect canonical destination')
  })
}
for (const path of ['/login', '/register', '/forgot-password', '/app/messages', '/app/videos', '/explore/videos', '/u/example']) {
  await check('Private or interactive shell is noindex: ' + path, async () => {
    const response = await request(path), html = await response.text()
    assert(response.status === 200 && response.headers.get('x-robots-tag')?.includes('noindex'), 'Missing noindex header')
    assert(metas(html, 'robots')?.includes('noindex') && html.includes('type="module"'), 'Missing private app shell')
    assert(!html.includes('<h1>Novvin') && !/accessToken|access_token/.test(html), 'Unexpected private HTML content')
  })
}
for (const [path, width, height] of [['/novvin-share.png', 1200, 630], ['/novvin-logo.png', 512, 512], ...[48, 96, 180, 192, 512].map(size => ['/icons/novvin-' + size + '.png', size, size])]) {
  await check('Public brand image: ' + path, async () => {
    const response = await request(path), data = Buffer.from(await response.arrayBuffer())
    assert(response.status === 200 && response.headers.get('content-type')?.includes('image/png'), 'Brand image unavailable')
    assert(data.toString('hex', 0, 8) === '89504e470d0a1a0a' && data.readUInt32BE(16) === width && data.readUInt32BE(20) === height, 'Brand image dimensions mismatch')
  })
}
await check('Favicon and truthful install manifest', async () => {
  const favicon = await request('/favicon.ico'), manifest = await request('/manifest.json')
  assert(favicon.status === 200 && Buffer.from(await favicon.arrayBuffer()).readUInt16LE(2) === 1, 'Favicon missing')
  const data = await manifest.json()
  assert(data.name === 'Novvin Web' && data.start_url === '/app/messages', 'Incorrect manifest')
  assert(!data.share_target && !data.file_handlers && !data.scope_extensions, 'Unimplemented manifest handlers advertised')
})
await check('Google verification and IndexNow public key', async () => {
  const home = await request('/'), key = await request('/indexnow-key.txt')
  assert(metas(await home.text(), 'google-site-verification'), 'Google verification missing')
  assert(key.status === 200 && /^[a-zA-Z0-9-]{8,128}$/.test((await key.text()).trim()), 'Invalid IndexNow key')
})
const report = { atUtc: new Date().toISOString(), passed: checks.every(check => check.passed), publicUrl: site, checks, indexingOrRankingGuaranteed: false, scope: 'Current deployed HTTP, public metadata, CSP, assets and private-shell exclusion. Not authenticated functionality, field Core Web Vitals, device calls or search-engine acceptance.' }
console.log(JSON.stringify(report, null, 2))
if (!report.passed) process.exitCode = 1
