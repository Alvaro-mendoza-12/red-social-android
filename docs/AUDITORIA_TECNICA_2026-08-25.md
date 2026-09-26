# Auditoría técnica de NOVVIN — 2026-08-25

**Rama auditada:** `feature/live-gifts`  
**Última actualización de evidencia:** 2026-08-26  
**Método:** revisión de código, pruebas automatizadas, análisis de dependencias,
análisis SQL y de configuración de ejecución, y comprobaciones de solo lectura del
servidor. No se desplegó ni se modificó producción durante esta auditoría.

## Resumen honesto

La rama contiene mejoras locales que corrigen dos clases importantes de fallos: la
recuperación de reproducción de vídeo cuando falla una URL temporal, y la recuperación
de estado de directos después de perder una instancia de señalización. Las pruebas de
código correspondientes aprobaron.

Esto **no equivale a que producción ya esté corregida**. La comprobación remota de
solo lectura del 2026-08-26 confirma que el servidor sigue en
`conectate-mini-pc-20260822T062433Z` (commit `d781c9d16dea`), anterior a estas
mejoras. Los siete contenedores activos —incluido coturn— están saludables, pero
no existe el contenedor `signaling-redis`; la instalación aún no contiene la
topología actual de signaling distribuido. `docker` y `conectate-stack.service`
están activos, mientras `novvin-cloudflared.service` permanece inactivo y siguen
existiendo dos procesos manuales de Cloudflare Quick Tunnel (`127.0.0.1:4174` y
`127.0.0.1:3080`). Es un estado transitorio, no apto para tráfico de producción.

La misma lectura confirmó almacenamiento montado y saludable a nivel operativo:
raíz `/dev/sda2` con 168 GB libres, multimedia `/dev/sdc1` con 861 GB libres y
backup `/dev/sdb1` con 427 GB libres. `sdb` es el disco externo de aproximadamente
500 GB y `sdc` el de aproximadamente 1 TB; no se ejecutó SMART porque el servidor
requiere una contraseña sudo. El endpoint local `/ready` respondió `ok: true` y
publicó las capacidades esperadas, pero esto no sustituye verificar el origen
público estable.

## Evidencia automática aprobada

| Área | Verificación | Resultado |
|---|---|---|
| Android, corrección focalizada | `:app:testDebugUnitTest --tests VideoPlaylistReconciliationTest --tests VideoPlaybackRecoveryPolicyTest` | Aprobada |
| Android, compuerta completa | `tools\quality_gate.ps1 -DebugOnly` / `:app:qualityGateDebug` (compilación, lint y pruebas unitarias; ejecución final 13m02s) | Aprobada; el mismo gate continuó con 60/60 pruebas Python y las auditorías runtime/SQL |
| API local | `npm run check` y `npm test` | Aprobados, 90/90 pruebas |
| Web | lint, 81 pruebas en 15 suites y build de producción | Aprobados; sin warning de chunk vendor vacío; JS principal 96.45 kB (27.36 kB gzip) |
| Señalización | sintaxis y 27 pruebas (una integración Redis omitida sin `NOVVIN_TEST_REDIS_URL`) | Aprobados; 26 pasaron y la integración Redis quedó omitida por falta de URL |
| Realtime gateway | sintaxis y pruebas | Aprobados |
| IA | sintaxis, pruebas, evaluación V5 y auditoría de dependencias | Aprobados; 100 % en dominio, conocimiento, seguridad, desconocidos y cálculo; `npm audit` sin vulnerabilidades |
| Python | suite completa de tooling | Aprobadas, 60/60 pruebas |
| Dependencias Node de producción | `npm audit --omit=dev --audit-level=low` en API, web, signaling, realtime e IA | 0 vulnerabilidades |
| Seguridad SQL/runtime | `security_sql_audit.py` y `runtime_security_audit.py` con umbral HIGH | Aprobados; 0 hallazgos en el estado SQL efectivo y 0 runtime HIGH/CRITICAL |

El AAB release firmado no se generó: la verificación de firma se niega correctamente a
continuar porque este entorno no contiene `keystore.properties` ni la clave de subida de
Play. Es un bloqueo deliberado, no un fallo de la aplicación.

Como verificación técnica separada, el release se ensambló con una clave efímera de
auditoría (14m57s), pasó R8, lint vital, firma estructural, páginas de memoria de 16 KiB
y presupuesto de rendimiento. El AAB se aisló como
`build/tmp/novvin-release-audit-20260826/artifacts/NOVVIN-3.3.4-66-AUDIT-NOT-FOR-PLAY.aab`
(SHA-256 `32A781B610D61F088F88C382F117D5F0AA5D3AD236086E305B5A7AC0A6503432`). Su
certificado no coincide con Play y no debe subirse ni instalarse como actualización de
producción.

La compuerta integral también confirmó la corrección del flujo de música de comunidad:
el test Android del selector pasa y el backend conserva el audio original para el editor,
mientras que el preview corto se usa únicamente en el catálogo. La renderización del
fragmento publicado usa AAC-LC/M4A y ahora repite la fuente cuando el recorte solicitado
supera el final de una pista corta; así una imagen mantiene seis segundos de duración sin
rellenarse con silencio por un `apad` prematuro.

## Hallazgos priorizados

### P0 — No desplegar sin una URL pública estable

**Estado:** pendiente operativo confirmado en servidor; mitigación preventiva añadida al repositorio.

El servidor usa dos procesos manuales `cloudflared tunnel --url ...`, uno hacia
`127.0.0.1:4174` y otro hacia `127.0.0.1:3080`. El servicio supervisado
`novvin-cloudflared.service` respondió `inactive`/`not-found`. Las URL de
`trycloudflare.com` cambian al reiniciar, no tienen SLA y los Quick Tunnels tienen un
límite de solicitudes concurrentes. Esto explica reintentos, URLs de media caducadas y
una parte de la lentitud percibida.

**Corrección local:** el script `scripts/deploy-stack-safely.sh` rechaza ahora una
producción con `PUBLIC_BASE_URL` vacío, HTTP o un dominio temporal, salvo que se indique
explícitamente `ALLOW_EPHEMERAL_PUBLIC_ORIGIN=true` para una prueba controlada.

**Acción necesaria antes de despliegue:** crear un Cloudflare Tunnel administrado con un
dominio HTTPS propio y ejecutar `cloudflared` como servicio supervisado. Publicar API,
edge y signaling bajo ese origen estable; actualizar CORS, discovery y App Links.

El verificador `verify-ubuntu-production.sh` fue endurecido para señalar como fallo
estricto la ausencia del túnel nombrado, un `PUBLIC_BASE_URL` temporal, Redis de
signaling no autenticado/ausente o un `/ready` público caído. La prueba funcional que
incluye crea y limpia fixtures, por lo que se ejecuta sólo durante una ventana de
despliegue autorizada.

### P0 — La instalación de producción no incluye las correcciones actuales

**Estado:** pendiente de despliegue controlado.

El release del servidor inspeccionado es anterior a esta auditoría. Su Compose activo
mostró siete contenedores sanos pero no `signaling-redis`; por ello no puede
certificarse que lleve la implementación exacta actual, aunque `/ready` anuncie
capacidades con esos nombres. Queda pendiente comprobar en el host el artefacto
instalado mediante la huella SHA-256. En particular, no se ha demostrado allí:

- recuperación limitada de errores de transporte en Media3;
- renovación de endpoint cuando rota una URL de backend;
- estado de Live respaldado en Redis, leases de host y terminación idempotente;
- preflight que evita publicar con un origen temporal;
- verificación post-despliegue de la configuración Nginx para Range, streaming y logs seguros.

Se requiere un despliegue con backup, health checks, smoke tests autenticados y rollback
preparado. No se ejecuta automáticamente porque modifica datos y disponibilidad.

### P1 — El marcador de release podía mentir después de un despliegue fallido

**Estado:** corregido localmente y cubierto por regresión.

`deploy-mini-pc-current.ps1` escribía `.conectate-release` antes de construir y
validar los servicios. Un fallo posterior podía dejar `/ready` anunciando un
commit que no estaba activo. El marcador ahora se confirma sólo después de los
health checks y de validar el túnel; el API se recrea para leer el nuevo metadata,
se comprueban el commit y la huella SHA-256 de los artefactos exactos transferidos,
y un trap restaura el marcador anterior si esa última operación falla. Esto evita
que un worktree con cambios sin commit sea presentado falsamente como su `HEAD`.
La prueba de configuración impide volver a mover la confirmación antes de la
validación.

### P1 — Búsqueda de comunidades devolvía un stub en Ubuntu

`CommunityRepository.searchCommunities()` delegaba correctamente al backend local,
pero el cliente Android respondía `Not implemented yet`. Se implementó la llamada al
endpoint de exploración, con consulta limitada, validación de HTTP/JSON y cancelación
cooperativa. También se alineó la eliminación de cuenta propia con el RPC Ubuntu para
evitar enviar esa operación al backend equivocado.

### P1 — Compartir publicaciones fallaba o no tenía efecto en Ubuntu

**Estado:** corregido localmente y cubierto por pruebas API; requiere prueba real
con sesión Ubuntu después del despliegue.

`ShareRepository` sólo desviaba videos al backend local. Para publicaciones,
compartir y descompartir podían terminar en Supabase aun cuando la sesión usaba el
servidor Ubuntu; además, la pantalla activa de ranking mostraba acciones sin
callback. El cliente ahora usa POST/DELETE locales autenticados para publicaciones,
la API elimina únicamente el share del usuario autenticado y el ranking aplica
mutaciones optimistas con rollback ante error. La suite API quedó en 90/90 y la
prueba de contrato comprueba ambos endpoints.

### P1 — Enlace físico del servidor limitado a 100 Mb/s

**Estado:** pendiente de infraestructura.

La interfaz `enp1s0f0` confirma `100Mb/s`, full-duplex y enlace activo. CPU e I/O del
servidor estaban holgados y las pruebas de transferencia desde el servidor fueron
aproximadamente 80.3 Mb/s de descarga y 49.2 Mb/s de subida. En una muestra remota
del 2026-08-26, `rx_errors=0` y `tx_dropped=0`, pero `rx_dropped` pasó de 107.587 a
107.589 en diez segundos; es un descarte real de baja frecuencia que debe vigilarse,
no atribuirse automáticamente al cable. Por tanto, el switch de 100 Mb/s es el límite
físico actual; no el SSD ni la CPU.

**Acción:** sustituir switch y tramos Ethernet por equipos gigabit, confirmar una
negociación `1000Mb/s`, y medir de nuevo. Un cable Cat 5e corto puede soportar gigabit si
está en buen estado, pero el switch de 100 Mb/s nunca podrá negociarlo.

### P1 — Reproducción de vídeo: reinicio/pausa ante refresh y URL rota

**Estado:** corregido localmente; requiere prueba real y despliegue.

La reconciliación de playlist podía reemplazar el MediaItem visible al llegar una página
nueva o al cambiar un endpoint, reiniciando el vídeo a cero. Una URL de túnel rotada podía
dejar el reproductor en estado idle sin recuperación controlada.

**Corrección:**

- cada entrada separa identidad estable de caché y URI de reproducción;
- la paginación agrega items sin sustituir el vídeo visible;
- al reordenar o rotar la URL se conserva el ID y la posición de la pieza visible;
- solo se reintentan fallos temporales de red/HTTP (máximo dos, 350 ms y 1 s);
- después del segundo fallo se redescubre el endpoint y, si no se recupera, se muestra
  una acción explícita de reintento en vez de un bucle infinito;
- la precarga espera un segundo, tiene un presupuesto de bytes y se cancela a los dos
  segundos para no competir indefinidamente con el vídeo visible.
- el visor individual abierto desde una notificación, comentario o enlace profundo usa
  la misma política acotada, conserva la posición al renovar la URL y ofrece reintento
  manual si el error no es transitorio.

El antiguo feed de cuadrícula y su `VideosViewModel` no tenían ninguna ruta activa y
mantenían una caché/ordenamiento paralelos. Se retiraron para que la única entrada del
feed sea `VideoFeedScreen` con `VideoFeedViewModel`; los componentes de comentarios,
editor, reporte y visor individual que sí tienen rutas activas se conservaron.

### P1 — Editor de momentos usaba el preview corto para recortes largos

**Estado:** corregido localmente y cubierto por `MomentMusicPickerPolicyTest` y la suite
API.

El catálogo comunitario genera un preview seguro de 30 segundos para explorar pistas,
pero el botón “Escuchar” dentro de “Elegir fragmento” volvía a la URL del preview. Al
seleccionar una posición posterior, el reproductor quedaba sin datos aunque la pista
original durara varios minutos. Todos los controles de la pista ya seleccionada usan
explícitamente `audioUrl`; el preview queda limitado a la fila del catálogo. El backend
recibe la pista original, renderiza sólo el fragmento solicitado y no depende del codec
del dispositivo para reproducir el momento publicado.

### P1 — Pistas cortas podían producir seis segundos con silencio

**Estado:** corregido localmente y cubierto por `moment-music-renderer.test.js`.

El renderizador recortaba la fuente y `apad` completaba con silencio cuando una pista de
menos de seis segundos, o un recorte cerca del final, no alcanzaba a cubrir todo el
momento. La entrada de FFmpeg ahora se repite de forma acotada (`-stream_loop -1`) y la
salida continúa limitada por `-t`, por lo que la duración publicada sigue siendo exacta
y el audio permanece audible. La prueba física con FFmpeg dentro del contenedor sigue
formando parte del smoke de despliegue.

### P1 — Superficie de reproducción retenida al cambiar de vídeo

**Estado:** corregido localmente y compilado con éxito; requiere verificación visual en
dispositivo real.

El feed utiliza un único ExoPlayer, pero cada página seleccionada crea su propio
`PlayerView`. Al reciclar una página, la vista anterior no se desvinculaba
explícitamente del reproductor. En determinados ciclos de `VerticalPager` podía quedar
una superficie antigua reteniendo un frame, aparentando que el vídeo se superponía o
volvía a cero mientras se adjuntaba la siguiente vista. La vista ahora se libera con
`onRelease`, y el guard de primer frame se reinicia cuando un vídeo deja de estar
seleccionado. Además, el efecto de reproducción ahora espera a que la entrada del
`MediaItem` seleccionado coincida con el índice visible y a que la reconciliación de
playlist haya terminado; así no puede hacer `seekTo` sobre la playlist anterior durante
una renovación. La compilación `:app:compileDebugKotlin` y las pruebas de reconciliación
de playlist/música aprobaron después de ambos cambios.

### P1 — El recomendador admitía candidatos sin vídeo reproducible

**Estado:** corregido localmente; API validada con 90/90 pruebas y chequeo estático.

La clasificación y moderación de un registro no garantizaban que su `video_url`
fuera utilizable. Un valor vacío, HTTP o con credenciales en la consulta podía llegar
al cliente como `null` y producir una página negra, un error de reproducción o un
salto aparentemente aleatorio. La fuente autoritativa ahora exige una URL HTTPS
pública y estable antes de admitir el candidato; si no existe, falla cerrado y deja
que otro vídeo complete la página. La misma URL validada sirve como respaldo del
sonido original. La regresión cubre URL válida, URL firmada/privada y saneamiento de
portada, miniatura, avatar y nombre público.

### P2 — Compuerta de calidad sensible a `java -version` en PowerShell

**Estado:** corregido localmente.

Java escribe su versión en `stderr`; con el modo estricto de PowerShell eso abortaba la
detección antes de ejecutar Gradle. `tools/quality_gate.ps1` convierte esa salida en
texto, selecciona automáticamente un JDK 17–21 disponible y conserva el fallo cerrado
si no existe uno compatible. La compuerta debug completa volvió a aprobar después del
ajuste.

### P1 — Directos no recuperables de forma fiable tras perder una instancia

**Estado:** corregido localmente; requiere prueba multi-réplica antes de escalar.

El estado mutable de Live estaba únicamente en memoria. Ahora se serializa una instantánea
sin tokens ni socket IDs en Redis, se emplean leases de host y bloqueos NX/PX de
terminación, y las operaciones de finalización son idempotentes. El lease es ahora
atómico: una segunda instancia no puede sobrescribir al anfitrión actual con un heartbeat
tardío; solo admite renovar al dueño o relevar el socket local anterior. La configuración mantiene
`SIGNALING_REPLICAS=1` hasta completar una prueba de carga y failover de múltiples
réplicas.

La suite de signaling cubre esta contención con dos stores que comparten Redis simulado.
También existe una prueba de integración que se ejecuta al proporcionar
`NOVVIN_TEST_REDIS_URL`; en este equipo Docker/Redis no está disponible, por lo que esa
prueba quedó omitida deliberadamente y no se usa como evidencia de failover real.

### P1 — Push legacy podía fallar, duplicar envíos y filtrar datos en logs

**Estado:** corregido localmente y cubierto por pruebas estáticas; pendiente de desplegar
la Edge Function y ejecutar un canario FCM real.

La Edge Function heredada referenciaba un cliente Supabase inexistente, llamaba a una IP
privada fija y registraba payloads, tokens y respuestas completas del proveedor. Tampoco
autenticaba el webhook ni acotaba concurrencia o tamaño de entrada.

**Corrección:** ahora falla cerrado si faltan el secreto del webhook o las credenciales de
servicio; solo admite `POST`/evento `INSERT`, compara el secreto en tiempo constante,
limita el cuerpo a 256 KiB, pagina destinatarios, deduplica tokens y envía a FCM con
concurrencia acotada. Los logs contienen únicamente referencias no reversibles y códigos
agregados. Se retiró la copia obsoleta del archivo. La sintaxis se validó con esbuild y el
contrato tiene pruebas; Deno no está instalado en esta estación, por lo que falta la
validación semántica y ejecución en el runtime real de Supabase.

### P1 — Uploads grandes podían reiniciarse o quedar bloqueados al finalizar

**Estado:** corregido localmente y probado en el backend; pendiente de despliegue y
validación en Android real/red móvil.

La app ya transmite los vídeos desde `Uri` en bloques de 128 KiB, sin cargar el archivo
entero en RAM. La app y el
fallback Ubuntu ahora implementan TUS para archivos de al menos 6 MiB: la sesión queda
en PostgreSQL, el offset se comprueba bajo `FOR UPDATE`, cada fragmento está limitado a
6 MiB y la finalización reutiliza la validación existente de firma, tipo, vídeo y límites.
Una respuesta perdida se reconcilia con `HEAD` y no repite ciegamente bytes confirmados.
La finalización está protegida por lock asesor PostgreSQL y `HEAD` recupera sesiones que
quedaron huérfanas en `finalizing` tras una caída de la API. La prueba de storage ejercita
`POST → HEAD → PATCH → HEAD`, simula esa caída y comprueba los bytes finales.

Android conserva de forma durable, antes del primer `PATCH`, la URL opaca de la sesión
TUS bajo una clave SHA-256 ligada a cuenta, endpoint, objeto y metadatos. En un reintento
posterior —también después de recrear el proceso— valida origen, ruta y antigüedad máxima
de 23 horas, consulta `HEAD` y continúa desde el offset confirmado. No guarda token,
nombre ni ruta del objeto en claro y estas preferencias no entran en backup. Esto aún no
equivale a una transferencia autónoma con WorkManager: la reanudación tras matar el
proceso requiere volver a ejecutar la operación con una URI que siga siendo legible.

Nginx tenía desactivado el buffering únicamente en la ruta de objetos; la ruta TUS
`/storage/v1/upload/resumable` caía en la ubicación general y podía escribir cada
fragmento en temporales antes de enviarlo a Node. Las rutas TUS y multipart legacy tienen
ahora `proxy_request_buffering off`, `proxy_buffering off` y
`proxy_max_temp_file_size 0`, protegidas por una prueba de contrato y por el verificador
del despliegue. Node tiene timeouts explícitos para headers, keepalive y solicitudes
largas. La limpieza de sesiones expiradas se ejecuta al iniciar y cada 15 minutos.

### P2 — Observabilidad insuficiente de los tiempos de subida

**Estado:** corrección parcial local y cubierta por prueba unitaria; falta recibir y revisar la telemetría de un canario real.

La UI muestra progreso agregado (8–90 % para transferencia). La instrumentación de
`StorageService` ahora separa, sin PII, los bytes escritos durante la transferencia y la
espera de respuesta del servidor, incluyendo throughput aproximado. La aritmética está
aislada y probada para transferencia normal, fallo previo al body y anomalías de reloj.
Aún falta medir en producción el procesamiento de vídeo, thumbnail y metadata como
etapas de backend.

**Mejora recomendada:** completar la medición de procesamiento de vídeo, thumbnail y
metadata con un identificador de operación sin PII y exponer métricas agregadas. Esto
permitirá atribuir una subida lenta a red del teléfono, túnel, API, FFmpeg o disco antes
de cambiar infraestructura a ciegas.

### P2 — Permisos y revisión de Play Console

**Estado:** verificado en el manifiesto actual; requiere revisar el AAB exacto en Console.

El manifiesto fusionado debug/release actual no contiene `FOREGROUND_SERVICE_DATA_SYNC` ni
un servicio de tipo `dataSync`; contiene tipos cámara, micrófono y media projection para
llamadas/directos. Si Play Console lo exige, el AAB cargado aún es de una versión anterior
o contiene una dependencia distinta: hay que descargar y revisar exactamente ese AAB antes
de declarar una tarea. `SYSTEM_ALERT_WINDOW` y ubicación siguen siendo permisos sensibles
que deben estar justificados funcionalmente en la ficha de Play.

### P3 — Ruido del escáner SQL histórico

**Estado:** corregido localmente y cubierto por pruebas automatizadas.

El informe anterior listaba 20 definiciones `SECURITY DEFINER` históricas como MEDIUM,
aunque migraciones posteriores revocaban o redefinían esas superficies. El auditor ahora
reconstruye, en el orden del manifiesto, la última definición por firma, `DROP FUNCTION` y
los privilegios `EXECUTE` finales de `PUBLIC`, `anon`, `authenticated` y `service_role`.
También analiza `CREATE FUNCTION`, que la versión anterior omitía. Las pruebas cubren
reemplazos, sobrecargas, helpers backend-only, firmas eliminadas y funciones sin
`search_path`. Resultado actual del manifiesto: **0 hallazgos efectivos**. La comprobación
estática no sustituye la consulta de catálogo posterior al despliegue.

### P3 — Prueba de presencia dependía de la fecha del calendario

**Estado:** corregido.

Una prueba del perfil público usaba una fecha fija del mismo día de la auditoría. Al
superar el TTL de presencia empezó a fallar aunque el código de privacidad era correcto.
Ahora construye una marca reciente relativa a `Date.now()`. La suite completa del API
volvió a aprobar 90/90 y no volverá a fallar por el paso del tiempo.

### P3 — Auditoría de dependencias de IA no era reproducible

**Estado:** corregido localmente.

El servicio `ai-chatbot` no tenía `package-lock.json`; por eso `npm audit` fallaba
cerrado con `ENOLOCK` y no podía demostrar el estado de sus dependencias. Se generó
el lockfile a partir del manifiesto actual y la auditoría de producción quedó
reproducible: **0 vulnerabilidades**. El lockfile debe conservarse y actualizarse
como parte del mantenimiento normal del servicio.

### P3 — Chunk web vendor vacío y carga inicial innecesaria

**Estado:** corregido localmente.

La configuración de Vite producía un chunk `vendor` vacío y añadía ruido al grafo
de distribución. Se ajustó el `manualChunks` para no crear grupos artificiales.
El build de producción actualiza sin ese warning y deja el JS principal en
96.45 kB (27.36 kB gzip), con la misma funcionalidad cubierta por lint y 81 pruebas.

## Configuración de edge verificada en el repositorio

- Nginx sirve bytes autorizados mediante `X-Accel-Redirect` y una ubicación interna.
- Los uploads de objetos, TUS y multipart legacy se proxyan sin buffering, sin temporales
  de Nginx y con keepalive.
- JSON se comprime; vídeo/audio con Range no se recomprimen.
- Los logs omiten query strings, IP, referer, user-agent y tokens potenciales.
- El script de despliegue ahora confirma que el contenedor Nginx realmente cargó estas
  directivas después de recrearse.

El contenedor observado en producción conservaba un formato de log anterior, lo que
confirma que todavía no se ha desplegado esta configuración.

## Límites de esta auditoría

- No hubo teléfono Android conectado, por lo que no se certifican cámara, Media3, audio,
  momentos ni upload con el AAB release en dispositivos físicos.
- SMART no pudo ejecutarse porque el servidor solicita contraseña sudo. Solo se verificó
  espacio, montaje, actividad, I/O y health del stack. El 2026-08-25 había espacio
  suficiente: raíz 170 GiB libres, media 861 GiB libres y backup 427 GiB libres.
- No se ejecutaron canarios que crean usuarios o contenido real en producción.
- No se desplegó código ni se modificó base de datos/archivos del servidor.

## Criterio de salida a producción

1. Configurar túnel administrado y dominio HTTPS estable.
2. Cambiar la red a gigabit y confirmar enlace a 1000 Mb/s.
3. Ejecutar el despliegue controlado con backup y rollback.
4. Aprobar `qualityGateDebug`, build release y AAB firmado.
5. Probar en al menos dos teléfonos físicos: feed en frío, scroll largo, cambio de red,
   una URL renovada, upload de vídeo, playback de momentos con audio y ciclo de Live.
6. Registrar latencia por etapa y vigilar errores Media3/HTTP durante un canario limitado.
7. Ejecutar `NOVVIN_TEST_REDIS_URL=redis://... npm run test:redis` dentro de
   `signaling-server` y una prueba de host/reconexión con dos procesos antes de cambiar
   `SIGNALING_REPLICAS` a más de uno.
