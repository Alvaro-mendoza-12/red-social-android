'use strict';

// Public compatibility RPC: it never queries a private account identifier.
// Alias resolution belongs in the authenticated password verification request.
function publicLoginIdentifier(body) {
  const raw = body?.identifier ?? body?.email ?? body?.username ?? body?.p_email ?? '';
  if (typeof raw !== 'string' || raw.length > 320) return '';
  return raw.trim().toLowerCase();
}

module.exports = { publicLoginIdentifier };
