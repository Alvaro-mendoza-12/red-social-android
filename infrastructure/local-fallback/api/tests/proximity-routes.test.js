const assert = require('assert')
const {
  approximateDistanceKm,
  haversineKm,
  mutuallyCompatible,
} = require('../src/proximity-routes')

const bogotaToNearby = haversineKm(4.711, -74.072, 4.721, -74.072)
assert.ok(bogotaToNearby > 1 && bogotaToNearby < 1.2)
assert.strictEqual(approximateDistanceKm(0.04), 0.5)
assert.strictEqual(approximateDistanceKm(1.24), 1)
assert.strictEqual(approximateDistanceKm(1.26), 1.5)

assert.strictEqual(
  mutuallyCompatible(
    { gender: 'man', looking_for: 'woman' },
    { gender: 'woman', looking_for: 'man' },
  ),
  true,
)
assert.strictEqual(
  mutuallyCompatible(
    { gender: 'man', looking_for: 'woman' },
    { gender: 'man', looking_for: 'any' },
  ),
  false,
)
assert.strictEqual(
  mutuallyCompatible(
    { gender: 'nonbinary', looking_for: 'any' },
    { gender: 'woman', looking_for: 'any' },
  ),
  true,
)

console.log('proximity route tests passed')
