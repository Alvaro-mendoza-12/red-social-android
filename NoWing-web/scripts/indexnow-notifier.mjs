import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { publicRoutes, site } from './public-seo.mjs'

const allowed = new Set(publicRoutes.map(([route]) => route))
const defaultState = path.resolve(import.meta.dirname, '../.indexnow-state.local.json')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const meta = (html, name) => new RegExp('<meta (?:name|property)="' + name + '" content="([^"]*)"', 'i').exec(html)?.[1]
export function retryDelay(value, now) {
  if (!value) return 1000
  const ms = /^\d+$/.test(value) ? Number(value) * 1000 : Date.parse(value) - now
  return Number.isFinite(ms) ? Math.max(0, ms) : 1000
}

// The default is read-only preparation. --submit belongs after deployment approval.
export async function prepareIndexNow({ routes, submit = false, deleted = false, stateFile = defaultState, fetchFn = fetch, now = Date.now, wait = pause }) {
  const unique = [...new Set(routes)]
  if (!unique.length || unique.length > 100) throw new Error('Pass 1–100 changed canonical public routes')
  const readState = () => fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, 'utf8')) : { version: 1, pages: {} }
  let lock
  if (submit) {
    fs.mkdirSync(path.dirname(stateFile), { recursive: true })
    try { lock = fs.openSync(stateFile + '.lock', 'wx') } catch { throw new Error('Another IndexNow submission is running; no duplicate request sent') }
  }
  try {
    const state = readState()
    if (state.version !== 1 || !state.pages || typeof state.pages !== 'object') throw new Error('Invalid IndexNow receipt history')
    const persist = () => {
      const temporary = stateFile + '.' + process.pid + '.tmp'
      fs.writeFileSync(temporary, JSON.stringify(state, null, 2) + '\n', { flag: 'wx' })
      try { fs.renameSync(temporary, stateFile) } catch (error) { fs.unlinkSync(temporary); throw error }
    }
    for (const route of unique) {
      if (typeof route !== 'string' || !route.startsWith('/') || /[?#\\]/.test(route) || route.includes('..') || (!allowed.has(route) && !(deleted && Object.hasOwn(state.pages, route)))) throw new Error('Not an eligible public route: ' + route)
    }
    const pending = [], skipped = []
    for (const route of unique) {
      const response = await fetchFn(site + route, { redirect: 'manual', signal: AbortSignal.timeout(15000) })
      let fingerprint = 'deleted'
      if (deleted) {
        if (!state.pages[route] || ![404, 410].includes(response.status)) throw new Error('Deletion needs previous public receipt and current HTTP 404/410: ' + route)
      } else {
        if (response.status !== 200 || !response.headers.get('content-type')?.includes('text/html')) throw new Error('Public page is unavailable: ' + route)
        const html = await response.text()
        if (/noindex|none/i.test(response.headers.get('x-robots-tag') || '') || /noindex|none/i.test(meta(html, 'robots') || '')) throw new Error('Page is noindex: ' + route)
        if (!html.includes('rel="canonical" href="' + site + route + '"') || !/<h1>/.test(html)) throw new Error('Missing canonical or readable public content: ' + route)
        fingerprint = createHash('sha256').update(html).digest('hex')
      }
      if (state.pages[route]?.hash === fingerprint) skipped.push(route)
      else pending.push({ route, hash: fingerprint })
    }
    const result = { submitted: false, dryRun: !submit, deleted, routes: pending.map(page => page.route), skipped, note: 'Receipts do not prove crawling, indexing or ranking.' }
    if (!submit || !pending.length) return result
    if ((state.cooldownUntil || 0) > now() || (state.lastAttemptAt && now() - state.lastAttemptAt < 60000)) throw new Error('Rate limit: wait one minute or the requested server cooldown between batches')
    const keyLocation = site + '/indexnow-key.txt'
    const proof = await fetchFn(keyLocation, { redirect: 'manual', signal: AbortSignal.timeout(15000) })
    const key = (await proof.text()).trim()
    if (proof.status !== 200 || !/^[a-zA-Z0-9-]{8,128}$/.test(key)) throw new Error('Published IndexNow key is invalid')
    state.lastAttemptAt = now(); persist()
    let response
    for (let attempt = 0; attempt < 3; attempt++) {
      response = await fetchFn('https://api.indexnow.org/indexnow', { method: 'POST', redirect: 'error', headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: JSON.stringify({ host: new URL(site).host, key, keyLocation, urlList: pending.map(page => site + page.route) }), signal: AbortSignal.timeout(20000) })
      if ([200, 202].includes(response.status)) break
      if (response.headers.has('retry-after')) {
        state.cooldownUntil = now() + retryDelay(response.headers.get('retry-after'), now()); persist()
      }
      if (![429, 500, 502, 503, 504].includes(response.status) || attempt === 2) throw new Error('IndexNow rejected notification: HTTP ' + response.status)
      const delay = retryDelay(response.headers.get('retry-after'), now()) * (response.headers.has('retry-after') ? 1 : 2 ** attempt)
      if (delay > 30000) throw new Error('IndexNow cooldown exceeds 30 seconds; retry later, after ' + Math.ceil(delay / 1000) + ' seconds')
      await wait(delay)
    }
    const acceptedAt = now()
    state.lastSubmittedAt = acceptedAt
    for (const page of pending) state.pages[page.route] = { hash: page.hash, acceptedAt, status: response.status }
    delete state.cooldownUntil
    persist()
    return { ...result, submitted: true, status: response.status, validationPending: response.status === 202 }
  } finally { if (lock !== undefined) { fs.closeSync(lock); fs.unlinkSync(stateFile + '.lock') } }
}
