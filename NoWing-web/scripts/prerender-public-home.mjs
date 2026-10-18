// Public HTML is complete for visitors and crawlers, without authentication or JS.
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { createHash } from 'node:crypto'
import ts from 'typescript'
import { renderToStaticMarkup } from 'react-dom/server'
import React from 'react'
import { publicRoutes, site, structuredData } from './public-seo.mjs'

const require = createRequire(import.meta.url), root = path.resolve(import.meta.dirname, '..')
function componentModule(file) {
  const code = ts.transpileModule(fs.readFileSync(path.join(root, file), 'utf8'), { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', code)(id => id === './publicInfoPages' ? infoData : id === './PublicNavigation' ? navigation : id === './publicNavigationData' ? navigationData : require(id), module, module.exports)
  return module.exports
}
const infoData = componentModule('src/ui/novvin/publicInfoPages.ts')
const { publicInfoPage } = infoData
const navigationData = componentModule('src/ui/novvin/publicNavigationData.ts')
const navigation = componentModule('src/ui/novvin/PublicNavigation.tsx')
const { PublicHome } = componentModule('src/ui/novvin/PublicHome.tsx')
const { PublicInfo } = componentModule('src/ui/novvin/PublicInfo.tsx')
const { NotFoundPage } = componentModule('src/ui/novvin/NotFoundPage.tsx')
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
for (const [route, file, label] of publicRoutes) {
  const info = route === '/' ? { title: 'Novvin Web — mensajes, videos y comunidades', description: 'Novvin es la red social para conversar, descubrir videos y compartir con tu gente. Entra a Novvin Web desde tu computadora, tablet o móvil con tu misma cuenta.' } : publicInfoPage(route)
  if (!info) throw new Error('Missing public route metadata')
  const url = route === '/' ? site + '/' : site + route
  let page = template.replace(/<title>.*?<\/title>/, `<title>${escape(info.title)}</title>`)
  for (const key of ['description', 'og:description', 'twitter:description', 'og:title', 'twitter:title']) {
    const value = key.endsWith('title') ? info.title : info.description
    page = page.replace(new RegExp(`(<meta (?:name|property)="${key}" content=")[^"]*(")`), (_, before, after) => before + escape(value) + after)
  }
  page = page.replace(/(<link rel="canonical" href=")[^"]*(")/, (_, before, after) => before + url + after).replace(/(<meta property="og:url" content=")[^"]*(")/, (_, before, after) => before + url + after)
  const data = structuredData(route, info.title, info.description, label)
  const hash = createHash('sha256').update(data).digest('base64')
  metadata.push({ route, file, url, title: info.title, description: info.description, schemaHash: hash })
  page = page.replace('</head>', `<meta name="robots" content="index, follow, max-image-preview:large" /><script type="application/ld+json">${data}</script></head>`)
  const content = renderToStaticMarkup(route === '/' ? React.createElement(PublicHome) : React.createElement(PublicInfo, { path: route }))
  page = page.replace('<div id="root"></div>', `<div id="root">${content}</div>`)
  if (!page.includes('<h1>') || /<script[^>]+type="module"/.test(page)) throw new Error('Incomplete static public page')
  fs.writeFileSync(path.join(root, 'dist', file), page)
}
const notFound = template.replace(/<title>.*?<\/title>/, '<title>Página no encontrada · Novvin</title>').replace(/\s*<link rel="canonical"[^>]*>/, '').replace('</head>', '<meta name="robots" content="noindex, follow" /></head>').replace('<div id="root"></div>', `<div id="root">${renderToStaticMarkup(React.createElement(NotFoundPage))}</div>`)
fs.writeFileSync(path.join(root, 'dist/404.html'), notFound)
// A missing or outdated CSP hash is a build failure, rather than a silent SEO defect.
for (const configPath of [path.join(root, 'vercel.json'), path.join(root, '../vercel.json')]) {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'))
  const csp = config.headers.flatMap(rule => rule.headers).find(header => header.key === 'Content-Security-Policy')?.value || ''
  for (const page of metadata) if (!csp.includes(`'sha256-${page.schemaHash}'`)) throw new Error('CSP must allow the structured data for ' + page.route)
}
fs.writeFileSync(path.join(root, 'dist/seo-build.json'), JSON.stringify({ publicPages: metadata, publicCSS: cssPath, publicJavaScriptBytes: 0 }, null, 2))
console.log('Novvin public home rendered; private routes keep the empty app shell.')
console.log('Public pages are readable without JavaScript; private routes and 404 remain noindex.')
