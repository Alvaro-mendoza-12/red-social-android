const assert = require('assert')
const crypto = require('crypto')
const fs = require('fs')
const os = require('os')
const path = require('path')
const {
  executeAdminHardDelete,
  hardDeleteConfirmation,
  isAppAdmin,
  isHardDeleteConfirmationValid,
  isTestAccountTarget,
  normalizeBadgeIds,
  normalizeModerationEvidence,
  normalizeModerationRule,
  normalizeModerationSeverity,
  normalizeProfileFrame,
  normalizeSuspensionDays,
  registerAdminModerationRoutes,
  suspensionWindow,
} = require('../src/admin-moderation-routes')

const ADMIN_ID = '11111111-1111-4111-8111-111111111111'
const TARGET_ID = '22222222-2222-4222-8222-222222222222'
const TARGET_PUBLIC_ID = '33333333-3333-4333-8333-333333333333'

function poolForEmail(email, isAdmin = false) {
  return {
    async query() {
      return { rows: email == null && !isAdmin ? [] : [{ email, is_admin: isAdmin }] }
    },
  }
}

function hardDeletePool({
  actorOwner = true,
  isAdmin = false,
  isTestAccount = true,
  isSuspended = false,
  storageRow,
  restoredAuth = false,
} = {}) {
  const statements = []
  let released = false
  const client = {
    async query(statement) {
      const sql = String(statement).replace(/\s+/g, ' ').trim()
      statements.push(sql)
      if (sql === 'BEGIN' || sql === 'COMMIT' || sql === 'ROLLBACK') return { rows: [], rowCount: 0 }
      if (sql.includes('SELECT u.email,') && sql.includes('COALESCE(m.is_admin')) {
        return {
          rows: [{
            email: 'admin@example.test',
            is_admin: true,
            admin_access_level: 'full',
            admin_is_owner: actorOwner,
          }],
        }
      }
      if (sql.startsWith('SELECT u.id::text,') && sql.includes('FOR UPDATE OF u')) {
        return {
          rows: [{
            id: TARGET_ID,
            public_id: restoredAuth ? TARGET_PUBLIC_ID : null,
            email: isTestAccount ? 'admin-delete-fixture@example.test' : 'persona@conectate.social',
            display_name: 'Fixture de borrado',
            sync_source: restoredAuth ? 'both' : (isTestAccount ? 'test' : 'local'),
            is_test_account: isTestAccount,
            is_suspended: isSuspended,
            is_admin: isAdmin,
          }],
        }
      }
      if (sql.includes("FROM information_schema.tables")) return { rows: [] }
      if (sql.includes('FROM fallback_storage_objects') && sql.includes('FOR UPDATE')) {
        return { rows: storageRow ? [storageRow] : [], rowCount: storageRow ? 1 : 0 }
      }
      if (sql.startsWith('SELECT id::text FROM fallback_') || sql.startsWith('SELECT DISTINCT c.id::text')) {
        return { rows: [], rowCount: 0 }
      }
      if (sql.includes("FROM information_schema.columns") && sql.includes("supabase_import")) {
        return { rows: [], rowCount: 0 }
      }
      if (sql.includes("SELECT to_regclass('auth.users')")) return { rows: [{ oid: restoredAuth ? 'auth.users' : null }] }
      if (sql.startsWith('SELECT EXISTS(SELECT 1 FROM fallback_users')) {
        return { rows: [{ user_row: false, storage_rows: false, profile_override: false, web_sessions: false, moderation_rows: false }] }
      }
      if (sql.startsWith('DELETE FROM fallback_users')) return { rows: [], rowCount: 1 }
      if (/^(DELETE|UPDATE|INSERT)/.test(sql)) return { rows: [], rowCount: 1 }
      throw new Error(`Consulta no contemplada por el fixture: ${sql}`)
    },
    release() {
      released = true
    },
  }
  return {
    client,
    pool: { async connect() { return client } },
    statements,
    wasReleased: () => released,
  }
}

function fakeApp() {
  const routes = []
  const app = {}
  for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
    app[method] = (routePath, ...handlers) => routes.push({ method, path: routePath, handlers })
  }
  return { app, routes }
}

async function invokeRoute(route, req = {}) {
  const response = {
    body: null,
    statusCode: 200,
    status(code) { this.statusCode = code; return this },
    json(body) { this.body = body; return this },
  }
  let index = 0
  const next = async (error) => {
    if (error) throw error
    const handler = route.handlers[index++]
    if (handler) await handler(req, response, next)
  }
  await next()
  return response
}

async function testHelpers() {
  const identity = { localUserId: 'user-1' }
  assert.strictEqual(await isAppAdmin(poolForEmail('gamergamerff004@gmail.com'), identity), false)
  assert.strictEqual(await isAppAdmin(poolForEmail('normal@example.com'), identity), false)
  assert.strictEqual(await isAppAdmin(poolForEmail('moderator@example.com', true), identity), true)
  assert.strictEqual(await isAppAdmin(poolForEmail(null), {}), false)

  assert.deepStrictEqual(normalizeBadgeIds(['creator', 'creator', 'bug-hunter']), ['creator', 'bug-hunter'])
  assert.strictEqual(normalizeBadgeIds(['unknown']), null)
  assert.strictEqual(normalizeProfileFrame('NEON'), 'neon')
  assert.strictEqual(normalizeProfileFrame('unknown'), null)
  assert.strictEqual(normalizeModerationRule('FRAUD_SCAM').title, 'Fraude o estafa')
  assert.strictEqual(normalizeModerationRule('unknown'), null)
  assert.strictEqual(normalizeModerationSeverity('critical'), 'critical')
  assert.strictEqual(normalizeModerationSeverity('unknown'), 'high')
  assert.deepStrictEqual(
    normalizeModerationEvidence([
      'Captura del reporte',
      'Captura del reporte',
      { source: 'report', reference: 'report-1', label: 'Reporte confirmado' },
    ]),
    [
      { source: 'manual', reference: '', label: 'Captura del reporte' },
      { source: 'report', reference: 'report-1', label: 'Reporte confirmado' },
    ],
  )
  assert.strictEqual(isTestAccountTarget({ email: 'fixture+test@example.com' }), true)
  assert.strictEqual(isTestAccountTarget({ email: 'persona@conectate.social' }), false)
  assert.strictEqual(hardDeleteConfirmation(TARGET_ID), `ELIMINAR ${TARGET_ID}`)
  assert.strictEqual(isHardDeleteConfirmationValid(TARGET_ID, `ELIMINAR ${TARGET_ID}`), true)
  assert.strictEqual(isHardDeleteConfirmationValid(TARGET_ID, TARGET_ID), false)

  assert.strictEqual(normalizeSuspensionDays(0), 0)
  assert.strictEqual(normalizeSuspensionDays('30'), 30)
  assert.strictEqual(normalizeSuspensionDays(-1), null)
  assert.strictEqual(normalizeSuspensionDays(3651), null)
  assert.strictEqual(normalizeSuspensionDays(1.5), null)
  assert.deepStrictEqual(suspensionWindow(0, 1_000), { days: 0, suspendedUntil: null })
  assert.deepStrictEqual(suspensionWindow(1, 1_000), {
    days: 1,
    suspendedUntil: new Date(1_000 + 86400000).toISOString(),
  })
}

async function testRoutePermissionsAndValidation() {
  const { app, routes } = fakeApp()
  const pool = {
    async query(sql) {
      if (String(sql).includes('SELECT u.email,')) return { rows: [{ email: 'normal@example.com', is_admin: false }] }
      throw new Error('Un usuario normal no debe llegar a consultas administrativas')
    },
  }
  registerAdminModerationRoutes(app, {
    pool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: TARGET_ID }),
    userIdentity: () => ({ localUserId: TARGET_ID }),
  })
  const listRoute = routes.find((route) => route.method === 'get' && route.path === '/v1/data/admin/users')
  const denied = await invokeRoute(listRoute, { query: {} })
  assert.strictEqual(denied.statusCode, 403)
  assert.deepStrictEqual(denied.body, { error: 'ADMIN_REQUIRED' })

  const viewOnly = fakeApp()
  const viewOnlyPool = {
    async query(sql) {
      if (String(sql).includes('SELECT u.email,')) {
        return {
          rows: [{
            email: 'auditor@example.test',
            is_admin: true,
            admin_access_level: 'view',
            admin_is_owner: false,
          }],
        }
      }
      throw new Error('Un auditor no debe alcanzar una mutación administrativa')
    },
  }
  registerAdminModerationRoutes(viewOnly.app, {
    pool: viewOnlyPool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: ADMIN_ID }),
    userIdentity: () => ({ localUserId: ADMIN_ID, displayUserId: ADMIN_ID }),
  })
  const viewAccessRoute = viewOnly.routes.find((route) =>
    route.method === 'get' && route.path === '/v1/data/admin/access')
  const viewAccess = await invokeRoute(viewAccessRoute, {})
  assert.strictEqual(viewAccess.statusCode, 200)
  assert.strictEqual(viewAccess.body.accessLevel, 'view')
  assert.strictEqual(viewAccess.body.canWrite, false)
  assert.strictEqual(viewAccess.body.capabilities.includes('admin.read'), true)
  const viewPatchRoute = viewOnly.routes.find((route) => route.method === 'patch')
  const viewMutation = await invokeRoute(viewPatchRoute, {
    body: { verified: true },
    params: { id: TARGET_ID },
  })
  assert.strictEqual(viewMutation.statusCode, 403)
  assert.deepStrictEqual(viewMutation.body, {
    error: 'ADMIN_CAPABILITY_REQUIRED',
    capability: 'admin.moderation.manage',
  })

  const adminPool = {
    async query(sql) {
      if (String(sql).includes('SELECT u.email,')) return { rows: [{ email: 'admin@example.test', is_admin: true }] }
      throw new Error('La insignia inválida debe rechazarse antes de buscar el objetivo')
    },
  }
  const second = fakeApp()
  registerAdminModerationRoutes(second.app, {
    pool: adminPool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: ADMIN_ID }),
    userIdentity: () => ({ localUserId: ADMIN_ID, displayUserId: ADMIN_ID }),
  })
  const patchRoute = second.routes.find((route) => route.method === 'patch')
  const invalid = await invokeRoute(patchRoute, { body: { badge_ids: ['not-real'] }, params: { id: TARGET_ID } })
  assert.strictEqual(invalid.statusCode, 400)
  assert.deepStrictEqual(invalid.body, { error: 'BADGE_IDS_INVALID' })
}

async function testPartialDecorationUpdate() {
  const { app, routes } = fakeApp()
  let storedPatch = null
  let invalidations = 0
  const baseUser = {
    local_id: TARGET_ID,
    public_id: null,
    email: 'admin-profile-test@example.test',
    display_name: 'Perfil de prueba',
    username: 'fixture',
    avatar_url: '',
    created_at: new Date(0).toISOString(),
    last_login_at: null,
    sync_source: 'test',
    is_admin: false,
    is_test_account: true,
    is_verified: false,
    is_suspended: false,
    suspended_until: null,
    suspension_reason: null,
    badges: [{ id: 'creator' }],
    profile_frame: 'gold',
  }
  const pool = {
    async query(statement, params = []) {
      const sql = String(statement).replace(/\s+/g, ' ').trim()
      if (sql.includes('SELECT u.email,') && sql.includes('COALESCE(m.is_admin')) {
        return { rows: [{ email: 'admin@example.test', is_admin: true }] }
      }
      if (sql.startsWith('SELECT u.id::text AS local_id')) {
        return { rows: [{ ...baseUser, is_verified: storedPatch?.is_verified ?? false }] }
      }
      if (sql.startsWith('INSERT INTO fallback_profile_overrides')) {
        storedPatch = JSON.parse(params[1])
        return { rows: [], rowCount: 1 }
      }
      if (sql.startsWith('INSERT INTO fallback_moderation_events')) return { rows: [], rowCount: 1 }
      throw new Error(`Consulta inesperada en actualización parcial: ${sql}`)
    },
  }
  registerAdminModerationRoutes(app, {
    pool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: ADMIN_ID }),
    userIdentity: () => ({ localUserId: ADMIN_ID, displayUserId: ADMIN_ID }),
    invalidateProfileCache: () => { invalidations += 1 },
  })
  const route = routes.find((entry) => entry.method === 'patch')
  const response = await invokeRoute(route, {
    body: { verified: true },
    params: { id: TARGET_ID },
  })
  assert.strictEqual(response.statusCode, 200)
  assert.deepStrictEqual(storedPatch, { is_verified: true })
  assert.strictEqual(response.body.user.is_verified, true)
  assert.strictEqual(response.body.user.profile_frame, 'gold')
  assert.deepStrictEqual(response.body.user.badges.map((badge) => badge.id), ['creator'])
  assert.strictEqual(invalidations, 1)
}

async function testCustomBadgeDelete() {
  const { app, routes } = fakeApp()
  let invalidations = 0
  let released = false
  const client = {
    async query(statement) {
      const sql = String(statement).replace(/\s+/g, ' ').trim()
      if (sql === 'BEGIN' || sql === 'COMMIT' || sql === 'ROLLBACK') {
        return { rows: [], rowCount: 0 }
      }
      if (sql.startsWith('SELECT id, name, created_by FROM fallback_admin_badges')) {
        return {
          rows: [{
            id: 'custom-prueba',
            name: 'Prueba',
            created_by: '44444444-4444-4444-8444-444444444444',
          }],
          rowCount: 1,
        }
      }
      if (sql.startsWith('UPDATE fallback_profile_overrides')) return { rows: [], rowCount: 2 }
      if (sql.startsWith('DELETE FROM fallback_admin_badges')) return { rows: [], rowCount: 1 }
      if (sql.startsWith('INSERT INTO fallback_moderation_events')) return { rows: [], rowCount: 1 }
      throw new Error(`Consulta inesperada al borrar insignia: ${sql}`)
    },
    release() {
      released = true
    },
  }
  const pool = {
    async query(statement) {
      const sql = String(statement).replace(/\s+/g, ' ').trim()
      if (sql.includes('SELECT u.email,') && sql.includes('COALESCE(m.is_admin')) {
        return { rows: [{ email: 'admin@example.test', is_admin: true }] }
      }
      throw new Error(`Consulta administrativa inesperada: ${sql}`)
    },
    async connect() {
      return client
    },
  }
  registerAdminModerationRoutes(app, {
    pool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: ADMIN_ID }),
    userIdentity: () => ({ localUserId: ADMIN_ID, displayUserId: ADMIN_ID }),
    invalidateProfileCache: () => { invalidations += 1 },
  })
  const route = routes.find((entry) =>
    entry.method === 'delete' && entry.path === '/v1/data/admin/badges/:id')
  const response = await invokeRoute(route, {
    body: {},
    params: { id: 'custom-prueba' },
  })
  assert.strictEqual(response.statusCode, 200)
  assert.deepStrictEqual(response.body, {
    success: true,
    badgeId: 'custom-prueba',
    profilesUpdated: 2,
  })
  assert.strictEqual(invalidations, 1)
  assert.strictEqual(released, true)
}

async function testFakeAccountHardDelete() {
  const storageRoot = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'conectate-admin-delete-'))
  try {
    const delegatedAdminFixture = hardDeletePool({ actorOwner: false })
    await assert.rejects(
      executeAdminHardDelete({
        actorIdentity: { localUserId: ADMIN_ID, displayUserId: ADMIN_ID },
        confirmation: `ELIMINAR ${TARGET_ID}`,
        pool: delegatedAdminFixture.pool,
        reason: 'Un administrador delegado no puede borrar cuentas',
        storageRoot,
        targetId: TARGET_ID,
      }),
      (error) => error.code === 'ADMIN_OWNER_REQUIRED' && error.status === 403,
    )
    assert.strictEqual(delegatedAdminFixture.statements.includes('ROLLBACK'), true)

    const objectName = `${TARGET_ID}/avatar.jpg`
    const objectPath = path.join(storageRoot, 'avatars', objectName)
    await fs.promises.mkdir(path.dirname(objectPath), { recursive: true })
    await fs.promises.writeFile(objectPath, 'fake-image')
    const digest = crypto.createHash('sha256').update(`avatars:${objectName}`).digest('hex')
    const variantPath = path.join(storageRoot, '_variants', digest.slice(0, 2), digest)
    await fs.promises.mkdir(variantPath, { recursive: true })
    await fs.promises.writeFile(path.join(variantPath, 'thumb.webp'), 'fake-thumbnail')

    const fixture = hardDeletePool({
      restoredAuth: true,
      storageRow: { id: 'object-1', bucket_id: 'avatars', name: objectName, local_path: objectPath },
    })
    const result = await executeAdminHardDelete({
      actorIdentity: { localUserId: ADMIN_ID, displayUserId: ADMIN_ID },
      confirmation: `ELIMINAR ${TARGET_ID}`,
      pool: fixture.pool,
      reason: 'Prueba automatizada aislada',
      storageRoot,
      targetId: TARGET_ID,
    })
    assert.strictEqual(result.storageObjects, 1)
    assert.strictEqual(result.authUsersDeleted, 1)
    assert.strictEqual(result.mediaRemoved, 2)
    assert.strictEqual(fs.existsSync(objectPath), false)
    assert.strictEqual(fs.existsSync(variantPath), false)
    assert.strictEqual(fixture.statements.includes('COMMIT'), true)
    assert.strictEqual(fixture.statements.includes('ROLLBACK'), false)
    assert.strictEqual(fixture.wasReleased(), true)

    const protectedFixture = hardDeletePool({ isTestAccount: false })
    await assert.rejects(
      executeAdminHardDelete({
        actorIdentity: { localUserId: ADMIN_ID, displayUserId: ADMIN_ID },
        confirmation: `ELIMINAR ${TARGET_ID} persona@conectate.social`,
        pool: protectedFixture.pool,
        reason: 'No debe ejecutarse',
        storageRoot,
        targetId: TARGET_ID,
      }),
      (error) => error.code === 'ACCOUNT_MUST_BE_SUSPENDED_BEFORE_DELETE' && error.status === 409,
    )
    assert.strictEqual(protectedFixture.statements.includes('ROLLBACK'), true)

    const suspendedFixture = hardDeletePool({ isTestAccount: false, isSuspended: true })
    const suspendedResult = await executeAdminHardDelete({
      acknowledgeFullPurge: true,
      acknowledgeIrreversible: true,
      actorIdentity: { localUserId: ADMIN_ID, displayUserId: ADMIN_ID },
      allowSuspendedAccountDelete: true,
      confirmation: `ELIMINAR ${TARGET_ID} persona@conectate.social`,
      expectedEmail: 'persona@conectate.social',
      pool: suspendedFixture.pool,
      reason: 'Fixture sancionado por fraude; no es una cuenta real',
      storageRoot,
      targetId: TARGET_ID,
    })
    assert.strictEqual(suspendedResult.deletedRows > 0, true)
    assert.strictEqual(suspendedFixture.statements.includes('COMMIT'), true)

    const activeAdminFixture = hardDeletePool({
      isAdmin: true,
      isTestAccount: false,
      isSuspended: false,
    })
    await assert.rejects(
      executeAdminHardDelete({
        actorIdentity: { localUserId: ADMIN_ID, displayUserId: ADMIN_ID },
        confirmation: `ELIMINAR ${TARGET_ID} persona@conectate.social`,
        pool: activeAdminFixture.pool,
        reason: 'Un administrador activo debe seguir protegido',
        storageRoot,
        targetId: TARGET_ID,
      }),
      (error) => error.code === 'ADMIN_CANNOT_BE_DELETED' && error.status === 403,
    )

    const suspendedAdminFixture = hardDeletePool({
      isAdmin: true,
      isTestAccount: false,
      isSuspended: true,
    })
    const suspendedAdminResult = await executeAdminHardDelete({
      acknowledgeFullPurge: true,
      acknowledgeIrreversible: true,
      actorIdentity: { localUserId: ADMIN_ID, displayUserId: ADMIN_ID },
      allowSuspendedAccountDelete: true,
      confirmation: `ELIMINAR ${TARGET_ID} persona@conectate.social`,
      expectedEmail: 'persona@conectate.social',
      pool: suspendedAdminFixture.pool,
      reason: 'Toda cuenta suspendida puede eliminarse',
      storageRoot,
      targetId: TARGET_ID,
    })
    assert.strictEqual(suspendedAdminResult.deletedRows > 0, true)
    assert.strictEqual(suspendedAdminFixture.statements.includes('COMMIT'), true)
  } finally {
    await fs.promises.rm(storageRoot, { recursive: true, force: true })
  }
}

async function run() {
  await testHelpers()
  await testRoutePermissionsAndValidation()
  await testPartialDecorationUpdate()
  await testCustomBadgeDelete()
  await testFakeAccountHardDelete()
  console.log('admin moderation route tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
