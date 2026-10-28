import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import ts from 'typescript'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { publicRoutes, site, structuredData, homeMetadata } from './public-seo.mjs'

// Build-only model: visitors and crawlers use the same existing components.
export function loadPublicPageModel(root) {
  const require = createRequire(import.meta.url)
  const dependencies = {}
  function componentModule(file) {
    const code = ts.transpileModule(fs.readFileSync(path.join(root, file), 'utf8'), {
      compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    }).outputText
    const module = { exports: {} }
    new Function('require', 'module', 'exports', code)(id => dependencies[id] || require(id), module, module.exports)
    return module.exports
  }
  const info = dependencies['./publicInfoPages'] = componentModule('src/ui/novvin/publicInfoPages.ts')
  dependencies['./publicNavigationData'] = componentModule('src/ui/novvin/publicNavigationData.ts')
  dependencies['./PublicNavigation'] = componentModule('src/ui/novvin/PublicNavigation.tsx')
  const { PublicHome } = componentModule('src/ui/novvin/PublicHome.tsx')
  const { PublicInfo } = componentModule('src/ui/novvin/PublicInfo.tsx')
  const { NotFoundPage } = componentModule('src/ui/novvin/NotFoundPage.tsx')
  const pages = publicRoutes.map(([route, file, label]) => {
    const metadata = route === '/' ? homeMetadata : info.publicInfoPage(route)
    if (!metadata) throw new Error('Missing public metadata: ' + route)
    const images = info.publicIllustrations[route] || []
    return {
      route, file, label, title: metadata.title, description: metadata.description, images,
      url: site + route,
      data: structuredData(route, metadata.title, metadata.description, label, images),
      content: renderToStaticMarkup(route === '/' ? React.createElement(PublicHome) : React.createElement(PublicInfo, { path: route })),
    }
  })
  return { pages, notFoundContent: renderToStaticMarkup(React.createElement(NotFoundPage)) }
}
