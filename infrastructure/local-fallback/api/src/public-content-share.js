'use strict'
const sharp = require('sharp')
const { readPublicContent, readPublicProfile } = require('./guest-mode-routes')
const { publicShareProfileAllowed } = require('./public-share-policy')
const { escapeHtml } = require('./public-profile-share')
const { readPublicShareImage } = require('./public-share-image')
const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const clip = (value, limit) => {
  const text = String(value || '').replace(/\s+/g, ' ').trim()
  return text.length <= limit ? text : text.slice(0, limit - 1) + '…'
}
function lines(value, width = 42, count = 4) {
  const words = clip(value, width * count).split(' '), result = ['']
  for (const word of words) {
    if (result.at(-1).length + word.length + 1 > width && result.length < count) result.push('')
    result[result.length - 1] += (result.at(-1) ? ' ' : '') + word
  }
  return result.map(text => clip(text, width))
}
function contentWebPath(kind, id) { return kind === 'videos' ? '/app/reels/' + id : '/app/feed/post/' + id }
function contentShareHtml(kind, content, profile, webBase = 'https://novvinapp.com') {
  const canonical = webBase + contentWebPath(kind, content.id)
  const author = profile.display_name || 'Usuario'
  const title = kind === 'videos' ? clip(content.title || `Video de ${author}`, 100) : `Publicación de ${author}`
  const description = clip(content.content || content.description || `Mira ${kind === 'videos' ? 'este video' : 'esta publicación'} de ${author} en Novvin.`, 200)
  const image = `${webBase}/v1/public/${kind}/${content.id}/share.jpg?v=1`
  return `<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)} · Novvin</title><meta name="description" content="${escapeHtml(description)}"><meta name="robots" content="noindex,nofollow,noarchive">
<link rel="canonical" href="${escapeHtml(canonical)}"><meta property="og:url" content="${escapeHtml(canonical)}"><meta property="og:type" content="${kind === 'videos' ? 'video.other' : 'article'}">
<meta property="og:site_name" content="Novvin"><meta property="og:title" content="${escapeHtml(title)}"><meta property="og:description" content="${escapeHtml(description)}">
<meta property="og:image" content="${escapeHtml(image)}"><meta property="og:image:secure_url" content="${escapeHtml(image)}"><meta property="og:image:type" content="image/jpeg"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630"><meta property="og:image:alt" content="${escapeHtml(title)}">
<meta name="twitter:card" content="summary_large_image"><meta name="twitter:title" content="${escapeHtml(title)}"><meta name="twitter:description" content="${escapeHtml(description)}"><meta name="twitter:image" content="${escapeHtml(image)}">
<style>html{scrollbar-width:none}*::-webkit-scrollbar{display:none}body{margin:0;padding:32px 20px;background:#eef5ff;color:#162d4b;font:16px/1.6 system-ui,sans-serif}main{max-width:760px;margin:auto}img{width:100%;border-radius:18px}a{color:#155b9e}h1{font-size:1.6rem}p{white-space:pre-wrap;overflow-wrap:anywhere}.open{display:inline-block;padding:12px 20px;border-radius:18px;background:#2168b5;color:white;text-decoration:none}</style>
</head><body><main><a href="https://novvinapp.com">Novvin</a><h1>${escapeHtml(title)}</h1><img src="${escapeHtml(image)}" alt="${escapeHtml(title)}"><p>${escapeHtml(description)}</p><a class="open" href="${escapeHtml(canonical)}">Abrir ${kind === 'videos' ? 'video' : 'publicación'} en Novvin</a></main></body></html>`
}

async function contentShareCard(kind, content, profile, publicBaseUrl, readImage = readPublicShareImage) {
  const media = kind === 'videos' ? content.cover_url || content.thumbnail_url : content.media_url || content.media_urls?.[0]
  const photo = typeof media === 'string' && !/video/.test(content.media_type || '') ? await readImage(media, publicBaseUrl) : null
  const author = clip(profile.display_name || 'Usuario', 30)
  const text = content.content || content.title || content.description || (kind === 'videos' ? 'Video en Novvin' : 'Publicación en Novvin')
  const textX = photo ? 684 : 72, lineWidth = photo ? 25 : 55
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630"><rect width="1200" height="630" fill="#eef5ff"/><rect width="1200" height="8" fill="#2168b5"/>
<text x="${textX}" y="100" font-family="sans-serif" font-size="28" font-weight="700" fill="#2168b5">Novvin</text>
<text x="${textX}" y="190" font-family="sans-serif" font-size="${photo ? 32 : 46}" font-weight="700" fill="#162d4b">${escapeHtml(author)}</text>
${lines(text, lineWidth, 4).map((line, i) => `<text x="${textX}" y="${280 + 54 * i}" font-family="sans-serif" font-size="${photo ? 28 : 36}" fill="#294460">${escapeHtml(line)}</text>`).join('')}
<text x="${textX}" y="565" font-family="sans-serif" font-size="23" fill="#50657d">${kind === 'videos' ? 'Video' : 'Publicación'} · novvinapp.com</text></svg>`
  const canvas = sharp(Buffer.from(svg))
  return (photo ? canvas.composite([{ input: await sharp(photo).resize(620, 614, { fit: 'cover' }).toBuffer(), left: 16, top: 8 }]) : canvas)
    .jpeg({ quality: 85, mozjpeg: true }).toBuffer()
}

function registerPublicContentShareRoutes(app, { pool, publicBaseUrl, loadContent = readPublicContent, loadProfile = readPublicProfile, profileAllowed = publicShareProfileAllowed }) {
  const webBase = String(process.env.PUBLIC_WEB_URL || 'https://novvinapp.com').replace(/\/+$/, '')
  for (const kind of ['posts', 'videos']) for (const image of [false, true]) {
    app.get(`/v1/public/${kind}/:id/${image ? 'share.jpg' : 'share'}`, async (req, res, next) => {
      res.set('Cache-Control', 'no-store').set('X-Robots-Tag', 'noindex, nofollow, noarchive')
      try {
        const id = String(req.params.id || '')
        if (!UUID.test(id)) return res.status(404).send('Contenido no disponible.')
        const content = await loadContent(pool, kind, id)
        const profile = content?.user_id ? await loadProfile(pool, content.user_id) : null
        if (!content || !profile || profile.is_private) return res.status(404).send('Contenido no disponible.')
        if (!await profileAllowed(pool, profile, content.user_id)) return res.status(404).send('Contenido no disponible.')
        if (image) return res.type('image/jpeg').send(await contentShareCard(kind, content, profile, publicBaseUrl))
        res.set('Content-Security-Policy', "default-src 'none'; img-src https:; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
        return res.type('html').send(contentShareHtml(kind, content, profile, webBase))
      } catch (error) { next(error) }
    })
  }
}
module.exports = { contentShareHtml, contentShareCard, registerPublicContentShareRoutes }
