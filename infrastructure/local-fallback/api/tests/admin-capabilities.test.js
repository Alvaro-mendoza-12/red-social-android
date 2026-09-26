const assert = require('node:assert/strict')
const test = require('node:test')
const {
  ADMIN_CAPABILITY,
  adminAccessContract,
  capabilitiesForAdminAccess,
  hasAdminCapability,
  normalizeAdminAccessLevel,
} = require('../src/admin-capabilities')

test('admin capabilities default deny for absent, invalid, and unknown access', () => {
  assert.equal(normalizeAdminAccessLevel('unexpected'), 'none')
  assert.deepEqual(capabilitiesForAdminAccess(), [])
  assert.equal(hasAdminCapability({ level: 'full' }, 'admin.future.unknown'), false)
})

test('view is read-only and cannot inherit mutation capabilities', () => {
  const access = { level: 'view', isOwner: false }
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.READ), true)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.CONTENT_MANAGE), false)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.MODERATION_MANAGE), false)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.CATALOG_MANAGE), false)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.FINANCE_MANAGE), false)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.SYSTEM_READ), false)
})

test('write moderates but cannot manage enterprise catalogs, finance, or system data', () => {
  const access = { level: 'write', isOwner: false }
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.CONTENT_MANAGE), true)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.MODERATION_MANAGE), true)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.CATALOG_MANAGE), false)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.FINANCE_MANAGE), false)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.SYSTEM_READ), false)
})

test('full administers services but cannot grant access or delete users', () => {
  const access = { level: 'full', isOwner: false }
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.CATALOG_MANAGE), true)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.FINANCE_MANAGE), true)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.SYSTEM_READ), true)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.ACCESS_MANAGE), false)
  assert.equal(hasAdminCapability(access, ADMIN_CAPABILITY.USERS_DELETE), false)
})

test('owner-only capabilities are explicit and compatibility fields remain stable', () => {
  const contract = adminAccessContract({ level: 'full', isOwner: true })
  assert.equal(contract.isAdmin, true)
  assert.equal(contract.canWrite, true)
  assert.equal(contract.canDeleteUsers, true)
  assert.equal(contract.capabilities.includes(ADMIN_CAPABILITY.ACCESS_MANAGE), true)
  assert.equal(contract.capabilities.includes(ADMIN_CAPABILITY.USERS_DELETE), true)
})
