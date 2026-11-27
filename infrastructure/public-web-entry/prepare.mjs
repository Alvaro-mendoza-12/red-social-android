import { copyFile, mkdir, readdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { publicRoutes } from '../../NoWing-web/scripts/public-seo.mjs'

// Only public documents and their assets are published here. Ubuntu handles app sessions.
const [dist, output] = process.argv.slice(2)
if (!dist || !output) throw new Error('Uso: node prepare.mjs <dist web> <directorio nuevo>')
await mkdir(output)
const assets = path.join(output, 'public')
async function copy(file) {
  const dest = path.join(assets, file)
  await mkdir(path.dirname(dest), { recursive: true })
  await copyFile(path.join(dist, file), dest)
}

const publicImages = new Set()
for (const [, file] of publicRoutes) {
  await copy(file)
  const html = await readFile(path.join(dist, file), 'utf8')
  for (const [image] of html.matchAll(/images\/novvin-[\w-]+\.(?:png|webp|svg)\b/g)) publicImages.add(image)
}
for (const file of publicImages) await copy(file)
for (const file of ['404.html', 'robots.txt', 'sitemap.xml', 'manifest.json', 'favicon.ico',
  'novvin-logo.png', 'novvin-share.png', '.well-known/assetlinks.json']) await copy(file)
for (const file of await readdir(path.join(dist, 'css'))) {
  if (/^public-[a-f0-9]+\.css$/.test(file)) await copy('css/' + file)
}
for (const file of await readdir(path.join(dist, 'icons'))) await copy('icons/' + file)
for (const file of await readdir(dist)) {
  if (/^google[a-f0-9]+\.html$/.test(file)) await copy(file)
}

const origin = 'https://conectate.tail9b267c.ts.net'
const routes = 'login|register|forgot-password|app|explore|u|p|profile|chat|group|community|voice|post|board-games'
const canonicalRedirects = publicRoutes.flatMap(([source, file]) => [
  { source: '/' + file, destination: source, type: 301 },
  ...(source === '/' ? [] : [{ source: source + '/', destination: source, type: 301 }]),
])
const config = {
  hosting: {
    site: 'novvin-entrada-web',
    public: 'public',
    ignore: [],
    redirects: [
      ...canonicalRedirects,
      { regex: '/(?P<route>(?:' + routes + ')(?:/.*)?)', destination: origin + '/:route', type: 302 },
    ],
    rewrites: publicRoutes.map(([source, file]) => ({ source, destination: '/' + file })),
    headers: [
      { source: '**', headers: [
        { key: 'X-Content-Type-Options', value: 'nosniff' },
        { key: 'Cache-Control', value: 'public, max-age=0, must-revalidate' },
        { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
        { key: 'X-Frame-Options', value: 'DENY' },
        { key: 'Content-Security-Policy', value: "default-src 'none'; img-src 'self' data:; style-src 'self'; manifest-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'" },
      ] },
      { source: '/css/public-*.css', headers: [
        { key: 'Cache-Control', value: 'public, max-age=31536000, immutable' },
      ] },
    ],
  },
}
await writeFile(path.join(output, 'firebase.json'), JSON.stringify(config, null, 2) + '\n')
