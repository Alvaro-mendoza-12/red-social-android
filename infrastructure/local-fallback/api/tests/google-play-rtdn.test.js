const assert = require('assert/strict')

const {
  createGooglePlayRtdnHandler,
  decodeGooglePlayRtdnEnvelope,
  googlePlayRtdnConfiguration,
  verifyGooglePlayRtdnRequest,
} = require('../src/google-play-rtdn')

function envelope(messageId, notification) {
  return {
    message: {
      messageId,
      publishTime: new Date().toISOString(),
      data: Buffer.from(JSON.stringify(notification)).toString('base64'),
    },
  }
}

function fakePool() {
  const events = new Map()
  return {
    events,
    async query(sql, params = []) {
      const text = String(sql)
      if (text.includes('INSERT INTO google_play_rtdn_events')) {
        if (events.has(params[0])) return { rows: [] }
        events.set(params[0], { status: 'processing', attempts: 1 })
        return { rows: [{ message_id: params[0] }] }
      }
      if (text.includes("SET status = 'processing'")) return { rows: [] }
      if (text.startsWith('SELECT status FROM google_play_rtdn_events')) {
        return { rows: events.has(params[0]) ? [events.get(params[0])] : [] }
      }
      if (text.includes("SET status = 'processed'")) {
        events.set(params[0], { status: 'processed', attempts: 1, result: JSON.parse(params[1]) })
        return { rows: [] }
      }
      if (text.includes("SET status = 'failed'")) {
        events.set(params[0], { status: 'failed', attempts: 1, last_error: params[1] })
        return { rows: [] }
      }
      throw new Error(`Unhandled SQL: ${text.slice(0, 100)}`)
    },
  }
}

function response() {
  return {
    statusCode: null,
    ended: false,
    status(code) { this.statusCode = code; return this },
    end() { this.ended = true; return this },
  }
}

async function run() {
  const purchased = decodeGooglePlayRtdnEnvelope(envelope('msg-1', {
    version: '1.0',
    packageName: 'conectate.com',
    eventTimeMillis: String(Date.now()),
    oneTimeProductNotification: {
      version: '1.0',
      notificationType: 1,
      purchaseToken: 'purchase-token',
      sku: 'novvin_coins_70',
    },
  }))
  assert.equal(purchased.kind, 'one_time_product')
  assert.equal(purchased.notificationType, 1)
  assert.equal(purchased.productId, 'novvin_coins_70')
  assert.equal(purchased.purchaseToken, 'purchase-token')
  assert.equal(purchased.purchaseTokenHash.length, 64)

  const voided = decodeGooglePlayRtdnEnvelope(envelope('msg-2', {
    packageName: 'conectate.com',
    eventTimeMillis: String(Date.now()),
    voidedPurchaseNotification: {
      purchaseToken: 'voided-token',
      orderId: 'GPA.1234',
      productType: 2,
      refundType: 1,
    },
  }))
  assert.equal(voided.kind, 'voided_purchase')
  assert.equal(voided.orderIdHash.length, 64)

  const config = googlePlayRtdnConfiguration({
    GOOGLE_PLAY_RTDN_ENABLED: 'true',
    GOOGLE_PLAY_RTDN_AUDIENCE: 'https://api.example.test/webhooks/google-play/rtdn',
    GOOGLE_PLAY_RTDN_PUSH_SERVICE_ACCOUNT_EMAIL: 'push@example.iam.gserviceaccount.com',
    GOOGLE_PLAY_PENDING_REFUND_PREFERENCE: 'INVALID',
  })
  assert.equal(config.ready, true)
  assert.equal(config.pendingRefundPreference, 'NEUTRAL')

  const oauthClient = {
    async verifyIdToken({ idToken, audience }) {
      assert.equal(idToken, 'signed-id-token')
      assert.equal(audience, config.audience)
      return {
        getPayload: () => ({
          iss: 'https://accounts.google.com',
          email: 'push@example.iam.gserviceaccount.com',
          email_verified: true,
        }),
      }
    },
  }
  await verifyGooglePlayRtdnRequest({
    headers: { authorization: 'Bearer signed-id-token' },
    query: {},
  }, config, oauthClient)

  const pool = fakePool()
  let processCount = 0
  const handler = createGooglePlayRtdnHandler({
    pool,
    packageName: 'conectate.com',
    configProvider: () => config,
    oauthClient,
    processNotification: async (event) => {
      processCount += 1
      return { status: event.kind }
    },
  })
  const request = {
    headers: { authorization: 'Bearer signed-id-token' },
    query: {},
    body: envelope('dedupe-1', {
      packageName: 'conectate.com',
      eventTimeMillis: String(Date.now()),
      testNotification: { version: '1.0' },
    }),
  }
  const first = response()
  await handler(request, first, (error) => { throw error })
  assert.equal(first.statusCode, 204)
  assert.equal(first.ended, true)
  const second = response()
  await handler(request, second, (error) => { throw error })
  assert.equal(second.statusCode, 204)
  assert.equal(processCount, 1)
  assert.equal(pool.events.get('dedupe-1').status, 'processed')

  await assert.rejects(
    () => verifyGooglePlayRtdnRequest({ headers: {}, query: {} }, config, oauthClient),
    (error) => error.code === 'GOOGLE_PLAY_RTDN_AUTH_REQUIRED',
  )
  assert.throws(
    () => decodeGooglePlayRtdnEnvelope({ message: { messageId: 'bad', data: '***' } }),
    /GOOGLE_PLAY_RTDN_INVALID/,
  )
}

run()
  .then(() => console.log('google-play-rtdn tests passed'))
  .catch((error) => {
    console.error(error)
    process.exit(1)
  })
