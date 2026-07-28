const { normalizeText } = require('./text')

const SEMANTIC_SEARCH_MODEL = 'novvin-semantic-local-v1'

const STOP_WORDS = new Set([
  'a', 'al', 'algo', 'como', 'con', 'de', 'del', 'el', 'en', 'es', 'esta',
  'este', 'la', 'las', 'lo', 'los', 'para', 'por', 'que', 'se', 'sin', 'su',
  'sus', 'un', 'una', 'unos', 'unas', 'y',
])

const CONCEPT_GROUPS = [
  ['alimentacion', 'alimento', 'comida', 'cocina', 'cocinar', 'gastronomia', 'receta', 'nutricion', 'nutritivo'],
  ['salud', 'saludable', 'bienestar', 'cuidado', 'sano'],
  ['animal', 'animales', 'gato', 'gatos', 'mascota', 'mascotas', 'perro', 'perros'],
  ['codigo', 'codificacion', 'desarrollo', 'desarrollador', 'programacion', 'programar', 'software'],
  ['ciencia', 'cientifico', 'experimento', 'fisica', 'investigacion', 'quimica'],
  ['aprender', 'aprendizaje', 'educacion', 'ensenanza', 'estudiar', 'estudio'],
  ['ejercicio', 'entrenamiento', 'fitness', 'gimnasio', 'deporte'],
  ['futbol', 'balompie'],
  ['arte', 'creatividad', 'dibujo', 'ilustracion', 'pintura'],
  ['cancion', 'musica', 'musical', 'sonido'],
  ['naturaleza', 'bosque', 'ecologia', 'medioambiente', 'planeta'],
  ['aventura', 'turismo', 'viajar', 'viaje', 'viajes'],
  ['juego', 'juegos', 'gaming', 'gamer', 'videojuego', 'videojuegos'],
  ['tecnologia', 'digital', 'innovacion', 'internet'],
]

const CONCEPT_BY_TOKEN = new Map()
CONCEPT_GROUPS.forEach((terms, index) => {
  const concept = `concept:${index}`
  terms.forEach((term) => CONCEPT_BY_TOKEN.set(term, concept))
})

function stemToken(value) {
  const token = String(value || '')
  if (token.length > 7 && token.endsWith('aciones')) return `${token.slice(0, -7)}acion`
  if (token.length > 6 && token.endsWith('mente')) return token.slice(0, -5)
  if (token.length > 6 && token.endsWith('ciones')) return `${token.slice(0, -6)}cion`
  if (token.length > 5 && token.endsWith('ando')) return token.slice(0, -4)
  if (token.length > 5 && token.endsWith('iendo')) return token.slice(0, -5)
  if (token.length > 5 && token.endsWith('es')) return token.slice(0, -2)
  if (token.length > 4 && token.endsWith('s')) return token.slice(0, -1)
  return token
}

function semanticTokens(value) {
  return normalizeText(value)
    .split(' ')
    .map(stemToken)
    .filter((token) => token.length > 1 && !STOP_WORDS.has(token))
}

function characterTrigrams(token) {
  if (token.length < 4) return []
  const padded = `_${token}_`
  const result = []
  for (let index = 0; index <= padded.length - 3; index += 1) {
    result.push(padded.slice(index, index + 3))
  }
  return result
}

function weightedFeatures(value, { title = false, tags = false } = {}) {
  const tokens = semanticTokens(value)
  const features = new Map()
  const lexicalWeight = title ? 2.4 : tags ? 1.8 : 1
  const add = (key, amount) => features.set(key, (features.get(key) || 0) + amount)

  tokens.forEach((token) => {
    add(`word:${token}`, lexicalWeight)
    const concept = CONCEPT_BY_TOKEN.get(token)
    if (concept) add(concept, lexicalWeight * 0.9)
    characterTrigrams(token).forEach((trigram) => add(`char:${trigram}`, lexicalWeight * 0.08))
  })
  for (let index = 0; index < tokens.length - 1; index += 1) {
    add(`pair:${tokens[index]}_${tokens[index + 1]}`, lexicalWeight * 0.65)
  }
  return features
}

function mergeFeatures(...maps) {
  const merged = new Map()
  maps.forEach((features) => {
    features.forEach((weight, feature) => {
      merged.set(feature, (merged.get(feature) || 0) + weight)
    })
  })
  return merged
}

function cosineSimilarity(left, right) {
  let dot = 0
  let leftNorm = 0
  let rightNorm = 0
  left.forEach((value, feature) => {
    leftNorm += value * value
    dot += value * (right.get(feature) || 0)
  })
  right.forEach((value) => {
    rightNorm += value * value
  })
  if (!leftNorm || !rightNorm) return 0
  return dot / Math.sqrt(leftNorm * rightNorm)
}

function rankSemanticDocuments(query, documents, { limit = 20, minimumScore = 0.02 } = {}) {
  const cleanQuery = String(query || '').trim()
  const boundedLimit = Math.max(1, Math.min(Number(limit) || 20, 50))
  if (cleanQuery.length < 2) return []

  const queryFeatures = weightedFeatures(cleanQuery, { title: true })
  const queryConcepts = new Set(
    semanticTokens(cleanQuery).map((token) => CONCEPT_BY_TOKEN.get(token)).filter(Boolean),
  )

  return (Array.isArray(documents) ? documents : [])
    .map((document) => {
      const titleFeatures = weightedFeatures(document?.title, { title: true })
      const bodyFeatures = weightedFeatures(document?.text)
      const tagsFeatures = weightedFeatures(
        Array.isArray(document?.tags) ? document.tags.join(' ') : document?.tags,
        { tags: true },
      )
      const documentFeatures = mergeFeatures(titleFeatures, bodyFeatures, tagsFeatures)
      const documentConcepts = new Set(
        semanticTokens([
          document?.title,
          document?.text,
          Array.isArray(document?.tags) ? document.tags.join(' ') : document?.tags,
        ].join(' ')).map((token) => CONCEPT_BY_TOKEN.get(token)).filter(Boolean),
      )
      const matchedConcepts = Array.from(queryConcepts).filter((concept) => documentConcepts.has(concept))
      const conceptBoost = Math.min(0.18, matchedConcepts.length * 0.09)
      const score = Math.min(1, cosineSimilarity(queryFeatures, documentFeatures) + conceptBoost)
      return {
        id: String(document?.id || '').trim(),
        score: Number(score.toFixed(6)),
        matchedConcepts: matchedConcepts.length,
        reason: matchedConcepts.length > 0 ? 'semantic_concept' : 'semantic_similarity',
      }
    })
    .filter((result) => result.id && result.score >= minimumScore)
    .sort((left, right) => right.score - left.score || left.id.localeCompare(right.id))
    .slice(0, boundedLimit)
}

module.exports = {
  SEMANTIC_SEARCH_MODEL,
  rankSemanticDocuments,
  semanticTokens,
}
