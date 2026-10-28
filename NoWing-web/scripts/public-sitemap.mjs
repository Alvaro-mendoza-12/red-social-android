import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { site } from './public-seo.mjs'

const digest = value => createHash('sha256').update(value).digest('hex')
const escapeXML = value => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')

export function validateDate(date, today = new Date().toISOString().slice(0, 10)) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date || '') || new Date(date + 'T00:00:00Z').toISOString().slice(0, 10) !== date || date > today) {
    throw new Error('Invalid or future sitemap date: ' + date)
  }
  return date
}

export function publicPageFingerprint(page, publicDirectory) {
  const images = page.images.map(image => {
    if (!image.src.startsWith('/') || image.src.startsWith('//') || /[?#\\]/.test(image.src) || image.src.split('/').includes('..')) throw new Error('Unsafe public image URL')
    const file = path.resolve(publicDirectory, '.' + image.src)
    if (!file.startsWith(path.resolve(publicDirectory) + path.sep)) throw new Error('Image outside public assets')
    return { src: image.src, sha256: digest(fs.readFileSync(file)) }
  })
  return digest(JSON.stringify({ title: page.title, description: page.description, content: page.content, data: page.data, images }))
}

export function refreshSitemapState(pages, previous, { date, initialDates = new Map() }) {
  validateDate(date)
  const routes = {}
  for (const page of pages) {
    const old = previous?.routes?.[page.route]
    const lastmod = old?.contentHash === page.contentHash ? old.lastmod : old ? date : initialDates.get(page.url) || date
    validateDate(lastmod)
    if (old && lastmod < old.lastmod) throw new Error('Sitemap dates cannot move backwards: ' + page.route)
    routes[page.route] = { file: page.file, contentHash: page.contentHash, lastmod }
  }
  return { version: 1, routes }
}

export function renderPublicSitemap(pages, state, { maximumUrls = 50000, maximumBytes = 50 * 1024 * 1024 } = {}) {
  if (state?.version !== 1 || !state.routes || pages.length > maximumUrls) throw new Error('Sitemap state/size invalid; split before adding more public URLs')
  const seen = new Set()
  const entries = pages.map(page => {
    const url = new URL(page.url)
    if (url.origin !== site || url.search || url.hash || url.href !== site + page.route || !page.route.startsWith('/') || (page.route !== '/' && page.route.endsWith('/')) || seen.has(url.href)) throw new Error('Noncanonical or duplicate sitemap URL')
    seen.add(url.href)
    const saved = state.routes[page.route]
    if (!saved || saved.file !== page.file || saved.contentHash !== page.contentHash) throw new Error('Public content changed: run npm run seo:config before publishing (' + page.route + ')')
    validateDate(saved.lastmod)
    const images = page.images.map(image => `<image:image><image:loc>${escapeXML(site + image.src)}</image:loc></image:image>`).join('')
    return `  <url><loc>${escapeXML(url.href)}</loc><lastmod>${saved.lastmod}</lastmod>${images}</url>`
  })
  if (Object.keys(state.routes).length !== pages.length) throw new Error('Removed public URL remains in sitemap state')
  const xml = '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">\n' + entries.join('\n') + '\n</urlset>\n'
  if (Buffer.byteLength(xml) > maximumBytes) throw new Error('Sitemap exceeds protocol size; split before publishing')
  return xml
}
