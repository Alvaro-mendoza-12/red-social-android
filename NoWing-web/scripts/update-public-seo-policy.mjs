// Run deliberately when public metadata changes; the build verifies these hashes.
import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { createRequire } from 'node:module'
import ts from 'typescript'
import { publicRoutes, structuredData } from './public-seo.mjs'
const root = path.resolve(import.meta.dirname, '..'), require = createRequire(import.meta.url)
const code = ts.transpileModule(fs.readFileSync(path.join(root, 'src/ui/novvin/publicInfoPages.ts'), 'utf8'), { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const module = { exports: {} }
new Function('require', 'module', 'exports', code)(require, module, module.exports)
const home = { title: 'Novvin Web — mensajes, videos y comunidades', description: 'Novvin es la red social para conversar, descubrir videos y compartir con tu gente. Entra a Novvin Web desde tu computadora, tablet o móvil con tu misma cuenta.' }
const hashes = publicRoutes.map(([route,,label]) => {
  const page = route === '/' ? home : module.exports.publicInfoPage(route)
  return "'sha256-" + createHash('sha256').update(structuredData(route,page.title,page.description,label)).digest('base64') + "'"
})
for (const file of [path.join(root, 'vercel.json'), path.join(root, '../vercel.json')]) {
  const config = JSON.parse(fs.readFileSync(file,'utf8'))
  const csp = config.headers[0].headers.find(header => header.key === 'Content-Security-Policy')
  csp.value = csp.value.replace(/script-src [^;]+;/, "script-src 'self' " + hashes.join(' ') + ';')
  fs.writeFileSync(file, JSON.stringify(config,null,2) + '\n')
}
console.log('Structured data hashes updated for the public pages. No broad inline script permission added.')
