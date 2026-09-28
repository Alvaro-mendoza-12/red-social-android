'use strict'

const { readPublicProfile } = require('./guest-mode-routes')
const { canonicalStorageUrl } = require('./storage-url-canonicalization')

function escapeHtml(value) {
  return String(value || '').replace(/[&<>"']/g, (char) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
}

function profileShareHtml(profile, publicBaseUrl) {
  const name = profile.display_name || 'Usuario'
  const handle = profile.username ? ` (@${profile.username})` : ''
  const title = `${name}${handle} · Perfil de Novvin`
  const description = String(profile.bio || `Conoce el perfil de ${name} en Novvin.`).slice(0, 280)
  const base = String(publicBaseUrl || '').replace(/\/+$/, '')
  const canonical = base ? `${base}/v1/public/profiles/${encodeURIComponent(profile.username || profile.id)}` : ''
  const avatar = canonicalStorageUrl(profile.avatar_url, base)
  const image = /^https:\/\//i.test(avatar || '') ? avatar : ''
  const appLink = `conectate://profile/${encodeURIComponent(profile.id)}`
  return `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title><meta name="description" content="${escapeHtml(description)}">
<meta property="og:type" content="profile"><meta property="og:site_name" content="Novvin">
<meta property="og:title" content="${escapeHtml(title)}">
<meta property="og:description" content="${escapeHtml(description)}">
${canonical ? `<meta property="og:url" content="${escapeHtml(canonical)}"><link rel="canonical" href="${escapeHtml(canonical)}">` : ''}
${image ? `<meta property="og:image" content="${escapeHtml(image)}">` : ''}
<style>body{margin:0;background:#f5f7fb;color:#15223d;font:1rem system-ui,sans-serif;line-height:1.6;padding:24px}main{max-width:520px;margin:8vh auto;background:#fff;border:1px solid #dae0ec;border-radius:24px;padding:clamp(20px,5vw,40px);overflow-wrap:anywhere}img{width:88px;height:88px;border-radius:50%;object-fit:cover}h1{font-size:clamp(24px,6vw,36px);line-height:1.2}a{display:block;margin-top:24px;background:#4534c9;color:#fff;padding:16px;text-align:center;border-radius:12px;text-decoration:none;font-weight:600}a:focus-visible{outline:3px solid #007e96;outline-offset:4px}.handle{color:#4534c9}</style>
</head><body><main>${image ? `<img src="${escapeHtml(image)}" alt="Foto del perfil">` : ''}
<h1>${escapeHtml(name)}</h1>${profile.username ? `<p class="handle">@${escapeHtml(profile.username)}</p>` : ''}
<p>${escapeHtml(description)}</p><a href="${escapeHtml(appLink)}">Abrir perfil en Novvin</a>
<p>Para abrir este perfil en la aplicación, necesitas tener Novvin instalada.</p></main></body></html>`
}

function registerPublicProfileShareRoutes(app, { pool, publicBaseUrl }) {
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

module.exports = { escapeHtml, profileShareHtml, registerPublicProfileShareRoutes }
