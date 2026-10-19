# Continuación 9.1.16 — lectura y medios

## Hallazgos y cambios

- Web: los IDs entrantes confirmados como leídos solo se conservaban en memoria. Una recarga seguida de un snapshot antiguo con actividad saliente posterior podía reactivar el contador. Ahora se conserva una caché por cuenta junto al cursor existente, compatible con su formato anterior: hasta 500 conversaciones, 100 IDs recientes por conversación y 4.000 en total. Un mensaje entrante distinto mantiene su contador. Solo se guardan identificadores y fechas; no texto ni medios. Esto no sustituye la lectura autoritativa ni una cola duradera de controles de servidor.
- Web: una función de preparación de adjuntos que lanza un error antes de devolver su promesa dejaba el trabajo en preparación. Se captura ese fallo y se permite reintentar sin perder el acceso inmediato a DataTransfer.
- Web: detener dos veces un MediaRecorder antes del evento stop podía rechazar con InvalidStateError; eventos antiguos podían interferir con recursos de una grabación posterior. La sesión ahora posee sus pistas, buffers y temporizadores. Cancelar, salir o cambiar de cuenta invalida resultados tardíos. El editor público ya se recreaba por cuenta: se conserva esa protección y se refuerza también el controlador del micrófono.
- Web: se muestra preparación del micrófono con cancelación, tiempo grabado y preparación final; se comprueban 2 minutos y 10 MB. Al ocultar la pestaña se detiene el micrófono y se prepara el audio local. No hay envío automático. El corrector local también se ofrece al escribir comentarios de publicaciones y videos.
- Android: cada captura y solicitud de micrófono recibe un token. Salir o pasar a segundo plano lo invalida; un resultado antiguo se elimina en lugar de abrir el editor al regresar. Cambiar de lente/modo y disparar quedan bloqueados durante la preparación/finalización pertinente. Al volver se revisa el permiso de cámara y el flash queda apagado. Se muestran estados de permiso y preparación; un archivo vacío tiene aviso recuperable.

## Impacto

La cámara es la compartida por chats, grupos y comunidades. Continúa entregando archivos al editor local, sin enviarlos. No cambian roles, cuotas, contratos de mensajes, almacenamiento de publicaciones, caducidad de salas ni el antivirus. El componente de comentarios sigue identificado por contenido y cuenta. Las políticas de lectura Android y servidor no se sustituyen con una heurística nueva.

## Fuentes consultadas

- [MDN: MediaRecorder.stop](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/stop): estado inactivo y orden de finalización.
- [MDN: error de MediaRecorder](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/error_event): error, datos finales y evento stop.
- [MDN: getUserMedia](https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia): permiso y liberación de pistas.
- [Android: VideoRecordEvent.Finalize](https://developer.android.com/reference/androidx/camera/video/VideoRecordEvent.Finalize): finalización y errores al cerrar la cámara por ciclo de vida.

Los resultados de pruebas, despliegue, instalación y sincronización se registrarán en `artifacts/release-9.1.16/`. No se declara certificación ISO/IEEE ni ausencia absoluta de fallos.

## Reporte posterior de QR de acceso no disponible

Durante esta revisión, la creación directa de un QR devolvió HTTP 200, estado pendiente y caducidad; salud y preparación del servidor también respondieron 200. En el navegador controlado, con permiso de red local permitido, el QR se mostró y tres peticiones de vinculación respondieron 200 sin errores de red. Al denegar ese permiso solo en un contexto de prueba aislado se reprodujo «QR no disponible». Esto demuestra una causa posible, sin atribuir todavía el incidente concreto del usuario a ese permiso.

La explicación específica ya existía, pero quedaba debajo de las instrucciones y fuera de la parte visible de la captura. Se mueve el aviso y un único botón Reintentar QR al panel principal. Si la conexión fue bloqueada por el navegador, se explica cómo revisar el permiso del sitio y se distingue del permiso de cámara. No se relajan las políticas del navegador, el certificado ni la aprobación desde el teléfono. La causa exacta en el navegador del usuario queda pendiente de su respuesta; no se declara una caída del servidor sin evidencia.

Referencia: [Chrome: acceso a red local](https://developer.chrome.com/blog/local-network-access?hl=es-419). Los permisos de prueba se cambian únicamente en un contexto aislado que se cierra al terminar.
