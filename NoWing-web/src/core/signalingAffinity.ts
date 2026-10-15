export type SignalingScope = 'voice' | 'call' | 'live'

const MAX_SCOPE_ID_LENGTH = 160

export function buildSignalingAffinity(scope: SignalingScope, rawScopeId: string): string {
  const scopeId = rawScopeId.trim()
  if (!scopeId || scopeId.length > MAX_SCOPE_ID_LENGTH) {
    throw new Error('Invalid signaling scope id')
  }
  return `${scope}:${scopeId}`
}
