// Render the same React home component for visitors and search crawlers.
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import ts from 'typescript'
import { renderToStaticMarkup } from 'react-dom/server'
import React from 'react'

const require = createRequire(import.meta.url)
const root = path.resolve(import.meta.dirname, '..')
const code = ts.transpileModule(
  fs.readFileSync(path.join(root, 'src/ui/novvin/PublicHome.tsx'), 'utf8'),
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
const html = fs.readFileSync(path.join(root, 'dist/index.html'), 'utf8')
const page = renderToStaticMarkup(React.createElement(module.exports.PublicHome))
const home = html.replace('<div id="root"></div>', `<div id="root">${page}</div>`)
if (home === html || !home.includes('N-O-V-V-I-N')) throw new Error('Public home prerender failed')
fs.writeFileSync(path.join(root, 'dist/app-shell.html'), html)
fs.writeFileSync(path.join(root, 'dist/index.html'), home)
console.log('Novvin public home rendered; private routes keep the empty app shell.')
