'use strict'

const { readPublicProfile } = require('./guest-mode-routes')
const { canonicalStorageUrl } = require('./storage-url-canonicalization')
const sharp = require('sharp')

function escapeHtml(value) {
  return String(value || '').replace(/[&<>"']/g, (char) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
}

function publicProfileUrl(profile, base) {
  return `${base}/v1/public/profiles/${encodeURIComponent(profile.username || profile.id)}`
}

function previewImageUrl(profile, base) {
  return `${publicProfileUrl(profile, base)}/card.jpg`
}

function previewText(value, limit) {
  const plain = String(value || '').replace(/[\r\n\t]+/g, ' ').trim()
  return plain.length > limit ? `${plain.slice(0, limit - 1)}…` : plain
}

async function publicProfileCard(profile, publicBaseUrl) {
  const base = String(publicBaseUrl || '').replace(/\/+$/, '')
  const name = previewText(profile.display_name || 'Usuario', 32)
  const handle = previewText(profile.username ? `@${profile.username}` : 'Perfil en Novvin', 30)
  const bio = previewText(profile.bio || `Conoce el perfil de ${name} en Novvin.`, 105)
  const avatarUrl = canonicalStorageUrl(profile.avatar_url, base)
  let avatarMarkup = `<circle cx="186" cy="246" r="86" fill="#234572"/><text x="186" y="273" text-anchor="middle" font-size="82" font-weight="700" fill="#ffffff">${escapeHtml(name.charAt(0).toUpperCase())}</text>`
  try {
    const url = new URL(avatarUrl)
    const origin = new URL(base)
    if (url.protocol === 'https:' && url.origin === origin.origin &&
        url.pathname.startsWith('/storage/v1/object/public/')) {
      const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(2500) })
      const type = response.headers.get('content-type') || ''
      const size = Number(response.headers.get('content-length') || 0)
      if (response.ok && /^image\/(jpeg|png|webp)$/i.test(type) && size <= 2_000_000) {
        const bytes = Buffer.from(await response.arrayBuffer())
        if (bytes.length <= 2_000_000) {
          const square = await sharp(bytes, { limitInputPixels: 12_000_000 })
            .resize(192, 192, { fit: 'cover' }).jpeg({ quality: 82 }).toBuffer()
          avatarMarkup = `<image x="90" y="150" width="192" height="192" href="data:image/jpeg;base64,${square.toString('base64')}" clip-path="url(#avatarClip)"/>`
        }
      }
    }
  } catch (_) { /* A missing avatar must not prevent the profile card. */ }
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630" viewBox="0 0 1200 630">
<defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="#101b36"/><stop offset="1" stop-color="#26336d"/></linearGradient><clipPath id="avatarClip"><circle cx="186" cy="246" r="96"/></clipPath></defs>
<rect width="1200" height="630" fill="url(#bg)"/><circle cx="1110" cy="32" r="320" fill="#22c4d9" opacity=".13"/>
<rect x="0" y="0" width="1200" height="10" fill="#34d6d0"/><circle cx="186" cy="246" r="102" fill="#34d6d0"/>
${avatarMarkup}<text x="324" y="210" font-family="sans-serif" font-size="64" font-weight="700" fill="#ffffff">${escapeHtml(name)}</text>
<text x="328" y="265" font-family="sans-serif" font-size="32" fill="#81e4e5">${escapeHtml(handle)}</text>
<text x="90" y="438" font-family="sans-serif" font-size="31" fill="#eef3ff">${escapeHtml(bio)}</text>
<rect x="0" y="540" width="1200" height="90" fill="#0b142a"/><text x="90" y="597" font-family="sans-serif" font-size="38" font-weight="700" fill="#ffffff">Novvin</text>
<text x="1070" y="596" text-anchor="end" font-family="sans-serif" font-size="25" fill="#b9cbe4">Mira mi perfil</text></svg>`
  return sharp(Buffer.from(svg)).jpeg({ quality: 85, mozjpeg: true }).toBuffer()
}

function profileShareHtml(profile, publicBaseUrl) {
  const name = profile.display_name || 'Usuario'
  const handle = profile.username ? ` (@${profile.username})` : ''
  const title = `${name}${handle} · Perfil de Novvin`
  const description = String(profile.bio || `Conoce el perfil de ${name} en Novvin.`).slice(0, 280)
  const base = String(publicBaseUrl || '').replace(/\/+$/, '')
  const canonical = base ? publicProfileUrl(profile, base) : ''
  const avatar = canonicalStorageUrl(profile.avatar_url, base)
  const image = base.startsWith('https://') ? previewImageUrl(profile, base) : ''
  const appLink = `conectate://profile/${encodeURIComponent(profile.id)}`
  return `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title><meta name="description" content="${escapeHtml(description)}">
<meta property="og:type" content="profile"><meta property="og:site_name" content="Novvin">
<meta property="og:title" content="${escapeHtml(title)}">
<meta property="og:description" content="${escapeHtml(description)}">
${canonical ? `<meta property="og:url" content="${escapeHtml(canonical)}"><link rel="canonical" href="${escapeHtml(canonical)}">` : ''}
${image ? `<meta property="og:image" content="${escapeHtml(image)}"><meta property="og:image:secure_url" content="${escapeHtml(image)}"><meta property="og:image:type" content="image/jpeg"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630"><meta name="twitter:card" content="summary_large_image"><meta name="twitter:image" content="${escapeHtml(image)}">` : ''}
<style>body{margin:0;background:#f5f7fb;color:#15223d;font:1rem system-ui,sans-serif;line-height:1.6;padding:24px}main{max-width:520px;margin:8vh auto;background:#fff;border:1px solid #dae0ec;border-radius:24px;padding:clamp(20px,5vw,40px);overflow-wrap:anywhere}img{width:88px;height:88px;border-radius:50%;object-fit:cover}h1{font-size:clamp(24px,6vw,36px);line-height:1.2}a{display:block;margin-top:24px;background:#4534c9;color:#fff;padding:16px;text-align:center;border-radius:12px;text-decoration:none;font-weight:600}a:focus-visible{outline:3px solid #007e96;outline-offset:4px}.handle{color:#4534c9}</style>
</head><body><main>${image ? `<img src="${escapeHtml(image)}" alt="Foto del perfil">` : ''}
<h1>${escapeHtml(name)}</h1>${profile.username ? `<p class="handle">@${escapeHtml(profile.username)}</p>` : ''}
<p>${escapeHtml(description)}</p><a href="${escapeHtml(appLink)}">Abrir perfil en Novvin</a>
<p>Para abrir este perfil en la aplicación, necesitas tener Novvin instalada.</p></main></body></html>`
}

function registerPublicProfileShareRoutes(app, { pool, publicBaseUrl }) {
  app.get('/v1/public/profiles/:identifier/card.jpg', async (req, res, next) => {
    try {
      const identifier = String(req.params.identifier || '')
      if (!/^[a-z0-9_.-]{3,36}$/i.test(identifier)) return res.sendStatus(404)
      const profile = await readPublicProfile(pool, identifier)
      if (!profile) return res.sendStatus(404)
      const image = await publicProfileCard(profile, publicBaseUrl)
      return res.set('Cache-Control', 'public, max-age=300').type('image/jpeg').send(image)
    } catch (error) { next(error) }
  })
  app.get('/v1/public/profiles/:identifier', async (req, res, next) => {
    try {
      const identifier = String(req.params.identifier || '')
      if (!/^[a-z0-9_.-]{3,36}$/i.test(identifier)) return res.status(404).type('text').send('Perfil no disponible.')
      const profile = await readPublicProfile(pool, identifier)
      if (!profile) return res.status(404).type('text').send('Perfil no disponible.')
      res.set('Cache-Control', 'no-store')
      res.set('Content-Security-Policy', "default-src 'none'; img-src https:; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
      return res.type('html').send(profileShareHtml(profile, publicBaseUrl))
    } catch (error) { next(error) }
  })
}

module.exports = { escapeHtml, profileShareHtml, publicProfileCard, registerPublicProfileShareRoutes }
