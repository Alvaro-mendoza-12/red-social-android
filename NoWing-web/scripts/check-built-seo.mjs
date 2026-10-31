import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { createHash } from 'node:crypto'
import { site, publicRoutes } from './public-seo.mjs'

const assert = (value, message) => { if (!value) throw new Error(message) }
const meta = (html, name) => new RegExp('<meta (?:name|property)="' + name + '" content="([^"]*)"', 'i').exec(html)?.[1]
const matches = (source, route) => new RegExp('^(?:' + source.replace(/:[a-zA-Z]+\*/g, '.*') + ')$').test(route)

export function robotsAllows(text, pathname, agent = 'Googlebot') {
  const groups = []; let group = { agents: [], rules: [] }
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/#.*/, '').trim(), match = /^([^:]+):\s*(.*)$/.exec(line)
    if (!match) continue
    const [, field, value] = match
    if (field.toLowerCase() === 'user-agent') {
      if (group.rules.length) { groups.push(group); group = { agents: [], rules: [] } }
      group.agents.push(value.toLowerCase())
    } else if (/^(allow|disallow)$/i.test(field) && value) group.rules.push({ allow: field.toLowerCase() === 'allow', pattern: value })
  }
  groups.push(group)
  const specific = groups.filter(item => item.agents.some(name => name !== '*' && agent.toLowerCase().includes(name)))
  const applicable = specific.length ? specific : groups.filter(item => item.agents.includes('*'))
  const rules = applicable.flatMap(item => item.rules).filter(rule => {
    const expression = rule.pattern.split('*').map(part => part.replace(/[.+?^{}()|[\]\\]/g, '\\$&')).join('.*').replace(/\$$/, '$')
    return new RegExp('^' + expression).test(pathname)
  }).sort((a, b) => b.pattern.replaceAll('*', '').length - a.pattern.replaceAll('*', '').length || Number(b.allow) - Number(a.allow))
  return !rules.length || rules[0].allow
}

export function auditBuiltSeo({ read, exists, configs }) {
  const robots = read('robots.txt'), sitemap = read('sitemap.xml'), manifest = JSON.parse(read('seo-build.json'))
  const urls = [...sitemap.matchAll(/<loc>(.*?)<\/loc>/g)].map(match => match[1])
  assert(JSON.stringify(urls) === JSON.stringify(publicRoutes.map(([route]) => site + route)), 'Sitemap includes missing, duplicate, private or noncanonical routes')
  assert(robots.includes('Sitemap: ' + site + '/sitemap.xml'), 'Robots is missing the canonical sitemap')
  const titles = new Set(), descriptions = new Set()
  for (const [route, file] of publicRoutes) {
    const html = read(file), canonical = site + route
    assert(/<html lang="es"/.test(html) && (html.match(/<h1>/g) || []).length === 1, 'Incomplete Spanish public HTML: ' + route)
    assert((html.match(/rel="canonical"/g) || []).length === 1 && html.includes('rel="canonical" href="' + canonical + '"'), 'Canonical mismatch: ' + route)
    assert(meta(html, 'robots')?.startsWith('index, follow') && !/noindex|none/i.test(meta(html, 'robots')), 'Public HTML is noindex: ' + route)
    const title = /<title>(.*?)<\/title>/.exec(html)?.[1], description = meta(html, 'description')
    assert(title && description && !titles.has(title) && !descriptions.has(description), 'Missing or duplicate metadata: ' + route)
    titles.add(title); descriptions.add(description)
    assert(meta(html, 'og:url') === canonical && meta(html, 'og:title') === title && meta(html, 'twitter:title') === title, 'Shared metadata mismatch: ' + route)
    assert(!/<script[^>]*(?:src=|type="module")|rel="modulepreload"/.test(html), 'Public content depends on app JavaScript: ' + route)
    const data = /<script type="application\/ld\+json">(.*?)<\/script>/.exec(html)?.[1]
    assert(data && JSON.parse(data)['@graph']?.some(node => node['@type'] === 'WebPage' && node.url === canonical), 'Invalid public structured data: ' + route)
    const hash = "'sha256-" + createHash('sha256').update(data).digest('base64') + "'"
    for (const config of configs) {
      assert(config.rewrites.some(rule => rule.source === route && rule.destination === '/' + file), 'Public route rewrite missing: ' + route)
      assert(config.redirects.some(rule => rule.source === '/' + file && rule.destination === route && rule.permanent), 'Canonical redirect missing: ' + file)
      if (route !== '/') assert(config.redirects.some(rule => rule.source === route + '(/)' && rule.destination === route && rule.permanent), 'Trailing-slash canonical redirect missing: ' + route)
      const headers = config.headers.filter(rule => matches(rule.source, route) || matches(rule.source, '/' + file)).flatMap(rule => rule.headers)
      assert(!headers.some(header => header.key.toLowerCase() === 'x-robots-tag' && /noindex|none/i.test(header.value)), 'Public HTTP header is noindex: ' + route)
      assert(headers.some(header => header.key.toLowerCase() === 'content-security-policy' && header.value.includes(hash)), 'Structured data blocked by CSP: ' + route)
    }
    const assets = [...html.matchAll(/(?:src|href)="(\/[^"?#]+\.(?:css|png|webp|svg|ico))"/g)].map(match => match[1])
    for (const asset of assets) assert(exists(asset.slice(1)), 'Missing public asset: ' + asset)
    for (const agent of ['Googlebot', 'bingbot', 'DuckDuckBot', 'YandexBot', 'OAI-SearchBot']) {
      for (const resource of [route, ...assets]) assert(robotsAllows(robots, resource, agent), 'Robots blocks ' + agent + ': ' + resource)
    }
    assert(manifest.publicPages.some(page => page.route === route && page.url === canonical && page.lastmod && page.contentHash), 'Build manifest misses public content state: ' + route)
  }
  const shell = read('app-shell.html'), notFound = read('404.html')
  assert(/noindex/i.test(meta(shell, 'robots')) && /type="module"/.test(shell), 'Interactive app shell was removed or made indexable')
  assert(/noindex/i.test(meta(notFound, 'robots')) && !/rel="canonical"/.test(notFound), '404 is indexable or points to home')
  for (const config of configs) {
    assert(config.rewrites.some(rule => rule.source === '/post/:path*' && rule.destination === '/app-shell.html'), 'Shared publication route cannot load the application')
    assert(!config.rewrites.some(rule => ['/(.*)', '/:path*'].includes(rule.source)), 'Unknown URLs would become soft 404s')
    for (const privateRoute of ['/app/messages', '/explore/videos', '/u/example', '/group/example', '/community/example', '/post/12345678']) {
      const headers = config.headers.filter(rule => matches(rule.source, privateRoute)).flatMap(rule => rule.headers)
      assert(headers.some(header => header.key.toLowerCase() === 'x-robots-tag' && /noindex/i.test(header.value)), 'Private route lost noindex: ' + privateRoute)
    }
  }
  return { passed: true, publicPages: publicRoutes.length, publicJavaScriptBytes: 0, checks: ['HTML', 'metadata', 'CSP', 'robots', 'sitemap', 'assets', 'canonical routes', 'private shell', '404'] }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const root = path.resolve(import.meta.dirname, '..'), directory = path.join(root, 'dist')
  try {
    console.log(JSON.stringify(auditBuiltSeo({ read: file => fs.readFileSync(path.join(directory, file), 'utf8'), exists: file => fs.existsSync(path.join(directory, file)), configs: ['vercel.json', '../vercel.json'].map(file => JSON.parse(fs.readFileSync(path.join(root, file), 'utf8'))) })))
  } catch (error) { console.error('SEO release gate failed: ' + error.message); process.exitCode = 1 }
}
