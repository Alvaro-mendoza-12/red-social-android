const assert = require('assert/strict')
const { _test, registerPostgrestCompatRoutes } = require('../src/postgrest-compat-routes')

global.fetch = async () => ({ ok: true, status: 202 })

function createAppRecorder() {
  const routes = { delete: [], get: [], head: [], patch: [], post: [] }
  const app = { routes }
  for (const method of Object.keys(routes)) {
    app[method] = (path, ...handlers) => routes[method].push({ handlers, path })
  }
  return app
}

function responseRecorder() {
  return {
    body: null,
    ended: false,
    headers: {},
    statusCode: 200,
    end() {
      this.ended = true
      return this
    },
    json(body) {
      this.body = body
      return this
    },
    setHeader(name, value) {
      this.headers[String(name).toLowerCase()] = String(value)
    },
    status(code) {
      this.statusCode = code
      return this
    },
  }
}

function routeHandler(app, method, fragment) {
  const route = app.routes[method].find((entry) => String(entry.path).includes(fragment))
  assert.ok(route, `Missing ${method.toUpperCase()} route containing ${fragment}`)
  return route.handlers.at(-1)
}

async function invoke(handler, req) {
  const res = responseRecorder()
  let nextError = null
  await handler(req, res, (error) => { nextError = error || null })
  return { nextError, res }
}

function identityRow() {
  return {
    avatar_url: null,
    display_name: 'Alice',
    email: 'alice@example.test',
    local_user_id: '11111111-1111-4111-8111-111111111111',
    public_user_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  }
}

async function run() {
  const ledgerIdentity = {
    ids: new Set([
      identityRow().local_user_id,
      identityRow().public_user_id,
    ]),
    localUserId: identityRow().local_user_id,
    preferredUserId: identityRow().public_user_id,
    values: [identityRow().local_user_id, identityRow().public_user_id],
  }
  const serverManagedTables = [
    'billing_purchases',
    'creator_earnings',
    'gift_transactions',
    'user_subscriptions',
    'wallet_transactions',
    'wallets',
  ]
  const noLedgerQueryPool = {
    async query() {
      assert.fail('A rejected generic ledger mutation must not query the database')
    },
  }
  for (const tableName of serverManagedTables) {
    assert.equal(_test.SERVER_MUTATION_ONLY_TABLES.has(tableName), true)
    await assert.rejects(
      _test.prepareInsertRow(noLedgerQueryPool, tableName, ledgerIdentity, {
        user_id: identityRow().public_user_id,
        balance_diamonds: 999_999,
      }),
      (error) => error?.status === 403 && error?.message === 'SERVER_MANAGED_RESOURCE',
      `${tableName} must reject generic inserts`,
    )
    assert.equal(
      await _test.authorizeMutation(
        noLedgerQueryPool,
        tableName,
        ledgerIdentity,
        { user_id: identityRow().public_user_id },
        { balance_diamonds: 999_999, status: 'paid' },
        'patch',
      ),
      false,
      `${tableName} must reject generic patches`,
    )
    assert.equal(
      await _test.authorizeMutation(
        noLedgerQueryPool,
        tableName,
        ledgerIdentity,
        { user_id: identityRow().public_user_id },
        {},
        'delete',
      ),
      false,
      `${tableName} must reject generic deletes`,
    )
  }

  const previousLiveGiftsFlag = process.env.LIVE_GIFTS_ENABLED
  delete process.env.LIVE_GIFTS_ENABLED
  try {
    let identityQueries = 0
    await assert.rejects(
      _test.sendVirtualGiftRpc({
        async query(sql) {
          identityQueries += 1
          assert.match(String(sql), /FROM fallback_users/)
          return { rows: [identityRow()] }
        },
      }, { sub: identityRow().local_user_id }, {
        gift_id: '7a100001-0000-4000-8000-000000000001',
        receiver_id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      }),
      (error) => error?.status === 503 && error?.message === 'GIFTS_DISABLED',
      'gift RPC must fail closed before any ledger/catalog query when the kill switch is off',
    )
    assert.equal(identityQueries, 1)
    assert.equal(_test.liveGiftTransactionsEnabled(), false)
  } finally {
    if (previousLiveGiftsFlag === undefined) delete process.env.LIVE_GIFTS_ENABLED
    else process.env.LIVE_GIFTS_ENABLED = previousLiveGiftsFlag
  }

  assert.notEqual(
    _test.rowId({ chat_id: 'chat-a', folder_id: 'folder-a', user_id: 'user-a' }, 'chat_folder_chats'),
    _test.rowId({ chat_id: 'chat-b', folder_id: 'folder-a', user_id: 'user-a' }, 'chat_folder_chats'),
  )
  assert.notEqual(
    _test.rowId({ moment_id: 'moment-a', highlight_id: 'highlight-a', user_id: 'user-a' }, 'moment_highlight_items'),
    _test.rowId({ moment_id: 'moment-b', highlight_id: 'highlight-a', user_id: 'user-a' }, 'moment_highlight_items'),
  )
  assert.notEqual(
    _test.rowId({ poll_id: 'poll-a', option_id: 'option-a', user_id: 'user-a' }, 'message_poll_votes'),
    _test.rowId({ poll_id: 'poll-a', option_id: 'option-b', user_id: 'user-a' }, 'message_poll_votes'),
  )
  assert.deepEqual(
    _test.parsePollOptions(['  Uno  ', 'Dos', 'uno', '', 'Tres']),
    ['Uno', 'Dos', 'Tres'],
  )
  assert.deepEqual(
    _test.parsePollOptions('{"Primera opcion","Segunda, opcion"}'),
    ['Primera opcion', 'Segunda, opcion'],
  )

  let reportSelect = ''
  const reportRows = await _test.readSpecificRows({
    async query(sql) {
      reportSelect = String(sql)
      return {
        rows: [{
          content_id: 'post-1',
          content_type: 'post',
          reporter_id: identityRow().public_user_id,
        }],
      }
    },
  }, 'reports')
  assert.match(reportSelect, /content_type/)
  assert.match(reportSelect, /content_id/)
  assert.match(reportSelect, /updated_at/)
  assert.equal(reportRows[0].content_type, 'post')
  assert.equal(reportRows[0].content_id, 'post-1')

  const purgeQueries = []
  const purgedRows = await _test.purgeImportedIdentityRows({
    async query(sql, params) {
      purgeQueries.push({ params, text: String(sql) })
      return { rows: [{ affected: 3 }] }
    },
  }, ['user-1'])
  assert.equal(purgedRows, 3)
  assert.equal(purgeQueries.length, 1)
  assert.match(purgeQueries[0].text, /novvin_import_mutation\.purge_rows/)
  assert.deepEqual(purgeQueries[0].params, [['user-1'], 'identity'])
  assert.doesNotMatch(purgeQueries[0].text, /DELETE FROM|LIKE|information_schema/)

  const evidenceIdentity = {
    localUserId: identityRow().local_user_id,
  }
  let evidenceQueries = 0
  const evidencePool = {
    async query(sql, params) {
      evidenceQueries += 1
      assert.match(String(sql), /fallback_storage_objects/)
      assert.deepEqual(params, [
        'report-evidence',
        'alice/reports/owned.png',
        identityRow().local_user_id,
      ])
      return { rows: [{ '?column?': 1 }] }
    },
  }
  assert.equal(await _test.canUseReportEvidence(evidencePool, evidenceIdentity, null), true)
  assert.equal(
    await _test.canUseReportEvidence(
      evidencePool,
      evidenceIdentity,
      'https://api.example.test/storage/v1/object/public/videos/alice/reports/owned.png',
    ),
    false,
  )
  assert.equal(
    await _test.canUseReportEvidence(
      evidencePool,
      evidenceIdentity,
      'https://api.example.test/storage/v1/object/public/report-evidence/alice/reports/owned.png',
    ),
    true,
  )
  assert.equal(evidenceQueries, 1)
  assert.equal(
    await _test.canUseReportEvidence(
      { async query() { return { rows: [] } } },
      evidenceIdentity,
      'https://api.example.test/storage/v1/object/public/avatars/alice/legacy.png',
    ),
    false,
  )

  const noQueryPool = {
    async query() {
      assert.fail('Database must not be queried for a rejected anonymous/unknown route')
    },
  }
  const app = createAppRecorder()
  registerPostgrestCompatRoutes(app, {
    authOptional: (_req, _res, next) => next(),
    pool: noQueryPool,
  })

  const getTable = routeHandler(app, 'get', 'rest\\/v1')
  let result = await invoke(getTable, {
    auth: null,
    headers: {},
    params: ['profiles'],
    query: {},
  })
  assert.equal(result.res.statusCode, 401)
  assert.deepEqual(result.res.body, { error: 'AUTH_REQUIRED' })

  result = await invoke(getTable, {
    auth: { sub: identityRow().local_user_id },
    headers: {},
    params: ['private_secrets'],
    query: {},
  })
  assert.equal(result.res.statusCode, 404)

  const postRpc = routeHandler(app, 'post', 'rpc')
  result = await invoke(postRpc, {
    auth: null,
    body: {},
    headers: {},
    params: ['secure_send_message'],
  })
  assert.equal(result.res.statusCode, 401)

  result = await invoke(postRpc, {
    auth: { sub: identityRow().local_user_id },
    body: {},
    headers: {},
    params: ['unknown_privileged_rpc'],
  })
  assert.equal(result.res.statusCode, 404)
  assert.equal(result.res.body.code, 'PGRST202')

  const suspendedApp = createAppRecorder()
  let suspensionQueries = 0
  registerPostgrestCompatRoutes(suspendedApp, {
    authOptional: (_req, _res, next) => next(),
    pool: {
      async query(sql) {
        suspensionQueries += 1
        assert.match(String(sql), /fallback_profile_moderation/)
        return {
          rows: [{
            is_suspended: true,
            suspended_until: null,
            suspension_reason: 'Incumplimiento de normas',
          }],
        }
      },
    },
  })
  result = await invoke(routeHandler(suspendedApp, 'get', 'rest\\/v1'), {
    auth: { sub: identityRow().local_user_id },
    headers: {},
    params: ['profiles'],
    query: {},
  })
  assert.equal(suspensionQueries, 1)
  assert.equal(result.res.statusCode, 403)
  assert.equal(result.res.body.error, 'ACCOUNT_SUSPENDED')

  const profileApp = createAppRecorder()
  let profileQueries = 0
  registerPostgrestCompatRoutes(profileApp, {
    authOptional: (_req, _res, next) => next(),
    pool: {
      async query(sql) {
        profileQueries += 1
        if (String(sql).includes('fallback_profile_moderation')) return { rows: [] }
        if (String(sql).includes('AS local_user_id')) return { rows: [identityRow()] }
        assert.fail(`Unexpected profile query: ${sql}`)
      },
    },
  })
  const postTableRoute = profileApp.routes.post.find((entry) => !String(entry.path).includes('rpc'))
  assert.ok(postTableRoute, 'Missing POST table route')
  const postTable = postTableRoute.handlers.at(-1)
  result = await invoke(postTable, {
    auth: { sub: identityRow().local_user_id },
    body: { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', display_name: 'Spoofed' },
    headers: {},
    params: ['profiles'],
    query: {},
  })
  assert.equal(profileQueries, 2)
  assert.equal(result.nextError?.status, 403)
  assert.equal(result.nextError?.message, 'PROFILE_ID_MISMATCH')

  const chatId = '22222222-2222-4222-8222-222222222222'
  const messageId = '33333333-3333-4333-8333-333333333333'
  const calls = []
  let persistedMessage
  const messageApp = createAppRecorder()
  registerPostgrestCompatRoutes(messageApp, {
    authOptional: (_req, _res, next) => next(),
    pool: {
      async query(sql, params = []) {
        const text = String(sql)
        calls.push({ params, text })
        if (text.includes('fallback_profile_moderation')) return { rows: [] }
        if (text.includes('AS local_user_id')) return { rows: [identityRow()] }
        if (text.includes('SELECT id::text AS chat_id')) return { rows: [{ chat_id: chatId }] }
        if (text.includes('FROM fallback_compat_rows')) return { rows: [] }
        if (text.includes('SELECT * FROM fallback_messages')) {
          assert.match(text, /ORDER BY created_at DESC, id DESC LIMIT 1 FOR SHARE/)
          assert.deepEqual(params, [chatId])
          assert.ok(persistedMessage, 'Summary reads the persisted row after INSERT')
          return { rows: [persistedMessage] }
        }
        if (text.includes('INSERT INTO fallback_messages')) {
          persistedMessage = {
              chat_id: params[1],
              content: params[3],
              content_type: params[4],
              created_at: new Date().toISOString(),
              id: params[0],
              media_size: params[6],
              media_url: params[5],
              sender_id: params[2],
              status: 'sent',
          }
          return { rows: [persistedMessage] }
        }
        if (text.includes('UPDATE fallback_chats')) {
          assert.deepEqual(params, [chatId, 'mensaje persistido', persistedMessage.created_at, identityRow().public_user_id, messageId])
          assert.match(text, /last_message_at <= \$3::timestamptz/)
          return { rowCount: 1, rows: [] }
        }
        if (text.includes('SELECT DISTINCT participant')) return { rows: [] }
        assert.fail(`Unexpected message query: ${text}`)
      },
    },
  })
  const messageRpc = routeHandler(messageApp, 'post', 'rpc')
  result = await invoke(messageRpc, {
    auth: { sub: identityRow().local_user_id },
    body: {
      p_chat_id: chatId,
      p_content: 'mensaje persistido',
      p_content_type: 'text',
      p_media_size: 0,
      p_message_id: messageId,
    },
    headers: {},
    params: ['secure_send_message'],
  })
  assert.equal(result.nextError, null)
  assert.equal(result.res.statusCode, 200)
  assert.equal(result.res.body.id, messageId)
  assert.equal(result.res.body.chat_id, chatId)
  assert.equal(result.res.body.content, 'mensaje persistido')
  assert.equal(result.res.body.sender_id, identityRow().public_user_id)
  assert.equal(calls.some((call) => call.text.includes('INSERT INTO fallback_messages')), true)

  console.log('postgrest security tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
