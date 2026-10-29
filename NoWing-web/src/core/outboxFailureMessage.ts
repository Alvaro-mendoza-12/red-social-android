export function outboxFailureMessage(error: unknown) {
 const code=String((error as {code?:string})?.code||(error as Error)?.message||'')
 if(code==='CHAT_MEDIA_REFERENCE_FORBIDDEN'||code==='COLLECTION_MEDIA_REFERENCE_FORBIDDEN') return 'Este archivo ya no está disponible para enviar. Adjunta otra copia o elige otro sticker.'
 if(code==='AUTH_REQUIRED'||code==='INVALID_TOKEN') return 'Vuelve a iniciar sesión para enviar el mensaje. Tu mensaje sigue guardado aquí.'
 if(code==='CHAT_ACCESS_DENIED'||code==='COLLECTION_ACCESS_DENIED') return 'Ya no tienes acceso a esta conversación.'
 if(code==='STORAGE_SCAN_LIMIT_EXCEEDED'||code==='STORAGE_SCAN_TIMEOUT') return 'No se completó la verificación del archivo. Puedes reintentar sin volver a adjuntarlo.'
 if(code==='STORAGE_ARCHIVE_ENCRYPTED') return 'El archivo comprimido tiene contraseña. Adjunta una copia sin contraseña para poder verificarla.'
 if(code==='STORAGE_FILE_REJECTED') return 'El archivo no superó la revisión de seguridad. Adjunta otra copia del archivo.'
 if(code==='MESSAGE_VIDEO_DURATION_TOO_LONG') return 'Los videos de mensajes pueden durar hasta 10 minutos.'
 if(code==='UPLOAD_TOO_LARGE') return 'Este archivo supera el tamaño permitido. Selecciona uno más pequeño.'
 if(Number((error as {status?:number})?.status)===403) return 'No se pudo enviar este archivo a la conversación. Comprueba que todavía tienes acceso.'
 return 'No se envió. Reintenta o adjunta otra copia.'
}

export function messageDeliveryFeedback(stored: string | undefined) {
 if(!stored || stored.startsWith('No se pudo enviar este mensaje.')) return outboxFailureMessage(null)
 if(/^[A-Z][A-Z_0-9]+$/.test(stored)) return outboxFailureMessage(new Error(stored))
 return /https?:\/\/|SQLSTATE|stack:|Bearer /i.test(stored) ? outboxFailureMessage(null) : stored
}
