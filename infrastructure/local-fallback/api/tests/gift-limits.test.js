const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const { liveGiftLimitsConfiguration } = require('../src/gift-safety-config')
const { DEFAULT_VIRTUAL_GIFTS } = require('../src/postgrest-compat-routes')._test

const names = [
  'LIVE_GIFT_MAX_COINS_PER_GIFT',
  'LIVE_GIFT_MAX_COINS_PER_DAY',
  'LIVE_GIFT_MAX_GIFTS_PER_MINUTE',
  'LIVE_GIFT_HIGH_VALUE_CONFIRMATION_COINS',
]
const previous = Object.fromEntries(names.map((name) => [name, process.env[name]]))

try {
  names.forEach((name) => { delete process.env[name] })
  assert.deepEqual(liveGiftLimitsConfiguration(), {
    max_coins_per_gift: 5000,
    max_coins_per_day: 25000,
    max_gifts_per_minute: 20,
    high_value_confirmation_coins: 900,
  })

  process.env.LIVE_GIFT_MAX_COINS_PER_GIFT = '700'
  process.env.LIVE_GIFT_MAX_COINS_PER_DAY = '100'
  process.env.LIVE_GIFT_MAX_GIFTS_PER_MINUTE = '999999'
  process.env.LIVE_GIFT_HIGH_VALUE_CONFIRMATION_COINS = '900'
  assert.deepEqual(liveGiftLimitsConfiguration(), {
    max_coins_per_gift: 700,
    max_coins_per_day: 700,
    max_gifts_per_minute: 300,
    high_value_confirmation_coins: 700,
  })

  const giftIds = DEFAULT_VIRTUAL_GIFTS.map((gift) => gift.id)
  const visualKeys = DEFAULT_VIRTUAL_GIFTS.map((gift) => gift.image_url.split('/').pop())
  const prices = DEFAULT_VIRTUAL_GIFTS.map((gift) => gift.price_diamonds)
  assert.equal(DEFAULT_VIRTUAL_GIFTS.length, 24)
  assert.equal(new Set(giftIds).size, 24)
  assert.equal(new Set(visualKeys).size, 24)
  assert.ok(prices.every((price, index) => index === 0 || price > prices[index - 1]))
  assert.deepEqual(
    DEFAULT_VIRTUAL_GIFTS.map((gift) => gift.sort_order),
    Array.from({ length: 24 }, (_, index) => index + 1),
  )
  assert.ok(DEFAULT_VIRTUAL_GIFTS.every((gift) => gift.price_credits === 0))
  assert.equal(DEFAULT_VIRTUAL_GIFTS.at(-1).name, 'Génesis Aurora')
  assert.equal(DEFAULT_VIRTUAL_GIFTS.at(-1).price_diamonds, 5000)

  const repositoryRoot = path.resolve(__dirname, '../../../..')
  const manifest = JSON.parse(fs.readFileSync(
    path.join(repositoryRoot, 'app/src/main/res/raw/novvin_gift_manifest_v1.json'),
    'utf8',
  ))
  assert.deepEqual(manifest.assets.map((asset) => asset.giftId), giftIds)
  assert.deepEqual(manifest.assets.map((asset) => asset.visualKey), visualKeys)

  const migration = fs.readFileSync(
    path.join(repositoryRoot, 'sql/migrations/20260801_live_gift_catalog_v2.sql'),
    'utf8',
  )
  const sqlRows = Array.from(migration.matchAll(
    /\('([0-9a-f-]{36})',\s*'([^']+)',\s*'[^']*',\s*'conectate:\/\/gift-assets\/([^']+)',\s*(\d+),\s*0\.00,[^\n]+true,\s*(\d+)\)/g,
  )).map((match) => ({
    id: match[1],
    name: match[2],
    visualKey: match[3],
    price: Number(match[4]),
    order: Number(match[5]),
  }))
  assert.deepEqual(
    sqlRows,
    DEFAULT_VIRTUAL_GIFTS.map((gift) => ({
      id: gift.id,
      name: gift.name,
      visualKey: gift.image_url.split('/').pop(),
      price: gift.price_diamonds,
      order: gift.sort_order,
    })),
  )

  console.log('gift catalog and limits tests passed')
} finally {
  names.forEach((name) => {
    if (previous[name] === undefined) delete process.env[name]
    else process.env[name] = previous[name]
  })
}
