export const NOVVIN_V5_SOURCE = 'conectate-first-party-hybrid-v5'

export type NovvinChatResponse = {
  answer: string
  intent: string
  confidence: number
  suggestions: string[]
  source: string
  learnedFromUsers: boolean
  responseMode?: string
  routingReason?: string
  knowledgeId?: string
}

export type NovvinHealth = {
  ready: boolean
  modelType: string
  datasetVersion: number
  datasetFingerprint: string
  guideVersion: number
  lastLoadAction: string
  knowledge?: {
    enabled?: boolean
    datasetVersion?: number
    networkAccess?: boolean
  }
}

export function validateNovvinChatResponse(response: NovvinChatResponse): NovvinChatResponse {
  if (response.source !== NOVVIN_V5_SOURCE) throw new Error('AI_VERSION_MISMATCH')
  if (!response.answer?.trim()) throw new Error('AI_EMPTY_RESPONSE')

  return {
    ...response,
    answer: response.answer.trim(),
    suggestions: Array.isArray(response.suggestions)
      ? response.suggestions.map((value) => String(value).trim()).filter(Boolean).slice(0, 3)
      : [],
  }
}

export function validateNovvinHealth(health: NovvinHealth): NovvinHealth {
  if (
    health.ready !== true ||
    health.modelType !== NOVVIN_V5_SOURCE ||
    Number(health.datasetVersion) < 5
  ) {
    throw new Error('AI_VERSION_MISMATCH')
  }
  return health
}

export function friendlyNovvinError(error: unknown): string {
  const code = error instanceof Error ? error.message.trim() : String(error || '').trim()
  if (code === 'AI_SERVICE_TIMEOUT') {
    return 'Novvin tardó demasiado en responder. Puedes reintentar sin perder el mensaje.'
  }
  if (code === 'AI_SERVICE_UNAVAILABLE') {
    return 'Novvin no está disponible en este momento. Tu conversación sigue guardada en este dispositivo.'
  }
  if (code === 'AI_VERSION_MISMATCH') {
    return 'La versión segura de Novvin v5 todavía no está disponible en este servidor.'
  }
  if (code === 'AI_EMPTY_RESPONSE') {
    return 'Novvin devolvió una respuesta vacía. Intenta nuevamente.'
  }
  if (code === 'MESSAGE_TOO_LONG') {
    return 'El mensaje supera los 1.000 caracteres. Acórtalo antes de enviarlo.'
  }
  if (code === 'AI_RATE_LIMITED') {
    return 'Has enviado muchos mensajes seguidos. Espera un momento antes de reintentar.'
  }
  if (/conectar|conexión|network|failed to fetch|load failed/i.test(code)) {
    return 'Sin conexión con Novvin. Revisa Internet y vuelve a intentarlo.'
  }
  return code || 'No fue posible obtener una respuesta de Novvin.'
}
