import { fallbackApiJson } from './auth/fallbackAuth'

export async function callUbuntuRpc<T>(
  name: string,
  body: Record<string, unknown> = {},
): Promise<T> {
  return fallbackApiJson<T>(`/rest/v1/rpc/${encodeURIComponent(name)}`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}
