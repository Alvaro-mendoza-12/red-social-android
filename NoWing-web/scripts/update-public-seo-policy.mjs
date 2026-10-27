// Run deliberately when public metadata changes; the build verifies these hashes.
import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { createRequire } from 'node:module'
import ts from 'typescript'
import { publicRoutes, structuredData, site, homeMetadata } from './public-seo.mjs'
const root = path.resolve(import.meta.dirname, '..'), require = createRequire(import.meta.url)
const code = ts.transpileModule(fs.readFileSync(path.join(root, 'src/ui/novvin/publicInfoPages.ts'), 'utf8'), { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const module = { exports: {} }
new Function('require', 'module', 'exports', code)(require, module, module.exports)
const home = homeMetadata
const hashes = publicRoutes.map(([route,,label]) => {
  const page = route === '/' ? home : module.exports.publicInfoPage(route)
  return "'sha256-" + createHash('sha256').update(structuredData(route,page.title,page.description,label,module.exports.publicIllustrations[route] || [])).digest('base64') + "'"
})
for (const file of [path.join(root, 'vercel.json'), path.join(root, '../vercel.json')]) {
  const config = JSON.parse(fs.readFileSync(file,'utf8'))
  const csp = config.headers.find(rule => rule.source === '/(.*)')?.headers.find(header => header.key === 'Content-Security-Policy')
  if (!csp) throw new Error('Global CSP header not found')
  csp.value = csp.value.replace(/script-src [^;]+;/, "script-src 'self' " + hashes.join(' ') + ';')
  for (const [route, file] of publicRoutes) {
    if (!config.rewrites.some(rule => rule.source === route)) config.rewrites.push({source:route,destination:'/'+file})
    if (!config.redirects.some(rule => rule.source === '/'+file)) config.redirects.push({source:'/'+file,destination:route,permanent:true})
  }
  fs.writeFileSync(file, JSON.stringify(config,null,2) + '\n')
}
const sitemapPath = path.join(root, 'public/sitemap.xml')
const previous = fs.readFileSync(sitemapPath,'utf8')
const previousDates = new Map([...previous.matchAll(/<url>([\s\S]*?)<\/url>/g)].map(([, entry]) => [/<loc>(.*?)<\/loc>/.exec(entry)?.[1], /<lastmod>(.*?)<\/lastmod>/.exec(entry)?.[1]]))
const updated = new Set((process.argv.find(arg=>arg.startsWith('--updated='))?.slice(10)||'').split(','))
const date = process.argv.find(arg=>arg.startsWith('--date='))?.slice(7) || new Date().toISOString().slice(0,10)
if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('Invalid sitemap date')
const escapeXML = value => value.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;')
const entries = publicRoutes.map(([route])=>{
  const url=site+route, modified=updated.has(route)?date:previousDates.get(url)||date
  const images=(module.exports.publicIllustrations[route]||[]).map(image=>`<image:image><image:loc>${escapeXML(site+image.src)}</image:loc></image:image>`).join('')
  return `  <url><loc>${escapeXML(url)}</loc><lastmod>${modified}</lastmod>${images}</url>`
})
fs.writeFileSync(sitemapPath,'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">\n'+entries.join('\n')+'\n</urlset>\n')
console.log('Structured data hashes updated for the public pages. No broad inline script permission added.')
