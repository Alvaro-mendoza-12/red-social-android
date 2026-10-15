export type NovvinMessage = {
  id: string
  role: 'user' | 'assistant'
  text: string
  suggestions: string[]
  createdAt: number
}

export type NovvinConversation = {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  messages: NovvinMessage[]
}

const MAX_CONVERSATIONS = 40
const MAX_MESSAGES = 100
const MAX_STORED_TEXT = 6_000
const storagePrefix = 'conectate-web:novvin-v5:'

function makeId(prefix: string): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  return uuid ? `${prefix}-${uuid}` : `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function finiteTimestamp(value: unknown, fallback: number): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? Math.round(parsed) : fallback
}

function cleanStoredMessage(value: unknown, fallbackTime: number): NovvinMessage | null {
  if (!value || typeof value !== 'object') return null
  const source = value as Partial<NovvinMessage>
  const role = source.role === 'user' || source.role === 'assistant' ? source.role : null
  const text = String(source.text || '').trim().slice(0, MAX_STORED_TEXT)
  if (!role || !text) return null
  return {
    id: String(source.id || makeId('message')).slice(0, 160),
    role,
    text,
    suggestions: Array.isArray(source.suggestions)
      ? source.suggestions.map((item) => String(item).trim().slice(0, 180)).filter(Boolean).slice(0, 3)
      : [],
    createdAt: finiteTimestamp(source.createdAt, fallbackTime),
  }
}

export function normalizeNovvinConversations(value: unknown): NovvinConversation[] {
  if (!Array.isArray(value)) return []
  const now = Date.now()
  return value
    .map((entry, index): NovvinConversation | null => {
      if (!entry || typeof entry !== 'object') return null
      const source = entry as Partial<NovvinConversation>
      const messages = Array.isArray(source.messages)
        ? source.messages
            .map((message) => cleanStoredMessage(message, now - index))
            .filter((message): message is NovvinMessage => Boolean(message))
            .slice(-MAX_MESSAGES)
        : []
      if (!messages.length) return null
      const createdAt = finiteTimestamp(source.createdAt, messages[0]?.createdAt || now)
      const updatedAt = finiteTimestamp(
        source.updatedAt,
        messages[messages.length - 1]?.createdAt || createdAt,
      )
      return {
        id: String(source.id || makeId('conversation')).slice(0, 160),
        title: String(source.title || 'Nuevo chat').trim().slice(0, 80) || 'Nuevo chat',
        createdAt,
        updatedAt,
        messages,
      }
    })
    .filter((conversation): conversation is NovvinConversation => Boolean(conversation))
    .sort((left, right) => right.updatedAt - left.updatedAt)
    .slice(0, MAX_CONVERSATIONS)
}

function storageKey(ownerId: string): string {
  return `${storagePrefix}${encodeURIComponent(ownerId.trim().slice(0, 180))}`
}

export function loadNovvinConversations(
  ownerId: string,
  target: Storage | null = typeof window === 'undefined' ? null : window.localStorage,
): NovvinConversation[] {
  if (!ownerId.trim() || !target) return []
  try {
    const raw = target.getItem(storageKey(ownerId))
    return raw ? normalizeNovvinConversations(JSON.parse(raw)) : []
  } catch {
    return []
  }
}

export function saveNovvinConversations(
  ownerId: string,
  conversations: NovvinConversation[],
  target: Storage | null = typeof window === 'undefined' ? null : window.localStorage,
): void {
  if (!ownerId.trim() || !target) return
  const normalized = normalizeNovvinConversations(conversations)
  try {
    target.setItem(storageKey(ownerId), JSON.stringify(normalized))
  } catch {
    // Browsers can reject storage in private mode or when the quota is full.
  }
}

export function createNovvinMessage(
  role: NovvinMessage['role'],
  text: string,
  suggestions: string[] = [],
): NovvinMessage {
  return {
    id: makeId('message'),
    role,
    text: text.trim().slice(0, MAX_STORED_TEXT),
    suggestions: suggestions.map((item) => item.trim()).filter(Boolean).slice(0, 3),
    createdAt: Date.now(),
  }
}

export function createNovvinConversation(): NovvinConversation {
  const now = Date.now()
  return {
    id: makeId('conversation'),
    title: 'Nuevo chat',
    createdAt: now,
    updatedAt: now,
    messages: [
      createNovvinMessage(
        'assistant',
        'Hola, soy Novvin. Puedo orientarte sobre la aplicación y sobre los temas revisados de mi base local. No aprendo automáticamente de esta conversación.',
        ['¿Cómo edito mi perfil?', '¿Cómo publico un video?', '¿Por qué el cielo es azul?'],
      ),
    ],
  }
}

export function appendNovvinMessage(
  conversation: NovvinConversation,
  message: NovvinMessage,
): NovvinConversation {
  const firstUserMessage = conversation.messages.every((entry) => entry.role !== 'user')
  return {
    ...conversation,
    title:
      firstUserMessage && message.role === 'user'
        ? message.text.replace(/\s+/g, ' ').slice(0, 48)
        : conversation.title,
    updatedAt: message.createdAt,
    messages: [...conversation.messages, message].slice(-MAX_MESSAGES),
  }
}
