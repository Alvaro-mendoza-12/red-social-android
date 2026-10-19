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
