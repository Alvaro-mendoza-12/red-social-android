# Cierre de los once puntos y cámara de mensajería

Fecha de inicio: 4 de octubre de 2026. Rama: `feature/live-gifts`.

## Alcance y criterio de cierre

La petición incluye corregir código, comprobar backend/servidor, probar en el Motorola g04 conectado y evaluar la estabilidad general después del cierre de cada recorrido. La referencia visual orienta una cámara propia con vista previa, captura de foto/video, cambio de cámara, flash, acceso a galería y revisión antes de enviar. Se reutilizará el editor multimedia y el envío existentes.

Las pruebas anteriores de Sorteos (1.300 unitarias y 26 físicas) son una línea de partida; no certifican automáticamente estos recorridos. El servidor vigente es `live-gifts-cierre11-a1299ad`, identificado por sus hashes de fuente e imágenes en la evidencia consolidada. Los cambios de las otras IA se conservan.

Cada punto debe tener causa o comportamiento identificado, cambio verificable si procede, pruebas de regresión, evidencia del recorrido y límites. Una prueba con transporte simulado no cierra una comunicación entre usuarios reales. La matriz siguiente recoge el estado actual; los checkpoints posteriores conservan el historial de intentos. La continuación de medios, Descubrir y LIVE del 5 de octubre está en el [informe 72](docs/oficial/72-medios-social-y-live-redes-distintas-2026-10-05.md); los resultados anteriores están en el [informe 70](docs/oficial/70-medios-arranque-y-avisos-validacion-2026-10-04.md) y la [evidencia consolidada](docs/oficial/evidencias/cierre-11-2026-10-04.json).

## Matriz de requisitos y pruebas

| ID | Requisito funcional | Criterio verificable | Estado actual |
| --- | --- | --- | --- |
| 1 | Enviar y abrir enlaces en chat directo, grupo y comunidad | URL HTTP/HTTPS y dirección sin esquema resaltadas en azul, pulsación abre destino; puntuación fuera de enlace; errores sin cierre | Azul/contraste y destino comprobados en tres pruebas nativas por dispositivo; enlaces persistidos y recibidos por HTTP en los tres ámbitos. El handler externo se comprobó con un receptor de URI de prueba |
| 2 | Llamadas y salas de voz | Dos identidades reciben/publican audio; aceptación, rechazo, expiración, salida y reconexión coherentes; ninguna sesión antigua cancela la nueva | Audio/video entre Motorola y tablet; el usuario confirmó recuperación en datos y Wi-Fi en la segunda prueba. La corrección ordena la señalización, conserva duración y añade el permiso normal que necesita NetworkMonitor. Ambos extremos nativos recuperaron RTP después de ambos cambios. Muestreo continuo: intervalo máximo sin paquetes nuevos 3.846–4.226 ms al pasar a datos y 6.964–7.346 ms al volver a Wi-Fi. Recuperación comprobada; continuidad sin pausas y otras redes pendientes |
| 3 | Entrada y colaboración LIVE | Separar petición/token/conexión/suscripción/primer fotograma; recuperar medios tras pérdida de red; mostrar seis invitados y batalla coherente | El candidato v10 alcanzó 4.742 ms en una muestra física; v11 volvió a 5.744 ms. SFU recuperó audio pero dejó congelado el video tras un corte de datos; continúa abierto. Aforo HTTP: seis invitados aceptados, séptimo rechazado y espectador sin publicación autorizada. Cuadrícula y marcador pasan pruebas nativas con datos sintéticos; no acreditan seis cámaras físicas ni puntuación financiera. Informe 72 |
| 4 | Encuestas donde corresponda | Crear, votar/cambiar voto, cerrar, ver resultados; permisos privados; concurrencia sin duplicados; actualización de observadores | HTTP/SQL correctos en tres ámbitos. Editor 9/9 en teléfono/emulador y regresión de tablet correcta; publicación/respuesta reales en tablet 7,256 s. Informe 69 |
| 5 | Mensaje desde perfil ajeno | Abrir o recuperar chat correcto, impedir doble navegación y soportar perfil inválido/error sin cerrar Activity | Corregida selección del controlador principal; dos pruebas nativas de navegación y destino inválido/obsoleto pasan en ambos dispositivos. No se atribuyen todos los cierres históricos a esta única causa |
| 6 | Música de perfil | Selección/persistencia/lectura/reproducción/pausa y cambio de perfil correctos; error recuperable | Escritura y lectura por otra cuenta reales; tablet reproduce en 2.226 ms y pausa al salir. Emulador previo: 1.037 ms. Corregido PATCH parcial de compatibilidad |
| 7 | Música de comunidad en momento de video | Catálogo y reproducción reales, selección/recorte/exportación coherentes; medir encabezados, preparación y avance | Selector real repetido en tablet: primer toque 310 ms tras revisar 1.500 ms y 1.760 ms sin espera previa; 35/43 ms corresponden al driver de pulsación. Pista original, recorte y ausencia de autoplay correctos. La métrica detecta onIsPlaying en UI; no mide salida acústica. Se conserva la muestra previa de 3.172 ms y las pruebas de dos audios; variación/redes adicionales pendientes |
| 8 | Foto de momento | Imagen visible sin depender de preparación musical; loader/error/retry visibles; medir primera imagen en caché y red | Publicación privada y píxeles reales: 1.233 ms en tablet, 465 ms en emulador previo; visible aun con URL musical fallida. Momento y objetos propios eliminados |
| 9 | Avisos administrativos | Formulario simple, publicación persistida y notificación al destinatario; errores/permisos/reintentos correctos | UI, transacción/outbox/idempotencia y permisos pasan; 11 checks HTTP del bypass corregido. FCM real pasó en la tablet física: título/cuerpo correctos, un destinatario QA propio, outbox 814 ms y sin duplicar emisión. Test nativo 2,960 s. Se conserva el bloqueo SSH previo y un primer fallo de codificación UTF-8 del fixture; no se difundió a cuentas humanas |
| 10 | Visitas de perfil por defecto | Cuentas nuevas y preferencias antiguas no configuradas activas; registrar visitante real, deduplicar y listar identidades autorizadas | Defaults, deduplicación y privacidad del historial comprobados en SQL y HTTP del servidor vigente con cuentas propias |
| 11 | Arranque máximo de 2,5–3 s | Medir TTID y contenido utilizable por separado; APK optimizado, mismo dispositivo/datos, arranques fríos/calientes, sin Gradle/trazas simultáneos | Tablet: 10/10 bajo 3 s en QA. Motorola con cuenta y datos conservados: nuevo APK optimizado 10/10 bajo 3 s; cinco COLD TTFD 2.001 / 1.474 / 1.318 / 1.337 / 2.841 ms, cinco WARM 611 / 494 / 540 / 445 / 443 ms. Se conservan dos excepciones de preparación sin muestra y los fallos anteriores. Alcance: bandeja local utilizable, sin esperar refresco remoto; no es P95 ni arranque tras reinicio. Las mejoras multimedia posteriores requieren su propia regresión |
| C | Cámara en chats, grupos y comunidades | Foto y video reales, permisos, flash/cambio frontal, galería, previsualización/edición y envío por repositorio existente | 31/31 Motorola y 31/31 tablet. Seis entregas integradas correctas: foto/video por ámbito, cámara/editor/confirmación/repositorio reales, recepción HTTP/WebSocket y descarga decodificada. Servidor AMR a AAC LC: siete pruebas FFmpeg. No cubre toda la navegación de cada pantalla de chat |

## Decisión actual y siguientes comprobaciones

**No se certifica todavía que los once puntos estén cerrados de extremo a extremo ni que toda la aplicación esté lista para producción.** El candidato actual tiene 1.358 unitarias Android aprobadas y 77/77 pruebas de regresión v19 en Motorola, incluidas cuatro de galería y publicación/respuesta reales de una encuesta propia. En LIVE con cámara compartida, la recuperación estable tras el corte tardó 12.107 ms en Motorola y 17.819 ms en emulador: ambos exceden el límite de diez segundos. El emulador conserva dos tiempos de espera en la repetición UI dirigida de 27 pruebas sobre el candidato anterior. API actual: 463 aprobadas y cuatro omitidas; signaling: 29 aprobadas y una omitida, sin fallos. El lint anterior con cero errores/348 advertencias y Linux PostgreSQL 111/111 corresponden a su candidato histórico; no se presentan como validación de todos los cambios actuales. Informe 72 y JSON contienen el alcance por artefacto.

Pendientes concretos: corregir y repetir la recuperación de video SFU y el cambio de cámara compartida; controlar la variación de entrada LIVE; repetir arranque, lint y compilación optimizada con los cambios multimedia actuales; comprobar duración/carga/batería y la entrega firmada. El arranque anterior pasó diez muestras optimizadas bajo tres segundos con los límites de la fila 11. El aviso administrativo pasó mediante FCM real dirigido a la cuenta QA propia; el usuario confirmó audio en ambos cambios de red de la segunda llamada. No se infiere P95 de estas muestras. El paquete QA usa firma de pruebas.

El Motorola volvió a conectarse durante la mañana del 5 de octubre; la tablet dejó de estar disponible por ADB alrededor de las 05:58. Los checkpoints siguientes muestran la secuencia histórica. La disponibilidad de cada equipo y los resultados del emulador se registran por separado en el informe 72.

## Requisitos no funcionales

- Mantener permisos y autenticación también en backend; sin credenciales privadas en código/evidencia pública.
- Cancelar trabajo de pantalla al salir y aislar respuestas de otra cuenta; liberar cámara, micrófono y reproductores.
- IO/decodificación/exportación fuera del hilo principal; límites de tamaño/duración y listas paginadas.
- Estados de carga/error/reintento, objetivos táctiles de 48 dp, contraste y texto escalable.
- Operaciones persistidas e idempotentes donde un reintento pueda duplicar chat, mensaje, voto o aviso.
- Separar el arranque y la conexión de operaciones secundarias, conservando la validación de sesión y permisos.
- Reutilizar módulos existentes y agregar políticas pequeñas comprobables; evitar reescrituras ajenas al alcance.

## Documentación técnica consultada

- [CameraX: arquitectura y lifecycle](https://developer.android.com/media/camera/camerax/architecture).
- [CameraX: captura de video y eventos de finalización](https://developer.android.com/media/camera/camerax/video-capture).
- [CameraX: versiones publicadas](https://developer.android.com/jetpack/androidx/releases/camera).
- [LiveKit: suscripción y recepción de pistas](https://docs.livekit.io/guides/room/receive).
- [LiveKit: eventos de conexión y reconexión Android](https://docs.livekit.io/reference/client-sdk-android/livekit-android-sdk/io.livekit.android.events/-room-event/index.html).
- [Android: análisis y optimización de arranque](https://developer.android.com/topic/performance/appstartup/analysis-optimization).

## Evidencia y seguimiento

Se registran comandos, revisión de fuentes/servidor, hashes de artefactos, resultados de pruebas y métricas sin sesiones humanas, tokens o datos privados. Las verificaciones destructivas utilizan fixtures propios. Se probó con teléfono y emulador; al desconectarse el teléfono el usuario indicó continuar solo con el emulador. Los límites de FCM, acústica y rendimiento físico quedan explícitos.

## Hallazgos y avances de la primera revisión

### Checkpoint de validación y distribución — 2026-10-04

- APK debug build 4: 1301 pruebas unitarias, 283 suites, sin fallos ni omisiones. Motorola: 14 pruebas de cámara real, navegación desde perfil, enlaces y sorteos, todas correctas. Emulador: 10 de navegación, enlaces y sorteos, todas correctas. Estos recorridos de componentes no sustituyen las verificaciones HTTP y entre usuarios.
- Backend local completo: 464 pruebas, 462 correctas y 2 omitidas; cero fallos. PostgreSQL real de fixtures para encuestas y sorteos. Gateway: autenticación y autorización correctas. Candidato Linux: 65 pruebas correctas, incluyendo las tablas y rutas nuevas de sorteos.
- Servidor distribuido: release `live-gifts-cierre11-92e509a`, fuente SHA-256 `92e509a80f4085756dd65f784831acb72098fdc583f850d0e2d255e7aaff84b1`; 111/111 hashes de procedencia coinciden y API/gateway saludables. Es una instantánea de fuentes con cambios locales sobre `1509c84`, no un commit limpio.
- Previo a distribuir se verificó respaldo de PostgreSQL, almacenamiento y configuración en `/home/alvaro/conectate-backups/20261004T170240Z`. El primer intento utilizó un nombre incorrecto de servicio de gateway, detectado por comprobación de imagen; se restauró la API anterior y se retiró únicamente el contenedor temporal creado. El segundo intento usó el servicio real `realtime-gateway` y terminó correctamente. Las tablas nuevas son aditivas; no se restauró ni eliminó la base de datos humana.
- Dos dispositivos y dos cuentas temporales: LIVE recibió audio y video (28,99 FPS, primer fotograma 1652 ms); sala de voz recibió audio/video en ambos sentidos (28,72 y 30,59 FPS); llamada recibió audio/video (28,99 y 29,79 FPS) mediante candidatos TURN relay en ambos sentidos. Las métricas comienzan después del intercambio de pares; aún no acreditan el tiempo completo desde pulsar Unirse ni la calidad acústica.
- Encuestas, nuevo reporte visual: se reprodujo un cuerpo de diálogo de altura cero en el WebView con texto ampliado. Se simplificaron título/preguntas/opciones, se plegaron ajustes avanzados, se retiró la barra horizontal de acciones y los elementos superpuestos. Se agregó guardado local de borrador incompleto y validación por campo antes de publicar. Se conserva el contrato de participación identificada, consentimiento y plazo automático. Validación final Android y publicación con servidor real en curso.

Actualización de cierre del editor: **9/9 pruebas en teléfono y 9/9 en emulador**, incluyendo límites de contacto/opciones, diálogo al 160 %, borrador recuperable y publicación/respuesta con API real. Documento de detalle: `docs/oficial/69-encuestas-editor-movil-y-publicacion-2026-10-04.md`.

HTTP desplegado: **23 verificaciones correctas** con dos cuentas propias. Visitas por defecto, escritura deduplicada y privacidad del historial; enlaces persistidos y recibidos en directo/grupo/comunidad; creación de sorteo por miembro sin administración e idempotencia; encuestas de mensaje en los tres ámbitos con cambio/retirada de voto sin borrar voto ajeno, cierre y rechazo de votos tras cerrar. Se eliminaron los tres espacios de prueba. La primera comprobación comparó toda la serialización de mensajes y fue demasiado estricta para las marcas temporales; la repetición verificó la identidad de mensaje y encuesta, el contrato relevante de idempotencia.

- Cambio solicitado en la captura de Sorteos: en grupos, cualquier integrante activo debe poder crear. Se separaron `canCreate` de administración de sala y la gestión del sorteo propio. El backend revalida membresía, propiedad del sorteo, expulsión y permisos del iniciador automático. Se añadieron pruebas SQL/HTTP y el formulario Android se prueba con un miembro sin administración. El error de la captura se investiga como un problema independiente del permiso.

- El usuario confirmó que se utilice un emulador como segundo dispositivo. Se inició el AVD existente `NovvinMediaPair35`, API 35, junto al Motorola físico; no se borraron sus datos.
- `MessageMediaComposer` ofrecía galería/editor, pero carecía de captura. Se integró CameraX 1.6.2 con permisos independientes, foto, video HD con audio opcional, cambio frontal, flash/linterna, límites de 60 s/100 MiB, URI privada mediante FileProvider y revisión en el editor existente. Se agregaron cuatro pruebas físicas de captura/confirmación/cancelación. Validación funcional pendiente.
- El botón Mensaje usaba el controlador local aunque el perfil puede pertenecer al grafo anidado de Home. Ahora selecciona el controlador principal suministrado; rutas codifican la identidad y un destino obsoleto/identidad ausente produce error recuperable. Se agregaron dos pruebas de navegación. Este riesgo encontrado no se presenta como una reproducción de todos los cierres históricos.
- La primera compilación de producto de cámara fue correcta; falló la compilación de su test por una dependencia de reglas inexistente. Se reutilizó UiAutomation para permisos. La siguiente ejecución detectó la lista estricta de FileProvider del test anterior: se añadió únicamente el directorio dedicado de capturas, conservando la prohibición de exponer raíces. Se preservan ambos logs y se repetirá la suite.
- SSH funciona con el usuario del runbook. El contenedor API sigue etiquetado `f42a3493ed4d06c9ff019acf71efc8b25b6b3a81`. Los hashes de servidor, datos, LIVE, almacenamiento y compatibilidad difieren de la rama; el módulo de avisos coincide. No se atribuye a producción el código local todavía no distribuido.
- El proveedor FCM confirmó `configured=true` mediante `pushNotificationsStatus()`. La configuración LiveKit está presente. Estos checks de configuración no sustituyen la recepción de una notificación o de pistas multimedia.
- Se añadió `auditRelease`, con la misma configuración de optimización que release y paquete QA separado, para medir arranque sin sustituir la instalación humana por un APK con otra firma. Compilación/medición aún pendientes.

### Continuación con celular y tablet — 4 de octubre de 2026

El usuario volvió a conectar Motorola g04 (Android14) y Samsung SM-X520 (Android16). La tablet conserva su instalación firmada por Play; se prepara una copia de QA independiente. La repetición de 31 pruebas de cámara/enlaces/editor en Motorola pasó 30; una falló al buscar SetText en un campo deshabilitado, cuya acción no existe por diseño. El selector se corrigió y la repetición queda pendiente.

Se reprodujo HTTP422 al enviar el video recortado de cámara: H264 con audio AMR_NB no podía copiarse directamente al contenedor MP4. El cambio 1e1515f convierte únicamente pistas no AAC a AAC LC, conserva el video y el AAC existente y reemplaza el original después de validar. Pasaron 498 pruebas locales, dos omitidas, cero fallos; 111/111 Linux con PostgreSQL aislado; siete casos de FFmpeg real, incluyendo el archivo que falló, conservación de hashes de paquetes, decodificación, faststart y limpieza ante rechazo. Documentación técnica: [FFmpeg: selección de códecs](https://ffmpeg.org/ffmpeg.html) y [contenedor MP4/faststart](https://ffmpeg.org/ffmpeg-formats.html).

El candidato `live-gifts-cierre11-a1299ad` fue desplegado tras verificar respaldo; API/gateway saludables, 187/187 archivos seleccionados de API coinciden. Pasaron de nuevo 23 comprobaciones HTTP funcionales y se eliminaron tres espacios propios. La primera repetición nativa en emulador terminó como «Process crashed» sin excepción AndroidRuntime; coincidió con menos de 0,5GiB libre en el host y no acredita entrega. Se priorizan dispositivos físicos y se conserva el intento fallido.


### Último checkpoint físico — celular retirado, tablet disponible

Pasaron **31/31 pruebas en Motorola (53,656 s) y 31/31 en Samsung SM-X520 Android16 (43,623 s)**: cámara frontal/trasera, video con audio, confirmación, enlaces azules y navegación al destino, representación/exportación por píxeles, copias privadas de la imagen y recorte combinado. Se conservan los intentos anteriores fallidos: selector SetText en un campo deshabilitado y un timeout de confirmación de recorte en tablet. El recorte aislado pasó y la repetición completa pasó; no se eliminó el registro del fallo intermitente.

Ambas copias independientes `conectate.com.qa.cierre11` iniciaron sesión con cuentas propias y obtuvieron tokens reales de Firebase. Las aplicaciones principales y una sesión QA ajena preexistente en tablet se conservaron. El tiempo de este bootstrap no mide el arranque.

Antes de retirarse el Motorola, pasaron las comunicaciones entre ambos dispositivos: llamada con TURN relay y audio/video bidireccional, primera imagen 2.906/2.919 ms; sala de voz con audio/video, 1.406/1.305 ms; LIVE con audio/video en la tablet, 1.709 ms. Estos tiempos comienzan después de intercambiar los pares. Se registran FPS, pérdidas y congelamientos; no certifican calidad acústica subjetiva ni todas las redes.

En tablet, una llamada entrante real por FCM se mostró en 2.036 ms y Aceptar conectó en 2.371 ms. El recorrido de LIVE con repositorio y renderer reales tuvo muestras de **5.450, 3.247, 5.671 y 3.208 ms**; dos superan el objetivo interno de cinco segundos. La petición de unión tardó 341–527 ms; falta cerrar la variación posterior hasta la primera imagen. Son cuatro muestras con dos endpoints nativos dentro de una tablet; no representan P95.

La prueba integrada de cámara detectó dos defectos de su propio entorno: no aceptaba limpiar el bucket dedicado `thumbnails`, y su cliente usaba DNS del sistema en vez de `PublicBackendDns`, necesario en esta Wi-Fi con respuesta DNS privada sin ruta Tailscale. Se corrige el fixture para usar configuración y DNS de producción y para conservar el fallo original aunque falle la limpieza. **Los seis envíos todavía no están acreditados.**

La compilación R8 y recursos reducidos de paquete principal terminó correctamente, SHA-256 `a6767773f2557eee23b44ffbed3468b68040c92371e7ed028964d57bc3e5a846`. El usuario se llevó el Motorola; no se atribuye una medición de arranque a ese APK. Se prepara otra copia optimizada propia para medir en tablet sin sustituir su aplicación firmada por Play. **Continúa pendiente la certificación general de producción.**
