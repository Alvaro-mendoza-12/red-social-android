// Public HTML is complete for visitors and crawlers, without authentication or JS.
import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { loadPublicPageModel } from './public-page-model.mjs'
import { publicPageFingerprint, renderPublicSitemap } from './public-sitemap.mjs'

const root = path.resolve(import.meta.dirname, '..')
const { pages, notFoundContent } = loadPublicPageModel(root)
for (const page of pages) page.contentHash = publicPageFingerprint(page, path.join(root, 'public'))
const state = JSON.parse(fs.readFileSync(path.join(root, 'scripts/public-seo-state.json'), 'utf8'))
const sitemap = renderPublicSitemap(pages, state)
const html = fs.readFileSync(path.join(root, 'dist/index.html'), 'utf8')
if (!html.includes('<div id="root"></div>')) {
  throw new Error('Public prerender requires a fresh Vite entry. Run npm run build to regenerate it.')
}
const escape = value => value.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
const schemaPattern = /\s*<script type="application\/ld\+json">[\s\S]*?<\/script>/g
const publicCSS = fs.readFileSync(path.join(root, 'src/ui/novvin/public-home.css'), 'utf8')
const cssPath = 'css/public-' + createHash('sha256').update(publicCSS).digest('hex').slice(0, 12) + '.css'
fs.mkdirSync(path.join(root, 'dist/css'), { recursive: true })
fs.writeFileSync(path.join(root, 'dist', cssPath), publicCSS)
// Private routes keep their lazy application entry and explicitly cannot be indexed.
fs.writeFileSync(path.join(root, 'dist/app-shell.html'), html.replace(schemaPattern, '').replace('</head>', '<meta name="robots" content="noindex, follow" /></head>'))
const template = html.replace(/\s*<script\b[^>]*type="module"[^>]*>[\s\S]*?<\/script>/g, '').replace(/\s*<link\b[^>]*rel="(?:modulepreload|stylesheet)"[^>]*>/g, '').replace(schemaPattern, '').replace('</head>', `<link rel="stylesheet" href="/${cssPath}" /></head>`)
const metadata = []
for (const info of pages) {
  const { route, file, url, data, content, contentHash } = info
  let page = template.replace(/<title>.*?<\/title>/, `<title>${escape(info.title)}</title>`)
  for (const key of ['description', 'og:description', 'twitter:description', 'og:title', 'twitter:title']) {
    const value = key.endsWith('title') ? info.title : info.description
    page = page.replace(new RegExp(`(<meta (?:name|property)="${key}" content=")[^"]*(")`), (_, before, after) => before + escape(value) + after)
  }
  page = page.replace(/(<link rel="canonical" href=")[^"]*(")/, (_, before, after) => before + url + after).replace(/(<meta property="og:url" content=")[^"]*(")/, (_, before, after) => before + url + after)
  const hash = createHash('sha256').update(data).digest('base64')
  metadata.push({ route, file, url, title: info.title, description: info.description, schemaHash: hash, contentHash, lastmod: state.routes[route].lastmod })
  page = page.replace('</head>', `<meta name="robots" content="index, follow, max-image-preview:large" /><script type="application/ld+json">${data}</script></head>`)
  page = page.replace('<div id="root"></div>', `<div id="root">${content}</div>`)
  if (!page.includes('<h1>') || /<script[^>]+type="module"/.test(page)) throw new Error('Incomplete static public page')
  fs.writeFileSync(path.join(root, 'dist', file), page)
}
const notFound = template.replace(/<title>.*?<\/title>/, '<title>Página no encontrada · Novvin</title>').replace(/\s*<link rel="canonical"[^>]*>/, '').replace('</head>', '<meta name="robots" content="noindex, follow" /></head>').replace('<div id="root"></div>', `<div id="root">${notFoundContent}</div>`)
fs.writeFileSync(path.join(root, 'dist/404.html'), notFound)
// A missing or outdated CSP hash is a build failure, rather than a silent SEO defect.
for (const configPath of [path.join(root, 'vercel.json'), path.join(root, '../vercel.json')]) {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'))
  const csp = config.headers.flatMap(rule => rule.headers).find(header => header.key === 'Content-Security-Policy')?.value || ''
  for (const page of metadata) if (!csp.includes(`'sha256-${page.schemaHash}'`)) throw new Error('CSP must allow the structured data for ' + page.route)
}
fs.writeFileSync(path.join(root, 'dist/seo-build.json'), JSON.stringify({ publicPages: metadata, publicCSS: cssPath, publicJavaScriptBytes: 0 }, null, 2))
fs.writeFileSync(path.join(root, 'dist/sitemap.xml'), sitemap)
console.log('Novvin public home rendered; private routes keep the empty app shell.')
console.log('Public pages are readable without JavaScript; private routes and 404 remain noindex.')
