const crypto = require('crypto')
const { NeuralIntentClassifier } = require('./neural-network')
const { normalizeText, tokenize, vectorize } = require('./text')
const { classifyGeneralRisk, safeReferral } = require('./safety-policy')

const KNOWLEDGE_MODEL_TYPE = 'conectate-first-party-knowledge-v1'
const DEFAULT_KNOWLEDGE_HIDDEN_SIZE = 96
const DEFAULT_KNOWLEDGE_EPOCHS = 140
const DEFAULT_KNOWLEDGE_LEARNING_RATE = 0.055
const DEFAULT_KNOWLEDGE_THRESHOLD = 0.34

const TOKEN_ALIASES = new Map([
  ['aprendizaje', 'aprender'],
  ['computadora', 'computador'],
  ['computadoras', 'computador'],
  ['ordenador', 'computador'],
  ['ordenadores', 'computador'],
  ['planetas', 'planeta'],
  ['porcentajes', 'porcentaje'],
  ['resumir', 'resumen'],
  ['resumo', 'resumen'],
  ['sintetizar', 'resumen'],
  ['sintetizo', 'resumen'],
  ['estudiar', 'estudio'],
  ['estudiante', 'estudio'],
  ['estudiantes', 'estudio'],
  ['entrenada', 'entrenamiento'],
  ['entrenadas', 'entrenamiento'],
  ['entrenado', 'entrenamiento'],
  ['entrenados', 'entrenamiento'],
  ['basura', 'residuo'],
  ['basuras', 'residuo'],
  ['residuos', 'residuo'],
  ['sonidos', 'sonido'],
  ['estrellas', 'estrella'],
])

function boundedNumber(value, fallback, minimum, maximum) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.max(minimum, Math.min(maximum, parsed))
}

function parseBoolean(value, fallback = true) {
  if (value == null || value === '') return fallback
  return /^(1|true|yes|on)$/i.test(String(value).trim())
}

function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => (
      `${JSON.stringify(key)}:${stableStringify(value[key])}`
    )).join(',')}}`
  }
  return JSON.stringify(value)
}

function datasetFingerprint(...datasets) {
  return crypto
    .createHash('sha256')
    .update(stableStringify(datasets))
    .digest('hex')
}

function canonicalTokens(value) {
  return tokenize(value).map((token) => {
    const alias = TOKEN_ALIASES.get(token)
    if (alias) return alias
    if (token.length > 6 && token.endsWith('ciones')) return `${token.slice(0, -6)}cion`
    if (token.length > 5 && token.endsWith('es')) return token.slice(0, -2)
    if (token.length > 4 && token.endsWith('s')) return token.slice(0, -1)
    return token
  })
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

function knowledgeFeatures(value) {
  const tokens = canonicalTokens(value)
  const features = tokens.map((token) => `w:${token}`)
  for (let index = 0; index < tokens.length - 1; index += 1) {
    features.push(`b:${tokens[index]}_${tokens[index + 1]}`)
  }
  for (const token of tokens) {
    for (const trigram of characterTrigrams(token)) features.push(`c:${trigram}`)
  }
  return features
}

function lexicalTerms(value) {
  const tokens = canonicalTokens(value)
  const terms = tokens.map((token) => `w:${token}`)
  for (let index = 0; index < tokens.length - 1; index += 1) {
    terms.push(`b:${tokens[index]}_${tokens[index + 1]}`)
  }
  return terms
}

function cardTrainingExamples(card) {
  return Array.isArray(card?.examples?.train) ? card.examples.train : []
}

function validateKnowledgeData(knowledgeData) {
  const cards = knowledgeData?.cards
  if (!Array.isArray(cards) || cards.length === 0) {
    throw new Error('Knowledge dataset must include cards')
  }
  const ids = new Set()
  for (const card of cards) {
    if (!/^[a-z0-9_]+$/.test(String(card.id || ''))) {
      throw new Error(`Invalid knowledge card id: ${card.id}`)
    }
    if (ids.has(card.id)) throw new Error(`Duplicate knowledge card id: ${card.id}`)
    ids.add(card.id)
    if (cardTrainingExamples(card).length < 5) {
      throw new Error(`Knowledge card ${card.id} needs at least 5 training examples`)
    }
    if (!Array.isArray(card?.examples?.evaluation) || card.examples.evaluation.length < 1) {
      throw new Error(`Knowledge card ${card.id} needs held-out evaluation examples`)
    }
    if (!String(card.summary || '').trim() || !Array.isArray(card.facts) || card.facts.length < 2) {
      throw new Error(`Knowledge card ${card.id} needs a summary and at least 2 facts`)
    }
    const outputText = [card.summary, ...(card.facts || []), card.example, card.followUp]
      .filter(Boolean)
      .join(' ')
    const unsafeCategory = classifyGeneralRisk(outputText)
    if (unsafeCategory) {
      throw new Error(`Knowledge card ${card.id} contains disallowed ${unsafeCategory} content`)
    }
  }
  return true
}

function buildKnowledgeTrainingSet(knowledgeData) {
  validateKnowledgeData(knowledgeData)
  const labels = knowledgeData.cards.map((card) => card.id)
  const vocabularySet = new Set()
  const rawSamples = []
  knowledgeData.cards.forEach((card, labelIndex) => {
    const trainingTexts = [
      ...cardTrainingExamples(card),
      card.title,
      ...(card.keywords || []),
      card.summary,
      ...(card.facts || []),
    ].filter(Boolean)
    for (const example of trainingTexts) {
      const features = knowledgeFeatures(example)
      features.forEach((feature) => vocabularySet.add(feature))
      rawSamples.push({ cardId: card.id, text: example, features, labelIndex })
    }
  })
  const vocabulary = Array.from(vocabularySet).sort()
  const samples = rawSamples.map((sample) => ({
    input: vectorize(sample.features, vocabulary),
    labelIndex: sample.labelIndex,
  }))
  return { labels, rawSamples, samples, vocabulary }
}

function createIdf(rawSamples) {
  const documentFrequency = new Map()
  for (const sample of rawSamples) {
    const uniqueTerms = new Set(lexicalTerms(sample.text))
    for (const term of uniqueTerms) {
      documentFrequency.set(term, (documentFrequency.get(term) || 0) + 1)
    }
  }
  const total = rawSamples.length
  return Object.fromEntries(Array.from(documentFrequency.entries()).map(([term, frequency]) => [
    term,
    Number((Math.log((total + 1) / (frequency + 1)) + 1).toFixed(8)),
  ]))
}

function sparseTfidf(value, idf) {
  const counts = new Map()
  for (const term of lexicalTerms(value)) counts.set(term, (counts.get(term) || 0) + 1)
  const entries = []
  let squaredNorm = 0
  for (const [term, count] of counts) {
    if (!Object.hasOwn(idf, term)) continue
    const weight = (1 + Math.log(count)) * idf[term]
    entries.push([term, weight])
    squaredNorm += weight * weight
  }
  if (squaredNorm === 0) return []
  const norm = Math.sqrt(squaredNorm)
  return entries.map(([term, weight]) => [term, Number((weight / norm).toFixed(8))])
}

function cosineSparse(left, right) {
  if (!left.length || !right.length) return 0
  const rightMap = new Map(right)
  let score = 0
  for (const [term, weight] of left) score += weight * (rightMap.get(term) || 0)
  return score
}

function classifierParameterCount(inputSize, hiddenSize, outputSize) {
  return (inputSize * hiddenSize) + hiddenSize + (hiddenSize * outputSize) + outputSize
}

function trainKnowledgeModel(knowledgeData, options = {}) {
  const hiddenSize = Math.round(boundedNumber(
    options.hiddenSize ?? process.env.AI_KNOWLEDGE_HIDDEN_SIZE,
    DEFAULT_KNOWLEDGE_HIDDEN_SIZE,
    32,
    256,
  ))
  const epochs = Math.round(boundedNumber(
    options.epochs ?? process.env.AI_KNOWLEDGE_EPOCHS,
    DEFAULT_KNOWLEDGE_EPOCHS,
    30,
    300,
  ))
  const learningRate = boundedNumber(
    options.learningRate ?? process.env.AI_KNOWLEDGE_LEARNING_RATE,
    DEFAULT_KNOWLEDGE_LEARNING_RATE,
    0.001,
    0.2,
  )
  const { labels, rawSamples, samples, vocabulary } = buildKnowledgeTrainingSet(knowledgeData)
  const classifier = new NeuralIntentClassifier({
    inputSize: vocabulary.length,
    hiddenSize,
    outputSize: labels.length,
    labels,
    seed: 20260715,
  })
  classifier.train(samples, { epochs, learningRate })
  const idf = createIdf(rawSamples)
  return {
    modelType: KNOWLEDGE_MODEL_TYPE,
    datasetVersion: knowledgeData.version || 1,
    datasetFingerprint: datasetFingerprint(knowledgeData),
    trainingSamples: samples.length,
    training: {
      seed: 20260715,
      epochs,
      learningRate,
      parameters: classifierParameterCount(vocabulary.length, hiddenSize, labels.length),
    },
    vocabulary,
    labels,
    classifier: classifier.toJSON(),
    lexical: {
      idf,
      examples: rawSamples.map((sample) => ({
        cardId: sample.cardId,
        vector: sparseTfidf(sample.text, idf),
      })),
    },
  }
}

function formatNumber(value) {
  if (!Number.isFinite(value)) return null
  const rounded = Math.abs(value) < 1e-12 ? 0 : Number(value.toFixed(8))
  return new Intl.NumberFormat('es-CO', { maximumFractionDigits: 8 }).format(rounded)
}

function parseDecimal(value) {
  return Number(String(value).replace(',', '.'))
}

function solveArithmetic(message) {
  const raw = String(message || '').trim().toLowerCase()
  const percent = raw.match(/(-?\d+(?:[.,]\d+)?)\s*(?:%|por\s+ciento)\s+de\s+(-?\d+(?:[.,]\d+)?)/i)
  if (percent) {
    const rate = parseDecimal(percent[1])
    const base = parseDecimal(percent[2])
    if (Math.abs(rate) > 1e9 || Math.abs(base) > 1e12) return null
    const result = (rate / 100) * base
    return {
      expression: `${formatNumber(rate)} % de ${formatNumber(base)}`,
      result: formatNumber(result),
      explanation: `Se multiplica ${formatNumber(base)} por ${formatNumber(rate)} y se divide entre 100.`,
    }
  }

  const normalized = raw
    .replace(/¿|\?/g, ' ')
    .replace(/cuanto\s+es|calcula|resultado\s+de/g, ' ')
    .replace(/multiplicado\s+por|veces|por/g, '*')
    .replace(/dividido\s+entre|dividido\s+por|entre/g, '/')
    .replace(/mas/g, '+')
    .replace(/menos/g, '-')
    .replace(/\s+/g, ' ')
    .trim()
  const basic = normalized.match(/^(-?\d+(?:[.,]\d+)?)\s*([+\-*/x×])\s*(-?\d+(?:[.,]\d+)?)$/)
  if (!basic) return null
  const left = parseDecimal(basic[1])
  const right = parseDecimal(basic[3])
  if (Math.abs(left) > 1e12 || Math.abs(right) > 1e12) return null
  const operator = basic[2] === 'x' || basic[2] === '×' ? '*' : basic[2]
  if (operator === '/' && right === 0) {
    return {
      expression: `${formatNumber(left)} / 0`,
      result: null,
      explanation: 'No se puede dividir entre cero.',
    }
  }
  const result = operator === '+'
    ? left + right
    : operator === '-'
      ? left - right
      : operator === '*'
        ? left * right
        : left / right
  return {
    expression: `${formatNumber(left)} ${operator} ${formatNumber(right)}`,
    result: formatNumber(result),
    explanation: `La operacion ${formatNumber(left)} ${operator} ${formatNumber(right)} da ${formatNumber(result)}.`,
  }
}

function wantsDetailedAnswer(message, context = {}) {
  if (context.detailLevel === 'expanded') return true
  return /\b(explica|explicame|detalle|detallado|detallada|pasos|ejemplo|por que|como funciona|amplia)\b/i
    .test(normalizeText(message))
}

function factOverlapScore(fact, queryTerms) {
  const factTerms = new Set(canonicalTokens(fact))
  let score = 0
  for (const term of queryTerms) if (factTerms.has(term)) score += 1
  return score
}

function composeCardAnswer(card, message, context) {
  const detailed = wantsDetailedAnswer(message, context)
  const queryTerms = canonicalTokens(message)
  const rankedFacts = (card.facts || [])
    .map((fact, index) => ({ fact, index, score: factOverlapScore(fact, queryTerms) }))
    .sort((left, right) => right.score - left.score || left.index - right.index)
  const factCount = detailed ? Math.min(4, rankedFacts.length) : Math.min(2, rankedFacts.length)
  const facts = rankedFacts.slice(0, factCount).map((entry) => entry.fact)
  const sections = [card.summary]
  if (facts.length) {
    sections.push(detailed
      ? `Puntos clave:\n${facts.map((fact) => `- ${fact}`).join('\n')}`
      : facts.join(' '))
  }
  if (detailed && card.example) sections.push(`Ejemplo: ${card.example}`)
  if (card.followUp) sections.push(card.followUp)
  return sections.join('\n\n')
}

class FirstPartyKnowledgeEngine {
  constructor({ knowledgeData, model, enabled, threshold } = {}) {
    validateKnowledgeData(knowledgeData)
    if (!model || model.modelType !== KNOWLEDGE_MODEL_TYPE) {
      throw new Error('A compatible first-party knowledge model is required')
    }
    this.knowledgeData = knowledgeData
    this.model = model
    this.enabled = enabled ?? parseBoolean(process.env.AI_KNOWLEDGE_ENABLED, true)
    this.threshold = boundedNumber(
      threshold ?? process.env.AI_KNOWLEDGE_THRESHOLD,
      DEFAULT_KNOWLEDGE_THRESHOLD,
      0.15,
      0.8,
    )
    this.cards = new Map(knowledgeData.cards.map((card) => [card.id, card]))
    this.classifier = NeuralIntentClassifier.fromJSON(model.classifier)
    this.lexicalExamples = model.lexical.examples
    this.idf = model.lexical.idf
    this.keywordTerms = new Map(knowledgeData.cards.map((card) => [
      card.id,
      new Set((card.keywords || []).flatMap((keyword) => lexicalTerms(keyword))),
    ]))
  }

  status() {
    return {
      enabled: this.enabled,
      modelType: this.model.modelType,
      datasetVersion: this.model.datasetVersion,
      cards: this.cards.size,
      vocabulary: this.model.vocabulary.length,
      hiddenNeurons: this.model.classifier.hiddenSize,
      parameters: this.model.training.parameters,
      trainingSamples: this.model.trainingSamples,
      threshold: this.threshold,
      networkAccess: false,
      pretrainedModel: false,
    }
  }

  match(message) {
    const features = knowledgeFeatures(message)
    if (!features.length) return null
    const queryVector = sparseTfidf(message, this.idf)
    if (!queryVector.length) return null
    const prediction = this.classifier.predict(vectorize(features, this.model.vocabulary))
    const neuralScores = new Map(this.model.labels.map((label, index) => [
      label,
      prediction.probabilities[index] || 0,
    ]))
    const lexicalScores = new Map(this.model.labels.map((label) => [label, 0]))
    for (const example of this.lexicalExamples) {
      const score = cosineSparse(queryVector, example.vector)
      if (score > lexicalScores.get(example.cardId)) lexicalScores.set(example.cardId, score)
    }
    const queryTermSet = new Set(lexicalTerms(message))
    const candidates = this.model.labels.map((cardId) => {
      const keywordTerms = this.keywordTerms.get(cardId) || new Set()
      let keywordHits = 0
      for (const term of queryTermSet) if (keywordTerms.has(term)) keywordHits += 1
      const keywordScore = Math.min(1, keywordHits / 2)
      const lexicalScore = lexicalScores.get(cardId) || 0
      const neuralScore = neuralScores.get(cardId) || 0
      return {
        cardId,
        lexicalScore,
        neuralScore,
        keywordHits,
        score: (lexicalScore * 0.55) + (neuralScore * 0.3) + (keywordScore * 0.15),
      }
    }).sort((left, right) => right.score - left.score)
    const best = candidates[0]
    const second = candidates[1]
    if (!best || best.lexicalScore < 0.08 || best.score < this.threshold) return null
    if (best.keywordHits < 1 && best.lexicalScore < 0.45) return null
    if (second && best.score - second.score < 0.025 && best.keywordHits < 2 && best.lexicalScore < 0.35) {
      return null
    }
    return {
      card: this.cards.get(best.cardId),
      confidence: Number(best.score.toFixed(4)),
      lexicalConfidence: Number(best.lexicalScore.toFixed(4)),
      neuralConfidence: Number(best.neuralScore.toFixed(4)),
    }
  }

  answer(message, context = {}) {
    const riskCategory = classifyGeneralRisk(message)
    if (riskCategory) {
      return {
        answer: safeReferral(riskCategory),
        intent: 'safe_referral',
        confidence: 1,
        suggestions: [],
        responseMode: 'safe_referral',
        knowledgeId: null,
        knowledgeConfidence: 1,
        safetyCategory: riskCategory,
        grounded: true,
      }
    }
    if (!this.enabled) return null

    const calculation = solveArithmetic(message)
    if (calculation) {
      return {
        answer: calculation.result == null
          ? calculation.explanation
          : `${calculation.expression} = ${calculation.result}.\n\n${calculation.explanation}`,
        intent: 'general_calculation',
        confidence: 1,
        suggestions: ['Otro calculo', 'Explicar porcentajes'],
        responseMode: 'calculation',
        knowledgeId: 'calculator',
        knowledgeConfidence: 1,
        safetyCategory: null,
        grounded: true,
      }
    }

    const match = this.match(message)
    if (!match?.card) return null
    return {
      answer: composeCardAnswer(match.card, message, context),
      intent: 'general_knowledge',
      confidence: match.confidence,
      suggestions: match.card.suggestions || [],
      responseMode: 'grounded_general',
      knowledgeId: match.card.id,
      knowledgeConfidence: match.confidence,
      lexicalConfidence: match.lexicalConfidence,
      neuralConfidence: match.neuralConfidence,
      safetyCategory: null,
      grounded: true,
    }
  }
}

module.exports = {
  DEFAULT_KNOWLEDGE_EPOCHS,
  DEFAULT_KNOWLEDGE_HIDDEN_SIZE,
  DEFAULT_KNOWLEDGE_LEARNING_RATE,
  DEFAULT_KNOWLEDGE_THRESHOLD,
  FirstPartyKnowledgeEngine,
  KNOWLEDGE_MODEL_TYPE,
  buildKnowledgeTrainingSet,
  composeCardAnswer,
  datasetFingerprint,
  knowledgeFeatures,
  lexicalTerms,
  solveArithmetic,
  trainKnowledgeModel,
  validateKnowledgeData,
}
