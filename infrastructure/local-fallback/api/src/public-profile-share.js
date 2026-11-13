'use strict'

const { readPublicProfile } = require('./guest-mode-routes')
const { canonicalStorageUrl } = require('./storage-url-canonicalization')
const sharp = require('sharp')
const { readPublicShareImage } = require('./public-share-image')
const { publicShareProfileAllowed } = require('./public-share-policy')
const logo = require('node:fs').readFileSync(require('node:path').join(__dirname, 'assets', 'novvin-logo.png')).toString('base64')

function escapeHtml(value) {
  return String(value || '').replace(/[&<>"']/g, (char) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
}

function publicProfileUrl(profile, base) {
  return `${base}/p/${encodeURIComponent(profile.username || profile.id)}`
}

function previewImageUrl(profile, base) {
  return `${publicProfileUrl(profile, base)}/card.jpg?v=5`
}

function previewText(value, limit) {
  const plain = String(value || '').replace(/[\r\n\t]+/g, ' ').trim()
  return plain.length > limit ? `${plain.slice(0, limit - 1)}…` : plain
}

function previewLines(value, lineLength = 36) {
  const plain = String(value || '').replace(/[\r\n\t]+/g, ' ').trim()
  const lines = []
  let rest = plain
  while (rest && lines.length < 2) {
    if (rest.length <= lineLength) { lines.push(rest); rest = ''; break }
    const cut = rest.lastIndexOf(' ', lineLength)
    const end = cut >= lineLength / 2 ? cut : lineLength
    lines.push(rest.slice(0, end).trim())
    rest = rest.slice(end).trim()
  }
  if (rest && lines.length === 2) lines[1] = previewText(lines[1], lineLength - 1) + '…'
  return lines
}

async function publicProfileCard(profile, publicBaseUrl) {
  const base = String(publicBaseUrl || '').replace(/\/+$/, '')
  const rawName = String(profile.display_name || 'Usuario')
  const name = previewText(rawName, /[^\u0000-\u00ff]/.test(rawName) ? 17 : 24)
  const handle = previewText(profile.username ? `@${profile.username}` : 'Perfil en Novvin', 30)
  const bioLines = previewLines(profile.bio || `Conoce el perfil de ${name} en Novvin.`)
  const nameSize = name.length > 18 || /[^\u0000-\u00ff]/.test(name) ? 44 : 64
  let avatarMarkup = `<circle cx="234" cy="325" r="128" fill="#2b629a"/><text x="234" y="361" text-anchor="middle" font-size="104" font-weight="700" fill="#ffffff">${escapeHtml(Array.from(name)[0]?.toUpperCase() || 'N')}</text>`
  const avatar = await readPublicShareImage(profile.avatar_url, base)
  if (avatar) {
    const square = await sharp(avatar).resize(256, 256, { fit: 'cover' }).jpeg({ quality: 85 }).toBuffer()
    avatarMarkup = `<image x="106" y="197" width="256" height="256" href="data:image/jpeg;base64,${square.toString('base64')}" clip-path="url(#avatarClip)"/>`
  }
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630" viewBox="0 0 1200 630">
<defs><clipPath id="avatarClip"><circle cx="234" cy="325" r="128"/></clipPath></defs>
<rect width="1200" height="630" fill="#eef5ff"/><rect width="1200" height="8" fill="#2168b5"/>
<image x="58" y="42" width="50" height="50" href="data:image/png;base64,${logo}"/>
<text x="122" y="79" font-family="sans-serif" font-size="30" font-weight="700" fill="#162d4b">Novvin</text>
${avatarMarkup}
<text x="430" y="255" font-family="sans-serif" font-size="${nameSize}" font-weight="700" fill="#162d4b">${escapeHtml(name)}</text>
<text x="434" y="316" font-family="sans-serif" font-size="30" fill="#2168b5">${escapeHtml(handle)}</text>
${bioLines.map((line, index) => `<text x="434" y="${386 + index * 42}" font-family="sans-serif" font-size="26" fill="#405976">${escapeHtml(line)}</text>`).join('')}
<text x="434" y="536" font-family="sans-serif" font-size="23" fill="#50657d">Perfil en novvinapp.com</text></svg>`
  return sharp(Buffer.from(svg)).jpeg({ quality: 85, mozjpeg: true }).toBuffer()
}

async function publicProfilePosts(pool, profile) {
  if (profile.is_private || !/^[0-9a-f-]{36}$/i.test(String(profile.id || ''))) return []
  const aliases = `WITH aliases AS (
      SELECT $1::text AS id
      UNION SELECT u.id::text FROM fallback_users u
        WHERE u.id::text = $1 OR u.supabase_user_id::text = $1
      UNION SELECT u.supabase_user_id::text FROM fallback_users u
        WHERE u.id::text = $1 OR u.supabase_user_id::text = $1
    )`
  const { rows } = await pool.query(`${aliases}
    SELECT p.content, COALESCE(NULLIF(p.media_url, ''), p.media_urls->>0) AS media_url,
      p.id::text AS id, p.media_urls, p.media_type, p.created_at
    FROM fallback_posts p
    WHERE lower(p.user_id) IN (SELECT lower(id) FROM aliases WHERE id IS NOT NULL)
      AND p.visibility = 'public' AND p.is_sensitive = FALSE
    ORDER BY p.created_at DESC LIMIT 12`, [profile.id])
  const table = await pool.query("SELECT to_regclass('supabase_import.posts') AS name")
  if (!table.rows[0]?.name) return rows
  const imported = await pool.query(`${aliases}
    SELECT to_jsonb(p) AS row FROM supabase_import.posts p
    WHERE lower(p.user_id) IN (SELECT lower(id) FROM aliases WHERE id IS NOT NULL)
      AND COALESCE(NULLIF(p.visibility, ''), 'public') = 'public'
      AND COALESCE(to_jsonb(p)->>'is_sensitive', 'false') NOT IN ('true', 't', '1')
      AND COALESCE(to_jsonb(p)->>'is_deleted', 'false') NOT IN ('true', 't', '1')
      AND NOT EXISTS (SELECT 1 FROM fallback_posts f WHERE f.id::text = p.id)
    ORDER BY p.created_at DESC LIMIT 12`, [profile.id])
  return [...rows, ...imported.rows.map(({ row }) => row)]
    .sort((a, b) => Date.parse(b.created_at || 0) - Date.parse(a.created_at || 0))
    .slice(0, 12)
}

function profileShareHtml(profile, publicBaseUrl, posts = []) {
  const name = profile.display_name || 'Usuario'
  const handle = profile.username ? ` (@${profile.username})` : ''
  const title = `${name}${handle} · Perfil de Novvin`
  const description = previewText(`Conoce a ${name} en Novvin. ${profile.bio || 'Mira su perfil y publicaciones.'}`, 155)
  const base = String(publicBaseUrl || '').replace(/\/+$/, '')
  const webBase = String(process.env.PUBLIC_WEB_URL || 'https://novvinapp.com').replace(/\/+$/, '')
  const canonical = publicProfileUrl(profile, webBase)
  const avatar = canonicalStorageUrl(profile.avatar_url, base)
  const image = previewImageUrl(profile, webBase)
  const appLink = `conectate://profile/${encodeURIComponent(profile.id)}`
  const storeLink = 'https://play.google.com/store/apps/details?id=conectate.com'
  const webPath = `/app/profile/${encodeURIComponent(profile.id)}`
  const loginLink = `${webBase}/login?next=${encodeURIComponent(webPath)}`
  const registerLink = `${webBase}/register?next=${encodeURIComponent(webPath)}`
  const safeAvatar = /^https:\/\//i.test(avatar || '') ? avatar : ''
  const postsHtml = posts.map((post) => {
    const urls = Array.isArray(post.media_urls) ? post.media_urls : []
    const mediaUrls = [...new Set([...urls, post.media_url]
      .map((entry) => typeof entry === 'string' ? entry : entry?.url || entry?.media_url)
      .filter((url) => typeof url === 'string' && url.trim())
      .map((url) => canonicalStorageUrl(url, base))
      .filter((url) => /^https:\/\//i.test(url || '')))]
    const type = String(post.media_type || '').toLowerCase()
    const visual = mediaUrls.length && !/video/.test(type)
      ? `<div class="gallery" role="group" aria-label="${mediaUrls.length} imágenes de la publicación">${mediaUrls.map((url, index) =>
          `<img src="${escapeHtml(url)}" alt="Imagen ${index + 1} de ${mediaUrls.length} de la publicación" loading="lazy">`).join('')}</div>${mediaUrls.length > 1 ? `<small>Desliza a la izquierda para ver las ${mediaUrls.length} fotos →</small>` : ''}`
      : mediaUrls.length && /video/.test(type)
        ? `<video src="${escapeHtml(mediaUrls[0])}" controls preload="none" aria-label="Video de una publicación"></video>`
        : ''
    const body = String(post.content || '').trim()
    const timestamp = post.created_at ? new Date(post.created_at) : null
    const date = timestamp && Number.isFinite(timestamp.getTime()) ? timestamp.toISOString().slice(0, 10) : ''
    return `<article class="post"><p>${escapeHtml(body)}</p>${visual}${date ? `<small>${escapeHtml(date)}</small>` : ''}</article>`
  }).join('')
  return `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex, follow">
<title>${escapeHtml(title)}</title>${image ? `<link rel="icon" href="${escapeHtml(image)}" type="image/jpeg">` : ''}<meta name="description" content="${escapeHtml(description)}">
<meta property="og:type" content="profile"><meta property="og:site_name" content="Novvin"><meta property="og:locale" content="es_PE">
<meta property="og:title" content="${escapeHtml(title)}">
<meta property="og:description" content="${escapeHtml(description)}">
${canonical ? `<meta property="og:url" content="${escapeHtml(canonical)}"><link rel="canonical" href="${escapeHtml(canonical)}">` : ''}
${image ? `<meta property="og:image" content="${escapeHtml(image)}"><meta property="og:image:secure_url" content="${escapeHtml(image)}"><meta property="og:image:type" content="image/jpeg"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630"><meta property="og:image:alt" content="Tarjeta del perfil de ${escapeHtml(name)}"><meta name="twitter:card" content="summary_large_image"><meta name="twitter:title" content="${escapeHtml(title)}"><meta name="twitter:description" content="${escapeHtml(description)}"><meta name="twitter:image" content="${escapeHtml(image)}">` : ''}
<style>
:root{color-scheme:dark}*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:#0b1020;color:#f6f8ff;font:16px/1.55 system-ui,-apple-system,sans-serif}a{color:inherit}a:focus-visible{outline:3px solid #45d8d1;outline-offset:3px}main{width:min(100% - 32px,740px);margin:0 auto;padding:24px 0 80px}.brand{font-size:23px;font-weight:800;letter-spacing:.03em;margin:0 0 20px}.brand-mark{color:#39d6d1}.hero,.details{background:#171f36;border:1px solid #33415d;border-radius:24px;overflow:hidden}.hero-image{display:block;width:100%;height:auto;aspect-ratio:1;object-fit:cover}.intro{padding:24px}.eyebrow{color:#7ce6df;font-weight:700;font-size:13px;text-transform:uppercase;letter-spacing:.08em;margin:0 0 8px}h1{font-size:clamp(27px,6vw,40px);line-height:1.18;margin:0}.handle{color:#83e9e3;margin:6px 0 14px}.bio{color:#d8e0f1;margin:0;overflow-wrap:anywhere}.choices{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-top:24px}.button{display:flex;align-items:center;justify-content:center;min-height:50px;padding:12px 15px;border-radius:13px;text-align:center;text-decoration:none;font-weight:750;line-height:1.25;background:#2d3b60;border:1px solid #637395}.button.primary{background:#53dbd5;color:#092230;border-color:#53dbd5}.button.wide{grid-column:1/-1}.hint{color:#b9c5dc;font-size:14px;margin:14px 0 0}.details{padding:24px;margin-top:24px}.details h2{font-size:24px;margin:0 0 6px}.details p{overflow-wrap:anywhere}.guest-note{color:#b9c5dc;margin:0 0 20px}.post{border-top:1px solid #34415c;padding:16px 0}.post p{white-space:pre-wrap;margin:0 0 12px}.post img,.post video{display:block;width:100%;max-height:520px;object-fit:contain;border-radius:12px;background:#0b1020}.post small{display:block;color:#aebbd2;margin-top:8px}.account-actions{display:flex;gap:18px;flex-wrap:wrap;border-top:1px solid #34415c;padding-top:22px;margin-top:24px}.account-actions a{color:#84e8e2;font-weight:650}footer{color:#aebbd2;text-align:center;font-size:13px;margin-top:24px}@media(max-width:520px){.choices{grid-template-columns:1fr}.button.wide{grid-column:auto}.intro,.details{padding:20px}}
</style><style>.gallery{display:flex;gap:12px;overflow-x:auto;scroll-snap-type:x mandatory;overscroll-behavior-inline:contain}.gallery img{flex:0 0 100%;scroll-snap-align:start}</style></head><body><main><div class="brand"><span class="brand-mark">✦</span> Novvin</div>
<section class="hero" aria-labelledby="profile-name">${image ? `<img class="hero-image" src="${escapeHtml(image)}" alt="Tarjeta del perfil de ${escapeHtml(name)}">` : ''}
<div class="intro"><p class="eyebrow">Perfil público</p><h1 id="profile-name">${escapeHtml(name)}</h1>${profile.username ? `<p class="handle">@${escapeHtml(profile.username)}</p>` : ''}<p class="bio">${escapeHtml(profile.bio || `Conoce a ${name} en Novvin.`)}</p>
<nav class="choices" aria-label="Cómo continuar"><a class="button primary" href="#perfil-web">Ver en la web</a><a class="button" href="${escapeHtml(appLink)}">Abrir en Novvin</a><a class="button wide" href="${escapeHtml(storeLink)}">Descargar Novvin en Google Play</a></nav>
<p class="hint">Puedes mirar el perfil como invitado. Para seguir o escribir, inicia sesión o crea una cuenta.</p></div></section>
<section class="details" id="perfil-web" aria-labelledby="web-title"><h2 id="web-title">Perfil de ${escapeHtml(name)}</h2><p class="guest-note">Estás viendo la versión web como invitado.</p>
${profile.is_private ? '<p>Esta cuenta es privada. Su contenido se ve según sus ajustes de privacidad.</p>' : postsHtml || '<p>Todavía no hay publicaciones públicas para mostrar.</p>'}
<nav class="account-actions" aria-label="Acceso a la cuenta"><a href="${escapeHtml(loginLink)}">Iniciar sesión</a><a href="${escapeHtml(registerLink)}">Crear cuenta</a><a href="${escapeHtml(storeLink)}">Instalar la app</a></nav></section>
<footer>Novvin · Comparte y conecta</footer></main></body></html>`
}

function registerPublicProfileShareRoutes(app, { pool, publicBaseUrl }) {
  app.get(['/v1/public/profiles/:identifier/card.jpg', '/p/:identifier/card.jpg'], async (req, res, next) => {
    try {
      // Share previews are not search opt-ins. Keep existing guest access, while
      // preventing indexing until a separate owner-controlled policy exists.
      res.set('X-Robots-Tag', 'noindex, noarchive').set('Cache-Control', 'no-store')
      const identifier = String(req.params.identifier || '')
      if (!/^[a-z0-9_.-]{3,36}$/i.test(identifier)) return res.sendStatus(404)
      const profile = await readPublicProfile(pool, identifier)
      if (!profile || !await publicShareProfileAllowed(pool, profile)) return res.sendStatus(404)
      const image = await publicProfileCard(profile, publicBaseUrl)
      return res.set('Cache-Control', 'no-store').type('image/jpeg').send(image)
    } catch (error) { next(error) }
  })
  app.get(['/v1/public/profiles/:identifier', '/p/:identifier'], async (req, res, next) => {
    try {
      res.set('X-Robots-Tag', 'noindex, follow, noarchive').set('Cache-Control', 'no-store')
      const identifier = String(req.params.identifier || '')
      if (!/^[a-z0-9_.-]{3,36}$/i.test(identifier)) return res.status(404).type('text').send('Perfil no disponible.')
      const profile = await readPublicProfile(pool, identifier)
      if (!profile || !await publicShareProfileAllowed(pool, profile)) return res.status(404).type('text').send('Perfil no disponible.')
      const posts = await publicProfilePosts(pool, profile)
      res.set('Cache-Control', 'no-store')
      res.set('Content-Security-Policy', "default-src 'none'; img-src https:; media-src https:; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
      return res.type('html').send(profileShareHtml(profile, publicBaseUrl, posts))
    } catch (error) { next(error) }
  })
}

module.exports = { escapeHtml, profileShareHtml, publicProfileCard, publicProfilePosts, registerPublicProfileShareRoutes }
