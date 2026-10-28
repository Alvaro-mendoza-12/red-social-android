// Local-only test server. It never proxies requests or changes a deployment.
import fs from 'node:fs'
import path from 'node:path'
import http from 'node:http'
import { pathToFileURL } from 'node:url'

const root = path.resolve(import.meta.dirname, '..')
const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.json': 'application/json', '.xml': 'application/xml', '.txt': 'text/plain', '.png': 'image/png', '.svg': 'image/svg+xml', '.webp': 'image/webp', '.ico': 'image/x-icon' }
export function createSeoCandidateServer(directory = path.join(root, 'dist')) {
  const config = JSON.parse(fs.readFileSync(path.join(root, 'vercel.json'), 'utf8'))
  return http.createServer((request, response) => {
    if (!['GET', 'HEAD'].includes(request.method)) { response.writeHead(405); response.end(); return }
    const url = new URL(request.url, 'http://localhost')
    let route
    try { route = decodeURIComponent(url.pathname) } catch { response.writeHead(400); response.end(); return }
    if (/[\\\0]/.test(route) || route.split('/').includes('..')) { response.writeHead(400); response.end(); return }
    // Never connect an isolated UI audit to live API/data or credentials.
    if (/^\/(v1|auth|rest|storage|api|realtime|socket\.io)(\/|$)/.test(route)) { response.writeHead(401, { 'Cache-Control': 'no-store', 'X-Robots-Tag': 'noindex' }); response.end(); return }
    const redirect = config.redirects.find(rule => rule.source.endsWith('(/)') ? new RegExp('^' + rule.source + '$').test(route) : rule.source === route)
    if (redirect) {
      response.writeHead(308, { Location: redirect.destination + url.search }); response.end(); return
    }
    for (const rule of config.headers) {
      if (new RegExp('^(?:' + rule.source.replace(/:[a-zA-Z]+\*/g, '.*') + ')$').test(route)) for (const header of rule.headers) response.setHeader(header.key, header.value)
    }
    const known = config.rewrites.find(rule => rule.source === route)
    const privateShell = /^\/(app|explore|u|profile|chat|group|community)(\/|$)/.test(route)
    const relative = known?.destination || (privateShell ? '/app-shell.html' : route === '/' ? '/index.html' : route)
    const file = path.resolve(directory, '.' + relative)
    if (!file.startsWith(path.resolve(directory) + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      response.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8', 'X-Robots-Tag': 'noindex' })
      response.end(request.method === 'HEAD' ? undefined : fs.readFileSync(path.join(directory, '404.html'))); return
    }
    const content = fs.readFileSync(file)
    response.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream', 'Content-Length': content.length })
    response.end(request.method === 'HEAD' ? undefined : content)
  })
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  createSeoCandidateServer().listen(4175, '127.0.0.1', () => console.log('Isolated candidate: http://127.0.0.1:4175 (API disabled)'))
}
