import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import {
  validateNovvinChatResponse,
  validateNovvinHealth,
  type NovvinChatResponse,
  type NovvinHealth,
} from './novvinContract'

export { friendlyNovvinError } from './novvinContract'
export type { NovvinHealth } from './novvinContract'

export type NovvinRequestContext = {
  conversationId: string
  history: string
  screen: string
  locale: string
  detailLevel: 'standard' | 'expanded'
}

export async function askNovvin(
  message: string,
  context: NovvinRequestContext,
  signal?: AbortSignal,
): Promise<NovvinChatResponse> {
  const cleanMessage = message.trim()
  if (!cleanMessage) throw new Error('MESSAGE_REQUIRED')
  if (cleanMessage.length > 1_000) throw new Error('MESSAGE_TOO_LONG')

  const response = await fallbackApiJson<NovvinChatResponse>('/v1/ai/chat', {
    method: 'POST',
    body: JSON.stringify({ message: cleanMessage, context }),
    signal,
  })

  return validateNovvinChatResponse(response)
}

export async function getNovvinHealth(signal?: AbortSignal): Promise<NovvinHealth> {
  const health = await fallbackApiJson<NovvinHealth>('/v1/ai/health', { signal })
  return validateNovvinHealth(health)
}
