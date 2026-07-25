const TEXT_PIPELINE_VERSION = 2

function normalizeText(value) {
  return String(value || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9ñ\s]/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function tokenize(value) {
  const stopWords = new Set([
    'a',
    'al',
    'como',
    'con',
    'de',
    'del',
    'el',
    'en',
    'es',
    'la',
    'las',
    'lo',
    'los',
    'mi',
    'mis',
    'para',
    'por',
    'que',
    'quiero',
    'se',
    'un',
    'una',
    'y',
  ])
  const aliases = new Map([
    ['alerta', 'notificacion'],
    ['alertas', 'notificacion'],
    ['avisa', 'notificacion'],
    ['aviso', 'notificacion'],
    ['avisos', 'notificacion'],
    ['banner', 'portada'],
    ['cabecera', 'portada'],
    ['charla', 'chat'],
    ['conversacion', 'chat'],
    ['conversaciones', 'chat'],
    ['fotografia', 'foto'],
    ['fotografias', 'foto'],
    ['ia', 'novvin'],
    ['inteligencia', 'novvin'],
    ['exportacion', 'exportar'],
    ['falla', 'error'],
    ['fallando', 'error'],
    ['fallo', 'error'],
    ['publicarlo', 'publicar'],
    ['subida', 'subir'],
  ])
  return normalizeText(value)
    .split(' ')
    .filter((token) => token.length > 1 && !stopWords.has(token))
    .map((token) => aliases.get(token) || token)
}

function vectorize(tokens, vocabulary) {
  const index = new Map(vocabulary.map((token, position) => [token, position]))
  const vector = Array.from({ length: vocabulary.length }, () => 0)
  for (const token of tokens) {
    const position = index.get(token)
    if (position != null) vector[position] += 1
  }
  const total = vector.reduce((sum, value) => sum + value, 0)
  return total > 0 ? vector.map((value) => value / total) : vector
}

module.exports = {
  TEXT_PIPELINE_VERSION,
  normalizeText,
  tokenize,
  vectorize,
}
