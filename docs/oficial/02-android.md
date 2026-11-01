# 02 — Aplicación Android

## Actualización 9.1.35 / 175

La [revisión 100](100-compartidos-completos-documentos-y-candidato-175-2026-10-31.md) corrige los compartidos de Social y Perfil de principio a fin, el enlace alterado en comunidades, el regreso al original tras iniciar sesión y las confirmaciones parciales de amistades. Aprueban 2.069 unitarias en 432 suites y cinco recorridos nativos con entrega real a cuentas QA; lint tiene cero errores y 319 advertencias (K1). **APK/AAB 9.1.35 / 175 firmados**, con firma/R8/16 KiB y actualización normal desde 174 comprobadas en AOSP 35. Web publicada: documentos y Reintentar reparados, 20 formatos enviados/descargados sin alterar bytes, 916 pruebas web y 90 controles públicos aprobados. Se publicaron cambios acotados en el servidor para compartidos de perfiles y listado de archivos. Los datos QA se retiraron. No publicado en Play; comunicaciones físicas y certificado de firma de Play pendientes. Esta ruta de archivos usa HTTPS y no implementa cifrado de extremo a extremo. `correciones` permanece para el otro chat.

## Actualización 9.1.34 / 174

La [revisión 99](99-contraste-auditoria-movil-y-candidato-174-2026-10-30.md) contrasta los 53 hallazgos del informe móvil de Antigravity y corrige los confirmados en acceso, navegación, Social, editor, chats, salas, LIVE y controles de gasto. Por decisión del propietario, Registro Android exige 13 años y Ajustes permite solicitar la eliminación por soporte. Aprueban 2.030 unitarias, 14 casos nativos seleccionados y lint sin errores (314 advertencias, K1). **APK/AAB 9.1.34 / 174 firmados**, con firma/R8/16 KiB y actualización desde 173 comprobadas en AOSP 35. No se cambia la API de producción ni se habilitan retiros. No publicado en Play; comunicaciones entre teléfonos físicos pendientes. `correciones` queda para el otro chat.

## Actualización 9.1.33 / 173

La [revisión 98](98-colas-web-y-carpetas-por-cuenta-2026-10-30.md) separa las carpetas por cuenta, conserva cancelación entre etapas y corrige acciones pendientes web. Aprueban 2.000 unitarias Android, lint sin errores con K1 (311 advertencias) y paquetes 173 firmados, con comprobación de actualización/entrada en AOSP 35. Web publicada, 875 pruebas y 90 controles públicos aprobados. El trabajo de correciones se conserva aparte para el otro chat; los siguientes apartados mantienen sus fechas y alcances históricos.

La app Android es el cliente principal de Novvin. Está desarrollada en Kotlin con Jetpack Compose y sigue una organización por capas, ViewModels, repositorios, servicios Android y módulos de datos.

La [revisión 96](96-segundo-plano-subidas-y-perfiles-2026-10-30.md) y la [segunda revisión 97](97-segunda-revision-cancelacion-y-cola-push-2026-10-30.md) cierran los cinco puntos solicitados: segundo plano, interrupción/reanudación de archivos, preparación del feed, consulta de perfiles del servidor y advertencias Android. Aprueban 1.979 unitarias en 417 suites y 70 casos nativos únicos seleccionados en 25 clases, en tandas con alcances diferenciados. Lint: cero errores y 312 advertencias, sin desactivar reglas. **APK/AAB 9.1.32 / 172 firmados**, con firma/R8/16 KiB y actualización desde 171 comprobados en AOSP 35. La consulta SQL está en producción: 26,007→3,154 ms de media en muestras de ejecución SQL, sin equipararlos a latencia completa. El informe conserva un timeout nativo inicial y la variabilidad del video; no certifica todos los escenarios. No publicado en Play ni verificado FCM/LIVE/llamadas entre teléfonos físicos. El destino de los enlaces de voz se conserva durante el acceso; la asociación HTTPS directa requiere la huella real de firma de Play pendiente. El historial siguiente conserva su fecha y alcance.

Cambios del candidato 166 que afectan al arranque y a los medios:

- El grafo de acceso/ranking/sincronización usa `CrashReporter`, con fallback local; Firebase no es un requisito para construir la pantalla inicial.
- El corrector de texto admite resultados nulos y rangos inválidos del servicio Android sin bloquear el editor ni el borrador.
- Invitado publica Social y Momentos independientemente y conserva las imágenes decodificadas dentro de su propia identidad; no obtiene caché privada en disco.
- La precarga de vídeo respeta el permiso y ETag del servidor, el presupuesto de red y el cambio de cuenta. No se descarga una precarga que no pueda reutilizarse.
- Los avatares de directos, concursos y recomendaciones usan el componente de identidad común. Las fotos/GIF y los pósteres de vídeo conservan sus rutas de renderizado correspondientes.

## Configuración de build

Archivo:

```text
app/build.gradle.kts
```

Estado observado:

- `namespace`: `conectate.com`
- `applicationId`: `conectate.com`
- `compileSdk`: 36
- `minSdk`: 24
- `targetSdk`: 36
- `versionCode`: 172
- `versionName`: 9.1.32
- Java/Kotlin target: 17
- Runtime de Gradle soportado: JDK 17–21. JDK 22+ se rechaza antes de compilar; el target de bytecode permanece en 17.
- Android Gradle Plugin: 8.13.2
- Gradle wrapper: 8.13
- Compose activo.
- Release signing por `keystore.properties`.
- `minifyEnabled`: true y `shrinkResources`: true en release.
- Todo build release exige configuración de firma mediante `keystore.properties` o variables `NOVVIN_UPLOAD_*`. Sin la clave original de carga, Gradle falla antes de compilar el release para impedir un bundle no publicable.

El proyecto apunta a Android 16/API 36. La compilación automatizada verifica compatibilidad de código, pero no reemplaza la prueba física de edge-to-edge, gesto predictivo atrás, teclado, permisos multimedia, foreground services, rotación y ventanas grandes.

La puerta local se ejecuta con `:app:qualityGateDebug`. Un release exige la clave original de subida de Play mediante `keystore.properties` o `NOVVIN_UPLOAD_*`. No existe una excepción de firma habilitada por `NOVVIN_AUDIT_BUILD`; no usar una llave efímera como sustituto de la clave original. La configuración de firma se comprobó en la revisión del 24/10/2026; consultar la auditoría 74 para el resultado del artefacto concreto.

## Secrets de build

La app requiere `secrets.properties`.

Ejemplo base:

```properties
FALLBACK_API_URL=https://conectate.tail9b267c.ts.net
BACKEND_DISCOVERY_HOST=conectate-alvaro.duckdns.org
BACKEND_CONFIG_URL=
BACKEND_DISCOVERY_PUBLIC_KEY=PEGA_SPKI_DER_BASE64

SUPABASE_URL=
SUPABASE_ANON_KEY=

OPENRELAY_TURN_URL=
OPENRELAY_USERNAME=
OPENRELAY_PASSWORD=
```

Reglas:

- No subir `secrets.properties` real.
- No subir `keystore.properties`.
- No subir keystores.
- No meter URLs temporales `trycloudflare.com` como endpoint final de producción.
- La clave pública no es secreta; la privada sólo existe en Ubuntu con modo `0600`.
- La clave pública canónica vive en
  `config/backend-discovery-public-key.txt`; `secrets.properties` puede
  reemplazarla explícitamente para otro entorno. Android y web consumen la
  misma ancla de confianza.
- La app sólo descubre una URL dinámica cuando el envelope `novvin1` tiene firma
  ECDSA P-256 válida, expiración acotada y `/ready` anuncia todas las capacidades.
- Sin clave pública, el descubrimiento se desactiva y sólo se confía en
  `FALLBACK_API_URL` (fail-closed).
- Si `FALLBACK_API_URL` usa temporalmente `trycloudflare.com` o `ngrok`, Gradle
  exige un `BACKEND_DISCOVERY_HOST` DNS válido y una clave pública EC SPKI
  válida. El build falla antes de empaquetar si el APK no podría recuperarse de
  una rotación del túnel.

## Capas principales

Rutas relevantes:

```text
app/src/main/java/conectate/com/data/
app/src/main/java/conectate/com/domain/
app/src/main/java/conectate/com/presentation/
app/src/main/java/conectate/com/services/
app/src/main/java/conectate/com/firebase/
```

### `data/`

Contiene modelos, entidades locales, repositorios, APIs y WebRTC.

Repositorios importantes:

- `AuthRepository.kt`
- `UserRepository.kt`
- `MessageRepository.kt`
- `ConversationRepository.kt`
- `GroupRepository.kt`
- `CommunityRepository.kt`
- `VoiceRoomRepository.kt`
- `CallRepository.kt`
- `CallHistoryRepository.kt`
- `VideoRepository.kt`
- `PostRepository.kt`
- `NotificationRepository.kt`
- `StickerRepository.kt`

### `presentation/`

Contiene pantallas Compose y ViewModels:

- mensajes;
- grupos;
- comunidades;
- llamadas;
- salas de voz;
- videos;
- perfil;
- admin;
- componentes compartidos.

### `services/`

Servicios Android para:

- llamadas;
- llamadas entrantes;
- salas activas;
- foreground services;
- chat heads.

Ejemplos:

```text
CallForegroundService.kt
IncomingCallForegroundService.kt
VoiceForegroundService.kt
LiveForegroundService.kt
```

### `firebase/`

FCM:

```text
MyFirebaseMessagingService.kt
```

## Flujos principales

### Autenticación

Casos que deben distinguirse:

1. Correo no existe:
   - mostrar mensaje de registro/no tienes cuenta;
   - no mostrar “cambia contraseña por migración”.

2. Cuenta importada de Supabase sin contraseña local final:
   - mostrar flujo de creación/cambio de contraseña una sola vez.

3. Cuenta local Ubuntu con contraseña incorrecta:
   - mostrar contraseña incorrecta;
   - no mostrar migración Supabase.

4. Cuenta local válida:
   - iniciar sesión normal.

### Mensajes 1 a 1

La bandeja debe mostrar:

- último mensaje real;
- hora del último mensaje;
- prefijo `Tú:` cuando el último mensaje fue enviado por el usuario actual;
- contador de no leídos cuando corresponde;
- estado leído/entregado si aplica;
- pin/archive/favorite/delete según estado persistente del servidor.

#### Persistencia y multimedia sin conexión

- La bandeja usa Room como fuente visual y una única sincronización remota
  compartida. Colecciones duplicadas, `ON_RESUME` y el refresco de respaldo no
  pueden abrir lecturas paralelas del mismo inbox.
- El primer inbox tiene un plazo total de 10 segundos y el historial de una
  conversación, 12 segundos. El plazo incluye redescubrimiento/reintento del
  endpoint; al vencer se conserva Room y una pantalla realmente vacía ofrece
  `Reintentar` en lugar de cargar indefinidamente.
- El polling de respaldo es de 60 segundos y sólo sincroniza grupos o
  comunidades cuando corresponde. Realtime sigue siendo el camino primario.
- Room se presenta primero al abrir una conversación; la sincronización remota en segundo plano no debe ocultar el historial local ni sustituirlo por un loader vacío.
- Las imágenes, GIF, stickers, audios y videos pequeños de los doce mensajes multimedia más recientes se copian de forma asíncrona a `filesDir/chat-media-offline`, tanto al recuperar historial como al recibir un mensaje en tiempo real.
- La misma política se aplica a chats directos, grupos y comunidades. Las copias se aíslan por hash de cuenta y una cuenta nunca puede resolver archivos pertenecientes a otra.
- El contenido `view_once_image` y `view_once_video` nunca entra en la caché offline. Los videos cuyo tamaño conocido supera 12 MiB permanecen en streaming hasta que la persona los abre deliberadamente.
- La caché usa una clave estable sin tokens temporales, valida tipo y tamaño, escribe primero un archivo parcial y solo publica una entrada completa. Respeta **Descarga automática de multimedia** y preserva al menos 64 MiB libres.
- Una copia ya descargada se resuelve antes de pedir URL firmada o tocar la red, por lo que continúa disponible en modo avión. El contenido que nunca terminó de descargarse no puede prometerse offline.
- Un perfil ajeno obtenido desde Ubuntu se normaliza y persiste en Room antes
  de mostrarse. Una respuesta remota vacía o un fallo posterior no puede borrar
  la identidad/foto previamente cacheada; las lecturas de perfil tienen un
  plazo total de 10 segundos.
- La renovación del token ocurre después de leer Room. Si la sesión remota no
  puede renovarse sin señal, el inbox existente sigue siendo un resultado
  válido; sólo se muestra error cuando nunca hubo datos locales. Abrir desde un
  perfil un chat directo ya conocido también consulta primero el índice local
  por participante.
- `ChatEntity` conserva `group_image` al reconstruir el dominio y el merge del
  inbox impide que una respuesta parcial borre nombre o foto de grupo. Las fotos
  directas y de grupo se precargan con un máximo de 24 identidades por refresco.
- Un avatar de video no se descarga completo mediante Coil. Al mostrarse por
  primera vez, `CircularProfileImage` captura el primer fotograma renderizado y
  `ProfileIdentityImageStore` guarda un WebP durable bajo `noBackupFilesDir`.
  El almacén está serializado y limitado a 96 archivos/24 MiB; con Media3 sin
  red se presenta ese póster en lugar de volver a una inicial.

#### Conservación de historial y cola de salida — 2026-09-04

El arranque de `MessageRepository` ya no ejecuta la antigua limpieza
`identity_fix_nuke_done_v4`. Se retiraron también los métodos DAO de borrado
global sin consumidores. Corregir identidad nunca debe borrar conversaciones
ni mensajes pendientes como efecto de abrir la aplicación.

Responsabilidades separadas:

- `DirectChatLookupPolicy`: selecciona por ambos participantes y actividad,
  descartando filas de otra cuenta, grupos, borrados y JSON inválido. El DAO
  devuelve candidatos, no una primera coincidencia arbitraria. Tanto búsqueda
  como `getOrCreateChat` revisan Room antes de pedir token/perfil remoto.
- `PendingMessageBatchRunner`: filtra la cuenta antes de limitar el lote,
  obtiene token sólo con trabajo local, conserva errores temporales y señala
  continuación si quedan pendientes. Cambios de sesión detienen el lote.
- `MessageSendCoordinator`: serializa únicamente el mismo ID entre envío
  foreground y worker; mensajes diferentes no comparten un bloqueo global.
  El worker relee Room al adquirir el turno para no repetir un envío confirmado.
- `PendingSyncRetryException`: comunica trabajo durable no terminado al
  coordinador y WorkManager. No depende del umbral de tres etapas fallidas ni
  del límite genérico de tres intentos; mantiene el backoff del trabajo.

Una respuesta temporal o cancelación no revierte un mensaje confirmado a
`sending`. El marcado de fallo definitivo usa un UPDATE condicionado a que la
fila siga pendiente. Este cambio no modifica el esquema Room ni requiere
reinstalación/borrado de datos. Las comprobaciones automatizadas no equivalen
a validar entrega entre dos teléfonos reales.

Referencia del contrato de reintento y restricciones del sistema:
[Android: definir solicitudes de trabajo](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
El backoff y las restricciones del SO no garantizan una hora exacta de envío;
la fila local permanece disponible mientras llega un intento autorizado.

#### Acceso a chats por cuenta — 2026-09-05

El módulo `AccountChatAccess` separa el acceso local de la identidad de la
sesión: búsquedas por ID validan ID y participantes tanto en Room/memoria como
en respuestas remotas. `getCachedMessages`, el observador de historial y el
de metadatos aplican la misma membresía. No se devuelve historial anónimo al
cerrar sesión. El backend Ubuntu usa `loadChatMetadata`, con plazo total de
lectura y sin consultar accidentalmente el endpoint Supabase.

Un chat recién descubierto guarda primero sus participantes y la identidad
que ya exista localmente. No espera la descarga opcional del perfil para
habilitar el observador. Este control usa la última membresía local conocida
para acceso offline; no pretende comprobar permisos remotos actuales sin red.

#### Contrato de migración Room

Room usa el esquema exportado de `APP_DATABASE_VERSION` como fuente autoritativa.
`DatabaseMigrationSchemaContractTest` compara en cada `testDebugUnitTest` todas
las tablas, sentencias `CREATE TABLE` e índices contra los planes de
`LatestSchemaNormalizer`; una columna o índice olvidado rompe CI. La suite
instrumentada complementaria abre bases 19, 25, 33 y 34, migra a la versión
vigente y comprueba que usuarios, conversaciones y mensajes sobrevivan. Nunca se
usa `fallbackToDestructiveMigration()` para ocultar una incompatibilidad.

Problemas conocidos que deben vigilarse:

- chat eliminado que reaparece después de reiniciar;
- último mensaje que no se actualiza con mensajes entrantes;
- contador que aparece aunque no haya chat visible;
- swipe vertical confundido con swipe lateral.

### Grupos

Debe soportar:

- lista de grupos;
- contador por grupo con mensajes no leídos;
- participantes visibles;
- agregar/quitar participantes;
- llamadas grupales;
- mensajes persistentes;
- estado leído que no reaparece como no leído después de refrescar.

### Comunidades

Debe soportar:

- “Mis comunidades”;
- “Explorar comunidades”;
- permisos por rol:
  - creador;
  - admin;
  - miembro;
- expulsar usuarios;
- promover/degradar admin;
- editar comunidad;
- restringir mensajes a admins si la comunidad está configurada así;
- límite de mensajes para usuarios normales si está activado.

No confundir grupos con comunidades. Son módulos relacionados, pero no idénticos.

### Social

- El primer frame consulta la instantánea local, aislada por cuenta, antes de
  iniciar una lectura remota. Una respuesta vacía, timeout o error remoto no
  reemplaza publicaciones útiles que ya estén en pantalla.
- La primera página de Ubuntu tiene un plazo total de 10 segundos, incluyendo
  redescubrimiento/reintento del endpoint. Al vencer se conserva caché y se
  muestra una acción recuperable; nunca un spinner permanente.
- Sólo una pantalla realmente vacía muestra carga. Con caché visible, la
  actualización continúa en segundo plano y el usuario puede desplazarse.
- Los posts recibidos se publican antes de consultar sus autores. El módulo
  `SocialAuthorHydrator` actualiza perfiles en un lote con plazo de 5 segundos;
  cancela el lote anterior al cambiar feed/cuenta y aplica sólo identidad a
  la lista actual, conservando reacciones, ediciones y borrados posteriores.
- El selector Descubrir/Seguidos tiene altura exterior fija de 52 dp tanto en
  la lista como en los estados vacíos, sin depender de la altura disponible.
- Social y Videos comparten una recuperación acotada: hasta dos intentos
  automáticos con espera creciente, únicamente con red, error real, contenido
  vacío y ninguna solicitud ya activa. Después queda `Reintentar`.
- Cancelar por cambio de cuenta, pestaña o generación se propaga como
  cancelación estructurada; no se convierte en error ni revive una respuesta
  obsoleta.

### Videos

Debe soportar:

- feed vertical;
- reproducción fluida;
- cache local;
- thumbnails;
- barra de progreso tipo TikTok;
- precarga del siguiente video cuando sea razonable;
- no parpadear negro/blanco al cambiar;
- no hacer resize brusco del layout.

El feed se mantiene por cuenta y por sesión. Cada apertura, reanudación o actualización crea una sesión de ranking nueva sin perder las preferencias ni el historial del usuario. Volver a pulsar **Videos** (incluso estando ya en esa pestaña) crea otra sesión y conserva la lista visible mientras llega la nueva página; la sesión se mezcla con la cuenta y las señales de interacción. Una instantánea de disco no sustituye al contenido visible al comenzar ese refresco. La creación y el borrado llegan por `feed:live`: un alta se incorpora sin desplazar la reproducción actual y un borrado se elimina de inmediato de listas y caché, sin exigir cerrar y abrir la app.

Media3 reconcilia el playlist por `video.id` y URL real: cambios de vistas,
likes o comentarios no reemplazan el `MediaItem` visible ni reinician su
posición. La siguiente página se agrega con append en sitio; solo un cambio
real de orden o medio reconstruye la lista. La primera solicitud de videos
tiene prioridad sobre tarjetas/personas recomendadas y la relación de seguidos
se obtiene en un lote para evitar N+1.

`VideoFollowState` registra una revisión por cada inicio/resultado de seguir.
Un lote de estado iniciado antes de esa revisión no puede hacer reaparecer el
`+` después de confirmar el seguimiento. Los fallos revierten sólo el usuario
pendiente, se ignoran resultados de otra cuenta y los lotes preservan los
usuarios seguidos que no estaban incluidos en la consulta.

El catálogo conserva hasta 50 metadatos normalizados por cuenta y los publica
antes de tocar la red. La pantalla distingue catálogo legítimamente vacío de
fallo/timeout, evita iniciar una segunda recuperación mientras la carga inicial
está activa y mantiene visible cualquier snapshot útil. La lectura de primera
página comparte el plazo total de 10 segundos y la recuperación acotada de
Social; el transporte de bytes del reproductor conserva sus propios límites y
reintentos porque catálogo y reproducción son módulos distintos.

La reproducción remota usa Media3 1.11.0 y una política de transporte común:
15 s de conexión, 30 s de lectura, `User-Agent` con versión de la app, sin
redirecciones entre protocolos y con caché LRU acotada para videos. El feed y
el visor individual comparten una decisión de reintento de red limitada a tres
intentos, con esperas de 350/1000/2000 ms. El endpoint se redescubre desde el
segundo fallo, o desde el primero ante HTTP de URL caducada o fallo del túnel.
Al agotar la política queda una acción manual. En el feed y visor individual,
`VideoPlaybackRecoveryRunner` cancela la recuperación al salir/cambiar de video,
comprueba la cuenta y selección al terminar la consulta y tiene plazo de
12 segundos por resolución. Conserva posición y bytes cacheados ante fallos
de red; sólo un rango inválido permite desalojarlos. `STATE_READY` no repone el
presupuesto: un ciclo de reproducción de un fotograma/error sigue acotado.
El visor individual publica el video antes de descargar comentarios; la
renovación de URL modifica sólo el medio y conserva comentarios/estadísticas.
La misma ruta HTTP se aplica a replays, previews de tarjetas, desafíos y
avatares animados. La música comunitaria reutiliza la política HTTP, pero no
crea descargas persistentes. Los videos sí tienen dos flujos separados:

- **Descargar video** exporta a `Películas/Novvin` mediante MediaStore, conserva
  el audio y aplica la marca `NOVVIN` más el usuario del creador. La descarga
  se transmite a archivos temporales con límite de 512 MiB para evitar cargar
  el video completo en memoria; un fallo elimina temporales y entradas
  pendientes de MediaStore.
- **Videos sin conexión**, en Ajustes → Videos y multimedia, mantiene una
  biblioteca privada por cuenta. El usuario elige la cantidad, WorkManager
  completa la selección cuando hay red, valida que cada archivo sea reproducible
  y permite reproducir o borrar el contenido sin depender del servidor.

La barra del feed conserva una zona táctil amplia, pero su pista visible es de
2 dp y el indicador de 8 dp. El botón superpuesto al avatar ejecuta seguimiento
optimista, muestra una confirmación animada con check y revierte el estado si el
servidor rechaza la operación.

La edición/exportación usa las secuencias tipadas actuales de Transformer:
audio/video para el medio principal, audio independiente para música y una
misma velocidad aplicada a ambas pistas. La línea compatible validada es
Kotlin 2.2.10, KSP 2.2.10-2.0.2, Compose BOM 2026.06.00, Room 2.8.4 y Hilt
2.57.2; `tools/tests/test_media3_dependency_guard.py` evita desalineaciones.

La admisión de video se valida en editor, repositorio y servidor: máximo 1080p,
31 fps, 4.5 Mb/s, 45 segundos y 32 MiB. Un archivo fuera de política debe
transcodificarse o rechazarse; aumentar el búfer no compensa un video cuyo
bitrate supera el caudal público.

### Momentos

Las imágenes usan una ventana funcional autoritativa de `6000 ms`. El reloj se basa en `SystemClock.elapsedRealtime()` y no en una animación Compose, por lo que las escalas de animación del teléfono —incluido el valor `0`— no pueden reducir la duración a uno o dos segundos.

Cuando hay música, la imagen espera reproducción efectiva (`isPlaying`), no solo `STATE_READY`. El servidor entrega para estos Momentos una versión M4A AAC-LC de exactamente seis segundos, 44,1 kHz y estéreo. Imagen y audio comienzan juntos y ambos se detienen al alcanzar el mismo límite; al cambiar de Momento o cerrar el visor el reproductor se libera.

En el selector de música comunitaria, la tarjeta puede reproducir una previsualización segura de 30 segundos, pero **Elegir fragmento** carga el `audio_url` original aprobado. Preview y original usan claves de caché distintas para que Media3 no sirva por error la previsualización al recortar una pista de varios minutos.

El texto del pie de un destacado respeta la navegación del sistema y mantiene margen inferior; el texto superpuesto también se limita a los bordes seguros del visor para que una posición cercana al borde no lo deje cortado.

El flujo de creación desde galería o cámara debe pasar por recorte, editor, selección musical y publicación. Tocar la tarjeta propia `Tú` abre el visor si existe contenido; el selector de galería se reserva para la tarjeta de creación.

Para videos largos:

- si supera el límite configurado, mostrar recorte;
- permitir reproducir el fragmento;
- permitir ajustar inicio/fin;
- subir versión optimizada.

### Stickers

Un sticker no debe comportarse como imagen normal.

Debe:

- renderizarse con tamaño controlado;
- no abrir visor de imagen al toque simple;
- permitir menú contextual:
  - añadir a favoritos;
  - quitar de favoritos;
  - reenviar;
  - eliminar si corresponde;
  - reportar si aplica.

### Llamadas y salas de voz

Módulos Android relevantes:

- `CallViewModel.kt`
- `CallScreen.kt`
- `VoiceRoomsViewModel.kt`
- `VoiceRoomActiveViewModel.kt`
- `VoiceRoomActiveScreen.kt`
- `WebRTCManager.kt`

Reglas:

- Nunca crashear si falla WebRTC.
- Mostrar error recuperable.
- Liberar cámara, micrófono y pantalla al salir.
- Solicitar permisos antes de inicializar.
- Validar conectividad ICE y mostrar estado.
- No dejar foreground service colgado.

## Cache móvil

La app debe cachear:

- fotos de perfil;
- thumbnails;
- últimos perfiles vistos;
- conversaciones recientes;
- previews de mensajes.

Para avatares y portadas se distinguen tres identidades: miniatura circular
acotada a 384 px, previsualización de visor a 1080 px y objeto original. Antes
de construir cualquiera de ellas se eliminan `width`, `quality` y `format`
anteriores para no encadenar transformaciones ni ampliar una imagen de 96 px.
Los parámetros de autorización y versión se conservan. Al entrar en un perfil,
la miniatura se muestra inmediatamente y el objeto original se precarga en la
caché de disco; el visor usa esa fuente con una decodificación acotada a 4096 px
por lado y una clave de memoria separada. Así una miniatura circular nunca
contamina la vista ampliada y una foto desproporcionadamente grande no provoca
una reserva de memoria peligrosa.

Si la previsualización ya está disponible, el visor no la tapa con un indicador
central mientras llega la calidad original. Si el original falla, conserva esa
previsualización y ofrece **Cargar calidad original**; solo muestra un error
bloqueante cuando tampoco existe una previsualización utilizable.

Debe evitar:

- pedir perfil completo cada vez que se abre una pantalla;
- recargar imágenes que no cambiaron;
- bloquear UI mientras espera red;
- quedarse en pantalla negra sin skeleton/placeholder.

Recomendación:

- usar Coil con memoria + disco;
- usar Room para datos de bandeja/perfiles básicos;
- usar timestamps/ETags si backend los expone;
- invalidar por `updated_at`.

### Archivos temporales compartidos

`FileProvider` no debe exponer `cacheDir`, `externalCacheDir` ni
`externalFilesDir` completos. `ShareableMediaFileStore` es el único punto para
crear archivos que se entregan temporalmente a cámara o recorte y los limita a:

- `file-provider/crops/`;
- `file-provider/challenges/`;
- `file-provider/moment-captures/`.

Al incorporar otro flujo con `FileProvider.getUriForFile`, primero debe añadirse
una subcarpeta nominal al almacén y al XML, nunca `path="."`. El contrato
`FileProviderPathContractTest` se ejecuta con las pruebas JVM y falla si vuelve a
exponerse una raíz completa.

## Buenas prácticas para cambios Android

Antes de editar:

```powershell
git status --short
```

Compilar rápido:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Compilar release:

```powershell
.\gradlew.bat :app:compileReleaseKotlin
```

Generar AAB:

```powershell
.\gradlew.bat :app:bundleRelease
```

Salida esperada:

```text
app/build/outputs/bundle/release/app-release.aab
```

Antes de Play Store:

- subir `versionCode`;
- probar instalación limpia;
- probar login;
- probar mensajes;
- probar notificaciones;
- probar llamadas/salas en Android real;
- revisar crash logs.

