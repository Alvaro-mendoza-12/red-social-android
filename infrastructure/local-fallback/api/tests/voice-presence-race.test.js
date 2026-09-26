'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

test('stale signaling disconnect cannot delete a newer voice presence row', () => {
  const dataRoutes = fs.readFileSync(path.resolve(__dirname, '..', 'src', 'data-routes.js'), 'utf8')
  const signaling = fs.readFileSync(
    path.resolve(__dirname, '..', '..', '..', '..', 'signaling-server', 'index.js'),
    'utf8',
  )

  assert.match(dataRoutes, /AND \(\$3 = '' OR socket_id = \$3\)/)
  assert.match(dataRoutes, /const departingSocketId = String\(req\.body\?\.socketId \|\| ''\)\.trim\(\)/)
  assert.match(signaling, /body: \{ socketId: socket\.id \}/)
})
