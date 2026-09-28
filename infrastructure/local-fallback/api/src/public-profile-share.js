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
${avatarMarkup}<text x="324" y="210" font-family="sans-serif" font-size="${nameSize}" font-weight="700" fill="#ffffff">${escapeHtml(name)}</text>
<text x="328" y="265" font-family="sans-serif" font-size="32" fill="#81e4e5">${escapeHtml(handle)}</text>
${bioLines.map((line, index) => `<text x="90" y="${438 + index * 42}" font-family="sans-serif" font-size="29" fill="#eef3ff">${escapeHtml(line)}</text>`).join('')}
<rect x="0" y="540" width="1200" height="90" fill="#0b142a"/><text x="90" y="597" font-family="sans-serif" font-size="38" font-weight="700" fill="#ffffff">Novvin</text>
<text x="1070" y="596" text-anchor="end" font-family="sans-serif" font-size="25" fill="#b9cbe4">Mira mi perfil</text></svg>`
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
  const canonical = base ? publicProfileUrl(profile, base) : ''
  const avatar = canonicalStorageUrl(profile.avatar_url, base)
  const image = base.startsWith('https://') ? previewImageUrl(profile, base) : ''
  const appLink = `conectate://profile/${encodeURIComponent(profile.id)}`
  const storeLink = 'https://play.google.com/store/apps/details?id=conectate.com'
  const webBase = String(process.env.PUBLIC_WEB_URL || 'https://red-social-android.vercel.app').replace(/\/+$/, '')
  const webPath = `/u/${encodeURIComponent(profile.id)}`
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
    const date = post.created_at ? new Date(post.created_at).toISOString().slice(0, 10) : ''
    return `<article class="post"><p>${escapeHtml(body)}</p>${visual}${date ? `<small>${escapeHtml(date)}</small>` : ''}</article>`
  }).join('')
  return `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title><meta name="description" content="${escapeHtml(description)}">
<meta property="og:type" content="profile"><meta property="og:site_name" content="Novvin"><meta property="og:locale" content="es_PE">
<meta property="og:title" content="${escapeHtml(title)}">
<meta property="og:description" content="${escapeHtml(description)}">
${canonical ? `<meta property="og:url" content="${escapeHtml(canonical)}"><link rel="canonical" href="${escapeHtml(canonical)}">` : ''}
${image ? `<meta property="og:image" content="${escapeHtml(image)}"><meta property="og:image:secure_url" content="${escapeHtml(image)}"><meta property="og:image:type" content="image/jpeg"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630"><meta property="og:image:alt" content="Tarjeta del perfil de ${escapeHtml(name)}"><meta name="twitter:card" content="summary_large_image"><meta name="twitter:title" content="${escapeHtml(title)}"><meta name="twitter:description" content="${escapeHtml(description)}"><meta name="twitter:image" content="${escapeHtml(image)}">` : ''}
<style>
:root{color-scheme:dark}*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:#0b1020;color:#f6f8ff;font:16px/1.55 system-ui,-apple-system,sans-serif}a{color:inherit}a:focus-visible{outline:3px solid #45d8d1;outline-offset:3px}main{width:min(100% - 32px,740px);margin:0 auto;padding:24px 0 80px}.brand{font-size:23px;font-weight:800;letter-spacing:.03em;margin:0 0 20px}.brand-mark{color:#39d6d1}.hero,.details{background:#171f36;border:1px solid #33415d;border-radius:24px;overflow:hidden}.hero-image{display:block;width:100%;height:auto;aspect-ratio:1200/630;object-fit:cover}.intro{padding:24px}.eyebrow{color:#7ce6df;font-weight:700;font-size:13px;text-transform:uppercase;letter-spacing:.08em;margin:0 0 8px}h1{font-size:clamp(27px,6vw,40px);line-height:1.18;margin:0}.handle{color:#83e9e3;margin:6px 0 14px}.bio{color:#d8e0f1;margin:0;overflow-wrap:anywhere}.choices{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-top:24px}.button{display:flex;align-items:center;justify-content:center;min-height:50px;padding:12px 15px;border-radius:13px;text-align:center;text-decoration:none;font-weight:750;line-height:1.25;background:#2d3b60;border:1px solid #637395}.button.primary{background:#53dbd5;color:#092230;border-color:#53dbd5}.button.wide{grid-column:1/-1}.hint{color:#b9c5dc;font-size:14px;margin:14px 0 0}.details{padding:24px;margin-top:24px}.details h2{font-size:24px;margin:0 0 6px}.details p{overflow-wrap:anywhere}.guest-note{color:#b9c5dc;margin:0 0 20px}.post{border-top:1px solid #34415c;padding:16px 0}.post p{white-space:pre-wrap;margin:0 0 12px}.post img,.post video{display:block;width:100%;max-height:520px;object-fit:contain;border-radius:12px;background:#0b1020}.post small{display:block;color:#aebbd2;margin-top:8px}.account-actions{display:flex;gap:18px;flex-wrap:wrap;border-top:1px solid #34415c;padding-top:22px;margin-top:24px}.account-actions a{color:#84e8e2;font-weight:650}footer{color:#aebbd2;text-align:center;font-size:13px;margin-top:24px}@media(max-width:520px){.choices{grid-template-columns:1fr}.button.wide{grid-column:auto}.intro,.details{padding:20px}}
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
      const posts = await publicProfilePosts(pool, profile)
      res.set('Cache-Control', 'no-store')
      res.set('Content-Security-Policy', "default-src 'none'; img-src https:; media-src https:; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
      return res.type('html').send(profileShareHtml(profile, publicBaseUrl, posts))
    } catch (error) { next(error) }
  })
}

module.exports = { escapeHtml, profileShareHtml, publicProfileCard, publicProfilePosts, registerPublicProfileShareRoutes }
