/** Keep server messages, private URLs and transport details out of the interface. */
export function attachmentFailureCode(error: unknown, online = navigator.onLine): string {
  if (!online) return 'MEDIA_OFFLINE'
  if (error instanceof Error && error.name === 'AbortError') return 'MEDIA_TIMEOUT'
  const message = error instanceof Error ? error.message : ''
  return /^(MEDIA_READ_(401|403|404|410|429|500|502|503|504)|MEDIA_TOO_LARGE|MEDIA_UNSUPPORTED_TYPE|MEDIA_EMPTY|MEDIA_SESSION_REQUIRED)$/.test(message)
    ? message : 'MEDIA_UNAVAILABLE'
}

export function attachmentFailureText(code: string, kind: string, sticker: boolean) {
  const subject = sticker ? 'el sticker' : kind === 'image' ? 'la imagen' : 'el archivo'
  switch (code) {
    case 'MEDIA_OFFLINE': return { title: 'Sin conexión', description: 'Reconecta Internet y vuelve a intentarlo.' }
    case 'MEDIA_TIMEOUT': return { title: 'La carga tardó demasiado', description: 'Comprueba tu conexión y vuelve a intentarlo.' }
    case 'MEDIA_READ_401':
    case 'MEDIA_SESSION_REQUIRED': return { title: 'Revisa tu sesión', description: 'Vuelve a iniciar sesión para cargar este archivo.' }
    case 'MEDIA_READ_403': return { title: 'Archivo protegido', description: 'Tu cuenta no tiene acceso a este archivo.' }
    case 'MEDIA_READ_404':
    case 'MEDIA_READ_410': return { title: 'Archivo no disponible', description: 'No hemos podido recuperar este archivo.' }
    case 'MEDIA_READ_429': return { title: 'Demasiadas solicitudes', description: 'Espera un momento antes de reintentar.' }
    case 'MEDIA_READ_500':
    case 'MEDIA_READ_502':
    case 'MEDIA_READ_503':
    case 'MEDIA_READ_504': return { title: 'Servicio temporalmente no disponible', description: 'Vuelve a intentarlo en unos momentos.' }
    case 'MEDIA_TOO_LARGE': return { title: 'Archivo demasiado grande para la vista web', description: 'Puedes descargar el original.' }
    case 'MEDIA_UNSUPPORTED_TYPE':
    case 'MEDIA_EMPTY': return { title: 'No pudimos mostrar este formato', description: 'El archivo no contiene una vista previa válida.' }
    default: return { title: `No pudimos cargar ${subject}`, description: 'Comprueba tu conexión y vuelve a intentarlo.' }
  }
}
