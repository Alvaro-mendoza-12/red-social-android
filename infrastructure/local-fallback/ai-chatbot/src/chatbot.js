const fs = require('fs')
const path = require('path')
const { NeuralIntentClassifier } = require('./neural-network')
const {
  FirstPartyKnowledgeEngine,
  datasetFingerprint,
  trainKnowledgeModel,
} = require('./knowledge-engine')
const { classifyGeneralRisk, redactSensitiveText } = require('./safety-policy')
const { TEXT_PIPELINE_VERSION, normalizeText, tokenize, vectorize } = require('./text')

const MODEL_TYPE = 'conectate-first-party-hybrid-v5'
const DEFAULT_HIDDEN_SIZE = 512
const DEFAULT_EPOCHS = 100
const DEFAULT_LEARNING_RATE = 0.06
const DEFAULT_FEEDBACK_MAX_BYTES = 16 * 1024 * 1024

function boundedNumber(value, fallback, minimum, maximum) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.max(minimum, Math.min(maximum, parsed))
}

function parseBoolean(value, fallback = true) {
  if (value == null || value === '') return fallback
  return /^(1|true|yes|on)$/i.test(String(value).trim())
}

function classifierParameterCount(inputSize, hiddenSize, outputSize) {
  return (inputSize * hiddenSize) + hiddenSize + (hiddenSize * outputSize) + outputSize
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function readJsonIfExists(file, fallback = {}) {
  return file && fs.existsSync(file) ? readJson(file) : fallback
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true })
}

function writeJsonAtomic(file, payload) {
  const temporaryFile = `${file}.${process.pid}.tmp`
  fs.writeFileSync(temporaryFile, JSON.stringify(payload))
  fs.renameSync(temporaryFile, file)
}

function stableIndex(text, length) {
  if (length <= 1) return 0
  let hash = 0
  for (const char of String(text || '')) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  return hash % length
}

function buildTrainingSet(intents, outOfDomainPatterns = []) {
  const vocabularySet = new Set()
  const labels = intents.map((intent) => intent.id)
  const rawSamples = []

  intents.forEach((intent, labelIndex) => {
    for (const pattern of intent.patterns || []) {
      const tokens = tokenize(pattern)
      tokens.forEach((token) => vocabularySet.add(token))
      rawSamples.push({ tokens, labelIndex })
    }
  })

  if (outOfDomainPatterns.length) {
    const labelIndex = labels.length
    labels.push('__out_of_domain__')
    for (const pattern of outOfDomainPatterns) {
      const tokens = tokenize(pattern)
      tokens.forEach((token) => vocabularySet.add(token))
      rawSamples.push({ tokens, labelIndex })
    }
  }

  const vocabulary = Array.from(vocabularySet).sort()
  const samples = rawSamples.map((sample) => ({
    input: vectorize(sample.tokens, vocabulary),
    labelIndex: sample.labelIndex,
  }))

  return { vocabulary, labels, samples }
}

function trainModel(intentsData, options = {}) {
  const intents = intentsData.intents || []
  const { vocabulary, labels, samples } = buildTrainingSet(
    intents,
    intentsData.outOfDomain?.patterns || [],
  )
  if (!labels.length || !vocabulary.length || !samples.length) {
    throw new Error('AI training dataset must include intents, patterns and vocabulary')
  }
  const hiddenSize = Math.round(
    boundedNumber(
      options.hiddenSize ?? process.env.AI_HIDDEN_SIZE,
      DEFAULT_HIDDEN_SIZE,
      32,
      5000,
    ),
  )
  const epochs = Math.round(
    boundedNumber(options.epochs ?? process.env.AI_TRAIN_EPOCHS, DEFAULT_EPOCHS, 20, 500),
  )
  const learningRate = boundedNumber(
    options.learningRate ?? process.env.AI_LEARNING_RATE,
    DEFAULT_LEARNING_RATE,
    0.001,
    0.25,
  )
  const classifier = new NeuralIntentClassifier({
    inputSize: vocabulary.length,
    hiddenSize,
    outputSize: labels.length,
    labels,
    seed: 20260714,
  })
  classifier.train(samples, {
    epochs,
    learningRate,
  })
  const knowledgeData = options.knowledgeData
  if (!knowledgeData) throw new Error('AI training requires the first-party knowledge dataset')
  const knowledgeModel = trainKnowledgeModel(knowledgeData, {
    hiddenSize: options.knowledgeHiddenSize,
    epochs: options.knowledgeEpochs,
    learningRate: options.knowledgeLearningRate,
  })
  return {
    createdAt: new Date().toISOString(),
    modelType: MODEL_TYPE,
    datasetVersion: intentsData.version || 1,
    knowledgeVersion: knowledgeData.version || 1,
    datasetFingerprint: datasetFingerprint(
      { textPipelineVersion: TEXT_PIPELINE_VERSION },
      intentsData,
      knowledgeData,
    ),
    trainingSamples: samples.length,
    training: {
      epochs,
      learningRate,
      parameters: classifierParameterCount(vocabulary.length, hiddenSize, labels.length),
    },
    vocabulary,
    labels,
    classifier: classifier.toJSON(),
    knowledge: knowledgeModel,
  }
}

class ConectateChatbot {
  constructor({
    intentsFile,
    guidesFile,
    knowledgeFile,
    modelFile,
    feedbackFile,
    threshold = 0.34,
    hiddenSize = process.env.AI_HIDDEN_SIZE || DEFAULT_HIDDEN_SIZE,
    epochs = process.env.AI_TRAIN_EPOCHS || DEFAULT_EPOCHS,
    learningRate = process.env.AI_LEARNING_RATE || DEFAULT_LEARNING_RATE,
    feedbackMaxBytes = process.env.AI_FEEDBACK_MAX_BYTES || DEFAULT_FEEDBACK_MAX_BYTES,
    knowledgeEnabled = process.env.AI_KNOWLEDGE_ENABLED,
    knowledgeThreshold = process.env.AI_KNOWLEDGE_THRESHOLD,
    knowledgeHiddenSize = process.env.AI_KNOWLEDGE_HIDDEN_SIZE,
    knowledgeEpochs = process.env.AI_KNOWLEDGE_EPOCHS,
    knowledgeLearningRate = process.env.AI_KNOWLEDGE_LEARNING_RATE,
  } = {}) {
    this.intentsFile = intentsFile
    this.guidesFile = guidesFile || path.join(path.dirname(intentsFile), 'guides.json')
    this.knowledgeFile = knowledgeFile || path.join(path.dirname(intentsFile), 'knowledge.json')
    this.modelFile = modelFile
    this.feedbackFile = feedbackFile
    this.threshold = boundedNumber(threshold, 0.34, 0.05, 0.95)
    this.hiddenSize = Math.round(boundedNumber(hiddenSize, DEFAULT_HIDDEN_SIZE, 32, 5000))
    this.epochs = Math.round(boundedNumber(epochs, DEFAULT_EPOCHS, 20, 500))
    this.learningRate = boundedNumber(learningRate, DEFAULT_LEARNING_RATE, 0.001, 0.25)
    this.feedbackMaxBytes = Math.round(
      boundedNumber(feedbackMaxBytes, DEFAULT_FEEDBACK_MAX_BYTES, 64 * 1024, 256 * 1024 * 1024),
    )
    this.knowledgeEnabled = parseBoolean(knowledgeEnabled, true)
    this.knowledgeThreshold = boundedNumber(knowledgeThreshold, 0.34, 0.15, 0.8)
    this.knowledgeHiddenSize = Math.round(boundedNumber(knowledgeHiddenSize, 96, 32, 256))
    this.knowledgeEpochs = Math.round(boundedNumber(knowledgeEpochs, 140, 30, 300))
    this.knowledgeLearningRate = boundedNumber(knowledgeLearningRate, 0.055, 0.001, 0.2)
    this.intentsData = readJson(intentsFile)
    this.guidesData = readJsonIfExists(this.guidesFile, { version: 0, guides: {} })
    this.knowledgeData = readJson(this.knowledgeFile)
    this.guides = this.guidesData.guides || {}
    this.intentById = new Map((this.intentsData.intents || []).map((intent) => [intent.id, intent]))
    this.model = null
    this.classifier = null
    this.knowledgeEngine = null
    this.vocabularySet = new Set()
    this.lastLoadAction = null
  }

  loadOrTrain() {
    ensureDir(path.dirname(this.modelFile))
    if (fs.existsSync(this.modelFile)) {
      try {
        this.model = readJson(this.modelFile)
      } catch {
        this.model = null
      }
    }
    const expectedFingerprint = datasetFingerprint(
      { textPipelineVersion: TEXT_PIPELINE_VERSION },
      this.intentsData,
      this.knowledgeData,
    )
    const shouldRetrain =
      !this.model ||
      this.model.modelType !== MODEL_TYPE ||
      this.model.datasetVersion !== (this.intentsData.version || 1) ||
      this.model.knowledgeVersion !== (this.knowledgeData.version || 1) ||
      this.model.datasetFingerprint !== expectedFingerprint ||
      this.model.classifier?.hiddenSize !== this.hiddenSize ||
      this.model.training?.epochs !== this.epochs ||
      this.model.training?.learningRate !== this.learningRate ||
      this.model.knowledge?.classifier?.hiddenSize !== this.knowledgeHiddenSize ||
      this.model.knowledge?.training?.epochs !== this.knowledgeEpochs ||
      this.model.knowledge?.training?.learningRate !== this.knowledgeLearningRate
    if (shouldRetrain) {
      this.model = trainModel(this.intentsData, {
        hiddenSize: this.hiddenSize,
        epochs: this.epochs,
        learningRate: this.learningRate,
        knowledgeData: this.knowledgeData,
        knowledgeHiddenSize: this.knowledgeHiddenSize,
        knowledgeEpochs: this.knowledgeEpochs,
        knowledgeLearningRate: this.knowledgeLearningRate,
      })
      writeJsonAtomic(this.modelFile, this.model)
      this.lastLoadAction = 'trained'
    } else {
      this.lastLoadAction = 'loaded'
    }
    this.classifier = NeuralIntentClassifier.fromJSON(this.model.classifier)
    this.knowledgeEngine = new FirstPartyKnowledgeEngine({
      knowledgeData: this.knowledgeData,
      model: this.model.knowledge,
      enabled: this.knowledgeEnabled,
      threshold: this.knowledgeThreshold,
    })
    this.vocabularySet = new Set(this.model.vocabulary)
    return this.status()
  }

  status() {
    return {
      ready: Boolean(this.classifier),
      modelType: this.model?.modelType || null,
      datasetVersion: this.model?.datasetVersion || null,
      datasetFingerprint: this.model?.datasetFingerprint?.slice(0, 16) || null,
      intents: this.intentsData.intents?.length || 0,
      vocabulary: this.model?.vocabulary?.length || 0,
      hiddenNeurons: this.model?.classifier?.hiddenSize || 0,
      parameters: this.model?.training?.parameters || 0,
      trainingSamples: this.model?.trainingSamples || 0,
      epochs: this.model?.training?.epochs || 0,
      guides: Object.keys(this.guides).length,
      guideVersion: this.guidesData.version || 0,
      threshold: this.threshold,
      lastLoadAction: this.lastLoadAction,
      knowledge: this.knowledgeEngine?.status() || null,
    }
  }

  answer(message, context = {}) {
    if (!this.classifier) this.loadOrTrain()

    const contextualMessage = this.contextualize(message, context)
    const tokens = tokenize(contextualMessage)
    const input = vectorize(tokens, this.model.vocabulary)
    const prediction = this.classifier.predict(input)
    const matchedTokenCount = tokens.reduce(
      (count, token) => count + (this.vocabularySet.has(token) ? 1 : 0),
      0,
    )
    const guardedIntent = this.resolveGuardedIntent(contextualMessage)
    const diagnosticIntentId = guardedIntent?.id === 'videos_subida_error'
      ? guardedIntent.id
      : null
    const tokenCoverage = tokens.length ? matchedTokenCount / tokens.length : 0
    const enoughDomainEvidence = matchedTokenCount >= 2 ||
      tokens.length <= 2 ||
      tokenCoverage >= 0.5
    const known = Boolean(guardedIntent) || (
      prediction.confidence >= this.threshold && matchedTokenCount > 0 && enoughDomainEvidence
    )
    const intent = known
      ? this.intentById.get(guardedIntent?.id || prediction.label)
      : null
    const riskCategory = classifyGeneralRisk(message)
    const allowedDomainSafetyIntent = riskCategory === 'security' &&
      ['privacidad_seguridad', 'recuperar_cuenta'].includes(intent?.id) &&
      !/\b(hack|hackear|piratear|vulnerar|malware|phishing|prompt|ignora)\b/i.test(
        normalizeText(message),
      )
    if (riskCategory && !allowedDomainSafetyIntent) {
      return {
        ...this.knowledgeEngine.answer(message, context),
        source: 'conectate-first-party-hybrid-v5',
        learnedFromUsers: false,
        contextual: false,
        guideUsed: false,
        matchedTokens: matchedTokenCount,
      }
    }

    const knowledgeResponse = this.knowledgeEngine.answer(message, context)
    const hasDomainAnchor = /\b(conectate|novvin|perfil|cuenta|social|publicacion|post|momento|historia|video|clip|mensaje|chat|grupo|comunidad|voz|notificacion|android|seguidor|seguidores)\b/i.test(
      normalizeText(message),
    )
    const preferKnowledge = knowledgeResponse && (
      !intent || (!diagnosticIntentId && !hasDomainAnchor && prediction.confidence < 0.65)
    )
    if (preferKnowledge) {
      return {
        ...knowledgeResponse,
        source: 'conectate-first-party-hybrid-v5',
        learnedFromUsers: false,
        contextual: false,
        guideUsed: false,
        matchedTokens: matchedTokenCount,
      }
    }
    const fallback = this.intentsData.fallback || {}
    const responses = intent?.responses?.length ? intent.responses : fallback.responses || ['Aun estoy aprendiendo.']
    const suggestions = intent?.suggestions || fallback.suggestions || []

    const baseAnswer = responses[stableIndex(`${message}:${context.userId || ''}`, responses.length)]
    const hasHistory = String(context.history || '').includes('Novvin:')
    const prefix = hasHistory && intent
      ? ['Claro. ', 'Entiendo. ', 'Siguiendo con eso, '][stableIndex(context.conversationId || message, 3)]
      : ''

    const guide = intent ? this.guides[intent.id] : null
    const guidedAnswer = this.buildGuidedAnswer({
      baseAnswer: `${prefix}${baseAnswer}`,
      guide,
      message,
      hasHistory,
      prediction,
      allowClarification: matchedTokenCount > 0,
      forceDetailed: context.detailLevel === 'expanded',
    })

    const responseMode = !intent
      ? matchedTokenCount > 0 ? 'clarification' : 'fallback'
      : guide ? 'guided' : 'catalog'

    return {
      answer: guidedAnswer,
      intent: intent?.id || 'fallback',
      confidence: guardedIntent
        ? Math.max(0.95, Number(prediction.confidence.toFixed(4)))
        : matchedTokenCount > 0 ? Number(prediction.confidence.toFixed(4)) : 0,
      suggestions,
      source: 'conectate-first-party-hybrid-v5',
      learnedFromUsers: false,
      contextual: contextualMessage !== message,
      guideUsed: Boolean(guide),
      responseMode,
      matchedTokens: matchedTokenCount,
      routingReason: guardedIntent?.reason || 'neural_intent',
    }
  }

  resolveGuardedIntent(message) {
    const normalized = normalizeText(message)
    const exactShortFailure = /^(?:el )?(?:mi )?video no carga$/.test(normalized)
    const mentionsVideo = /\b(video|clip)\b/.test(normalized)
    const uploadOrExport = /\b(subir|subiendo|publicar|publicando|exportar|exportando|procesar|procesando)\b/.test(normalized)
    const failure = /\b(error|no carga|no sube|no exporta|no publica|no termina|se detiene|cancelada|cancelado|falla|fallo)\b/.test(normalized)
    const playback = /\b(reproducir|reproduccion|ver el video|pantalla negra|sin audio|se escucha|buffer)\b/.test(normalized)
    if (exactShortFailure || (mentionsVideo && uploadOrExport && failure && !playback)) {
      return { id: 'videos_subida_error', reason: 'video_diagnostic_guard' }
    }
    if (
      /\b(post|publicacion)\b/.test(normalized) &&
      /\b(guardar|favorito|comentar|comentario|compartir|me gusta)\b/.test(normalized)
    ) return { id: 'publicaciones_interaccion', reason: 'domain_action_guard' }
    if (
      (
        /\b(enviar|mando|mandar)\b/.test(normalized) ||
        /\b(chat|mensaje|conversacion)\b/.test(normalized)
      ) &&
      /\b(nota de voz|audio|fotografia|foto|imagen|archivo|multimedia)\b/.test(normalized) &&
      !/\b(publicacion|post|social|momento|historia)\b/.test(normalized)
    ) return { id: 'mensajes_multimedia', reason: 'domain_action_guard' }
    if (
      /\bandroid\b/.test(normalized) &&
      /\b(permiso|permisos|acceso|habilitar|activar)\b/.test(normalized)
    ) return { id: 'permisos_android', reason: 'domain_action_guard' }
    return null
  }

  buildGuidedAnswer({
    baseAnswer,
    guide,
    message,
    hasHistory,
    prediction,
    allowClarification,
    forceDetailed,
  }) {
    if (!guide) {
      const topLabels = prediction.probabilities
        .map((confidence, index) => ({ confidence, label: this.model.labels[index] }))
        .sort((a, b) => b.confidence - a.confidence)
        .slice(0, 3)
        .map((candidate) => this.guides[candidate.label]?.label)
        .filter(Boolean)
      if (!allowClarification || !topLabels.length || !tokenize(message).length) return baseAnswer
      return `${baseAnswer}\n\nPara entenderte mejor: ¿tu duda es sobre ${topLabels.join(', ')} o sobre otra parte de Conectate?`
    }

    const normalizedMessage = normalizeText(message)
    const wantsSteps = forceDetailed || /\b(como|pasos?|explica|detalle|ayuda|quiero|puedo|no funciona|no carga|no sube|no exporta|error|problema)\b/i.test(normalizedMessage)
    const isFollowUp = hasHistory && tokenize(message).length <= 6
    if (!wantsSteps && !isFollowUp) {
      return guide.followUp ? `${baseAnswer}\n\n${guide.followUp}` : baseAnswer
    }

    const steps = (guide.steps || []).slice(0, 5)
    const stepText = steps.map((step, index) => `${index + 1}. ${step}`).join('\n')
    const tip = guide.tip ? `\n\nConsejo: ${guide.tip}` : ''
    const followUp = guide.followUp ? `\n\n${guide.followUp}` : ''
    return `${baseAnswer}\n\n${stepText}${tip}${followUp}`
  }

  contextualize(message, context = {}) {
    const clean = String(message || '').trim()
    const normalizedClean = normalizeText(clean)
    const shortFollowUp = tokenize(clean).length <= 4 && /^(y|tambien|entonces|eso|esa|ese|ahi|donde|pero|foto|portada|grupo|comunidad)\b/i.test(normalizedClean)
    if (!shortFollowUp) return clean
    const userMessages = String(context.history || '')
      .split('\n')
      .filter((line) => line.startsWith('Usuario:'))
      .map((line) => line.slice('Usuario:'.length).trim())
      .filter(Boolean)
    if (!userMessages.length) return clean
    const lastMessageIsCurrent = normalizeText(userMessages[userMessages.length - 1]) === normalizedClean
    const previousIndex = lastMessageIsCurrent ? userMessages.length - 2 : userMessages.length - 1
    if (previousIndex < 0) return clean
    return `${userMessages[previousIndex]} ${clean}`
  }

  recordFeedback(payload = {}) {
    ensureDir(path.dirname(this.feedbackFile))
    if (fs.existsSync(this.feedbackFile) && fs.statSync(this.feedbackFile).size >= this.feedbackMaxBytes) {
      const rotatedFile = `${this.feedbackFile}.1`
      fs.rmSync(rotatedFile, { force: true })
      fs.renameSync(this.feedbackFile, rotatedFile)
    }
    const event = {
      createdAt: new Date().toISOString(),
      message: redactSensitiveText(payload.message, 1000),
      answer: redactSensitiveText(payload.answer, 1500),
      intent: String(payload.intent || '').slice(0, 80),
      rating: String(payload.rating || '').slice(0, 32),
      expectedIntent: String(payload.expectedIntent || '').slice(0, 80),
      comment: redactSensitiveText(payload.comment, 500),
      containsRawUserId: false,
    }
    fs.appendFileSync(this.feedbackFile, `${JSON.stringify(event)}\n`)
    return { queued: true }
  }
}

module.exports = {
  ConectateChatbot,
  DEFAULT_EPOCHS,
  DEFAULT_HIDDEN_SIZE,
  MODEL_TYPE,
  classifierParameterCount,
  trainModel,
}
