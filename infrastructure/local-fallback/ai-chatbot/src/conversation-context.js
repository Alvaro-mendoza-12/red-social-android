const { normalizeText, tokenize } = require('./text')

function isFollowUp(message) {
  const normalized = normalizeText(message)
  return tokenize(message).length <= 8 && (
    /^(y|tambien|entonces|eso|esa|ese|ahi|donde|pero|foto|portada|grupo|comunidad)\b/.test(normalized) ||
    /^(explica(?:me)? mas|dame un ejemplo|otro ejemplo|mas detalles|paso a paso|no funciono|sigue igual|continua|por que|como asi)$/.test(normalized)
  )
}

/** Only the caller's bounded conversation is considered; assistant replies are never promoted to user instructions. */
function contextualizeMessage(message, context = {}) {
  const clean = String(message || '').trim().slice(0, 1000)
  if (!isFollowUp(clean)) return clean
  const history = typeof context.history === 'string' ? context.history.slice(-8000) : ''
  const prior = history.split(/\r?\n/)
    .filter((line) => line.startsWith('Usuario:'))
    .map((line) => line.slice('Usuario:'.length).trim().slice(0, 1000))
    .filter(Boolean)
    .slice(-8)
  const normalized = normalizeText(clean)
  while (prior.length && normalizeText(prior.at(-1)) === normalized) prior.pop()
  const topic = [...prior].reverse().find((line) => !isFollowUp(line)) || prior.at(-1)
  return topic ? `${topic} ${clean}`.slice(0, 2000) : clean
}

module.exports = { contextualizeMessage }
