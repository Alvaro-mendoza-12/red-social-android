export const authenticationTimeoutMs = 20_000
export const authenticationConnectionMessage = 'No pudimos conectar con Novvin. Vuelve a intentarlo en unos momentos.'
export class AuthenticationLocalNetworkError extends Error {
  constructor() { super('El navegador bloqueó la conexión con Novvin. Permite el acceso a la red local en los permisos de este sitio y vuelve a intentarlo.'); this.name='AuthenticationLocalNetworkError' }
}
async function deniedLocalNetwork(input: string): Promise<boolean> {
  try {
    if(!new URL(input).hostname.endsWith('.ts.net') || typeof navigator==='undefined' || !navigator.permissions?.query)return false
    for(const name of ['local-network','local-network-access']) {
      try { return (await navigator.permissions.query({name} as PermissionDescriptor)).state==='denied' } catch { /* Older engines support only the legacy name, or neither name. */ }
    }
  } catch { /* Keep the regular connection message for unsupported browsers. */ }
  return false
}

/** Bound sign-in and QR requests without replaying credentials or consuming a QR twice. */
export async function fetchAuthentication(input: string, init: RequestInit): Promise<Response> {
  const parent = init.signal
  if (parent?.aborted) throw parent.reason ?? new DOMException('Cancelled', 'AbortError')
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  let cancel: (() => void) | undefined
  const interrupted = new Promise<never>((_, reject) => {
    cancel = () => {
      const reason = parent?.reason ?? new DOMException('Cancelled', 'AbortError')
      controller.abort(reason)
      reject(reason)
    }
    parent?.addEventListener('abort', cancel, { once: true })
    timer = setTimeout(() => {
      const error = new Error(authenticationConnectionMessage)
      controller.abort(error)
      reject(error)
    }, authenticationTimeoutMs)
  })
  try {
    return await Promise.race([fetch(input, { ...init, signal: controller.signal }), interrupted])
  } catch(error) {
    if(error instanceof TypeError && await deniedLocalNetwork(input))throw new AuthenticationLocalNetworkError()
    throw error
  } finally {
    clearTimeout(timer)
    if (cancel) parent?.removeEventListener('abort', cancel)
  }
}
