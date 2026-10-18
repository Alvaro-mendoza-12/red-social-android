// Notify participating search engines only after a changed public page is deployed.
import { publicRoutes, site } from './public-seo.mjs'

const routes = [...new Set(process.argv.slice(2))]
const allowed = new Set(publicRoutes.map(([route]) => route))
if (!routes.length || routes.some(route => !allowed.has(route))) {
  throw new Error('Pass changed canonical public routes, for example /salas-de-voz.')
}
const keyLocation = site + '/indexnow-key.txt'
const keyResponse = await fetch(keyLocation, { signal: AbortSignal.timeout(15000) })
if (!keyResponse.ok) throw new Error('The published IndexNow key is unavailable.')
const key = (await keyResponse.text()).trim()
if (!/^[a-zA-Z0-9-]{8,128}$/.test(key)) throw new Error('The published IndexNow key is invalid.')
const urlList = routes.map(route => site + route)
for (const url of urlList) {
  const page = await fetch(url, { signal: AbortSignal.timeout(15000) })
  if (!page.ok || page.url !== url) throw new Error('The changed canonical page is unavailable: ' + url)
}
const result = await fetch('https://api.indexnow.org/indexnow', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json; charset=utf-8' },
  body: JSON.stringify({ host: new URL(site).host, key, keyLocation, urlList }),
  signal: AbortSignal.timeout(20000),
})
if (result.status !== 200 && result.status !== 202) throw new Error('IndexNow returned HTTP ' + result.status)
console.log(JSON.stringify({ submitted: true, status: result.status, urlList,
  note: 'Receipt confirms notification only; indexing and ranking remain search-engine decisions.' }))
