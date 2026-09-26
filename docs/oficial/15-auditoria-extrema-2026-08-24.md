# Auditoría extrema de producción — 2026-08-24

## Dictamen

La rama auditada es `feature/live-gifts`. El código Android, backend, web,
realtime, signaling e IA supera sus puertas automatizadas, y se corrigieron
defectos graves del feed y de la admisión de videos. El backend, Nginx y la
normalización histórica sí quedaron desplegados posteriormente; no corresponde
declarar el producto listo para publicación general hasta probar el AAB firmado
en dispositivos reales y cerrar la infraestructura pública estable.

## Actualización exhaustiva — 2026-08-25

La segunda pasada amplió la auditoría a concurrencia entre réplicas, durabilidad de workers, aislamiento entre cuentas web, rutas profundas Android, confianza en rutas físicas de storage y privacidad/abuso de señalización. Se corrigieron estos hallazgos adicionales:

### Actualización de cierre técnico — 2026-08-25

Una tercera pasada se concentró en la ruta real de reproducción, las superficies
sociales más consultadas y la configuración efectiva de los contenedores:

- **P0, privacidad de salas privadas:** una sala de grupo/comunidad privada sin
  filas de membresía podía tratarse como accesible y devolver sus mensajes. La
  ausencia de miembros ya no concede acceso; sólo creador, miembro, sala pública
  o una vista previa expresamente autorizada pueden continuar.
- **P1, autorización de lectura de chats:** “marcar como leído” podía
  materializar un chat UUID sin demostrar primero que existía y que la cuenta
  pertenecía a él. Ahora resuelve chat y participantes por ID, devuelve 404/403
  antes de escribir y sólo entonces actualiza recibos.
- **P1, lecturas globales en rutas calientes:** detalle de post/perfil/chat,
  seguidores, notificaciones, colecciones guardadas, amistades, retos, reenvío
  multimedia, salas de voz y salas de comunidad ya no cargan catálogos de entre
  5.000 y 50.000 filas para una respuesta pequeña. Consultan primero los IDs del
  usuario o contenido y sólo hidratan autores/interacciones necesarios.
- **P1, precargas de video huérfanas:** al deslizar rápidamente, cancelar la
  corrutina no detenía el `CacheWriter` bloqueante; descargas viejas podían
  competir con el video visible. La precarga es ahora cancelable de extremo a
  extremo y comparte el mismo cliente OkHttp/pool/cache acotado del reproductor.
- **P1, proceso API privilegiado:** ClamAV y la migración única de propietario
  del volumen se ejecutan durante el arranque controlado; el proceso Node de
  larga duración baja después a `novvin:novvin` (UID/GID 10001). El filesystem
  del contenedor es de sólo lectura, `/tmp` es `tmpfs`, se eliminan capabilities
  y sólo se agregan las mínimas necesarias para esa migración y cambio de UID.
- **P1, metadata de release inconsistente:** el despliegue completo podía escribir
  `.conectate-release` antes de construir y validar el stack; tras un fallo, `/ready`
  podía anunciar un commit inexistente. La confirmación ahora ocurre después de los
  health checks, recrea el API para leer el metadata, compara el commit esperado y
  restaura el marcador previo si la operación final falla. Además registra la huella
  SHA-256 de los artefactos exactos transferidos, la expone como `sourceDigest` en
  `/ready` y la verifica contra el marcador; así un worktree con cambios sin commit
  ya no puede presentarse como el `HEAD` limpio. Una regresión estática protege el
  orden y la identidad.
- **P1, bearer web persistente y revocación incompleta:** el JWT web de siete
  días se guardaba en `localStorage`, el pairing reutilizaba el mismo JWT como
  access/refresh token y los cambios de contraseña no revocaban
  `fallback_web_sessions`. La credencial persistente es ahora un secreto opaco
  `HttpOnly + Secure + SameSite=None + Partitioned`, almacenado sólo como hash;
  el bearer web dura 15 minutos y vive en memoria. Login, registro, QR,
  recuperación, cambio de contraseña, recarga, multi-tab, rotación del endpoint,
  uploads, realtime, llamadas y Live conservan un camino explícito de
  renovación. Las operaciones de cookie exigen origen CORS confiable y header
  anti-CSRF, y todos los cambios de contraseña revocan sesiones móviles y web.

Evidencia de la pasada anterior (2026-08-25): `58/58` archivos de prueba del API, compilación Kotlin
y pruebas dirigidas de reproducción aprobadas, auditoría runtime con cero
hallazgos, `npm audit --omit=dev` con cero vulnerabilidades y auditoría SQL sin
violaciones nuevas. La imagen Docker no pudo ejecutarse localmente porque el
daemon no estaba disponible; sí aprobaron sintaxis, pruebas de configuración y
`docker compose config`. Tampoco había un dispositivo ADB conectado, por lo que
la evidencia visual, accesibilidad física y variación por fabricante sigue
pendiente y no se declara aprobada.

### Actualización de evidencia local — 2026-08-26

La compuerta ejecutada sobre el estado actual de `feature/live-gifts` volvió a
aprobar todas las áreas locales disponibles. Tras retirar código heredado Android
terminó la ejecución final de `qualityGateDebug` en 13m02s; la misma compuerta completó 60/60 pruebas Python y las auditorías
runtime/SQL con cero hallazgos. La web aprobó
lint, 15 suites con 81 pruebas y el build de producción. El bundle web ya no
genera el chunk vendor vacío y el JS principal queda en 96.45 kB (27.36 kB gzip).

La API aprobó 90/90 pruebas Node. Signaling aprobó check y 27 pruebas (26 pasaron y
una integración Redis quedó omitida porque no se proporcionó `NOVVIN_TEST_REDIS_URL`);
realtime aprobó check y
pruebas. IA aprobó check, pruebas y evaluación V5 con 100 % en los cinco conjuntos,
con media de 1.17 ms, p95 de 1.855 ms y p99 de 2.285 ms. Después de añadir el
`package-lock.json` que faltaba, `npm audit --omit=dev` quedó reproducible y
reportó 0 vulnerabilidades en API, web, realtime, signaling e IA.

El script de la compuerta ahora autodetecta un JDK 17–21 compatible cuando el
`JAVA_HOME` del equipo apunta a una versión no soportada, y falla con un mensaje
explícito si no encuentra ninguno.

También se corrigió la lectura de `java -version` en PowerShell: Java escribe esa
respuesta en `stderr`, pero la compuerta ahora la captura como texto sin convertirla en
un falso fallo bajo `$ErrorActionPreference = "Stop"`.

Esta evidencia sigue siendo local: no sustituye el despliegue de la rama, un AAB
firmado con la clave de Play ni la prueba física en dispositivos Android.

Como verificación adicional, el release actual se ensambló con una clave efímera de
auditoría en 14m57s. R8 (35.907 clases), lint vital, firma estructural, alineación de
páginas de 16 KiB y presupuesto de rendimiento aprobaron. El artefacto quedó aislado
como `build/tmp/novvin-release-audit-20260826/artifacts/NOVVIN-3.3.4-66-AUDIT-NOT-FOR-PLAY.aab`,
con SHA-256 `32A781B610D61F088F88C382F117D5F0AA5D3AD236086E305B5A7AC0A6503432`.
La clave es exclusivamente de auditoría y no coincide con la de Google Play.

### Hallazgos nuevos de la pasada de código — 2026-08-26

- **P1, exploración de comunidades:** el endpoint Ubuntu existía, pero el cliente
  Android devolvía `Not implemented yet` al buscar comunidades. Se implementó la
  llamada autenticada con consulta limitada, validación de HTTP/JSON y cancelación
  cooperativa.
- **P1, eliminación de cuenta por backend incorrecto:** la ruta propia de cuenta
  usaba Supabase aun con sesión Ubuntu y una pantalla heredada conservaba un botón
  sin acción. Ahora el cliente usa el RPC local autenticado cuando corresponde,
  limpia el estado sólo tras éxito y la pantalla muestra confirmación, progreso,
  error y navegación al login.
- **P1, compartir publicaciones inconsistente en Ubuntu:** `ShareRepository` sólo
  cambiaba al backend local para videos; las publicaciones se enviaban al backend
  equivocado y no existía la operación local de descompartir. Se añadieron los
  endpoints autenticados POST/DELETE, el enrutamiento Android por tipo de contenido
  y actualización optimista con rollback en las acciones visibles del ranking.
  La prueba de rutas y la suite API completa cubren ambos caminos.
- **P1, preview corto usado durante el recorte de música:** el catálogo comunitario
  mostraba un preview de 30 segundos y el botón “Escuchar” del editor podía volver a
  cargar ese preview aunque el usuario hubiera seleccionado el audio completo. Los
  controles del editor ahora fuerzan `audioUrl`; el preview queda restringido a la
  exploración del catálogo. Esto permite seleccionar y escuchar posiciones posteriores
  en pistas de varios minutos antes de publicar el momento.
- **P1, silencio al final de una pista corta:** el renderizador AAC-LC/M4A completaba
  con silencio si el recorte superaba el final de la fuente. FFmpeg ahora repite la
  entrada de forma acotada y conserva el límite exacto de salida, de modo que una
  imagen puede mantener seis segundos audibles incluso con una fuente corta o un
  recorte cercano al final. La regresión está cubierta en la prueba del renderizador.
- **P1, superficie de reproducción retenida:** el feed tenía un único ExoPlayer, pero
  al reciclar una página de `VerticalPager` no desvinculaba explícitamente el
  `PlayerView` anterior. Eso podía dejar un frame retenido o aparentar superposición al
  cambiar de vídeo. Ahora `onRelease` libera la vista, el guard del primer frame se
  reinicia al perder selección y la reproducción espera a que el `MediaItem` visible
  coincida con la playlist reconciliada. Compilación Kotlin y pruebas unitarias
  específicas aprobaron; falta la comprobación visual en un teléfono real.
- **P1, candidato de feed sin multimedia reproducible:** la fuente autoritativa podía
  aceptar un vídeo moderado cuya URL estuviera vacía, fuera HTTP o incluyera
  credenciales. El cliente recibía entonces una tarjeta negra o un medio imposible de
  iniciar. La admisión ahora exige una URL HTTPS pública y estable, falla cerrado para
  que otro candidato ocupe el espacio y reutiliza únicamente esa URL validada como
  respaldo del sonido original. La suite completa del API aprobó 90/90 pruebas y el
  chequeo estático terminó limpio.

### P0 — crítico, corregido en código

- **Imagen Docker de signaling no arrancable:** `index.js` requería
  `payload-policy.js`, pero el Dockerfile no lo copiaba. Una reconstrucción del
  contenedor habría terminado al iniciar con `MODULE_NOT_FOUND`, aunque las
  pruebas locales pasaran. El contexto incluye ahora todos los módulos runtime
  y una prueba deriva automáticamente cada `require('./...')` de `index.js` y
  falla si el Dockerfile omite alguno.
- **Base nueva bloqueada por DDL fuera de orden:** la inicialización intentaba
  crear `idx_fallback_video_deletions_lookup` antes de crear
  `fallback_video_deletions`. Un servidor previamente migrado ocultaba el fallo,
  pero una instalación limpia podía abortar el arranque. La tabla se crea ahora
  antes del índice, una regresión fija ese orden y un barrido automático de todo
  el DDL confirmó que no queda otro índice anterior a su tabla.
- **Regalo Live falsificable:** quedaba un listener heredado `live-gift` que
  aceptaba importe, receptor y regalo suministrados por el cliente y podía
  alterar el marcador visual de una batalla sin transacción. Se eliminó. La
  animación y el marcador sólo avanzan tras consultar en Ubuntu el recibo
  comprometido, comprobar live, emisor, receptor permitido, visibilidad,
  importe positivo e idempotencia por `transactionId`.

### P1 — alto, corregido en código

- **Inicialización concurrente del esquema:** varias réplicas podían ejecutar DDL de arranque simultáneamente. La inicialización ahora toma un advisory lock compartido, conserva una sola conexión PostgreSQL y tiene timeout configurable/fail-closed.
- **Rate limiting no distribuido:** login, registro, recuperación, pairing y uploads sensibles usaban memoria local; reinicios o réplicas multiplicaban el límite. Los contadores ahora se incrementan atómicamente en PostgreSQL, usan claves SHA-256 sin PII legible, expiran y fallan cerrados ante error de base de datos.
- **Sesiones revocadas en rutas de compatibilidad:** la validación común de sesión se aplica también a superficies legacy/PostgREST; los tokens administrativos se comparan en tiempo constante.
- **Pérdida silenciosa de recordatorios:** un recordatorio de evento podía marcarse enviado antes de persistir su notificación. Claim, persistencia y marca se confirman ahora en la misma transacción con `FOR UPDATE SKIP LOCKED`; realtime/push se despachan después del commit.
- **Cumpleaños truncados a 100 usuarios:** el worker recorría siempre la primera página y se duplicaba entre réplicas. Ahora usa lease diario, recuperación de lease vencido, cursor keyset, heartbeat y resolución de alias por lote; procesa hasta 50.000 cumpleaños por ejecución controlada.
- **Deep link de recuperación y FileProvider:** una Activity exportada aceptaba recuperación desde orígenes arbitrarios y registraba la URI completa, incluidos tokens del fragmento. La recuperación queda limitada a `conectate://recovery`, se validan hosts/valores acotados y nunca se registra la URI completa. FileProvider ya no expone toda la raíz externa.
- **Reintentos web de escrituras:** la rotación de túnel podía repetir automáticamente `POST`, `PUT`, `PATCH` o `DELETE` después de una respuesta ambigua. Sólo `GET`, `HEAD` y `OPTIONS` se reintentan; las mutaciones quedan para reconciliación explícita.
- **Cruce de cuentas en navegador:** cola diferida, cache de salas de voz y sala activa no tenían propietario. Las claves son ahora por cuenta; la cola legacy sin dueño se descarta en vez de ejecutarse con la siguiente sesión.
- **Lectura/borrado arbitrario por `local_path`:** la identidad pública `bucket/name` podía ser redirigida por una ruta almacenada en base de datos. Lectura, borrado y render de música exigen que `local_path` coincida exactamente con la ruta canónica bajo `STORAGE_ROOT`.
- **Privacidad y abuso en señalización:** los logs contenían correo, IP, IDs directos y fragmentos SDP. Se sustituyeron por referencias seudonimizadas; los errores se acotan, no se registra SDP, Socket.IO limita cada mensaje a 256 KiB, valida SDP/ICE y aplica límite de paquetes por socket.
- **Emparejamiento web heredado inseguro/inoperante:** la función antigua de Supabase tenía un cliente nulo, tipos sin importar y una IP LAN fija, aunque producción ya exige Ubuntu. Se retiró todo manejo de credenciales y quedó como endpoint de cierre `410 UBUNTU_BACKEND_REQUIRED`; la auditoría runtime impide reintroducir tokens o direcciones privadas en esa ruta.
- **Confianza del endpoint dinámico:** DNS TXT y `backend-config.json` podían redirigir el bearer a cualquier origen HTTPS que imitara `/ready`. La rotación ahora usa envelopes `novvin1` con URL y expiración firmadas mediante ECDSA P-256; Android y web verifican la firma antes de probar/adoptar el origen, exigen capacidades y rechazan cachés legacy. La clave privada se genera y permanece sólo en Ubuntu.
- **Transporte signaling entre réplicas:** se incorporó el adaptador oficial
  Socket.IO Redis Streams con Redis 7.4 privado, autenticado, sin puerto de
  host, AOF `everysec`, memoria acotada y `noeviction`. Voz, llamadas y Live
  pueden difundir eventos y consultar sockets entre procesos; la autorización
  de offer/answer/ICE valida la sala real también cuando el destino está en
  otra réplica y falla cerrada ante timeout. Los bearer permanecen en un
  `WeakMap` local y nunca se serializan en `socket.data`/Redis.
- **Presencia y reemplazo de conexiones:** snapshots de voz y llamadas se
  obtienen de todas las réplicas, con metadata pública explícita. Una
  desconexión antigua del mismo usuario ya no puede borrar de PostgreSQL la
  presencia nueva: `/leave` compara también el `socket_id` que realmente se
  desconectó.
- **Afinidad de Socket.IO y proxy de producción:** Nginx usa hash consistente
  por sala/llamada/directo, con fallback por IP para versiones anteriores, DNS
  dinámico de réplicas y clientes web/Android que envían una clave acotada. Se
  conserva polling en llamadas donde WebSocket puro reduce compatibilidad. El
  edge ya no reemplaza a todos los usuarios por la IP del túnel ni HTTPS por
  HTTP interno: propaga `CF-Connecting-IP` y el protocolo público validado,
  evitando límites globales falsos y decisiones de seguridad incorrectas.
- **Entrega multimedia innecesariamente mediada por Node:** la API conserva la
  autorización, las URL firmadas, ETag, política de cache y selección de
  variantes, pero delega el archivo aprobado mediante `X-Accel-Redirect` a una
  ubicación `internal` de Nginx. El edge monta storage en solo lectura y usa
  `sendfile`; las peticiones `Range` continúan soportadas sin ocupar Node para
  mover cada byte del video, audio o imagen.
- **Contenedores stateless ejecutados como root:** signaling y realtime ahora
  se ejecutan como el usuario `node`, con filesystem de solo lectura, todas las
  capabilities eliminadas, `no-new-privileges`, límites de PID/CPU/memoria y
  `/tmp` acotado. IA corrige únicamente el pequeño volumen de modelo al iniciar
  y baja privilegios antes de ejecutar Node.
- **Permisos de presencia acoplados:** el perfil público mostraba `last_seen`
  cuando sólo el indicador “en línea” estaba autorizado, y podía ocurrir el
  caso inverso. Ambos permisos se aplican ahora de forma independiente y las
  combinaciones cruzadas quedan cubiertas por regresión automatizada.
- **Visibilidad “Solo conexiones” incoherente:** Android persistía
  `connections`, mientras la API sólo reconocía `followers`; el perfil quedaba
  oculto incluso a sus conexiones, pero todavía aparecía en búsqueda y ranking
  para desconocidos. La API normaliza ambos nombres al mismo significado,
  permite el acceso al seguidor real y filtra búsqueda/ranking según el usuario
  autenticado, incluida la excepción segura para ver el perfil propio.
- **Metadatos sociales sin compresión en el edge:** las respuestas JSON del
  feed, perfiles y social atravesaban el túnel sin compresión del proxy. Nginx
  ahora aplica gzip con umbral de 1 KiB y `Vary: Accept-Encoding` sólo a tipos
  textuales/JSON; video, audio y octet-stream quedan excluidos para no interferir
  con `Range`, `sendfile` ni la caché multimedia.
- **PII de diagnóstico en logcat:** varios flujos Android heredados interpolan
  IDs de usuario/chat/mensaje y URLs en `android.util.Log`. Debug conserva ese
  detalle para soporte local, pero R8 elimina todas las llamadas directas a
  Log del artefacto minificado de Play; producción continúa usando los canales
  estructurados de Crashlytics/backend. Una guarda impide retirar esta regla.
- **Tokens firmados en logs del edge:** el formato de acceso predeterminado de
  Nginx habría persistido la query completa de URLs firmadas, además de IP,
  referrer y user-agent. El formato `novvin_safe` registra sólo método, `$uri`
  sin argumentos, request-id, estado, bytes y tiempos; una prueba prohíbe
  reintroducir `$request_uri`, `$args` o identificadores directos.
- **Paginación autoritativa mutable:** el ranking recalculaba el reloj en cada
  página y el snapshot incluía contadores de likes/vistas/comentarios. Una vista
  nueva podía invalidar el cursor o reordenar contenido mientras el usuario
  hacía scroll. El cursor conserva ahora su `issuedAt`, candidatos e inferencia
  quedan congelados al mismo `asOf`, los contadores no rotan el snapshot y una
  edición real del contenido sí lo invalida.
- **Sobrecarga crítica en Video y Social:** rutas de compatibilidad
  materializaban entre 5.000 y 10.000 perfiles y decenas de miles de likes,
  comentarios y shares para devolver una página pequeña. Video consulta ahora
  sólo autores e interacciones de sus candidatos; Social pagina primero posts
  recientes/seguidos y luego consulta únicamente relaciones, autores e
  interacciones visibles. También se eliminaron lecturas globales al abrir un
  video, resolver su audio o procesar un alta realtime.
- **Resultados cruzados al cambiar de cuenta en Social:** solicitudes
  suplementarias de la cuenta anterior podían terminar después del cambio de
  sesión y repoblar personas, amistades o tarjetas antiguas. Un contador de
  generación cancela/descarta respuestas obsoletas, limpia todo el estado
  visible y prioriza el feed principal antes de cargar suplementos.
- **Errores backend con PII y detalles internos:** varios `catch` persistían el
  mensaje crudo del driver, rutas o proveedores. Producción registra ahora
  código acotado y referencia hash estable; el mensaje legible queda sólo en
  desarrollo. Health, push, IA, pagos, storage, moderación y workers usan la
  misma política.
- **Vista previa de enlaces con confianza débil:** la comparación heredada por
  subcadena aceptaba dominios como `youtube.com.evil.test`, seguía redirecciones
  automáticamente y no acotaba la respuesta. Sólo se consulta HTTPS en una
  lista exacta de hosts/sufijos, sin credenciales; cada redirección vuelve a
  validarse, se limita a cuatro saltos y 1 MB, y URLs no confiables generan una
  tarjeta local sin petición de red.
- **SSRF por DNS en el render de música:** el filtro remoto bloqueaba IP
  privadas escritas en la URL, pero una resolución DNS cambiante todavía podía
  llevar la descarga del servidor a loopback, metadata cloud o la LAN. El
  renderizador resuelve todas las direcciones, exige unicast público, fija la
  conexión HTTPS a ese conjunto y repite validación/fijación en cada redirección;
  rechaza también CGNAT, IPv4-mapeado en IPv6, link-local, unique-local y rangos
  reservados.

### P2 — medio, corregido en código

- CORS de API acepta listas explícitas y rechaza wildcard, HTTP, credenciales o paths en producción.
- Web desactiva source maps públicos de producción y endurece `Cross-Origin-Resource-Policy`/DNS prefetch.
- Los límites de cumpleaños convierten entradas numéricas a enteros antes de usarlas como `LIMIT`, evitando errores por configuración decimal.
- El runtime de Gradle queda explícitamente soportado en JDK 17–21; JDK 22+ falla antes de iniciar una compilación engañosa.
- El verificador release ya distingue AAB/JAR de APK Signing Block v2+; antes declaraba soportar APK pero producía un falso negativo al pasarlo por `jarsigner`.
- Las imágenes mantenidas de Node y PostgreSQL ya fijan versión de parche en
  vez de seguir tags móviles por versión mayor; su existencia se verificó en el
  registro oficial y una prueba impide volver a tags flotantes.
- Media3 usa una sola instancia OkHttp compartida para reproducción, con pool
  de conexiones, HTTP/2 cuando el origen lo negocia, timeouts acotados y
  reintento de conexión sólo para las lecturas GET seguras del reproductor. La
  ventana de precarga se cancela al cambiar de video para que contenido obsoleto
  no consuma el ancho de banda del elemento visible.

### Riesgos residuales que no deben ocultarse

- **P2 compatibilidad de sesiones web antiguas:** la sesión nueva ya no conserva el
  bearer en almacenamiento JavaScript: el refresh secret vive en cookie `HttpOnly`
  y el access token sólo en memoria. Para no cerrar de golpe sesiones creadas por
  versiones anteriores, existe una ruta de migración de una sola lectura que toma
  el JWT legacy, lo intercambia por la cookie opaca y sobrescribe inmediatamente el
  registro persistente con metadatos sin token. Debe retirarse cuando termine la
  migración de usuarios antiguos; las sesiones nuevas ya cumplen el modelo
  `HttpOnly; Secure; SameSite` con protección CSRF/BFF.
- **P1 ensayo de failover de Live:** fuente, pausa, cámara/micrófono, reacciones,
  moderación, chat pausado, moderadores, palabras bloqueadas, solicitudes de
  invitados, invitados activos, batallas, puntajes, temporizadores y leases del
  host ya se serializan en Redis sin bearer ni identificadores de socket. El
  contrato de lease distribuido pasó contra el Redis de producción usando un
  prefijo temporal que se eliminó al terminar. Aun así,
  `SIGNALING_REPLICAS=1` sigue siendo el valor oficial hasta ejecutar y registrar
  una caída real de nodo con rehidratación multi-proceso. No debe anunciarse
  failover completo antes de esa prueba operativa.
- **P1 release:** falta la clave original de carga de Google Play. La variante release se verificó con certificado efímero y fue aislada como `AUDIT-NO-USAR-EN-PLAY`; no se dejó un AAB ambiguo en la carpeta publicable.
- **P2 búsqueda/ranking global:** ranking público y búsqueda semántica todavía
  construyen corpus globales acotados. Es correcto funcionalmente para el
  volumen actual, pero una escala grande requiere índices de búsqueda/vectores,
  vistas materializadas y cálculo asíncrono en vez de ampliar esos límites.
- **P2 precarga multimedia de siguiente generación:** la ventana manual actual
  ya es acotada y cancelable. Media3 ofrece `DefaultPreloadManager`; su adopción
  queda como mejora medible, no como cambio a ciegas, y debe compararse mediante
  tiempo a primer frame, rebuffering, bytes descartados y consumo de batería.
- **P2 mantenibilidad:** lint tarda cerca de doce minutos y el release incremental catorce minutos en esta estación; el módulo Android monolítico requiere modularización gradual y presupuestos de tiempo CI.

Ninguno de estos cambios fue desplegado automáticamente. La evidencia de esta actualización demuestra código/build local; no demuestra comportamiento del servidor remoto ni de todos los fabricantes Android.

La compilación release de humo validada corresponde a:

- `applicationId`: `conectate.com`
- `versionCode`: `66`
- `versionName`: `3.3.4`
- `compileSdk` / `targetSdk`: 36
- Android Gradle Plugin: 8.13.2
- Gradle wrapper: 8.13
- Kotlin / KSP: 2.2.10 / 2.2.10-2.0.2
- Media3: 1.11.0
- Compose BOM: 2026.06.00
- Room / Hilt: 2.8.4 / 2.57.2
- AAB aislado: `build/tmp/novvin-release-audit-20260826/artifacts/NOVVIN-3.3.4-66-AUDIT-NOT-FOR-PLAY.aab`
- tamaño: `123,867,780` bytes (118.13 MiB)
- descarga comprimida máxima estimada por `bundletool`: `62,077,338` bytes (59.20 MiB)
- SHA-256: `32A781B610D61F088F88C382F117D5F0AA5D3AD236086E305B5A7AC0A6503432`
- firma: válida con certificado efímero de smoke; **no compatible con la clave de carga de Play Store**

La puerta release está endurecida y exige firma. Los AAB de humo se mueven fuera de `app/build/outputs/bundle/release/` con el sufijo `NOT-FOR-PLAY`; no son candidatos de producción. La carpeta publicable quedó sin AAB para impedir una subida accidental. El modo explícito `NOVVIN_AUDIT_BUILD=true` también dejó `uploadCrashlyticsMappingFileRelease` en `SKIPPED`, de modo que una compilación local con clave efímera no contamina Crashlytics con un mapa no publicable.

## Cobertura y método

La auditoría cubrió Android (690 archivos de producción, 107 archivos de pruebas unitarias y
13 suites instrumentadas), API Ubuntu (56 fuentes/58 archivos de prueba), web
(119 fuentes), realtime, signaling, IA, SQL, Nginx, Docker, scripts de
operación, permisos Android, privacidad/backup, dependencias, firma, tamaño y
compatibilidad de páginas de 16 KiB.

Se combinaron revisión de flujos extremo a extremo, búsqueda de código muerto e
implementaciones peligrosas, lint/compilación, pruebas automatizadas, auditoría
de dependencias/secretos/runtime/SQL, inspección de multimedia real y métricas
del servidor. No equivale a pentest externo ni a validación física de todos los
fabricantes Android; esos límites aparecen explícitamente en el cierre.

## Hallazgos por prioridad

### P0 — crítico

#### P0.1 Video de producción imposible de transmitir de forma fluida

Se inspeccionó el video activo “Viaje” en el servidor:

- 3840 × 2160;
- H.264;
- aproximadamente 53.45 fps;
- 45,981,875 bytes para 7.327 segundos;
- bitrate total aproximado: 50.2 Mb/s.

La descarga pública medida del túnel fue aproximadamente 5.02 Mb/s. El archivo exige casi diez veces el caudal disponible, por lo que el búfer, la pausa o el regreso a cero no pueden corregirse únicamente aumentando timeouts del reproductor.

Corrección implementada:

- Android fuerza edición/transcodificación cuando el archivo supera 4.5 Mb/s, 1080p, 31 fps, 45 segundos o 32 MiB;
- el repositorio vuelve a validar antes de subir, incluso si se omite la interfaz;
- los perfiles de exportación quedaron entre 1.8 y 3.6 Mb/s según capacidad del dispositivo;
- el backend usa `ffprobe` y rechaza con HTTP 422 videos incompatibles;
- se añadió `npm run normalize:video-delivery` para auditar o normalizar archivos históricos de forma atómica.

Estado actualizado 2026-09-01: desplegado. El inventario autoritativo contiene dos
videos y ambos cumplen la política. “Viaje” quedó en 1080 × 1920, 30 fps y
aproximadamente 2.96 Mb/s; el segundo archivo mide aproximadamente 1.24 Mb/s. El
dry-run reportó `requiresNormalization=0`, `missing=0` y `failed=0`.

#### P0.2 AAB local no publicable por falta de firma

Antes del endurecimiento, `bundleRelease` podía terminar correctamente y dejar un AAB local sin firma. No se generó una clave nueva de producción porque no sería compatible con la clave de carga registrada en Play Console.

Corrección adicional: todo build release ahora falla antes de compilar si no existe una configuración de firma, CI usa una clave efímera únicamente para smoke testing y el workflow manual de producción exige la clave original y fija su huella SHA-256. El bloqueador operativo sigue pendiente hasta configurar esos secretos.

#### P0.3 Requisito inminente de Google Play: Android 16/API 36

La auditoría detectó que NOVVIN seguía en `targetSdk 35`. Google Play exige API 36 para actualizaciones desde el 31 de agosto de 2026, por lo que la siguiente versión habría quedado bloqueada pocos días después de esta auditoría.

Corrección implementada:

- `compileSdk` y `targetSdk` migrados a 36;
- Android Gradle Plugin actualizado a 8.13.2;
- Gradle wrapper actualizado a 8.13;
- reemplazo de constructores `Locale` deprecados encontrados durante la migración;
- revisión estática de edge-to-edge, gesto atrás, redimensionamiento y orientación;
- matriz de pruebas físicas Android 16 añadida al handoff.

Estado: compilación debug y release API 36 aprobadas. Pendiente instalar desde una pista interna y ejecutar la matriz en teléfono Android 16 y pantalla grande; una compilación verde no demuestra por sí sola el comportamiento visual ni los servicios en segundo plano.

#### P0.4 RPC `SECURITY DEFINER` heredadas podían omitir privacidad y alterar contadores

La auditoría correlacionada del manifiesto SQL encontró que las búsquedas por
hashtag y la búsqueda global se ejecutaban como propietario sin reproducir las
reglas RLS de publicaciones privadas/solo seguidores. También seguían
ejecutables RPC antiguas de contadores sociales que confiaban en el UUID del
cliente. Esto permitía fuga de contenido no público o manipulación de métricas
si el backend Supabase legado continuaba accesible.

Corrección implementada:

- migración aditiva `20260824_security_definer_surface_hardening.sql`;
- identidad enlazada a `auth.uid()`/`auth.role()`;
- límites/offsets acotados y filtros de visibilidad/suspensión explícitos;
- contadores de seguidores backend-only y contador de publicaciones limitado al propio usuario;
- `leave_chat` exige pertenencia real al chat;
- funciones trigger dejan de exponerse como RPC públicas;
- auto-etiquetado de hashtags opera con `search_path` fijo y no infla contadores al editar;
- el esquema canónico de video usa nombres snake_case coherentes con Android/API.

Estado: corregido en código y aprobado por auditoría estática; la migración aún
debe aplicarse en el entorno legado antes de considerar cerrada esa superficie.

### P1 — alto

#### P1.1 El primer video podía reiniciarse al cambiar estadísticas

El feed reconstruía/reemplazaba el playlist por cambios que no alteraban la identidad del medio, como el incremento de visualizaciones. Eso explica el patrón “reproduce un segundo, vuelve a cero y queda pausado”.

Corrección implementada:

- identidad y cache key estables por video;
- reconciliación del playlist por identidad: append en sitio para páginas nuevas y reemplazo solo cuando cambia el orden o la URL real del medio;
- registrar una vista ya no fuerza una recarga del video;
- precarga limitada al siguiente video, con retardo de un segundo, en el feed principal;
- perfil conserva precarga acotada de anterior/siguiente.
- una única solicitud autoritativa por sesión/cuenta evita que una respuesta tardía se superponga al primer feed;
- volver a Videos, refrescar o tocar de nuevo la pestaña crea una sesión nueva con semilla distinta;
- el servidor conserva ranking por afinidad y añade exploración/variación por sesión; el cliente no vuelve a ordenar arbitrariamente esa respuesta;
- la paginación cancela trabajos obsoletos y nunca mezcla páginas de sesiones distintas;
- la consulta de seguidos se redujo de una llamada por creador a una llamada por lote;
- tarjetas sociales/personas recomendadas esperan a que termine la primera carga de video;
- altas y bajas de videos se aplican por realtime sin reconstruir el playlist completo.

Estado: corregido y cubierto por la puerta Android; falta prueba física del release firmado.

#### P1.2 Admisión de multimedia sin defensa extremo a extremo

Antes, un cliente antiguo o un flujo que evitara el editor podía subir material 4K/alto bitrate. La validación se aplicó tanto en cliente como en servidor y tiene pruebas unitarias.

Estado actualizado 2026-09-01: corregido, desplegado y verificado con el inventario
histórico completo en modo lectura.

#### P1.3 Infraestructura pública frágil

El origen local respondió en 1–2 ms y los siete contenedores estaban sanos en la
lectura remota del 2026-08-26. El acceso público, en cambio, depende de dos
Cloudflare Quick Tunnel con URL temporal y `CF-Cache-Status: DYNAMIC`. La interfaz
del servidor confirma 100 Mb/s full duplex por el switch; el cable Cat 5e no es el
limitante. En una muestra de diez segundos, `rx_errors=0` y `tx_dropped=0`, pero
`rx_dropped` aumentó de 107.587 a 107.589: es una pérdida baja pero real que debe
monitorizarse y no atribuirse automáticamente al cable.

Mejoras implementadas:

- política Media3 única para video, música y cine, con timeouts explícitos,
  `User-Agent` versionado, caché acotada donde corresponde y prohibición de
  redirecciones entre protocolos;
- reintento automático limitado a lecturas idempotentes (`GET`/`HEAD`/`OPTIONS`); los `PUT` de storage no se repiten silenciosamente;
- Nginx con keepalive de socket, streaming sin buffering y sin archivo temporal;
- soporte y pruebas de `Range`/HTTP 206, ETag, `Accept-Ranges` y `Server-Timing`;
- configuración Nginx validada con `nginx -t`.

Pendiente de infraestructura:

- reemplazar el switch Fast Ethernet por uno gigabit;
- migrar de Quick Tunnel a túnel nombrado/dominio estable;
- configurar caché/CDN para objetos multimedia inmutables.

Mitigación operativa desplegada el 2026-08-31 mientras se consigue el dominio
estable:

- el vigilante de túnel publica en el TXT de DuckDNS un envelope `novvin1`
  firmado con ECDSA P-256 y vigencia de 24 horas; la privada queda sólo en
  Ubuntu con modo `0600`;
- Android y web comparten la clave pública versionada y verifican firma,
  expiración, URL canónica y capacidades de `/ready` antes de adoptar una
  rotación;
- Gradle y el prebuild web rechazan un origen efímero que no tenga recuperación
  firmada;
- `verify:social-read-paths` prueba sin escrituras Social, Videos, perfiles,
  tres imágenes y dos rangos consecutivos de video después de cada despliegue.

La mitigación evita que una rotación deje instalaciones nuevas apuntando a un
host muerto, pero no convierte Quick Tunnel en infraestructura con SLA. El
túnel nombrado y el dominio estable continúan siendo el cierre obligatorio de
este P1.

Seguimiento operativo del 2026-09-01:

- se reprodujo una intermitencia real: el origen local respondía, pero el
  Quick Tunnel agotaba tiempo desde el propio host y los logs mostraban pérdida
  QUIC/UDP e intentos IPv6 sin ruta;
- el vigilante podía alternar dos hostnames cada ciclo; cada cambio recreaba
  API/realtime/signaling y Nginx conservaba la IP antigua, produciendo `502`
  aunque el túnel y el proceso Node estuvieran sanos;
- la corrección conserva el endpoint mientras su conector propietario viva,
  usa sondeo múltiple para candidatos, crea el respaldo con HTTP/2/IPv4 y
  mantiene un único bloqueo durante reconciliación y rollback;
- tanto la rotación como `health-watchdog.sh` reinician `edge` después de
  `docker compose up`, eliminando la resolución DNS obsoleta de upstream;
- el verificador ahora identifica la etapa exacta de un fallo (`health`, feed,
  imagen optimizada o rango de video) sin imprimir URL privada, JWT ni PII.

#### P1.4 Medición de tamaño incorrecta

El chequeo anterior comparaba el AAB bruto —que contiene todas las ABI y metadatos de ofuscación— con un presupuesto de descarga. La medición oficial con `bundletool get-size total` calcula un máximo de 88,892,010 bytes (84.77 MiB) para una configuración de dispositivo; el ARM64 más pesado queda aproximadamente en 87.02 MB decimales. El AAB bruto continúa midiendo 170.35 MB, pero ese archivo completo no se entrega a un teléfono.

Estado: herramienta corregida. El gate ahora separa tamaño de subida, descarga comprimida máxima por dispositivo, DEX comprimido y nativos comprimidos. La descarga queda bajo el presupuesto interno de 100 MiB. Continúa siendo conveniente converger en un solo motor WebRTC, evaluar ML Kit descargable/dinámico y mover recursos LIVE pesados a entrega remota; no se excluyeron ABI ni funciones para reducir artificialmente el archivo.

Optimización aplicada: el OCR latino de detección de marcas de agua pasó de la variante ML Kit empaquetada a la variante oficial distribuida por Google Play Services, solicitada durante la instalación con `com.google.mlkit.vision.DEPENDENCIES=ocr`. La misma API permanece en uso. Resultado medido:

- AAB: 170,420,365 → 152,104,021 bytes (−10.75 %);
- descarga máxima por dispositivo: 88,892,010 → 83,252,325 bytes (−6.35 %);
- mayor porción nativa comprimida: 23,333,059 → 18,862,381 bytes (−19.16 %).

#### P1.5 Compatibilidad de páginas de memoria de 16 KiB no estaba verificada

NOVVIN incluye WebRTC, DataStore y Xeno como bibliotecas nativas. Una compilación exitosa no demuestra que su empaquetado ni sus segmentos ELF funcionen en dispositivos de 16 KiB.

Corrección implementada:

- el AAB API 36 declara `PAGE_ALIGNMENT_16K`;
- las ocho bibliotecas de 64 bits de `arm64-v8a` y `x86_64` tienen alineación mínima ELF de 16,384 bytes;
- un APK universal generado desde el AAB pasó `zipalign -c -P 16` y verificación de firma v2/v3;
- `verify_android_page_size.py` quedó integrado en los gates locales, CI smoke y workflow protegido de producción;
- se añadieron pruebas unitarias del parser para impedir regresiones.

Estado: corregido y automatizado. Continúa pendiente una prueba funcional en emulador/dispositivo configurado con páginas de 16 KiB.

#### P1.6 Escrituras multimedia podían quedar en estado ambiguo

El cliente permitía reintento de conexión de OkHttp durante un `PUT` grande. Si
el servidor confirmaba el objeto pero la respuesta se perdía, un reintento
podía repetir bytes o devolver un conflicto engañoso. En el backend, una falla
al limpiar variantes antiguas después del commit podía convertir una subida ya
exitosa en HTTP 500.

Corrección implementada:

- `retryOnConnectionFailure(false)` en uploads mutables;
- reintentos automáticos únicamente en lecturas idempotentes;
- rollback de objeto/metadata si el commit principal falla;
- limpieza posterior de variantes como best-effort con warning observable;
- pruebas de storage, ownership, canonicalización, rangos y rollback aprobadas.

Estado: corregido en código; pendiente desplegar API y probar pérdida de red en
un Android físico durante upload.

### P2 — medio

#### P2.1 CI obsoleto y engañoso

Había dos workflows duplicados/incorrectos que invocaban tareas inexistentes (`detekt`, `ktlint`, `spotless`, `jacoco`, `sonarqube`, entre otras). Se retiraron y el workflow Android vigente ahora valida backend, web, realtime, signaling e IA, incluyendo pruebas y auditorías npm.

Además, el gate PowerShell no comprobaba `$LASTEXITCODE`: una falla real de
Gradle podía continuar hasta un último comando exitoso y devolver verde. Ahora
cada proceso nativo falla cerrado. Caché/temporales se alojan en el disco del
workspace para no confundir falta de espacio de C: con defectos de aplicación.
El wrapper incluye checksum de distribución, binario oficial de Gradle 8.13 y
validación en los dos jobs Android y en el workflow protegido de producción.

Estado: corregido.

#### P2.2 Objetos de video posiblemente huérfanos

Storage contiene 52 objetos de video por aproximadamente 629 MB, mientras la tabla activa referencia 2 por aproximadamente 46 MB. Hay 50 objetos/583 MB potencialmente huérfanos. No se eliminaron porque primero debe comprobarse si los referencian Momentos, mensajes, borradores u otras tablas.

Se añadió `npm run audit:storage-references`: usa un snapshot PostgreSQL de solo
lectura, descubre referencias en columnas multimedia/texto/JSON/UUID, verifica
metadata contra el filesystem y protege uploads de los últimos siete días.
También detecta referencias sin objeto, archivos referenciados ausentes,
archivos físicos sin metadata, diferencias de tamaño, symlinks y rutas fuera
del volumen. No dispone de modo de borrado y rechaza flags destructivos.

Estado: auditor implementado, incluido en CI y probado extremo a extremo con
base de datos y filesystem simulados. La eliminación continúa deliberadamente
bloqueada: falta desplegarlo y obtener dos informes consecutivos del servidor,
separados por al menos el periodo de gracia, antes de reclasificar esos 50
objetos. Cada eliminación futura también exige backup verificable y revisión
humana de la lista exacta.

#### P2.3 Métricas de red acumuladas

La interfaz registró 76,972 paquetes RX descartados de 5.27 millones, aproximadamente 1.46 % acumulado, sin errores físicos reportados por `ethtool`. Debe medirse el incremento por hora después de sustituir el switch; el contador acumulado por sí solo no prueba un daño actual.

Se añadió `audit-mini-pc-health.sh`, instalado por el despliegue oficial. Es de
solo lectura y toma dos snapshots durante 1–60 segundos para calcular deltas de
pérdida/errores/caudal de red, ocupación y cola de cada disco, espacio, memoria,
carga, salud HTTP y contenedores. Admite una prueba HTTPS Range de 1 MiB sin
registrar la URL. También se incorporaron alertas Prometheus sostenidas para
pérdida de paquetes (0.1/1 %), errores de interfaz, enlace menor a gigabit y
saturación de disco (80/95 %).

Estado: instrumentación, tests y runbook completados; falta desplegar y tomar
una línea base del servidor antes y después de reemplazar el switch. Hasta esa
medición no se atribuye el problema a daño físico.

#### P2.4 Dependencias Android envejecidas

La pila crítica de reproducción y exportación se modernizó de forma coordinada:

- Media3 1.5.1 → 1.11.0;
- Kotlin 1.9.22 → 2.2.10 y KSP 2.2.10-2.0.2;
- Room 2.6.1 → 2.8.4;
- Hilt 2.51 → 2.57.2;
- Compose BOM 2023.10.01 → 2026.06.00, con el plugin oficial de Compose para Kotlin 2.

Los exportadores ya no usan constructores ni efectos de velocidad deprecados de
Media3. Declaran explícitamente secuencias de audio/video, audio independiente y
velocidad sincronizada para ambas pistas. Esto reduce incompatibilidades de
edición que podían producir imagen negra, pérdida de audio o desfase. Un guard
automatizado impide volver a mezclar versiones de Media3 o reintroducir esas APIs.
Todos los reproductores remotos relevantes conservan la política común de red y
caché; la música comunitaria sigue siendo streaming temporal, sin servicio de
descarga ni catálogo offline.

No se forzó Compose 1.12/AGP 9 ni Ktor 3 en este release: Compose 1.12 exige
compileSdk 37/AGP 9.1.2, y Ktor está acoplado al cliente Supabase legado. Esas son
migraciones mayores separadas que requieren actualizar API, autenticación y la
matriz física, no simples cambios de número.

Estado: modernización crítica completada y validada en debug/release; continúan
pendientes las pruebas físicas de codecs, exportación y reproducción y la futura
retirada del cliente Supabase/Ktor legado.

#### P2.5 Minificación conservadora

Las reglas R8 conservaban paquetes completos de presentación, repositorios,
Compose, Media3, OkHttp, Ktor y Supabase aunque esas bibliotecas ya incluyen
reglas consumidoras. Se retiraron los `keep` globales de dependencias y código
NOVVIN, manteniendo las protecciones concretas de JNI/WebRTC, componentes de
manifiesto, WorkManager, Firebase, Room y Hilt.

Se añadió `verify_r8_release.py`, ejecutado por los gates locales, CI smoke y el
workflow firmado de producción. El verificador inspecciona el mapping real y
falla si falta o se renombra un componente NOVVIN del manifiesto, un serializer
crítico, Room/JNI/WebRTC o si reaparece un `keep` global. El release minificado
conservó los 16 componentes del manifiesto y todas las entradas críticas.

Resultado medido frente al smoke anterior: AAB 145.33 → 117.33 MiB, descarga
máxima 79.20 → 58.85 MiB y DEX 17.89 → 9.41 MiB. El payload nativo permaneció
en 17.99 MiB. Estado: corregido en build y verificación estructural; falta el
smoke funcional del AAB firmado con la clave original en la pista interna.

#### P2.6 Unidad C: de la estación de trabajo sin espacio

Durante la auditoría, C: llegó a 0 bytes libres por temporales locales de
Gradle/IA. Este dato corresponde a la laptop Windows, **no** a los discos del
servidor Ubuntu. Provocó un fallo `ENOSPC` real y demostró el antiguo verde
falso del script PowerShell.

Corrección de proyecto: el gate y las evaluaciones se ejecutan con caché y
temporales bajo D:. Pendiente operativo: limpiar C: desde Windows; no se
eliminaron carpetas ajenas automáticamente.

#### P2.7 APK universal fuera del presupuesto de descarga

El APK universal de auditoría mide 216,284,770 bytes (206.26 MiB) e incluye
todas las ABI; por ello supera el presupuesto de 100 MiB y 30 MiB de nativos
definido para una descarga por dispositivo. El AAB que se entrega mediante Play
sí cumple: `bundletool` estima un máximo de 59.28 MiB y 17.99 MiB nativos para
una configuración real. No se elevó el presupuesto para ocultar la diferencia.

Estado: no bloquea la publicación por AAB. Si NOVVIN distribuye APK fuera de
Play, debe generar y firmar APK separados por ABI y probar cada variante; el APK
universal queda limitado a auditoría/sideload interno.

### P3 — bajo

#### P3.1 Lockfile faltante en IA

El servicio `ai-chatbot` no tenía lockfile, de modo que una auditoría npm no podía
ejecutarse de forma reproducible y fallaba cerrado con `ENOLOCK`. Se añadió
`infrastructure/local-fallback/ai-chatbot/package-lock.json`; la auditoría actual
de sus dependencias es reproducible y reporta cero vulnerabilidades.

#### P3.2 Chunk vendor vacío en web

La configuración de Vite generaba un chunk vendor vacío. Se ajustó la segmentación
del bundle; el build actual termina sin ese warning y el JS principal queda en
96.45 kB (27.36 kB gzip). Es una mejora de carga inicial y mantenimiento, no un
cambio funcional.

Android Lint reporta 188 advertencias, sin errores. Las 21 advertencias que
señalaban APIs obsoletas, orden de modificadores, recursos/configuración y
offsets ineficientes fueron corregidas. La deuda residual está acotada a:

- 174 sugerencias mecánicas `UseKtx`, sin impacto funcional;
- 12 recursos marcados como no usados, varios reservados por contratos de
  backend/pruebas o por pantallas y recursos nuevos aún no rastreados por lint;
- 2 avisos `IconXmlAndPng` del launcher adaptativo/legado, conservados
  intencionalmente para compatibilidad entre versiones de Android.

Una recompilación completa y forzada de Kotlin terminó con **0 advertencias del
compilador**. Se migraron las APIs de Compose/Android obsoletas detectadas,
incluyendo lifecycle, indicadores de progreso, pestañas, portapapeles, enlaces,
iconos RTL, gestos con centroide y callbacks de memoria. Las dos APIs antiguas
de ventana que aún necesita la compatibilidad con Android 7–10 quedaron
encapsuladas por nivel de API y no son la ruta utilizada en Android moderno.

## Salud del servidor verificada

- Comprobación remota de solo lectura del 2026-08-26: API, edge, realtime,
  signaling, IA, base de datos y coturn saludables;
- `docker` y `conectate-stack.service`: activos;
- `novvin-cloudflared.service`: inactivo; permanecen dos procesos manuales de
  Quick Tunnel, por lo que el origen público sigue siendo transitorio;
- `signaling-redis`: no está desplegado en la instalación observada;
- reinicios de contenedores: 0;
- OOM: no detectado;
- memoria disponible: aproximadamente 13 GiB;
- disco raíz: aproximadamente 168 GB libres;
- multimedia: aproximadamente 861 GB libres;
- backup: aproximadamente 427 GB libres;
- PostgreSQL: contenedor saludable; el tamaño aproximado de 48 MB y las 7
  conexiones pertenecen a la inspección de referencia del 2026-08-25 y no se
  renovaron en esta lectura;
- logs API de 24 horas: la ausencia de coincidencias fatales, uncaught, timeout u
  OOM corresponde a la inspección de referencia del 2026-08-25.

La lectura de bloques identificó `/dev/sdb1` como el disco externo de backup de
aproximadamente 500 GB y `/dev/sdc1` como el volumen multimedia de aproximadamente
1 TB. SMART no se ejecutó porque el servidor solicita contraseña sudo. No se detectó
que CPU, RAM o discos sean el cuello de botella actual de reproducción.

## Cambios técnicos principales

- política común de entrega para videos y pruebas unitarias;
- defensa de bitrate dentro de la ruta de upload y del remux sin pérdida;
- exportaciones adaptativas a 720p/1080p y 1.8–3.6 Mb/s;
- validación real con `ffprobe` en el backend;
- normalizador seguro y reversible para videos históricos;
- feed con identidad estable, menor churn y precarga acotada;
- sesiones de feed por cuenta, ranking con exploración, paginación cancelable y realtime create/delete;
- snapshot estable durante toda la paginación y consultas dirigidas a los
  candidatos/interacciones visibles en Video y Social;
- OkHttp/Media3 con política compartida, Media3 1.11.0, timeouts explícitos,
  pool HTTP/2, caché de reproducción acotada y reintentos solo para lecturas seguras;
- escritura de storage sin repetición automática, commit/rollback atómico y limpieza posterior best-effort;
- MP4 `faststart`, observabilidad de solicitudes y consultas de feed acotadas/indexadas;
- Nginx afinado para range streaming;
- superficie SQL privilegiada endurecida y esquema de video normalizado;
- catálogo cine limitado a dominio público/CC0 y HTTPS directo, sin WebView/YouTube oculto, torrents ni debrid;
- CI consolidado, fail-closed y con wrapper validado;
- catálogo TOML y documentación de versión actualizados.
- parser GIF puro y acotado, sin `android.graphics.Movie`, con pruebas de
  cabeceras válidas, truncadas y límites defensivos;
- callbacks de presión de memoria compatibles con Android moderno, sin liberar
  recursos de video mientras la aplicación permanece activa;
- migración de Compose/Material 3 a APIs vigentes para lifecycle, progreso,
  pestañas, portapapeles, enlaces, RTL y gestos focales;
- guardas automatizadas de mantenimiento para impedir la reintroducción de
  APIs de memoria, GIF, Room, Compose y Material 3 ya retiradas.
- política unificada de logs de producción sin mensajes crudos, y vistas
  previas de enlaces con allowlist HTTPS, redirecciones y tamaño acotados.
- descarga remota del render de música con DNS público validado y conexión
  HTTPS fijada para impedir rebinding/SSRF.

## Refuerzo de Mensajes y perfiles — 2026-09-01

La auditoría de conectividad encontró que la pantalla de Mensajes podía iniciar
la misma sincronización desde dos colecciones del inbox, el `init`, `ON_RESUME`
y un polling cada 12 segundos. Las solicitudes podían solaparse y amplificar
latencia de API, perfiles y multimedia. Se añadió una única sincronización
account-scoped con exclusión mutua, supresión de duplicados, polling de respaldo
de 60 segundos y refresco selectivo de grupos/comunidades.

El inbox, historial y perfiles ahora tienen plazos totales de 10/12/10 segundos,
preservan cancelación por cambio de pantalla/cuenta y nunca sustituyen Room por
un loader remoto. La bandeja y la conversación vacías muestran error recuperable
con `Reintentar`. Además, los perfiles remotos ajenos se persisten en Room y una
respuesta remota vacía ya no anula una identidad cacheada.

Se incorporó `verify:messaging-read-paths` como módulo separado y como puerta de
despliegue. La ejecución pública autenticada de esta corrección validó 7 chats,
4 grupos, 3 comunidades, un historial de 78 mensajes, perfil actual, perfil del
interlocutor y dos avatares WebP. Todas las lecturas quedaron entre 388 y 823 ms,
sin exponer JWT ni PII.

## Administración empresarial y mínimo privilegio — 2026-09-01

La auditoría encontró que los niveles `view`, `write` y `full` estaban definidos
en el panel principal, pero música, PlayMatch, compatibilidad PostgREST, pagos y
salud interna usaban comprobaciones booleanas distintas. En particular, un actor
de solo lectura podía heredar mutaciones en módulos que sólo preguntaban si era
“admin”.

Se creó una política central de capacidades con denegación por defecto. `view`
queda como auditor, `write` como moderador, `full` administra catálogos, finanzas y
sistema, y únicamente `owner` delega acceso o borra cuentas. La eliminación vuelve
a comprobar propiedad dentro de la función transaccional. El contrato nominal se
expone a Android y web sin retirar todavía los booleanos de compatibilidad.

Las credenciales humanas, el token operativo de sincronización y SSH/Tailscale
quedan documentados como planos separados. La matriz canónica, altas, revisiones y
bajas están en `docs/empresa/06-roles-y-capacidades-administrativas.md`.

Estado: desplegado el 2026-09-01 después de backup dirigido. API aprobó 100/101
pruebas Node, con una prueba funcional Linux omitida en Windows y aprobada en el
servidor; web aprobó lint, build y 81/81 pruebas; Android aprobó
`testDebugUnitTest` y `compileDebugKotlin`. El verificador público autenticado
confirmó tres contratos administrativos y nueve endpoints, incluida salud interna.
Después del despliegue también aprobaron Social/Videos —37 posts, 12 videos, tres
WebP y dos rangos HTTP 206— y Mensajes —8 chats, 7 grupos, 3 comunidades, 184
mensajes y dos avatares WebP—. Un primer fetch de variante agotó timeout y la
repetición completa pasó en 426 ms; se conserva como evidencia de variabilidad del
Quick Tunnel, no como garantía SLA.

Para que esa variabilidad no vuelva a quedar oculta detrás de un `/ready` local
verde, se instaló `conectate-public-read-paths.timer` en el systemd del usuario
operativo. Cada diez minutos ejecuta los canarios autenticados reales de
Social/Videos y Mensajes por Cloudflare, guarda estado sin PII y sólo marca fallo
después de tres ejecuciones consecutivas degradadas. No reinicia contenedores ni
túneles. Durante la validación se demostró además que ejecutar el canario dentro
del bridge Docker producía timeouts de hairpin a Cloudflare mientras el host
obtenía HTTP 200. El canario ahora emite el token dentro del API, pero realiza
HTTPS desde un contenedor efímero con red del host, conserva evidencia por módulo
sin PII y registra duración. La ejecución corregida terminó `success`, con ambas
sondas en `ok` y contador cero.

La evidencia gestionada de las 18:33 UTC midió Social/Videos en 7.833 s y
Mensajes en 6.453 s de recorrido completo. Individualmente, feeds y perfiles
respondieron entre 0.47 y 0.57 s, tres WebP entre 0.42 y 0.74 s y dos rangos
consecutivos de 1 MiB del MP4 en 0.456 y 0.218 s con HTTP 206. Mensajes confirmó
8 chats, 7 grupos, 3 comunidades, historial de 184 mensajes y dos avatares WebP;
ninguna solicitud individual superó su presupuesto.

### Actualización WebRTC web — 2026-09-01

Se cerraron tres P1 heredados de llamadas, salas de voz y LIVE web. El nuevo
`WebRtcPeerSession` separa la máquina de estado SDP/ICE de Socket.IO y aplica
negociación perfecta: rollback para el peer `polite`, descarte de oferta y de sus
candidatos para el `impolite`, y serialización de cambios. `onnegotiationneeded`
propaga cámara/pantalla añadidas después de conectar. `failed` inicia ICE restart
y `disconnected` espera dos segundos antes de hacerlo; hay tres intentos máximos
con backoff y cancelación al recuperar conexión. Finalizar pantalla restaura el
track de cámara tanto desde la UI como desde el control nativo del navegador.

La política común quedó integrada en `WebCallManager` y en `WebRTCManager`, que
alimenta Voz y LIVE. Pasaron 87/87 pruebas web, lint y build de producción. Seis
pruebas nuevas cubren oferta inicial, rollback, `ignoreOffer`, candidatos, ICE
restart acotado e integración con signaling. Sigue siendo obligatoria la matriz
física navegador ↔ Android y redes distintas antes de certificar WebRTC público.

### Actualización de migraciones offline — 2026-09-01

El normalizador Room 54 dejó de depender únicamente de revisión manual. Un
contrato JVM incluido en `qualityGateDebug` compara las 17 tablas y sus índices
contra el JSON que Room exporta como esquema autoritativo. La ejecución dirigida
terminó sin fallos (`1/1`, 105 ms). Se mantienen cuatro pruebas instrumentadas
que migran bases históricas y validan preservación de datos; esas pruebas requieren
emulador/dispositivo y siguen siendo obligatorias antes del release. La puerta
rápida evita que Social, perfiles o Mensajes pierdan su fuente offline por una
columna olvidada en una futura actualización.

### Actualización de seguridad FileProvider — 2026-09-01

Se eliminó la exposición de las raíces completas de caché y almacenamiento
externo. Cámara, recorte, retos y Momentos crean ahora sus temporales mediante
`ShareableMediaFileStore`, únicamente dentro de tres subdirectorios nominales.
`FileProviderPathContractTest` analiza el XML en la puerta JVM y rechaza
`path="."` o cualquier conjunto de rutas distinto al autorizado. La prueba
dirigida y `:app:compileDebugKotlin` aprobaron; queda la comprobación funcional
en un teléfono real para acreditar la interoperabilidad con su aplicación de
cámara.

### Actualización del estado distribuido de LIVE — 2026-09-01

La revisión del código y las pruebas corrigió un diagnóstico documental ya
obsoleto: el estado avanzado del directo no depende sólo de memoria local.
`distributed-live-state.js` persiste y restaura fuente, pausa, audio/video,
reacciones y deduplicación, moderación, invitados, batalla, puntajes,
temporizadores y coordinación del host. Las 27 pruebas de signaling terminaron
con 26 aprobadas y una integración Redis omitida por no tener URL local; el
mismo contrato de lease se ejecutó después dentro del contenedor contra Redis
de producción y aprobó con limpieza del prefijo temporal. El pendiente P1 es el
drill multi-réplica de caída y rehidratación, no la ausencia de persistencia.

### Runtime API y propiedad de storage desplegados — 2026-09-02

La comprobación directa encontró que Node ya corría como UID/GID 10001, pero
los buckets heredados (`avatars`, `videos`, `chat-media` y los demás) seguían
como `root:root` modo 0755. Las lecturas públicas funcionaban, mientras que la
primera escritura de un usuario nuevo podía fallar con `EACCES`. El entrypoint
usa ahora el marcador v2, detecta cualquier directorio de primer nivel con
propietario incorrecto, ejecuta una reparación recursiva única y falla el
arranque si la postcondición no se cumple.

Antes del despliegue se generó y verificó el backup
`20260902T014937Z` (PostgreSQL, 2,017,854,930 bytes de storage y datos de IA).
El release dirigido `conectate-api-storage-owner-20260902T015207Z`, huella
`1373df91c79d9463b281b102474eed85e28c4283ad1bd64a43cdc9946a9b4843`,
quedó saludable. Node conserva UID/GID 10001, los 19 directorios de storage
aceptaron una creación/eliminación temporal como ese usuario y no quedó ningún
bucket de primer nivel con otro propietario. `clamscan` 1.4.6 detectó EICAR con
firmas 28110 y las sondas públicas posteriores aprobaron Social/Videos en
11.910 s y Mensajes en 6.388 s. Los PID 1 de realtime, signaling e IA también
se verificaron sin root (UID/GID 1000).

El E2E de registro creó una cuenta ficticia, subió un PNG al bucket `avatars`,
persistió la URL en el perfil y volvió a descargarla como `image/*`; todos los
pasos aprobaron. Su limpieza dejó cero cuentas fixture, cero filas de storage y
cero archivos de avatar temporales. Esto confirma el flujo que antes podía
degradarse a una inicial sin foto por falta de escritura del bucket.

## Evidencia de validación

- Android `:app:qualityGateDebug`: aprobado con API 36, Kotlin 2.2.10, Compose BOM 2026.06.00 y Media3 1.11.0; 395 pruebas en 107 suites, 0 fallos, 0 errores y 0 omitidas;
- Kotlin `:app:compileDebugKotlin --rerun-tasks`: compilación completa aprobada,
  20/20 tareas ejecutadas y 0 advertencias del compilador;
- Android `:app:bundleRelease`: aprobado nuevamente después de la auditoría final, con R8, shrink de recursos, lint vital y firma de auditoría en 14 min 57 s; la clave efímera no es apta para Play;
- Crashlytics: `uploadCrashlyticsMappingFileRelease SKIPPED` bajo `NOVVIN_AUDIT_BUILD=true`;
- firma: AAB verificado con `jarsigner` y APK con `apksigner` v2, un firmante; ambos usan el mismo certificado efímero y están aislados como **NO APTOS PARA PLAY**;
- hash del último AAB: `32A781B610D61F088F88C382F117D5F0AA5D3AD236086E305B5A7AC0A6503432`; certificado efímero `A3CE00DE2EB95E8448938171ECE287F37EFBF2B714CA776D4718A06BD11CE439`;
- presupuesto del AAB: 118.13 MiB bruto, 59.20 MiB de descarga máxima, 9.81 MiB DEX y 17.99 MiB de nativos; todo aprobado;
- el APK universal de 206.26 MiB se registró como riesgo P2.7 y no se presenta como descarga de Play;
- verificación R8: 35,907 clases mapeadas, 16 componentes NOVVIN de manifiesto y entradas críticas de serialización/red/JNI presentes; 0 `keep` globales prohibidos;
- compatibilidad 16 KiB: AAB con `PAGE_ALIGNMENT_16K` y diez ELF de 64 bits aprobados;
- pruebas Python de herramientas release/SQL/R8/host/dependencias y guardas de
  mantenimiento Android: 60 aprobadas;
- Android Lint: 0 errores, 188 advertencias de prioridad baja/mantenimiento
  (174 `UseKtx`, 12 `UnusedResources`, 2 `IconXmlAndPng`);
- backend: los 35 scripts `test:*` aprobados, incluida la auditoría referencial
  de storage con pruebas de referencias conocidas/colgantes, archivos
  huérfanos, rutas anómalas y rechazo de flags destructivos;
- web: lint, build y 81 pruebas aprobadas;
- realtime: check y pruebas aprobadas;
- signaling: check y 27 pruebas aprobados (26 pasaron y una integración Redis fue
  omitida sin `NOVVIN_TEST_REDIS_URL`); startup local validado en modo memoria y
  health expone bus/consistencia sin secretos;
- API: check y 90 pruebas Node aprobadas en una ejecución conjunta, incluidas
  las regresiones de presencia de voz, Redis privado, afinidad, DNS dinámico,
  IP/protocolo de Cloudflare, paginación estable, lecturas dirigidas,
  delegación multimedia interna y hardening de contenedores;
- IA: check, pruebas y evaluación v5 aprobadas; 100 % en los conjuntos de dominio,
  conocimiento, seguridad, desconocidos y cálculo, 1.17 ms de respuesta media,
  p95 de 1.855 ms y p99 de 2.285 ms;
- `npm audit` completo: 0 vulnerabilidades en API, web, realtime, signaling e IA;
- auditoría de secretos: sin coincidencias nuevas;
- auditoría de seguridad runtime: 0 hallazgos;
- auditoría SQL: 0 hallazgos en el estado efectivo del manifiesto; el analizador reconstruye definiciones, sobrecargas, eliminaciones y privilegios finales, y sus regresiones automatizadas aprobaron;
- wrapper Gradle 8.13 oficial: SHA-256 `81A82AAEA5ABCC8FF68B3DFCB58B3C3C429378EFD98E7433460610FECD7AE45F`; distribución fijada por SHA-256;
- Nginx: sintaxis válida;
- Docker Compose: interpolación y estructura válidas con configuración de
  auditoría; las imágenes exactas existen en el registro oficial;
- `git diff --check`: sin errores de whitespace.

### Estabilización de conectividad, identidad y runtime — 2026-09-04

Producción ejecuta `conectate-resource-budgets-20260902T020157Z` con huella
`af355ece55d29c7b3815a19017ea3ede191e3cd674e3ff67dc499463c2009d3e`.
Los ocho contenedores están saludables y `/ready` aprobó por API directa, edge
y Quick Tunnel. El despliegue recreó los servicios con límites de memoria, CPU,
PID y swap; no se reinició el host completo porque no había evidencia de una
falla global del sistema operativo.

El muestreo por recorrido separó la latencia real del tiempo total del monitor:
feeds Social/Videos alrededor de 0.48/0.50 s, perfiles 0.48–0.56 s, imágenes
0.41–0.73 s y rangos de video 206 en 0.21–0.44 s. El primer health público en
frío llegó a 1.18–1.65 s. Persisten dos límites de infraestructura: Quick Tunnel
sin SLA y enlace físico negociado a 100 Mbps.

La auditoría productiva de storage, ejecutada sin mutaciones, contabilizó 866
objetos, 381 referenciados, 485 candidatos huérfanos y 23 referencias históricas
colgantes. Los avatares activos examinados existen y el E2E de registro/subida/
lectura de avatar volvió a aprobar; no se borraron objetos candidatos.

En Android se aisló otra causa de iniciales sin foto: el refresco Supabase del
inbox creaba usuarios mínimos mediante reemplazo y una respuesta parcial podía
borrar la foto/nombre ya cacheados. `InboxIdentityCache` conserva la identidad
completa, precarga de forma acotada hasta 24 fotos estáticas y centraliza sus
claves/variantes de caché. Los avatares de video se excluyen de Coil para no
descargar el MP4 completo; al renderizarse guardan un póster WebP acotado en
`noBackupFilesDir`, disponible cuando Media3 no tiene red. Las fotos estáticas
ya entregadas guardan la misma representación durable, con calentamiento máximo
de tres descargas simultáneas. La reconstrucción de
Room vuelve a exponer `group_image`, y una respuesta parcial ya no elimina la
identidad del grupo. Además, la lectura local del inbox y la búsqueda de un chat
directo conocido ocurren antes de renovar el token. Social y Videos priorizan el
estado offline sobre el spinner cuando no hay datos locales.

La verificación final del 2026-09-04 a las 20:40 -05:00 aprobó 455 pruebas
Android en 124 suites, sin fallos,
y `assembleDebug`; `lintDebug` terminó con 0 errores y 192 advertencias, ninguna
en los módulos nuevos. El APK resultante mide 274,194,560 bytes y tiene SHA-256
`374B6F84EE5F9D5DC1B028DE9EF819D114E83FC3E8690CC36F2A63855B18C2B7`.
Sigue pendiente la prueba en el teléfono físico que presentó el problema.

La revisión adicional separó la hidratación de autores (`SocialAuthorHydrator`)
de la publicación del feed, conservó los videos durante refrescos y acotó el
selector Social a 52 dp. `VideoPlaybackRecoveryRunner` cancela consultas viejas
en ambos visores, preserva posición/caché y respeta la cuenta/selección actual.
`VideoFollowState` evita que consultas antiguas hagan reaparecer el `+` tras
seguir correctamente. El visor individual ya no espera comentarios para
mostrar el video.

Los diagnósticos desplegados también se corrigieron: lectura real de carrier,
compatibilidad con gawk al calcular CPU y tiempos HTTP hasta recibir el cuerpo
completo. El muestreo nuevo confirmó enlace activo 100 Mbps/full duplex,
`/ready` local en 3.3 ms y ocho contenedores saludables. Los rangos públicos de
1 MiB tardaron 1.20–1.31 s completos; los anteriores 0.21–0.44 s medían sólo
cabeceras. Estos resultados no prueban por sí solos la reproducción del teléfono.

## Secuencia obligatoria para cerrar producción

1. Recuperar la clave de carga original y compilar un AAB firmado.
2. Crear backup verificable de base de datos y storage referenciado.
3. Aplicar y verificar `20260824_security_definer_surface_hardening.sql` en el backend Supabase legado si continúa habilitado.
4. API, realtime y Nginx ya están desplegados en el release indicado; para un
   cambio posterior, repetir despliegue dirigido y verificaciones `/ready`.
5. Ejecutar `npm run audit:storage-references`, conservar el JSON y repetirlo
   después del periodo de gracia; no borrar nada entre ambas mediciones.
6. Ejecutar primero `npm run normalize:video-delivery` sin `--apply` y revisar la lista.
7. Ejecutar `npm run normalize:video-delivery -- --apply` para los videos activos incompatibles.
8. Probar el release firmado en al menos dos Android reales, Wi-Fi y datos móviles: primer video, scroll, retorno a la pestaña, upload y eliminación en tiempo real.
9. Sustituir el switch por gigabit y medir de nuevo caudal, TTFB y paquetes descartados.
10. Migrar a túnel nombrado/dominio estable con caché multimedia.

El backend y el runtime sí están desplegados. Hasta completar la prueba física,
el túnel estable, el enlace gigabit y la firma Play, la corrección Android no
debe anunciarse como 100 % confirmada en todos los dispositivos ni como paridad
exacta con WhatsApp/TikTok/Facebook.

## Referencias oficiales consultadas

- Android Gradle Plugin 8.13 / API 36: https://developer.android.com/build/releases/agp-8-13-0-release-notes
- Versiones de AndroidX Media3: https://developer.android.com/jetpack/androidx/releases/media3
- Secuencias de Media3 Transformer: https://developer.android.com/reference/androidx/media3/transformer/EditedMediaItemSequence
- Kotlin 2.2.10: https://kotlinlang.org/docs/releases.html
- Versiones de Room: https://developer.android.com/jetpack/androidx/releases/room
- Versiones de Dagger/Hilt: https://github.com/google/dagger/releases
- Compose BOM: https://developer.android.com/develop/ui/compose/bom
- Requisitos de Compose 1.12: https://developer.android.com/blog/posts/what-s-new-in-the-jetpack-compose-august-26-release
- Reproducción progresiva y formatos Media3: https://developer.android.com/media/media3/exoplayer/progressive
- Callbacks modernos de memoria Android: https://developer.android.com/reference/android/content/ComponentCallbacks2.html
- Constructor y downgrade explícito de Room: https://developer.android.com/reference/androidx/room/RoomDatabase.Builder.html
- Procesadores de audio Media3: https://developer.android.com/reference/androidx/media3/common/audio/BaseAudioProcessor
- Portapapeles Compose: https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/Clipboard
- Gestos focales de transformación Compose: https://developer.android.com/reference/kotlin/androidx/compose/foundation/gestures/rememberTransformableState.composable
- Pestañas Material 3: https://developer.android.com/develop/ui/compose/components/tabs
- Enlaces anotados Compose: https://developer.android.com/reference/kotlin/androidx/compose/ui/text/LinkAnnotation
- Escalado multi-nodo y afinidad Socket.IO: https://socket.io/docs/v4/using-multiple-nodes/
- Adaptador Socket.IO Redis Streams: https://github.com/socketio/socket.io-redis-streams-adapter
- Resolución DNS dinámica de upstreams Nginx: https://nginx.org/en/docs/http/ngx_http_upstream_module.html
- IP original y protocolo de Cloudflare: https://developers.cloudflare.com/fundamentals/reference/http-headers/
- Cookies HTTP seguras, `HttpOnly`, `SameSite` y prefijos: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie
- Cookies particionadas CHIPS: https://developer.mozilla.org/en-US/docs/Web/Privacy/Guides/Third-party_cookies/Partitioned_cookies
- Política de nivel de API objetivo de Google Play: https://support.google.com/googleplay/android-developer/answer/11926878
- Checksum oficial de distribuciones Gradle: https://gradle.org/release-checksums/
