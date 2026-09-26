# Música, permisos de multimedia y plan Gamer Hub — 2026-09-13

## Alcance de esta continuación

Trabajo en `D:\red social - live-gifts`, rama `feature/live-gifts`, preservando cambios existentes. Se incorporó el documento del usuario con la ampliación de PlayMatch/Juegos, edición multimedia y auditoría progresiva. PlayMatch se trata como el módulo gamer existente; no se renombró la empresa ni toda la aplicación.

Este es un **mapa inicial y una auditoría incremental**, no una afirmación de haber probado cada botón o certificado producción. Continúan los bloqueos del informe 23, especialmente la lectura pública de archivos privados y la transición de clientes. No se añadieron pantallas ficticias, jugadores inventados ni otro sistema de chat.

## 1. Stack y conexiones existentes

| Área | Código y componentes reutilizables | Conexión principal |
|---|---|---|
| Android | Kotlin, Compose, Navigation, Hilt, ViewModel/StateFlow | Repositorios → clientes Ktor → API Ubuntu |
| Persistencia y medios | Room, almacenamiento privado por cuenta, Coil, Media3/ExoPlayer/Transformer | Caché local → resolución de referencias → storage |
| Web | React 19, TypeScript, Vite, React Query/Zustand, rutas existentes | Adaptadores de autenticación/datos Ubuntu y compatibilidad Supabase |
| Servidor | Node/Express, PostgreSQL, Nginx, storage local, contenedores Docker | Auth/sesiones, módulos de datos, archivos, notificaciones |
| Mensajes/grupos/comunidades | MessageRepository, chat existente, tablas fallback/importadas | Historial HTTP, realtime, adjuntos, permisos de pertenencia |
| Social/Videos/Perfiles | Repositorios, snapshots de feed, precarga/reproductor, caché de perfiles | Feed paginado, almacenamiento, reacciones y estado por cuenta |
| Momentos/música | CreateMomentViewModel, MomentRepository, picker local/comunidad, MomentMusicCompositionExporter | `moments-routes`, catálogo musical autorizado y renderizador FFmpeg |
| Juegos | PlayMatchViewModel/Repository/ApiClient y pantallas existentes | `playmatch-routes`, catálogo dinámico, perfiles, juegos, horarios, preferencias, conexiones y matchmaking |
| Llamadas/salas/LIVE | WebRTC/Socket.IO, puente LiveKit, coordinador de regalos y overlay animado | Señalización, TURN/media, API de salas, ledger/catálogo de regalos |

Autenticación y autorización se mantienen en el servidor; los IDs enviados por la UI no constituyen permisos. Firebase se usa en las notificaciones. Supabase se conserva como compatibilidad/importación, no como nueva dependencia principal. Mapa general y límites de infraestructura: documento 01.

## 2. Problemas por prioridad

| Prioridad | Hallazgo y evidencia | Estado |
|---|---|---|
| CRÍTICO | GET público/legado entrega archivos de chat sin autenticar; 8/8 rutas sintéticas aún expuestas | Pendiente; puerta de release Android continúa bloqueando |
| ALTO | Reenvío autorizaba por conversación sin excluir siempre borrado/vista única; además cargaba el historial completo de salas para comprobar acceso | Corregido en el recorrido de reenvío declarado y desplegado; no cierra otros accesos a archivos |
| ALTO | `MUSIC_AUDIO_URL_MISMATCH` compara URL completa de catálogo con una URL cuyo dominio ya cambió | Reproducido, corregido, probado y desplegado |
| ALTO | Búsqueda gamer puede aplicar una respuesta antigua: `setQuery` cancela su job, pero `search` crea otro job independiente en viewModelScope | Corrección de cancelación/generación pendiente |
| ALTO | Filtro gamer acepta juego y plataforma de entradas diferentes del mismo perfil | Reproducción sintética: pide Valorant+PlayStation, candidato tiene Valorant+PC y Fortnite+PlayStation; devuelve true cuando debe ser false. Pendiente |
| MEDIO | Home gamer lanza consultas en paralelo pero aplica sus datos después de esperar a las cinco, con límite individual de 8 s | Separar estados/resultados conservando caché, sin modal global ni recargas superpuestas |
| MEDIO | Matchmaking actual limita candidatos a 250 y ya produce razones; los pesos están dentro de `computeCompatibility` | Extraer configuración y ampliar restricciones sin introducir un segundo algoritmo desconectado |
| MEDIO | Autorización general de reutilización de stickers y referencias de archivos necesita revisión coordinada con el nuevo acceso privado | Pendiente; no equiparar el arreglo de reenvíos a privacidad completa |
| BAJO | Lint del bloque Android matutino: 195 advertencias, sin errores | Mantenimiento; no se suprimieron reglas |

LFG y squads no aparecieron como implementaciones completas en las búsquedas dirigidas del código actual. Se comprobará cada punto de integración antes de introducir tablas o UI; no se deduce ausencia absoluta de todo concepto equivalente por una sola búsqueda.

## 3. Causas del lag: qué se sabe y qué no

Hay esperas y carreras de estado identificadas en el código, rotaciones reales del hostname público, y versiones instaladas distintas de los artefactos validados. Eso puede producir recargas, contenido tardío o errores, aunque la conexión del teléfono sea buena. No hay evidencia suficiente para atribuir todo el lag a falta de capacidad del servidor.

Las muestras posteriores al parche, desde el servidor **a través del origen público** `minerals-cohen-stockings-bite.trycloudflare.com`, dieron:

| Lectura | Resultado | Tiempo hasta cuerpo completo |
|---|---|---|
| Social / Videos / búsqueda de perfiles | HTTP 200 | 582 / 599 / 512 ms |
| Primer y segundo MiB de video | HTTP 206, rangos correctos | 744 / 630 ms |
| Bandejas chat / grupos / comunidades | HTTP 200 | 798 / 709 / 425 ms |
| Historial / programados | HTTP 200 | 506 / 170 ms |
| Momentos / salas de voz / Juegos | HTTP 200 | 826 / 1470 / 1229 ms |
| Directorio LIVE / catálogo regalos | HTTP 200 | 417 / 422 ms |

No había LIVE activo. Estos resultados no demuestran primer cuadro, audio bidireccional, publicación ni fluidez de scroll. El profiling de arranque, frames y memoria se hará sobre Android real con trazas y escenarios repetibles, no sustituyéndolo por tiempos de tests unitarios. [Macrobenchmark de Android](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) cubre arranque y recorridos de UI/scroll y genera resultados y trazas.

## 4. Corrección de la música: causa y solución

El catálogo devuelve URLs normalizadas al dominio actual mediante `canonicalStorageResponseMiddleware`. Android también reescribe el origen. Sin embargo, `verifyApprovedMomentMusic` comparaba ese string contra `fallback_music_tracks.audio_url`, que podía conservar el dominio anterior. La canción y el objeto eran los mismos, pero la comparación rechazaba la publicación.

`moment-music-reference.js` separa la identidad del objeto de su URL de entrega. Sólo aplica equivalencia de rutas a objetos de `community-music`, preserva mayúsculas/minúsculas y decodifica cada segmento una vez. Rechaza otro bucket, propietario/ruta, separadores codificados, recorrido de directorios, credenciales en URL y controles. Las referencias externas legadas requieren coincidencia exacta.

La selección sigue autorizándose por ID en el catálogo, con aprobación, permiso de reutilización y ausencia de retirada/deshabilitación. El procesamiento usa **la URL de la fila autorizada**, no la URL enviada por el cliente. No se relajaron derechos, MIME ni propiedad para esconder el error.

Evidencia:

- Antes: dos de cuatro nuevas regresiones fallaron con `MUSIC_AUDIO_URL_MISMATCH`, `tmp/music-reference-before-20260913.log`.
- Después: 22 pruebas de Momentos, referencia musical, renderer y catálogo pasaron, `tmp/music-reference-after-20260913.log`.
- Catálogo real: transacción `BEGIN READ ONLY`, dos pistas aprobadas verificadas, ambas con dominio diferente; resultado correcto. `tmp/music-real-catalog-20260913.log`. No se descargó audio ni se creó un Momento para esta comprobación.
- `verify-moment-music-references.js` conserva el diagnóstico reproducible sin imprimir IDs, URLs personales ni credenciales. La primera invocación por stdin no ejecutó su entry point; **no se contó como evidencia**. La invocación corregida con `node -` sí generó el resultado anterior.

Esto corrige la validación comunitaria. No prueba aún todas las variantes de edición, portada, cancelación, múltiples imágenes, mezcla y reproducción real.

## 5. Permisos de reenvío

`message-media-access.js` rechaza fuentes eliminadas para todos/para esa cuenta, de vista única y vencidas. La consulta por ID trae la fila completa y da precedencia a la fila local. Se comprueban pertenencia y bloqueo, incluso cuando el solicitante es propietario del archivo. Las comunidades públicas mantienen el acceso que ya permitía el contrato de historial.

`loadCollectionRoomAccess` separa autorización de carga de mensajes/perfiles. Reenviar deja de cargar el historial completo del grupo o comunidad; el historial se consulta después de autorizarlo cuando realmente se solicita.

Las 13 pruebas nuevas comprueban recorrido legítimo en chat/grupo/comunidad, denegaciones, borrado, vista única, alias y acceso público/eliminación de comunidad. Son fixtures que invocan funciones reales, no una simulación de transacción completa entre teléfonos. La lectura pública de storage y la migración de firma siguen pendientes. La política debe comprobar cada objeto y acción, no sólo el acceso al endpoint: [OWASP API1](https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/).

## 6. Despliegue y pruebas

- API local completa: 139 pruebas, **138 pasan y una omitida**, sin fallos. `tmp/api-privacy-music-20260913.log`.
- Imagen candidata Linux: 35 pruebas seleccionadas pasan y resolución de imports correcta. `tmp/api-media-candidate-20260913.log`.
- Sólo se recreó `api`, con validación de salud y rollback preparado. Los demás servicios permanecieron activos. Imagen actual `conectate-api-media:20260913`, ID `sha256:8711ac094a7b25fe5e426b053d56b79446380458ace27b7629bbadf560d39a90`.
- Fuente del contenedor y host reconciliada. SHA-256: `data-routes.js` = `29638753238657d82fdc722671edbd547c73da8415750b5cd5d80103abdd4158`; `message-media-access.js` = `2eb808b70ce3949c008821325168ea969e43f94e8f240df5b7fbaec011e1a037`; `moments-routes.js` = `a856bd2e707d63f2aed7923b110257ca2aae26199b04ec74ef741944c7956d7d`; `moment-music-reference.js` = `919c2b71f23896559176e9de674695fdf1677c3e1377e512cd71ad9ffa0ce8c8`.
- Rollback: imagen `conectate-api-before-media:20260913`, fuente previa en `/home/alvaro/conectate-releases/api-media-20260913/host-before.hyft3n`. El script de ese release revierte imagen si la actualización falla. Para reversión manual, reetiquetar la imagen previa como `conectate-local-fallback-api:latest`, recrear sólo `api`, restituir los dos archivos previos del host y verificar `/ready`. Los módulos nuevos no usados pueden permanecer; no borrar datos de storage.
- Logs HTTP: `tmp/services-after-media-20260913.log`, `tmp/social-after-media-20260913.log`, `tmp/messaging-after-media-20260913.log`.
- Se volvió a ejecutar la puerta de privacidad: sigue fallando en 8 rutas, sin ocultarlo. `tmp/messaging-media-privacy-after-music-20260913.log`.

## 7. Plan exacto de implementación progresiva

1. **Cerrar los errores críticos:** completar ACL de multimedia, referencias firmadas Android/Web, caducidad/revocación/rangos y transición de clientes; revisar también stickers. La API reparada de música no levanta este bloqueo.
2. **Verificar teléfono y rendimiento:** actualización con firma compatible y datos conservados; arranque frío/caliente, Social, Videos, perfil, navegación y preview de Momentos. Registrar frames/primer cuadro/errores y comparar antes/después. Entrega, llamadas y LIVE requieren segundo dispositivo/cuentas de prueba.
3. **Estabilizar Juegos existente:** búsquedas cancelables con generación, paginación que no mezcle filtros, resultados independientes por sección, filtros de juego/plataforma/rol sobre la misma entrada, restricciones de bloqueos/suspensión, pesos centralizados y razones de compatibilidad. Probar timezone y disponibilidad.
4. **LFG:** adaptar tablas existentes tras revisar contratos; estados OPEN/FULL/STARTED/CANCELLED/EXPIRED, unicidad/idempotencia, control transaccional de cupos, líder/solicitudes y expiración. Reutilizar chat grupal y notificaciones. Backend/persistencia/tests antes de mostrar acciones nuevas.
5. **Squads y perfil gamer 2.0:** reutilizar grupos, membresía y roles; extender preferencias/juegos/horarios; visibilidad por campo y región aproximada, nunca coordenadas precisas. Después integrar búsqueda, filtros guardados y reputación ligada a encuentros verificables, no solicitudes falsas.
6. **Editor multimedia:** ampliar el picker/exportador actual con recorte/portada y modelo único de original, música, fragmento y volúmenes; conservar originales. Validar preview/exportación/publicación, progreso, retry/cancelación y limpieza de fallos antes de añadir controles.
7. **Puerta por módulo:** pruebas backend y persistencia, build/typecheck, Android unit/Lint y escenarios físicos; documentar rollback y lo no probado. No certificar escala de un millón de usuarios sin carga representativa, infraestructura y límites medidos.

### Teléfono y APK en curso

A las 21:54 se detectó un moto g04. Paquete instalado `conectate.com`, código 66, `3.3.4-debug`, actualización registrada el 4 de septiembre. El usuario autorizó actualizarlo y probar conservando datos. Se guardó su APK anterior en `tmp/phone-validation-20260913/installed-before.apk`; no se borraron datos.

La APK matutina del informe 23 ya no estaba en `app/build/outputs` al intentar comparar firmas. Se inició una reconstrucción con JDK 21, `assembleDebug`, `testDebugUnitTest` y `lintDebug`, log `tmp/android-phone-validation-20260913.log`. No se instaló otra firma a la fuerza. El teléfono estaba bloqueado; se pidió al usuario desbloquearlo, sin solicitar su PIN. La pantalla negra capturada bajo bloqueo **no se atribuye a un fallo de la aplicación**. No se considera probado el arranque por haber traído una tarea al frente.

Actualización de esta validación:

- Build incremental terminó a las **22:03:34**, `BUILD SUCCESSFUL in 1m 35s`. Reconstruyó APK y reutilizó resultados vigentes de compilación, 642 tests y Lint según entradas de Gradle (`UP-TO-DATE`/caché); no se describe como 642 tests ejecutados nuevamente.
- APK reconstruida: 269 153 463 bytes, SHA-256 `3EB8B68205C30FFA2D13BD089E69E46C3A2ED8266B0BA7B909E4CB69AB48910E`. La firma SHA-256 del certificado coincidió con la instalada. `adb install -r -t` devolvió **Success**. `lastUpdateTime=2026-09-13 22:06:40`; `firstInstallTime=2026-08-27 16:04:58` permanece. Sin desinstalar ni limpiar datos.
- El usuario desbloqueó el equipo. El primer lanzamiento posterior a actualización fue **COLD, TotalTime 8664 ms, WaitTime 8684 ms**. Es una sola muestra debug tras instalación, no mediana de arranque ni tiempo de carga completa; sigue siendo un pendiente de rendimiento.
- Social mostró contenido, avatar e imagen de publicación y permitió dos desplazamientos. Evidencia local privada en `tmp/phone-validation-20260913/social.png` y `social-scrolled.png`. No se observó el `INTERNAL_ERROR` ni la cabecera deformada en esa secuencia.
- Videos mostró un elemento y su imagen/cuadro, sin cartel de reintento en esa captura. Después la pantalla cambió a una foto de perfil gamer y al detalle del jugador mientras se medía. Se pausaron los gestos y se consultó por posibles interacciones simultáneas. **No se cuenta como prueba de reproducción continua ni como navegación automática defectuosa sin confirmación.**
- Muestra inicial `dumpsys gfxinfo`: 1392 frames, 34 fuera de deadline (2,44%), p95 15 ms, p99 150 ms; incluye arranque y navegación, no sólo Social. Sus valores legacy son distintos; no mezclar métricas. Memoria en una muestra posterior: PSS 337 730 KiB, RSS 274 848 KiB y swap PSS 148 503 KiB. No prueba fuga ni rendimiento sostenido.
- No se enviaron mensajes, regalos ni se publicaron Momentos de prueba. Las capturas del diagnóstico pueden contener datos visibles de la cuenta: no subirlas como artefacto público.
