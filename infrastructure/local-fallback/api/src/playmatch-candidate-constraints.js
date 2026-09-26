'use strict'

// Discovery and matchmaking must apply the same mandatory constraints. A shared
// normal form also lets SQL filter before the bounded candidate pool is loaded.
function normalizeValue(value) {
  return typeof value === 'string' ? value.trim().slice(0, 80).toLowerCase() : ''
}

function choices(value, limit) {
  const values = Array.isArray(value) ? value : [value]
  return [...new Set(values.map(normalizeValue).filter(Boolean))].slice(0, limit)
}

function normalizeCandidateCriteria(criteria = {}) {
  return {
    gameIds: choices(criteria.gameIds ?? criteria.game_ids ?? criteria.gameId, 12),
    platforms: choices(criteria.platforms ?? criteria.platform, 8),
    roles: choices(criteria.roles ?? criteria.role, 12),
    languages: choices(criteria.languages, 8),
    regions: choices(criteria.regions ?? criteria.region, 8),
    styles: choices(criteria.styles, 4),
    voiceRequired: (criteria.voiceRequired ?? criteria.voice_required) === true,
  }
}

function candidateFitsCriteria(candidate, criteria = {}) {
  const wanted = normalizeCandidateCriteria(criteria)
  const games = Array.isArray(candidate?.games) ? candidate.games : []
  if (wanted.gameIds.length || wanted.platforms.length || wanted.roles.length) {
    // Never combine Valorant/PC with a PlayStation role from Fortnite.
    const sameEntry = games.some((game) => game &&
      (!wanted.gameIds.length || wanted.gameIds.includes(normalizeValue(game.game_id ?? game.gameId))) &&
      (!wanted.platforms.length || wanted.platforms.includes(normalizeValue(game.platform))) &&
      (!wanted.roles.length || wanted.roles.includes(normalizeValue(game.role_key ?? game.role))))
    if (!sameEntry) return false
  }
  const profile = candidate?.profile || {}
  const languages = Array.isArray(profile.languages) ? profile.languages : []
  if (wanted.languages.length && !languages.some((language) => wanted.languages.includes(normalizeValue(language)))) return false
  if (wanted.regions.length && !wanted.regions.includes(normalizeValue(profile.region_code))) return false
  if (wanted.styles.length && !wanted.styles.includes(normalizeValue(profile.play_style))) return false
  if (wanted.voiceRequired && profile.voice_preference !== true) return false
  return true
}

module.exports = { candidateFitsCriteria, normalizeCandidateCriteria }
