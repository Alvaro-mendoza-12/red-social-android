'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const {
  usernameProblem,
  usernameSeed,
  usernameCandidates,
  usernameOwnedByAnother,
} = require('../src/username-policy')

test('visible names may repeat while public handles obey a separate policy', () => {
  assert.equal(usernameSeed('Mirella Vásquez'), 'mirellavasquez')
  assert.equal(usernameProblem('mirella_vr07'), null)
  assert.equal(usernameProblem('admin'), 'USERNAME_RESERVED')
  assert.equal(usernameProblem('a'), 'USERNAME_INVALID')
  assert.equal(usernameProblem('mirella vr07'), 'USERNAME_INVALID')
  assert.ok(usernameCandidates('Mirella Vásquez', 'mirella_vr07').includes('mirellavr072'))
})

test('availability checks every stored profile source and excludes the owner', async () => {
  const queries = []
  const pool = {
    query: async (sql, values) => {
      queries.push({ sql, values })
      if (sql.includes('fallback_profile_overrides')) return { rows: [{ '?column?': 1 }] }
      return { rows: [] }
    },
  }
  assert.equal(await usernameOwnedByAnother(pool, 'Mirella_VR07'), true)
  assert.deepEqual(queries[0].values, ['mirella_vr07', []])
  assert.match(queries[0].sql, /fallback_compat_rows/)
})
