const ADMIN_ACCESS_RANK = Object.freeze({ none: 0, view: 1, write: 2, full: 3 })

const ADMIN_CAPABILITY = Object.freeze({
  ACCESS_MANAGE: 'admin.access.manage',
  CATALOG_MANAGE: 'admin.catalog.manage',
  CONTENT_MANAGE: 'admin.content.manage',
  FINANCE_MANAGE: 'admin.finance.manage',
  MODERATION_MANAGE: 'admin.moderation.manage',
  READ: 'admin.read',
  SYSTEM_READ: 'admin.system.read',
  USERS_DELETE: 'admin.users.delete',
})

const LEVEL_CAPABILITIES = Object.freeze({
  none: Object.freeze([]),
  view: Object.freeze([ADMIN_CAPABILITY.READ]),
  write: Object.freeze([
    ADMIN_CAPABILITY.READ,
    ADMIN_CAPABILITY.CONTENT_MANAGE,
    ADMIN_CAPABILITY.MODERATION_MANAGE,
  ]),
  full: Object.freeze([
    ADMIN_CAPABILITY.READ,
    ADMIN_CAPABILITY.CONTENT_MANAGE,
    ADMIN_CAPABILITY.MODERATION_MANAGE,
    ADMIN_CAPABILITY.CATALOG_MANAGE,
    ADMIN_CAPABILITY.FINANCE_MANAGE,
    ADMIN_CAPABILITY.SYSTEM_READ,
  ]),
})

const OWNER_CAPABILITIES = Object.freeze([
  ADMIN_CAPABILITY.ACCESS_MANAGE,
  ADMIN_CAPABILITY.USERS_DELETE,
])

function normalizeAdminAccessLevel(value, fallback = 'none') {
  const level = String(value || '').trim().toLowerCase()
  return Object.prototype.hasOwnProperty.call(ADMIN_ACCESS_RANK, level) ? level : fallback
}

function capabilitiesForAdminAccess(access = {}) {
  const level = normalizeAdminAccessLevel(access.level)
  const capabilities = new Set(LEVEL_CAPABILITIES[level])
  if (access.isOwner === true) OWNER_CAPABILITIES.forEach((capability) => capabilities.add(capability))
  return Array.from(capabilities).sort()
}

function hasAdminCapability(access, capability) {
  if (!Object.values(ADMIN_CAPABILITY).includes(capability)) return false
  return capabilitiesForAdminAccess(access).includes(capability)
}

function adminAccessContract(access = {}) {
  const level = normalizeAdminAccessLevel(access.level)
  const isOwner = access.isOwner === true
  const capabilities = capabilitiesForAdminAccess({ level, isOwner })
  return {
    isAdmin: level !== 'none',
    accessLevel: level,
    isOwner,
    capabilities,
    // Compatibility fields for clients predating named capabilities.
    canWrite: hasAdminCapability({ level, isOwner }, ADMIN_CAPABILITY.MODERATION_MANAGE),
    canDeleteUsers: hasAdminCapability({ level, isOwner }, ADMIN_CAPABILITY.USERS_DELETE),
  }
}

module.exports = {
  ADMIN_ACCESS_RANK,
  ADMIN_CAPABILITY,
  LEVEL_CAPABILITIES,
  adminAccessContract,
  capabilitiesForAdminAccess,
  hasAdminCapability,
  normalizeAdminAccessLevel,
}
