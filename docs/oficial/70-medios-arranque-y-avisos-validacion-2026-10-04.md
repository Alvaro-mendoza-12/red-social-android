# Medios, arranque y avisos: implementación y validación

**Fecha:** 4 de octubre de 2026. **Rama:** `feature/live-gifts`.

Este documento complementa la auditoría de los once puntos. Los resultados corresponden a los artefactos y dispositivos indicados. El registro reproducible, sin cuentas ni tokens, está en [evidencia consolidada](evidencias/cierre-11-2026-10-04.json). El editor móvil de encuestas se documenta en [el informe 69](69-encuestas-editor-movil-y-publicacion-2026-10-04.md).

**Estado vigente:** sección 8, resultados consolidados en tablet. Los apartados anteriores conservan checkpoints históricos y pendientes que después se actualizaron. La certificación general de producción continúa pendiente.

## 1. Música de perfil y fotografías de momentos

**Requisito:** guardar una canción, recuperarla desde otra cuenta, reproducirla al tocar y detenerla al abandonar la pantalla. Una fotografía debe mostrarse aunque su música asociada no esté disponible.

La prueba utiliza dos cuentas propias, un WAV y un JPEG propios. Publica la fotografía exclusivamente para la otra cuenta de prueba, verifica su recepción y comprueba los píxeles del componente real. Al terminar elimina sus objetos y momento y restablece el perfil de prueba. Conserva la sesión del usuario del dispositivo.

| Comprobación en emulador API 35 | Resultado |
| --- | --- |
| Canción persistida y leída por la otra cuenta | Correcto |
| Pulsación a reproducción y avance de posición | 1.037 ms |
| Pausa al detener el lifecycle; sin reproducción automática al regresar | Correcto |
| Primera imagen decodificada | 465 ms; presupuesto de prueba inferior a 3.000 ms |
| Foto visible con URL musical inexistente | Correcto |
| Limpieza de los objetos y momento propios | Correcto |

Se encontró además un defecto de compatibilidad: un PATCH parcial de perfil reenviaba un nombre de usuario derivado del nombre visible. Podía producir `USERNAME_IMMUTABLE` aunque se cambiase otro campo. La combinación de campos ahora omite ese valor proyectado cuando el PATCH no incluye `username`; una modificación explícita conserva su validación. La prueba móvil utiliza la ruta nativa `/v1/data/profile/me`; no atribuye a esa ruta el fallo de la ruta antigua.

## 2. Selector de música de comunidad

**Requisito:** un toque debe iniciar la pista correcta; la preparación anticipada no debe reproducir música por sí sola. El recorte debe conservar la URL original y respetar la duración del video y de la pista.

`InteractiveMusicPreview` comparte el constructor de Media3 entre `DefaultPreloadManager` y el reproductor. Mantiene como máximo tres pistas y prepara 1,5 segundos por pista en memoria, con objetivo de búfer de precarga de 1 MiB. Reutiliza la conexión HTTP del aplicativo. No crea un catálogo descargado ni almacenamiento musical permanente.

El selector actualiza la ventana según las pistas visibles. Al salir de primer plano pausa y detiene el reproductor y vacía la precarga. Al cerrar libera ambos recursos. Volver de la selección al catálogo también actualiza la ventana. La elección y exportación siguen usando la pista original.

| Prueba con catálogo real, emulador API 35 | Resultado |
| --- | --- |
| Tocar inmediatamente | 2.192 ms hasta estado de reproducción |
| Revisar el catálogo 1.500 ms y tocar | 500 ms desde el toque |
| Reproducción automática antes del toque | Ausente |
| Pista y URL elegidas; recorte dentro de ambos límites | Correcto |

Las dos condiciones se reportan por separado. El tiempo de revisión no se suma a una mejora ficticia del arranque de audio. El estado de reproducción no equivale a una medición acústica: el emulador se ejecuta sin salida sonora. Las pruebas añadidas de retorno al catálogo y de pausa/reanudación pasaron dentro de la regresión nativa de 28 casos.

Referencia técnica: [Media3: crear el administrador de precarga](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/create) y [usar las fuentes preparadas](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/manage-play).

## 3. Llamadas, salas de voz y LIVE

Los callbacks nativos de WebRTC llevan una generación de sesión. El cierre o reinicio incrementa esa generación antes de liberar recursos; los callbacks encolados verifican la generación dentro del ejecutor. Esto impide que un callback tardío vuelva a crear estado de conexión después de colgar.

La prueba de llamada entrante real, recibida fuera de la aplicación mediante FCM en el teléfono, pasó después del cambio: notificación en 2.349 ms, conexión tras aceptar en 5.081 ms y ausencia de pares dos segundos después del cierre. El emulador y el teléfono intercambiaron audio RTP y video en llamada y sala de voz; la llamada utilizó TURN relay en ambos extremos. Los valores detallados están en el JSON. Estas pruebas no acreditan calidad acústica de dos teléfonos.

Para LIVE se añadió una medición que comienza antes de `joinLiveAsViewer` y termina en `onFirstFrameRendered` del componente real `WebRtcVideoView`, utilizando el repositorio del aplicativo. En el emulador obtuvo **563 ms para la petición y 3.148 ms hasta el fotograma visible**, por debajo del presupuesto de prueba de 5.000 ms. Una sola muestra no demuestra un percentil ni garantiza ese tiempo en todas las redes. Las mediciones anteriores de RTP, iniciadas después del intercambio de pares, se mantienen separadas.

La primera versión de la prueba bloqueaba el reloj de Compose con un latch e impedía representar la pista; se corrigió la espera para avanzar el reloj de interfaz. Ese intento fallido no se cuenta como un resultado de rendimiento del aplicativo.

## 4. Arranque: contenido local utilizable y sincronización

**Objetivo del usuario:** abrir el aplicativo en 2,5–3 segundos como máximo. Se miden por separado la primera pantalla (TTID) y la bandeja utilizable (TTFD).

La primera medición con R8, cuenta y base de datos existentes en Motorola g04 produjo:

| Cinco muestras por condición | TTID, ms | TTFD, ms |
| --- | --- | --- |
| Proceso frío | 2.251; 670; 364; 457; 463 | 7.229; 4.698; 4.480; 5.076; 4.509 |
| Activity reabierta, proceso vivo | 179; 174; 134; 138; 140 | 735; 711; 627; 730; 620 |

**El objetivo completo todavía no está acreditado.** La pantalla inicial cumple en esas muestras, pero la bandeja fría esperaba la sincronización remota incluso después de una lectura local válida y vacía.

La corrección registra la primera lectura real de Room para la cuenta activa. La bandeja permite buscar o crear conversaciones mientras muestra «Buscando tus conversaciones…» y un indicador de sincronización. No declara que la lista remota esté completa. Al cambiar de cuenta se reinicia el estado; respuestas de una sesión anterior no completan la sincronización de la siguiente.

Ocho nuevos casos unitarios cubren el estado inicial, la instantánea vacía utilizable, ausencia de cuenta y reinicio. La regresión posterior, que incluye los cambios visuales de sorteos, pasó **1.316 pruebas unitarias en 285 suites**, sin fallos ni omisiones. Falta repetir las mediciones con el APK optimizado que contiene este cambio. El teléfono quedó desconectado y el usuario indicó continuar únicamente con el emulador por ahora; una medición allí no sustituirá la del Motorola.

Referencia: [Android: análisis y optimización del arranque](https://developer.android.com/topic/performance/appstartup/analysis-optimization).

## 5. Avisos administrativos

La publicación administrativa persiste aviso, notificaciones y outbox en una transacción. Los reintentos conservan la identidad del aviso y no repiten la publicación. La entrega se procesa por lotes de 25 con bloqueo y reintento. La interfaz y sus validaciones tienen pruebas nativas; la publicación, expiración, permisos e idempotencia tienen pruebas de servidor.

Se reprodujo un bypass: el POST de compatibilidad `/rest/v1/notifications` devolvió 201 para un aviso de sistema creado por una cuenta común. La notificación pertenecía exclusivamente a una cuenta de prueba y fue eliminada inmediatamente. La protección nueva reconoce el evento reservado también en los nombres y envoltorios antiguos. Las rutas públicas nativa y REST rechazan ese evento; el receptor puede marcar una notificación como leída o eliminarla, y no puede cambiar su tipo, remitente o destinatario.

La regresión local completa pasó **468 de 470 pruebas, dos omitidas y ningún fallo**. El candidato pasó **81 de 81 pruebas en Linux con PostgreSQL aislado**. Se distribuyó `live-gifts-cierre11-c646f9f` tras verificar un respaldo nuevo y las imágenes anteriores. API y gateway quedaron saludables; coincidieron los hashes de 114 archivos de fuentes de API. Después de distribuir pasaron **11 comprobaciones HTTP** de rechazo de avisos reservados y de edición limitada al receptor, y las **23 comprobaciones funcionales** de mensajería, visitas, sorteos y encuestas. Los avisos y espacios propios de esas pruebas fueron eliminados.

La recepción administrativa mediante el outbox real **pasó en el nuevo emulador API 35 con Google APIs**: publicación idempotente, un destinatario propio, ejecución del worker real en 772 ms y notificación Android con título y cuerpo verificados. El test nativo pasó en 5,498 s; ese tiempo incluye preparación y coordinación y no se presenta como latencia exclusiva de FCM. La sesión propia siguió activa y el esquema temporal se eliminó. La prueba de preparación también acreditó inicio de sesión normal, bandeja visible y token real de Firebase. No se envió un aviso general a usuarios humanos.

## 6. Límites de cierre

Estas pruebas mejoran la evidencia de los once puntos, pero todavía no constituyen una certificación global de producción. Permanecen la medición de arranque optimizado en Motorola, la verificación acústica entre extremos y los recorridos completos de captura hasta recepción en los tres ámbitos. Se preservan los resultados previos de cámara real y las pruebas HTTP por contexto; no se presentan como una sola prueba integrada.

## 7. Continuación con dos dispositivos físicos

El 4 de octubre el usuario volvió a conectar el Motorola g04, Android14, y una tablet Samsung SM-X520, Android16. Se conserva la instalación de Play en la tablet; las pruebas requieren una aplicación QA independiente porque las firmas no coinciden. Sus cuentas y datos humanos no se borran.

La regresión de encuestas, navegación desde perfiles y selector musical pasó **24/24 en Motorola, 71,038 s**. Las pruebas de cámara, enlaces y editor pasaron 30/31: el único fallo fue un selector de prueba que exigía la acción SetText en un campo deshabilitado. El selector se corrigió; no se cuenta como aprobado hasta repetirlo. Las nueve comprobaciones de instantánea inmutable y las siete de renderizado de píxeles sí pasaron en ese intento.

### Video de cámara rechazado por el servidor

El envío integrado encontró un fallo real: el JPEG llegó al receptor, pero el MP4 recortado obtuvo HTTP422. FFprobe identificó H264 con audio AMR_NB; copiar ese audio al contenedor MP4 fallaba. El cambio `1e1515f` detecta todas las pistas, copia video/AAC existentes y convierte únicamente el resto a AAC LC. Valida tamaño y restricciones aplicables antes de reemplazar el archivo original; el rechazo conserva el original y elimina el temporal.

La documentación oficial explica la selección de códecs por pista y la copia de flujos, y sitúa el índice MP4 al principio mediante faststart: [FFmpeg](https://ffmpeg.org/ffmpeg.html), [formatos MP4](https://ffmpeg.org/ffmpeg-formats.html).

| Evidencia del candidato | Resultado |
| --- | --- |
| Regresión local completa | 498 aprobadas, 2 omitidas, 0 fallos |
| Linux con PostgreSQL aislado | 111/111 |
| FFmpeg real, archivo de cámara que falló | H264 intacto por hash de paquetes; una pista AAC LC; duración 3,913 s; decodificación correcta |
| AAC existente, video silencioso y mezcla AAC+PCM | Paquetes de video y AAC existentes conservados; todas las pistas previstas presentes; decodificación y faststart correctos |
| Límite de tamaño, archivo inválido y fallo de proceso simulado | Original intacto por SHA256; cero temporales pendientes |
| Entrada HTTP real después del despliegue | El mismo clip obtuvo 200; descarga por receptor 200, 63.789 bytes; objeto y chat propios eliminados |

La versión vigente es `live-gifts-cierre11-a1299ad`, digest `a1299ad2435257c0cef15d30f65df7dc2124f9d35d5850a17c4ce681eb44a96b`. Se verificó respaldo antes del despliegue y la salud de API/gateway después. Coinciden **187/187 archivos seleccionados de API**; este conteo incluye configuración y scripts, por lo que su alcance es más amplio que los 114 archivos de fuentes del checkpoint anterior. Pasaron nuevamente las 23 comprobaciones HTTP funcionales y se eliminaron tres espacios de prueba.

Las pruebas de FFmpeg y del ingreso HTTP no sustituyen los seis recorridos nativos de captura, editor, confirmación, repositorio y recepción en chat/grupo/comunidad. Esa repetición continúa junto con la medición optimizada y las comunicaciones físicas.


### Último checkpoint físico — celular retirado, tablet disponible

Pasaron **31/31 pruebas en Motorola (53,656 s) y 31/31 en Samsung SM-X520 Android16 (43,623 s)**: cámara frontal/trasera, video con audio, confirmación, enlaces azules y navegación al destino, representación/exportación por píxeles, copias privadas de la imagen y recorte combinado. Se conservan los intentos anteriores fallidos: selector SetText en un campo deshabilitado y un timeout de confirmación de recorte en tablet. El recorte aislado pasó y la repetición completa pasó; no se eliminó el registro del fallo intermitente.

Ambas copias independientes `conectate.com.qa.cierre11` iniciaron sesión con cuentas propias y obtuvieron tokens reales de Firebase. Las aplicaciones principales y una sesión QA ajena preexistente en tablet se conservaron. El tiempo de este bootstrap no mide el arranque.

Antes de retirarse el Motorola, pasaron las comunicaciones entre ambos dispositivos: llamada con TURN relay y audio/video bidireccional, primera imagen 2.906/2.919 ms; sala de voz con audio/video, 1.406/1.305 ms; LIVE con audio/video en la tablet, 1.709 ms. Estos tiempos comienzan después de intercambiar los pares. Se registran FPS, pérdidas y congelamientos; no certifican calidad acústica subjetiva ni todas las redes.

En tablet, una llamada entrante real por FCM se mostró en 2.036 ms y Aceptar conectó en 2.371 ms. El recorrido de LIVE con repositorio y renderer reales tuvo muestras de **5.450, 3.247, 5.671 y 3.208 ms**; dos superan el objetivo interno de cinco segundos. La petición de unión tardó 341–527 ms; falta cerrar la variación posterior hasta la primera imagen. Son cuatro muestras con dos endpoints nativos dentro de una tablet; no representan P95.

La prueba integrada de cámara detectó dos defectos de su propio entorno: no aceptaba limpiar el bucket dedicado `thumbnails`, y su cliente usaba DNS del sistema en vez de `PublicBackendDns`, necesario en esta Wi-Fi con respuesta DNS privada sin ruta Tailscale. Se corrige el fixture para usar configuración y DNS de producción y para conservar el fallo original aunque falle la limpieza. **Los seis envíos todavía no están acreditados.**

La compilación R8 y recursos reducidos de paquete principal terminó correctamente, SHA-256 `a6767773f2557eee23b44ffbed3468b68040c92371e7ed028964d57bc3e5a846`. El usuario se llevó el Motorola; no se atribuye una medición de arranque a ese APK. Se prepara otra copia optimizada propia para medir en tablet sin sustituir su aplicación firmada por Play. **Continúa pendiente la certificación general de producción.**

## 8. Resultados consolidados después de retirarse el Motorola

Esta sección actualiza los pendientes históricos de las anteriores. Todas las publicaciones utilizaron cuentas y objetos propios; no se sustituyó la instalación Play ni la sesión QA ajena de la tablet. Los APK, resultados y hashes de logs se vinculan en [evidencia consolidada](evidencias/cierre-11-2026-10-04.json).

### 8.1 Cámara, editor y entrega

La prueba nativa integrada pasó en **68,151 s, seis casos correctos**: foto y video en mensajes directos, grupos y comunidades. Comprueba captura real, editor compartido, comentario, confirmación explícita, repositorio y subida de producción, lectura por otra cuenta mediante HTTP y WebSocket, descarga autorizada y decodificación. En fotos comprueba dimensiones; en videos, fotograma, audio y duración recortada. Esta prueba monta componentes y repositorios reales; no recorre toda la navegación de cada pantalla completa de chat.

| Contexto | Duración capturada | Intervalo confirmado en editor | Video entregado |
| --- | ---: | ---: | ---: |
| Directo | 4.272 ms | 2.938 ms | 2.971 ms |
| Grupo | 4.206 ms | 2.892 ms | 2.904 ms |
| Comunidad | 4.272 ms | 2.938 ms | 2.971 ms |

Se compara la selección real del RangeSlider con el archivo exportado y normalizado, con tolerancia de 200 ms, y se exige que el clip sea al menos 250 ms más corto que la captura. Se corrigieron cuatro problemas del fixture: limpieza que omitía thumbnails; DNS del sistema diferente al transporte del producto; límite fijo que suponía cuatro segundos de captura; creación HTTP de grupos/comunidades sin sincronizar antes sus padres en Room. La corrección llama a los métodos reales de sincronización. Se conservan los fallos y sus causas; no se modificó el repositorio de producción para esconder el fallo de preparación del fixture.

Se retiraron los objetos propios y los espacios de grupo/comunidad por sus APIs. **DELETE del chat directo oculta/limpia para el propietario: no acredita borrado físico de todas sus filas.** Se eliminaron los archivos privados de captura y exportación del caso. APK principal de esta ejecución: `93f2d8ae66cdac96573850fd751d6815c37b0b4b7a5129d9acb612f7bcc51205`; test: `dca3cd32974f86f6239e82e2fc5dbb8ce6820e47c199613ef65ce651f5b29e7f`.

### 8.2 Encuestas, perfil, fotografías y música en tablet

Pasaron **29/29 pruebas de regresión UI en 44,097 s** de encuestas, perfiles, música y sorteos. La publicación desde el WebView empaquetado contra la API distribuida y la respuesta de otra cuenta pasó en **7,256 s**; ese total de prueba no es una métrica exclusiva de publicación.

| Recorrido real en Samsung SM-X520 | Resultado |
| --- | --- |
| Persistencia de música de perfil y lectura por otra cuenta | Correcta |
| Toque a reproducción con avance de posición de perfil | 2.226 ms; pausa al detener lifecycle |
| Foto publicada visible y píxeles decodificados | 1.233 ms; visible con URL musical fallida |
| Selector de música de comunidad después de revisar 1.500 ms | 3.172 ms del toque a reproducción |
| Pista original, selección, recorte y ausencia de autoplay | Correctos |

No se trasladan a la tablet los 500 ms medidos antes en emulador. La variación del selector completo todavía necesita diagnóstico y reducción.

La prueba de dos audios publicados pasó en **12,545 s**, comparando el mismo transporte de red de producción. El baseline usa streaming sin caché preparada. Su primer intento usaba DNS del sistema y falló; se corrigió solo ese baseline de auditoría antes de repetirlo.

| Audio publicado | Baseline | Ruta fría actual | Reproducción tras preparación completada | Preparación |
| --- | ---: | ---: | ---: | ---: |
| 1 | 2.503 ms | 3.319 ms | 103 ms | 141 ms |
| 2 | 1.165 ms | 1.240 ms | 82 ms | 127 ms |

El audio avanzó y se pausó en los seis casos; la salida estaba silenciada para evitar sonido no solicitado. Se eliminó la cuenta propia de este caso y se verificó que su token ya no autorizaba. **La ruta fría no mejoró en todas las muestras.** Los 82–103 ms dependen de preparación completada y pertenecen al reproductor aislado; no equivalen a los 3.172 ms del selector y catálogo completos ni certifican calidad acústica.

### 8.3 LIVE con emisor separado y estabilidad

Se separó el emisor en el emulador propio Google APIs API35 y el espectador en la tablet. El espectador usa el repositorio real de unión y el renderer WebRTC real. Cada test de emisor y espectador pasó; no se alteró la sesión humana.

| Muestra | Petición de unión | Unión a primer fotograma representado |
| --- | ---: | ---: |
| 1 | 438 ms | 4.424 ms |
| 2 | 917 ms | 4.747 ms |
| 3 | 946 ms | 4.012 ms |

Los tiempos incluyen coordinación/polling del fixture. Comienzan al invocar la unión del repositorio; no incluyen toda la navegación y el botón de la pantalla del producto. Las tres muestras cumplen el presupuesto interno de cinco segundos, pero **no demuestran P95 ni invalidan las anteriores de 5.450 y 5.671 ms con dos motores en la tablet**. No se afirma que toda unión LIVE esté corregida en cualquier equipo/red. El emisor LIVE no exige audio entrante cuando el espectador es de solo recepción; se mantienen las aserciones bidireccionales de llamadas/salas.

La prueba de estabilidad de **240 s** pasó en 257,209 s, con media de **28,27 FPS** y recuperación ICE forzada de **805 ms**, preservando cámara y silencio. Pasaron desactivar/reactivar y cambiar cámara. La medida PSS de crecimiento usada por el test fue 33.993 KiB; la evidencia conserva todas las muestras. Dos motores estaban en una tablet. Cuatro minutos no certifican ausencia de fugas, batería, capacidad, múltiples espectadores ni cambio físico de Wi-Fi a datos móviles; una imagen casi estática no es una prueba de bitrate/carga.

APK de test de los tres escenarios con emisor separado y de audio final: `e10b490825c795fe4779b612d8fd2737d1d734772091317905c05426caaf56ad`. El emulador se detuvo y se retiraron únicamente sus paquetes C11 creados para esta tarea.

### 8.4 Arranque optimizado en tablet

Se instaló únicamente el paquete propio `conectate.com.qa.cierre11`, con R8, recursos reducidos y `debuggable=false`; se conservaron sus datos y cuenta. La aplicación principal Play permaneció instalada. SHA-256 del APK: `ee6e88b9b55f975de4709f58ed74c6e26747b634b42639da8ca8c581c2bb2864`.

Se hicieron dos tandas de cinco aperturas frías y cinco calientes. La tabla muestra la tanda final que además verifica ausencia/presencia del proceso y exige la clasificación COLD/WARM de Android. No hubo Gradle, emulador, instrumentación o trazado simultáneos.

| Apertura | TTID, muestras 1–5 | Bandeja local utilizable/TTFD, muestras 1–5 |
| --- | --- | --- |
| Fría | 257 / 238 / 256 / 251 / 223 ms | 606 / 565 / 530 / 569 / 513 ms |
| Caliente | 79 / 73 / 95 / 78 / 73 ms | 296 / 283 / 278 / 292 / 281 ms |

**10/10 por debajo de 3.000 ms.** La primera tanda también pasó: TTFD fría 446–642 ms, caliente 260–334 ms. La bandeja es local, con Room limitado a la cuenta autenticada y controles utilizables; la actualización remota puede continuar. La cuenta es QA propia, no la base humana poblada del Motorola. Force-stop no vacía cachés de disco/OS; no se midió primer inicio tras instalación o reinicio ni se estima P95. El APK optimizado principal para Motorola está construido, pero no se ha medido allí porque el usuario se llevó ese equipo.

### 8.5 Aviso administrativo y decisión de producción

La repetición del aviso en tablet se detuvo **antes de publicar y antes de iniciar el test**: Tailscale exigió renovar autenticación SSH durante SCP. Se canceló únicamente el proceso propio pendiente; no se omitió la autenticación ni se envió un aviso masivo. La API siguió disponible para los otros recorridos reales. Se conserva la recepción real previa por FCM en emulador Google APIs, con título/cuerpo y outbox/idempotencia comprobados; no se presenta como nueva recepción física.

La regresión Android completa quedó en **1.316/1.316, 285 suites, cero fallos/omisiones** y lint en **cero errores, 348 advertencias**. Backend local: 498 aprobadas, dos omitidas, cero fallos; Linux con PostgreSQL aislado: 111/111. Los tests de auditoría añadidos después compilaron y pasaron los escenarios indicados; no se suman repeticiones como nuevas funcionalidades probadas.

**Decisión: certificación general pendiente.** Faltan el arranque optimizado en Motorola con datos conservados, controlar la variación LIVE/selector musical, repetir aviso físico, acústica, redes externas, duración/carga/batería y lista general de release. La cámara tiene seis entregas integradas acreditadas en los componentes/repositorios descritos, sin prometer toda la navegación de todas las pantallas. No se certifica firma de tienda/AAB ni la totalidad del producto por estas suites.

### 8.6 Estado de los dispositivos y fixtures

Se archivaron en el directorio privado los reportes externos de la copia C11 de tablet y se retiraron de ese dispositivo; se desinstaló solo el paquete de instrumentación creado para esta tarea. La copia QA optimizada queda instalada y detenida para revisión posterior. Se verificó su hash antes de limpiar. Se retiraron de la caché de la QA antigua únicamente dos archivos de fixture cuyos IDs coincidían con las cuentas propias; no se borró su autenticación ni la instalación Play. Las cuentas desechables de coordinación se conservan para continuar con el Motorola; no se afirma que todas las cuentas/filas del fixture estén eliminadas. La limpieza de archivos propios del teléfono desconectado queda pendiente de su regreso.
