// Run deliberately when public metadata changes; the build verifies these hashes.
import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { publicRoutes } from './public-seo.mjs'
import { loadPublicPageModel } from './public-page-model.mjs'
import { publicPageFingerprint, refreshSitemapState, renderPublicSitemap } from './public-sitemap.mjs'
const root = path.resolve(import.meta.dirname, '..')
const { pages } = loadPublicPageModel(root)
for (const page of pages) page.contentHash = publicPageFingerprint(page, path.join(root, 'public'))
const hashes = pages.map(page => "'sha256-" + createHash('sha256').update(page.data).digest('base64') + "'")
const statePath = path.join(root, 'scripts/public-seo-state.json')
const previousState = fs.existsSync(statePath) ? JSON.parse(fs.readFileSync(statePath, 'utf8')) : null
const sitemapPath = path.join(root, 'public/sitemap.xml')
const previous = fs.existsSync(sitemapPath) ? fs.readFileSync(sitemapPath, 'utf8') : ''
const initialDates = new Map([...previous.matchAll(/<url>([\s\S]*?)<\/url>/g)].map(([, entry]) => [/<loc>(.*?)<\/loc>/.exec(entry)?.[1], /<lastmod>(.*?)<\/lastmod>/.exec(entry)?.[1]]))
const date = process.argv.find(arg => arg.startsWith('--date='))?.slice(7) || new Date().toISOString().slice(0, 10)
const updated = (process.argv.find(arg => arg.startsWith('--updated='))?.slice(10) || '').split(',').filter(Boolean)
if (updated.some(route => !publicRoutes.some(([known]) => known === route))) throw new Error('Unknown changed public route')
const state = refreshSitemapState(pages, previousState, { date, initialDates })
const sitemap = renderPublicSitemap(pages, state)
for (const file of [path.join(root, 'vercel.json'), path.join(root, '../vercel.json')]) {
  const config = JSON.parse(fs.readFileSync(file,'utf8'))
  const csp = config.headers.find(rule => rule.source === '/(.*)')?.headers.find(header => header.key === 'Content-Security-Policy')
  if (!csp) throw new Error('Global CSP header not found')
  csp.value = csp.value.replace(/script-src [^;]+;/, "script-src 'self' " + hashes.join(' ') + ';')
  for (const [route, file] of publicRoutes) {
    if (!config.rewrites.some(rule => rule.source === route)) config.rewrites.push({source:route,destination:'/'+file})
    if (!config.redirects.some(rule => rule.source === '/'+file)) config.redirects.push({source:'/'+file,destination:route,permanent:true})
    // Require the final slash explicitly; do not normalize API/socket paths globally.
    if (route !== '/' && !config.redirects.some(rule => rule.source === route + '(/)')) config.redirects.push({source:route+'(/)',destination:route,permanent:true})
  }
  fs.writeFileSync(file, JSON.stringify(config,null,2) + '\n')
}
fs.writeFileSync(statePath, JSON.stringify(state, null, 2) + '\n')
fs.writeFileSync(sitemapPath, sitemap)
console.log('Public content fingerprints, truthful lastmod and CSP hashes updated. Unchanged pages retain their dates.')
