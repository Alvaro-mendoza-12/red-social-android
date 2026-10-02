# Aplicación móvil: cancelación de llamadas y adjuntos simultáneos

Fecha: 2026-10-01. Proyecto `D:\red social - live-gifts`, rama `feature/live-gifts`.
Android mantiene versión 117 / 9.0.20. El trabajo vuelve a priorizar móvil por indicación del propietario.

## Fallo reproducido y correcciones

La prueba `cancellingTheVisibleInvitationClosesItsAnswerScreen` falló antes de corregir el código: al cancelar una invitación, la pantalla de contestar permanecía abierta. Esta prueba utiliza una invitación local ficticia y no llama a usuarios reales.

- La pantalla observa la guardia persistente de cancelación por ID, sin construir `CallRepository` ni WebRTC solo para recibir el aviso.
- Se cierra al cancelar, incluso si está detrás de otra pantalla o un diálogo de permisos. También comprueba la cancelación al abrirse, al volver y antes/después de aceptar.
- Una invitación cancelada no se acepta automáticamente. Una intención tardía de una llamada antigua no cierra una invitación nueva.
- La parada del servicio de timbre recibe el ID correspondiente a esa llamada. El vencimiento de timbre a los 45 segundos marca la invitación para cerrar su pantalla.
- “Procesando llamada…” explica la espera al aceptar/rechazar. Los dos botones quedan desactivados durante la operación y conservan colores legibles sobre el fondo negro. Si se deniegan permisos, se vuelve a permitir responder.
- Cancelar la pantalla no muestra un error de conexión como si la operación hubiese fallado. Los límites de espera de activación de cuenta y rechazo conservan su mensaje de error.

## Grupos y comunidades

El nombre de un adjunto se construía con prefijo, propietario y milisegundo. Dos envíos concurrentes del mismo tipo/propietario podían generar la misma ruta; el upload permitía sobrescribirla.

Fotos, audios y otros archivos ahora añaden un UUID a la ruta. Tanto el upload de bytes como el de URI envían `x-upsert=false`, también al usar el bucket alternativo. La prueba de nombres prepara 128 archivos con el mismo milisegundo y comprueba rutas diferentes y extensión válida.

Esto elimina ese riesgo de sobrescritura; no demuestra que fuera la causa de todos los mensajes no entregados que reportaron los usuarios.

## Verificación

Compilación final correcta: 1.045 pruebas unitarias en 234 suites, sin fallos, errores ni omisiones; 11 pruebas Android correctas en el emulador (80,185 segundos). El resultado definitivo de compilación, pruebas y SHA-256 de la APK se registra en el directorio [evidencias de esta revisión](../evidencias-movil-llamadas-adjuntos-2026-10-01/). Las capturas usan cuentas/invitaciones ficticias. La captura estrecha corresponde a un área de 320 × 480 dp, texto al doble y desplazamiento hasta los botones; no es una fotografía de un teléfono físico.

El servidor se volvió a comprobar durante esta revisión: nueve contenedores saludables, `/ready` con `ok:true` y FCM configurado; Docker montado desde el NVMe autorizado. Esta comprobación no mide entrega push con un teléfono bloqueado ni audio/vídeo entre redes.

## Siguiente validación física

Al comenzar, ADB detectaba solamente el emulador. Después se conectaron y autorizaron el Motorola moto g04 y la Samsung SM-X520. El Motorola recibió la APK 117 / 9.0.20-debug conservando datos y sesión; su red predeterminada es celular. La tablet usa Wi-Fi y mantiene la versión anterior 80 / 3.3.18: Android rechazó actualizarla por firma incompatible. Se encontró el almacén PKCS12 original, alias key0; falta su contraseña para firmar una actualización que conserve los datos. No se desinstaló ni borró ninguna cuenta o aplicación. Las pruebas entre versiones distintas deberán indicarse como tales.

Orden solicitado por el usuario:

1. Conversación individual: texto, foto con comentario, archivo, audio, sticker, respuesta multimedia, pérdida de red/reintento, cambio de cuenta y progreso de carga.
2. Grupos: crear/administrar, todos los envíos, respuestas, reintentos y encuestas con cambio/quitar voto y votantes.
3. Comunidades: canales, permisos y todas las opciones de mensajes.
4. Llamadas: timbre fuera de la app, aceptar/rechazar, colgar antes de contestar y cierre repetido sin detener la aplicación.
5. Salas y LIVE: entrada/salida, anfitrión/invitado, voz, cámara, pantalla, reconexión y medición de FPS/retardo entre redes.

Siguen pendientes esas comprobaciones físicas, identificar el stacktrace del cierre del Motorola y certificar los 60 FPS. Esta revisión no certifica todas las opciones de la aplicación para producción. La paridad web queda documentada por separado en la [revisión 38](38-web-autenticacion-avisos-2026-10-01.md).
