'use strict'
const { createRaffleService, RaffleError, requireUuid, validateTarget } = require('./service')
let registeredService

function registerRaffleRoutes(app, dependencies) {
  const { pool, authRequired, getCurrentUser } = dependencies
  const service = createRaffleService(dependencies)
  registeredService = service
  const base = '/v1/data/raffles/:kind/:roomId'
  function route(handler) {
    return async (req, res, next) => {
      try {
        const { kind, roomId } = validateTarget(req.params.kind, req.params.roomId)
        const user = await getCurrentUser(pool, req)
        if (!user) throw new RaffleError('USER_NOT_FOUND', 401)
        if (req.params.id) requireUuid(req.params.id)
        res.json(await handler(req, user, kind, roomId))
      } catch (error) {
        if (error instanceof RaffleError) return res.status(error.status).json({ error: error.code })
        next(error)
      }
    }
  }
  app.get(`${base}/access`, authRequired, route(async (_req, user, kind, roomId) => {
    const ctx = await service.access(pool, user, kind, roomId)
    return { allowed: true, canManage: ctx.canManage, canCreate: ctx.canCreate }
  }))
  app.get(`${base}/members`, authRequired, route(async (req, user, kind, roomId) => {
    const { ctx, participants } = await service.eligible(pool, user, kind, roomId)
    const offset = Number(req.query?.offset || 0)
    const limit = Number(req.query?.limit || 200)
    if (!Number.isInteger(offset) || offset < 0 || offset > 10000 || !Number.isInteger(limit) || limit < 1 || limit > 500) throw new RaffleError('INVALID_PAGE')
    const q = String(req.query?.q || '').trim().toLowerCase().slice(0, 80)
    const filtered = participants.filter((p) => !q || p.name.toLowerCase().includes(q))
    return { participants: filtered.slice(offset, offset + limit), total: filtered.length,
      nextOffset: offset + limit < filtered.length ? offset + limit : null, canManage: ctx.canManage, canCreate: ctx.canCreate }
  }))
  app.get(base, authRequired, route(async (req, user, kind, roomId) => {
    const ctx = await service.access(pool, user, kind, roomId)
    let at = null, id = null
    if (req.query?.cursor) {
      try {
        const cursor = String(req.query.cursor)
        if (cursor.length > 256) throw Error('bad')
        const value = JSON.parse(Buffer.from(cursor, 'base64url').toString())
        at = new Date(value.at).toISOString(); id = requireUuid(value.id)
      } catch (_) { throw new RaffleError('INVALID_CURSOR') }
    }
    const { rows } = await pool.query(`SELECT * FROM fallback_raffles WHERE room_kind=$1 AND room_id=$2
      AND ($3::timestamptz IS NULL OR (created_at,id)<($3::timestamptz,$4::uuid)) ORDER BY created_at DESC,id DESC LIMIT 21`, [kind, roomId, at, id])
    const page = rows.slice(0, 20), last = page.at(-1)
    return { raffles: page.map(service.mapRaffle), canManage: ctx.canManage, canCreate: ctx.canCreate,
      nextCursor: rows.length > 20 ? Buffer.from(JSON.stringify({ at: last.created_at, id: last.id })).toString('base64url') : null }
  }))
  app.post(base, authRequired, route((req, user, kind, roomId) => service.create(user, kind, roomId, req.body || {})))
  app.get(`${base}/:id`, authRequired, route((req, user, kind, roomId) => {
    const before = Number(req.query?.beforeRound || 2147483647)
    if (!Number.isInteger(before) || before < 1 || before > 2147483647) throw new RaffleError('INVALID_PAGE')
    return service.detail(pool, user, kind, roomId, req.params.id, before)
  }))
  app.post(`${base}/:id/:action`, authRequired, route((req, user, kind, roomId) => service.command(user, kind, roomId, req.params.id, req.params.action, req.body || {})))
  return service
}

function startRaffleWorker(service = registeredService, onError = (error) => console.error('[raffles] worker failed', { code: error.code || 'UNKNOWN' })) {
  if (!service) throw new Error('Raffle routes must be registered before starting the worker')
  let running = false
  const tick = async () => {
    if (running) return
    running = true
    try { await service.processDue() } catch (error) { onError(error) } finally { running = false }
  }
  const timer = setInterval(() => { void tick() }, 1000)
  timer.unref?.()
  return () => clearInterval(timer)
}
module.exports = { registerRaffleRoutes, startRaffleWorker }
