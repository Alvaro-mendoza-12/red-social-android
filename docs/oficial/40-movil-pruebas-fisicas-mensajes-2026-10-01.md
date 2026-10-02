# Mensajes: pruebas físicas, notas de voz y adjuntos

Fecha: 2026-10-01. Rama `feature/live-gifts`, versión 117 / 9.0.20 conservada.

## Equipos y alcance

Se utilizaron el Motorola moto g04 con Android 14 y datos móviles LTE, y la Samsung SM-X520 con Android 16 y Wi-Fi. La red Wi-Fi pasa por un hotspot de Windows; esta condición permitió reproducir un fallo real de DNS dividido. También se había comprobado texto y foto con comentario entre el Motorola y un emulador por Wi-Fi.

La tablet mantiene su instalación original de Play y sus datos. Como su certificado es diferente del certificado debug, se instaló `conectate.com.qa` («Novvin QA») con el mismo código de esta revisión y una cuenta ficticia independiente. No se desinstaló ni borró la app original. Esta variante sirve para API, interfaz y multimedia; no certifica FCM de producción, porque no está registrada como una aplicación Android distinta en Firebase.

El script `scripts/build-android-device-audit.ps1` prepara exclusivamente una configuración Firebase local y temporal para esa variante, ejecuta las tareas indicadas y elimina ese archivo al terminar. No modifica el archivo Firebase original ni la firma de release. Para actualizar la instalación original de Play debe utilizarse una distribución compatible con su firma, normalmente un canal de pruebas de Play; disponer de la llave de subida no equivale a disponer de la llave de firma de la aplicación de Google Play.

## Fallos reproducidos y corregidos

### Envío de notas de voz: HTTP 422

Una grabación física de 18 segundos se reconocía como `video/mp4` por su cabecera genérica `ftyp/isom`. El servidor intentaba procesarla como vídeo y rechazaba el envío. El contenedor MP4 no demuestra por sí solo que haya una pista de vídeo.

El resolvedor conserva ahora el tipo audio de la nota de voz y la envía como `audio/mp4` con extensión M4A. También contempla reintentos de borradores antiguos guardados como MP4. No se desactivó la validación del servidor. Antes del cambio fallaban dos de las cinco pruebas específicas de MIME; después pasaron las cinco.

Después de actualizar ambos equipos se reintentó la misma nota fallida: el servidor la recibió una sola vez, la tablet la mostró y el estado final fue leído. El envío medido tardó 2.039 ms en total, con 283 ms de transporte y 968 ms de espera de respuesta. Este resultado no cumple un límite universal de un segundo.

### Reproducción de notas de voz: ruta privada inaccesible

La tablet resolvía el hostname del servidor a una IP privada de Tailscale sin tener acceso a esa red. La API utilizaba la política DNS pública de la app, pero la descarga de notas de voz utilizaba `HttpURLConnection`; el reproductor también intentaba esa misma ruta al fallar la descarga. Se reprodujeron esperas de cinco segundos y nuevos intentos sin sonido.

Las notas de voz utilizan ahora el cliente HTTP compartido, su política `PublicBackendDns`, conexiones reutilizables y HTTPS. El reproductor abre un archivo local completo y se prepara de manera asíncrona, sin bloquear la interfaz. Una descarga fallida muestra un mensaje para volver a intentarlo; no pasa al reproductor una URL que evita la política de red.

La caché guarda primero un archivo temporal y solo publica una descarga completa; comprueba longitud conocida, respuesta HTTP, tipo de contenido y límites de tamaño. Mantiene hasta 128 MB, con un máximo de 100 MB por archivo, y elimina archivos completos antiguos. Se añaden pruebas de respuesta fallida, descarga incompleta, límites, reapertura sin red y rechazo de HTTP.

En la tablet física se descargaron los 152.256 bytes y MediaPlayer reprodujo hasta el final los 18,135 segundos. No hubo `SocketTimeoutException` en el registro de esa ejecución. La reapertura mostró «Pausar audio» y avance de reproducción en la interfaz. Se verifica descarga, duración y funcionamiento del reproductor; no se certifica por este registro la calidad percibida del sonido.

### Galería y adjuntos

Los filtros de la galería se distribuyen en varias líneas si el ancho o el tamaño de texto lo requieren. Cada foto o vídeo tiene nombre accesible y estado de selección; el botón de continuar tiene área de 48 dp. La hoja de adjuntos permite desplazamiento y respeta la barra inferior, evitando que las últimas opciones queden fuera del área utilizable.

Dos pruebas Android pasaron en el Motorola: seleccionar/continuar y filtros en 320 × 480 dp con texto al doble. Se utilizaron elementos ficticios para esas pruebas.

## Pruebas físicas realizadas

| Operación | Resultado observado |
| --- | --- |
| Texto tablet → Motorola y Motorola → tablet | Recibido en UI nativa y leído en servidor, entre Wi-Fi y LTE. |
| Foto con comentario desde Motorola | Imagen y comentario visibles en el emulador receptor; estado leído. |
| Responder a una foto | El mensaje conserva en servidor la referencia exacta a la foto. |
| Enviar texto sin red y reconectar | Reintento disponible; se entregó una sola copia al recuperar datos. |
| Reintentar nota de voz fallida | Recibida y leída tras corregir MIME. |
| Reproducir nota en tablet | Descarga completa y reproducción hasta el final tras corregir DNS. |
| Stickers entre dos cuentas | Sticker recibido, añadido a la cuenta QA y devuelto al Motorola; ambos leídos. |
| Favoritos de stickers en cuenta nueva | Inicialmente vacíos; no heredaron los de la otra cuenta. |
| Añadir/quitar favorito | Conteos observados 0 → 1 → 0 → 1; al quitarlo desapareció la imagen inmediatamente. |

Los archivos de [evidencia sanitizada](../evidencias-movil-mensajes-fisicos-2026-10-01/) registran compilación, pruebas, hashes y resultados. Las grabaciones, capturas con datos de usuarios, tokens y registros completos permanecen fuera de Git.

Compilación final: `testDebugUnitTest`, `assembleDebug` y `assembleAudit`, correcta en 5 min 44 s. Pasaron 1.057 pruebas unitarias en 236 suites, sin fallos, errores u omisiones. Las dos pruebas de galería física duraron 5,168 s.

## Hallazgos y comprobaciones pendientes

- El arranque frío después de instalar tardó 7.579 ms en el Motorola y 1.666 ms en la tablet QA. No se atribuye automáticamente esta diferencia al servidor; falta medir y perfilar el arranque del Motorola.
- El servidor actualizó el sticker devuelto a leído; una captura de la tablet todavía mostraba enviado. Falta revisar la actualización de confirmaciones de lectura mientras permanece abierto el chat.
- Falta terminar archivos grandes/progreso, reenviar fotos y archivos fallidos, respuestas a vídeo/sticker, búsqueda, guardados, carpetas y demás opciones del chat individual.
- Después se seguirá, en el orden solicitado, con todas las opciones de grupos, encuestas, comunidades, llamadas físicas, salas y LIVE. Siguen pendientes reproducción/voz entre participantes, timbre con la app fuera de pantalla, cancelación remota, cierres repetidos y medición de FPS/retardo.
- No se han certificado 60 FPS ni el cierre completo del aplicativo para producción. Las cuentas QA se mantienen mientras continúen las pruebas; se eliminarán al terminarlas.

## Reversión

Revertir el commit de esta revisión restaura el resolvedor de MIME, la descarga anterior y la galería anterior. No hay migración de base de datos ni cambios del backend en este bloque. La variante QA se instala aparte de la app de Play. No borrar datos de la app original para cambiar de certificado.
