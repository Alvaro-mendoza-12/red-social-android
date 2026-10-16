export const authenticationTimeoutMs = 20_000
export const authenticationConnectionMessage = 'No pudimos conectar con Novvin. Vuelve a intentarlo en unos momentos.'

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
  } finally {
    clearTimeout(timer)
    if (cancel) parent?.removeEventListener('abort', cancel)
  }
}
