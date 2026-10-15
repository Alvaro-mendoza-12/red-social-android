// Render the same React home component for visitors and search crawlers.
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import ts from 'typescript'
import { renderToStaticMarkup } from 'react-dom/server'
import React from 'react'

const require = createRequire(import.meta.url)
const root = path.resolve(import.meta.dirname, '..')
function componentModule(file) {
const code = ts.transpileModule(
  fs.readFileSync(path.join(root, file), 'utf8'),
  {
    compilerOptions: {
      jsx: ts.JsxEmit.ReactJSX,
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  },
).outputText
// Compile in memory so no transient source or dependency files enter the deployment.
const module = { exports: {} }
new Function('require', 'module', 'exports', code)(require, module, module.exports)
return module.exports
}
const homeModule = componentModule('src/ui/novvin/PublicHome.tsx')
const infoModule = componentModule('src/ui/novvin/PublicInfo.tsx')
const html = fs.readFileSync(path.join(root, 'dist/index.html'), 'utf8')
const page = renderToStaticMarkup(React.createElement(homeModule.PublicHome))
const home = html.replace('<div id="root"></div>', `<div id="root">${page}</div>`)
if (home === html || !home.includes('N-O-V-V-I-N')) throw new Error('Public home prerender failed')
fs.writeFileSync(path.join(root, 'dist/app-shell.html'), html)
fs.writeFileSync(path.join(root, 'dist/index.html'), home)
const escape = value => value.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
for (const [route, file] of [['/novvin-web', 'novvin-web.html'], ['/ayuda/vincular-web', 'vincular-web.html']]) {
  const info = infoModule.publicInfoPage(route)
  let template = html.replace(/<title>.*?<\/title>/, `<title>${escape(info.title)}</title>`)
  for (const key of ['description', 'og:description', 'twitter:description', 'og:title', 'twitter:title']) {
    const value = key.endsWith('title') ? info.title : info.description
    template = template.replace(new RegExp(`(<meta (?:name|property)="${key}" content=")[^"]*(")`), (_, before, after) => before + escape(value) + after)
  }
  template = template.replace(/(<link rel="canonical" href=")[^"]*(")/, (_, before, after) => before + 'https://red-social-android.vercel.app' + route + after)
  template = template.replace(/(<meta property="og:url" content=")[^"]*(")/, (_, before, after) => before + 'https://red-social-android.vercel.app' + route + after)
  const content = renderToStaticMarkup(React.createElement(infoModule.PublicInfo, { path: route }))
  fs.writeFileSync(path.join(root, 'dist', file), template.replace('<div id="root"></div>', `<div id="root">${content}</div>`))
}
console.log('Novvin public home rendered; private routes keep the empty app shell.')
