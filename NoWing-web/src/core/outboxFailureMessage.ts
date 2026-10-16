export function outboxFailureMessage(error: unknown) {
 const code=String((error as Error)?.message||'')
 if(code==='STORAGE_FILE_REJECTED') return 'No se pudo verificar este archivo. Prueba con otro archivo o envíalo en partes más pequeñas.'
 if(code==='MESSAGE_VIDEO_DURATION_TOO_LONG') return 'Los videos de mensajes pueden durar hasta 10 minutos.'
 if(code==='UPLOAD_TOO_LARGE') return 'Este archivo supera el tamaño permitido. Selecciona uno más pequeño.'
 if(Number((error as {status?:number})?.status)===403) return 'No se pudo enviar este archivo a la conversación. Comprueba que todavía tienes acceso.'
 return 'No se pudo enviar este mensaje. Puedes volver a intentarlo o adjuntar otro archivo.'
}
