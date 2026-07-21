# AUDITORÍA TÉCNICA COMPLETA — Proyecto Conéctate

> **Tipo:** Auditoría de arquitectura de software senior, pre-lanzamiento a miles de usuarios.
> **Fecha:** 2026-07-19
> **Alcance:** Repositorio completo — App Android (Kotlin/Compose), App Web (React/TypeScript),
> Backend self-hosted "Ubuntu" (Node/Postgres/Docker/Nginx), servidor de señalización WebRTC,
> infraestructura, seguridad, rendimiento e higiene del repositorio.
> **Método:** análisis estático multi-agente + compilaciones reales (Gradle, Vite/tsc, Jest,
> npm audit) + inspección de logs de crash reales + verificación contra el esquema autoritativo de
> Room. Sin despliegues, sin migraciones, sin cambios en producción.

Para cada hallazgo: **gravedad · archivo:línea · explicación · evidencia · causa raíz · impacto ·
solución · prioridad**.

---

## RESUMEN EJECUTIVO

El proyecto es funcional y compila en las tres capas (Android debug, Web build, backend Docker),
con **prácticas de ingeniería sorprendentemente maduras en algunas áreas** (negociación perfecta
WebRTC en Android, cifrado con Android Keystore, validación de subidas con *magic bytes*, respaldos
locales verificados con *restore-drill* semanal, señalización con autorización por-mensaje). Sin
embargo, **no está listo para un lanzamiento a miles de usuarios** por un conjunto de problemas
críticos concentrados en tres ejes: **exposición de datos personales, control de acceso al
almacenamiento, y disponibilidad/recuperación ante desastres**.

### Top de bloqueantes de lanzamiento (Crítico)

| # | Hallazgo | Capa | Sección |
|---|---|---|---|
| C1 | Exportaciones **reales de la BD de producción con PII** (emails, mensajes, logs de seguridad) commiteadas en git | Repo/Datos | [§7.1](#71-pii-de-produccion-en-git-critico) |
| C2 | **Buckets de almacenamiento privados servidos sin autenticación** (`report-evidence`, `chat-media`, `group-audio`) | Backend | [§4.1](#41-almacenamiento-privado-sin-autenticacion-critico) |
| C3 | **Sin backup fuera de sitio** — el mini-PC es punto único de fallo total de datos | Infra | [§4.10](#410-backuprecuperacion-sin-copia-fuera-de-sitio-critico) |
| C4 | `WebView` con JS + acceso a ficheros carga un **reproductor de streaming pirata** (riesgo de suspensión total en Play Store + seguridad) | Android | [§3.9](#39-webview-de-streaming-de-terceros-critico) |
| C5 | Crash de **migración Room** que impide abrir la app a usuarios existentes | Android | [§3.5](#35-migracion-room-crash-de-arranque-critico--ya-corregido) *(ya corregido/verificado)* |

### Panorama por gravedad

- **Crítico:** 5 (C1–C5; C5 ya corregido en rama de estabilización).
- **Alto:** ~14 (control de acceso, escalado señalización, STUN-only sin TARN fiable, no-root en
  contenedores, PII en historial, bloat de git de >2 GB, etc.).
- **Medio:** ~30. **Bajo/Informativo:** ~25.

---

## 1. ARQUITECTURA GENERAL

**Estructura real (lo que se compila/ejecuta):**
- `app/` — App Android Kotlin/Jetpack Compose (módulo único `:app`, `namespace conectate.com`,
  versionName 3.0.5 / versionCode 45). Es el único módulo incluido en `settings.gradle.kts`.
- `Conectate-web/` — App Web React 19 + Vite 7 + TypeScript, desplegada en Vercel.
- `infrastructure/local-fallback/` — Backend self-hosted real: API Node/Express, Postgres 16,
  realtime-gateway (WS), coturn (TURN), todo en Docker Compose sobre un mini-PC Ubuntu.
- `signaling-server/` — Servidor de señalización WebRTC (Socket.IO) independiente.
- `supabase/` — Configuración/funciones Supabase heredadas (**migración incompleta**, ver §6).

**Hallazgo A-1 (Medio) · Migración de backend a medio hacer.** El proyecto está migrando de
Supabase a un backend propio ("Ubuntu"). Conviven ambos: `app/build.gradle.kts:223-227` aún declara
`supabase-realtime/gotrue/postgrest/storage` ("Legacy compatibility while remaining repositories
are moved to the Ubuntu API") mientras `SUPABASE_URL`/`SUPABASE_ANON_KEY` en `BuildConfig` están
vacíos. **Impacto:** dos backends conceptuales, peso muerto en APK, confusión de mantenimiento.
**Solución:** terminar o abortar explícitamente la migración y eliminar dependencias + reglas
ProGuard de Supabase si ya no se usan. **Prioridad:** Media.

**Hallazgo A-2 (Alto) · "26 sistemas enterprise" son código muerto.** Directorios de nivel raíz
(`quantum/`, `photonic/`, `neuromorphic/`, `space/`, `dna/`, `ai/federated/`, `network/6g/`,
`infrastructure/{quantum,edge,blockchain}/`) contienen ~70 archivos Kotlin (~500 KB) con cabeceras
casi idénticas generadas por IA. **Evidencia:** `settings.gradle.kts` solo incluye `:app`, y
`app/build.gradle.kts` fija `java.setSrcDirs(listOf("src/main/java"))`; ninguna de estas clases
está bajo esa ruta y `grep` de sus nombres en `app/src/main` y `Conectate-web/src` da **0
referencias**. Un informe previo (`AUDITORIA_FINAL_CONECTATE.md`) los listaba como *fortaleza* —
es una afirmación **falsa**. **Impacto:** inflan el repo y la percepción de funcionalidad.
**Solución:** eliminar del árbol. **Prioridad:** Media.

---

## 2. COMPILACIÓN, LINT Y PRUEBAS (resultados reales)

**Web (`Conectate-web`):**
- `npm run build` (tsc + vite) → ✅ **compila** (1714 módulos, ~23 s). Bundle principal
  `index-*.js` 285 KB (89 KB gzip) — aceptable, con *code-splitting* por página.
- `eslint .` → ✅ **sin errores**.
- `jest` → ✅ **63/63 tests** en 10 suites. Advertencia: *"A worker process has failed to exit
  gracefully"* (handles sin cerrar en tests — fuga de teardown, no de producción).
- `npm audit` → **23 vulnerabilidades: 0 críticas, 8 altas, 10 moderadas, 5 bajas.** Casi todas en
  `devDependencies` (workbox-cli, cypress, vite, postcss). Altas destacadas: `serialize-javascript`
  (RCE, vía workbox), `vite` (path traversal en dev server), `form-data`, `fast-uri`, `flatted`,
  `picomatch`. **Prioridad:** Media (dev-only en su mayoría; actualizar antes de release para
  reducir superficie). Directa de producción a vigilar: `postcss <8.5.10` (XSS).

**Android (`:app`):** compila en Debug (`compileDebugKotlin`/`hiltJavaCompileDebug` OK). La cadena
`assembleDebug → testDebugUnitTest → lintDebug → assembleRelease` es **muy lenta** por configuración
deliberada de un solo worker (ver §5). Estado de la verificación de esta rama de estabilización en
`CORRECCIONES_REALIZADAS.md`.

---

## 3. ANDROID (Gradle, Kotlin, R8/ProGuard, Room, Seguridad, Crashes)

### 3.1 Gradle / configuración de build
- **(Medio)** `gradle/libs.versions.toml`: **Compose BOM `2023.10.01`** (~2 años obsoleto frente a
  `compileSdk 35`), **Kotlin 1.9.22** (Kotlin 2.x lleva tiempo estable). Pierde parches y APIs.
  *Solución:* subida escalonada a Kotlin 2.x + Compose BOM reciente. *Prioridad:* Media.
- **(Medio)** `gradle.properties:26-28`: `android.enableR8.fullMode=false` como **workaround** de un
  `ConcurrentModificationException` de R8 full-mode. Release corre en modo compatibilidad → menos
  optimización. *Solución:* reintentar full-mode con AGP/R8 más nuevos. *Prioridad:* Media.
- **(Medio-Alto)** Librerías **alpha/beta en producción**: `securityCrypto = 1.1.0-alpha06`,
  `mlkitSegmentation = 16.0.0-beta6`. *Solución:* fijar a versiones estables. *Prioridad:* Media.
- **(Medio)** Inconsistencia de versiones Firebase: se importa `platform(firebase-bom:33.15.0)` pero
  `firebase-messaging`/`crashlytics` están pineadas a versiones explícitas → se anula el BOM.
  *Solución:* quitar `version.ref` y dejar que el BOM gobierne. *Prioridad:* Media.
- **(Buena práctica)** Firma de release lee `keystore.properties` sin hardcodear; deshabilita firma
  si falta en vez de caer a valores inseguros. `secrets.properties`/`keystore.properties`/
  `local.properties` **gitignorados y no rastreados** (verificado).

### 3.2 R8 / ProGuard
- **(Alto)** `app/proguard-rules.pro:15-24`: reglas `-keep ... { *; }` con **comodín de paquete**
  sobre enormes zonas de código propio (`conectate.com.core.**`, `data.repository.**`, `util.**`,
  `di.**`) y `-keep class androidx.compose.** { *; }` (línea 90). Anulan el shrinking/ofuscación de
  casi toda la lógica de negocio; combinado con full-mode desactivado, R8 aporta poco beneficio pero
  paga su coste. *Solución:* acotar a las clases que realmente necesitan reflexión/serialización
  (DTOs `@Serializable`). *Prioridad:* Media (no es crash, es tamaño/tiempo).
- **(Verificado / Buena práctica)** Las reglas para **WebRTC/JNI** *sí* son correctas y completas:
  `-keep class org.webrtc.**`, `native <methods>` global, y `@org.webrtc.CalledByNative(Unchecked)`
  (métodos y campos) preservados (líneas 63-77), más `conectate.com.data.webrtc.**` y
  `data.signaling.**` (líneas 19-20). → **La hipótesis de "crash de Release por R8 eliminando
  clases WebRTC" es falso positivo.** No hay `@CalledByNative` en código propio (0 resultados).

### 3.3 AndroidManifest / permisos
- **(Alto)** `AndroidManifest.xml:74-84`: deep link de **recuperación de cuenta** por esquema
  personalizado `conectate://recovery` **sin `autoVerify`**. Los esquemas custom no son exclusivos:
  otra app puede registrarse e interceptar un enlace con token de reset. *Solución:* usar App Links
  HTTPS verificados (`assetlinks.json`) para flujos sensibles; confirmar tokens de un solo uso.
  *Prioridad:* Alta.
- **(Medio)** Deep links HTTPS (`conectate.com/...`, incluido `/web-login`) **sin
  `android:autoVerify="true"`** → no son App Links verificados. *Prioridad:* Media.
- **(Medio)** `SYSTEM_ALERT_WINDOW` (usado por `LiveChatHeadService`) y `ACCESS_FINE_LOCATION` son
  permisos sensibles en revisión de Play; requieren justificación. *Prioridad:* Media (checklist Play).
- **(Bajo)** `res/xml/file_paths.xml`: el `FileProvider` expone `path="."` (raíz de external/cache)
  con `grantUriPermissions="true"`. *Solución:* acotar a subcarpetas concretas. *Prioridad:* Baja.
- **(Buena práctica)** `usesCleartextTraffic=false` + `network_security_config` (solo CAs del
  sistema, overrides de debug bien acotados); solo `MainActivity` exportada; sin
  `MANAGE_EXTERNAL_STORAGE`/`QUERY_ALL_PACKAGES`; `allowBackup` con allow-list que **excluye** BD y
  almacenes cifrados.

### 3.4 JNI / código nativo
- **(Informativo)** **No hay código nativo propio** (sin `.so`, `CMakeLists.txt`, `System.loadLibrary`,
  `externalNativeBuild`). El único nativo es transitivo: AAR `io.github.webrtc-sdk:android` y MLKit.
  Riesgo nativo acotado a esas dependencias. `webrtc = 137.7151.05` es un *fork* comunitario que
  puede ir por detrás de parches oficiales — vigilar CVEs. *Prioridad:* Baja/Media.

### 3.5 Migración Room — crash de arranque (Crítico — YA CORREGIDO)
- **Archivo:** `app/src/main/java/conectate/com/data/local/DatabaseMigrations.kt`
  (`LatestSchemaNormalizer`).
- **Evidencia (logs reales):** `IllegalStateException: Migration didn't properly handle:
  users/video_comments/groups` (`crash_log.txt`, 2026-07-16).
- **Causa raíz:** el normalizador mantiene a mano el esquema objetivo; al añadir columnas a las
  entidades sin espejarlas, el hash de esquema que Room valida al abrir la BD deja de coincidir →
  **la app no arranca para usuarios con datos existentes** (adyacente a pérdida de datos).
- **Impacto:** catastrófico en un lanzamiento (todos los usuarios que actualizan la app).
- **Estado:** corregido en el working tree (`users`←4 col, `video_comments`←3, `groups`←`rest_until`,
  `communities`←`daily_message_limit`, + `cleanupOrphanedRecords`) y **verificado en esta auditoría**
  con un comparador contra el esquema autoritativo `51.json`: **las 17 tablas coinciden exactamente**.
- **Solución de robustez pendiente:** test con `MigrationTestHelper` en CI que falle si el
  normalizador vuelve a divergir de las entidades. *Prioridad:* Alta (prevención de regresión).

### 3.6 Diseño de migraciones (Medio — riesgo latente)
- `DatabaseMigrations.kt` genera **50 migraciones casi idénticas** (`1..51`) que reconstruyen
  **todas las tablas** vía `DROP/CREATE AS SELECT/RENAME`. **Riesgos:** (a) el `TablePlan` debe
  mantenerse en sincronía perfecta con las entidades (fuente del crash §3.5); (b) reconstruir las
  17 tablas en cada actualización puede causar **migración lenta/ANR** en usuarios con historial de
  chat grande (Room migra de forma síncrona al abrir la BD). *Solución:* test de migración en CI +
  considerar migraciones incrementales o pre-calentamiento en background. *Prioridad:* Media.
- **(Buena práctica)** **No** se usa `fallbackToDestructiveMigration()` en ningún sitio — se evita
  deliberadamente destruir el historial local de chats.

### 3.7 Concurrencia / fugas / ANR
- **(Buena práctica)** **0** usos de `GlobalScope.launch` y **0** `Thread(` crudos. Helper común
  `newSafeScope` (SupervisorJob + CoroutineExceptionHandler) usado en 25 archivos. DAOs 100%
  `suspend`, sin `allowMainThreadQueries`.
- **(Bajo)** `SupabaseConfig.kt:243-253` usa `runBlocking(Dispatchers.Main.immediate)` bajo guard
  (no bloquea Main); `ThemePreferences.kt` lee `SharedPreferences` síncrono en el constructor
  (`@Singleton`, primer arranque) — I/O bloqueante menor en Main. *Prioridad:* Baja.
- **(Medio · seguimiento)** Repos gigantes (`MessageRepository.kt` 4641 líneas, `VideoRepository.kt`
  3230) requieren una revisión focalizada de simetría registro/desregistro de listeners realtime.
  *Prioridad:* Media.

### 3.8 Crashes varios (de los logs reales)
- **(Alto)** **Navegación de notificación** (`NotificationScreen.kt` `handleNotificationNavigation`):
  `IllegalArgumentException: destination moment_viewer/... cannot be found` desde el NavHost anidado
  de pestañas (`HomeScreen.kt:322`, start=messages). **Corregido con guard `runCatching`** en la
  rama de estabilización (detiene el crash; la corrección completa requiere enrutar por el
  NavController raíz). Ver `CORRECCIONES_REALIZADAS.md §3`. *Prioridad:* Alta.
- **(Bajo-Medio)** `AudioRecorder.kt:94` `audioRecord!!.audioSessionId` sin guard de null previo →
  posible NPE si falla la init del micro. *Solución:* early-return con chequeo de null. *Prioridad:* Media.
- **(Informativo)** SIGABRT en `audioserver` (pid 631) en los logs = proceso del sistema/emulador,
  no de la app.

### 3.9 WebView de streaming de terceros (Crítico)
- **Archivo:** `app/src/main/java/conectate/com/presentation/voicerooms/CinemaPlayer.kt:289-397` +
  `CinemaCatalog.kt` (motor "Meta → Scraper → RealDebrid → ExoPlayer", token `REAL_DEBRID_TOKEN`).
- **Evidencia:** `WebView` con `javaScriptEnabled=true`, `allowFileAccess=true`,
  `allowContentAccess=true`, `domStorageEnabled=true`, cargando `videoUrl` desde un pipeline de
  scraping de sitios de streaming; el propio código incluye un "ad killer" con keywords
  `bet/gamble/18+/bonus/prize`.
- **Causa raíz:** feature de contenido pirata embebido en una red social.
- **Impacto:** (a) **seguridad** — ejecutar JS de terceros no confiables en un WebView con acceso a
  ficheros es vector clásico de malvertising/drive-by; (b) **negocio** — violación de política de
  Google Play (copyright/DMCA + contenido de apuestas/adulto) con **riesgo de suspensión total de la
  app**, existencial para un lanzamiento.
- **Solución:** escalar a decisión de producto/legal antes de lanzar. Si se mantiene: desactivar
  `allowFileAccess`/`allowContentAccess`, o mover a Custom Tab/reproductor aislado. *Prioridad:* Alta/Crítica.

### 3.10 Otras buenas prácticas Android
- `MediaUploadGuard.kt`: validación de subidas con *magic bytes*, bloqueo de ejecutables (MZ/PE,
  ELF, shebang), anti path-traversal — superior a la media (con la salvedad de que es cliente).
- `EncryptionManager`/`EncryptedSharedPreferences`: cifrado con Android Keystore (AES-256-GCM) para
  tokens de sesión. Fallback a `SharedPreferences` en claro si el Keystore falla
  (`AccountSessionRepository.kt:445-452`) — **(Medio)** degrada confidencialidad en silencio; añadir
  telemetría + re-cifrado al recuperar Keystore.
- `OperationGuard.kt`: rate limiting **solo de cliente** (in-memory) — **(Medio)** debe existir
  equivalente en servidor (ver §4.3).
- **(Medio)** Sin *certificate pinning* (`CertificatePinner` no aparece). Recomendable dado que
  maneja tokens/2FA/medios. *Prioridad:* Media.

---

## 4. BACKEND / INFRAESTRUCTURA (Docker, Postgres, API, Nginx, Scripts)

### 4.1 Almacenamiento privado sin autenticación (Crítico)
- **Archivo:** `infrastructure/local-fallback/api/src/storage-routes.js:507-538` (`serveStoredObject`)
  y registro de rutas `:732,741` (sin `authRequired`).
- **Evidencia:** `GET /storage/v1/object/:bucket/:path` y `.../public/:bucket/:path` sirven cualquier
  fichero por lookup de nombre; **la columna `public` del bucket nunca se lee**. Buckets afectados:
  `chat-media`, `group-audio`, **`report-evidence`** (evidencia de moderación).
- **Causa raíz:** al reemplazar el RLS de Supabase, el control de acceso a Storage quedó sin
  implementar en la ruta de servido.
- **Impacto:** cualquiera con (o adivinando) la ruta del objeto descarga medios privados de chats,
  audios de grupo y **evidencias de reportes**. Las rutas son semi-predecibles
  (`${userId}/${Date.now()}-file`).
- **Solución:** aplicar el flag `public`; si es privado, exigir `authRequired` + verificación de
  propiedad/participación (reusar `identityOwnsRow`/membresía de chat). Gate inmediato para
  `report-evidence`, `chat-media`, `group-audio`. *Prioridad:* **Crítica — antes del lanzamiento.**

### 4.2 Docker / Compose
- **(Alto)** Contenedores como **root**: `api/Dockerfile` y `realtime-gateway/Dockerfile` no fijan
  `USER node`; `ai-chatbot/Dockerfile:15` hace `USER root` explícito. *Solución:* `USER node` +
  arreglar propiedad de volúmenes. *Prioridad:* Alta.
- **(Alto)** **Sin límites de memoria/CPU/PID** en 5 de 6 servicios (`db`, `api`, `signaling`,
  `edge`, `realtime-gateway`). Un request desbocado puede OOM-matar todo el mini-PC (incluida la BD).
  *Solución:* `mem_limit`/`cpus`/`pids_limit` por servicio. *Prioridad:* Alta.
- **(Medio)** Secreto placeholder de 36 chars pasa el check de longitud: `.env`
  `JWT_SECRET=change-this-with-openssl-rand-hex-32` y `server.js:58-60` solo comprueba `< 32`.
  *Solución:* rechazar valores del ejemplo explícitamente. *Prioridad:* Alta.
- **(Medio)** `coturn` con `network_mode: host` sin usuario no-root — máximo radio de impacto si se
  compromete. *Solución:* aislar en VPS dedicado. *Prioridad:* Media.
- **(Medio)** Ficheros `*.remote` (snapshots viejos del VM) dentro de `api/src/` entran en la imagen
  vía `COPY src ./src` (código obsoleto sin rate-limit horneado). *Solución:* moverlos fuera del
  contexto de build. *Prioridad:* Media.
- **(Buena práctica)** Sin tags `:latest` (todas las imágenes pineadas); healthchecks +
  `restart: unless-stopped` en todos; puertos ligados a `127.0.0.1`; rotación de logs en `api`/
  `ai-chatbot`.

### 4.3 API (Node/Express)
- **(Medio)** **Admins hardcodeados fail-open:** `admin-moderation-routes.js:125-134`
  `DEFAULT_ADMIN_EMAILS` (3 Gmail literales) concede admin si `ADMIN_EMAILS` no está configurado.
  *Solución:* fail-closed (0 admins) + warning de arranque. *Prioridad:* Alta.
- **(Medio)** **Rate limiting in-memory** (`request-security.js:35-71`) — se reinicia en cada
  deploy/crash y no escala horizontalmente. *Solución:* mover a Postgres/Redis al escalar. *Prioridad:* Media.
- **(Bajo)** `CORS_ORIGIN=*` en el `.env` de dev; sin `credentials`, auth por Bearer (no CSRF
  clásico), pero conviene **rechazar arranque en producción si `CORS_ORIGIN=*`**. *Prioridad:* Media.
- **(Bajo)** Subidas hasta 512 MB sin **cuota por usuario** → agotamiento de disco por un solo
  usuario. *Solución:* cuota acumulada por propietario. *Prioridad:* Media.
- **(Buena práctica)** Consultas **parametrizadas** (identificadores por whitelist regex), JWT con
  `issuer`+`audience`, bcrypt coste 12, errores 500 sin filtrar stack traces, sin patrones IDOR
  (identidad siempre del JWT server-side).

### 4.4 Postgres / esquema
- **(Medio)** Migraciones **no automatizadas**: solo `db/init/001_schema.sql` corre en primer
  arranque; `002/003` se aplican a mano y **no hay tabla `schema_migrations`** que registre estado.
  *Solución:* runner de migraciones + tabla de control. *Prioridad:* Media.
- **(Medio)** **Sin equivalente a RLS** en la BD (autorización 100% en código de ~14k líneas de
  rutas) → cada consulta es un punto potencial de bypass sin red de seguridad. *Solución:* auditoría
  scriptada de scoping por usuario + wrapper de consulta. *Prioridad:* Media.
- **(Buena práctica)** `CHECK` enums, `email CITEXT UNIQUE`, índices en hot paths; migraciones
  idempotentes (`ADD COLUMN IF NOT EXISTS`).

### 4.5 Nginx / proxy
- **(Medio)** **Sin cabeceras de seguridad** (HSTS, CSP, X-Content-Type-Options, X-Frame-Options) en
  `nginx/default.conf`. *Prioridad:* Media.
- **(Medio)** **Sin rate limiting** a nivel proxy (`limit_req`/`limit_conn`). *Prioridad:* Media.
- **(Bajo)** `client_max_body_size 512m` global aplicado a todos los endpoints (incl. WS). *Solución:*
  default pequeño + subir solo en rutas de subida. *Prioridad:* Baja.
- **(Buena práctica)** Timeouts largos para WS + headers `Upgrade` correctos; bloqueo de
  `/realtime/internal/`.

### 4.6 Realtime gateway / señalización WS (Alto)
- **(Alto)** `realtime-gateway` `POST /internal/publish` **sin secreto compartido** y canal
  `admin:live` **sin check de admin en subscribe** (`server.js:143-168,216-222`). Cualquier
  contenedor en la red interna (o `api`/`ai-chatbot` comprometido) puede **falsificar broadcasts
  admin**. Nginx bloquea la ruta pública y el puerto es loopback, pero la superficie interna queda
  abierta. *Solución:* header `X-Internal-Token` + verificación de rol admin. *Prioridad:* Alta.
- **(Alto)** **Sin escalado horizontal:** estado de rooms/clientes en memoria de un solo proceso
  (`realtime-gateway` y `signaling-server`: `rooms/calls/lives` Maps). Correr >1 réplica **rompe**
  pub/sub y señalización cruzada. Sin `@socket.io/redis-adapter`. *Solución:* adaptador Redis antes
  de escalar. *Prioridad:* Alta si hay plan de escalar; si no, documentar "1 sola instancia" como
  restricción dura.
- **(Medio)** `realtime-gateway` sin `maxPayload` en el `ws.Server` ni rate-limit por-mensaje.
  *Prioridad:* Media.
- **(Buena práctica)** `signaling-server` autentica cada socket contra el backend, valida `userId`
  reclamado y exige compartir scope (room/call/live) antes de reenviar `offer/answer/ice` — previene
  el IDOR clásico de señalización.

### 4.7 Push notifications
- **(Medio)** `push-notifications.js:88-137` sin retry/backoff ante fallos transitorios de FCM (la
  notificación se pierde). Limpia tokens inválidos correctamente. *Solución:* retry con backoff.
  *Prioridad:* Media. **(Buena práctica)** credenciales por fichero read-only, init tolerante a fallo.

### 4.8 TURN / coturn
- **(Buena práctica)** Credenciales **efímeras HMAC** (patrón REST de coturn, TTL 3600s); el secreto
  no sale del servidor.
- **(Medio)** **Un solo relay coturn** con rango UDP de **solo 40 puertos** (49160-49200), sin
  failover, potencialmente en CGNAT doméstico → llamadas de usuarios en NAT simétrica **fallarán**.
  *Solución:* ampliar rango, TURN público real (VPS), failover `TURN_STATIC_URLS`. *Prioridad:* Alta.

### 4.9 Scripts de despliegue
- **(Buena práctica)** `set -Eeuo pipefail` en todos; `backup-ubuntu-primary.sh` con `flock`,
  escritura atómica, verificación (`pg_restore --list`/`tar -tzf`) y `SHA256SUMS`; export cifrado
  con `openssl aes-256-cbc -pbkdf2`. Sin credenciales hardcodeadas ni `curl | bash`.
- **(Bajo)** `export-mini-pc-migration.sh` usa CBC (sin autenticación); preferir GCM. `configure-
  mini-pc-turn.sh` reescribe `.env` sin backup previo. *Prioridad:* Baja.

### 4.10 Backup/recuperación — sin copia fuera de sitio (Crítico)
- **Evidencia:** `backup-ubuntu-primary.sh:5` escribe en `$HOME/conectate-backups` (**misma máquina**
  que corre Postgres/storage). Existe `scripts/conectate-sync-external-backups.sh` que replica vía
  `rsync` a `/mnt/conectate-media|archive|backup` — pero **son discos USB del mismo mini-PC**
  (`format-conectate-backup-disk-ext4.sh` formatea `/dev/sdb1` local). **No hay paso `rclone`/`scp`/
  `s3` a una ubicación remota** (grep confirmó 0 en los scripts de backend).
- **Causa raíz:** estrategia de DR limitada a redundancia de disco local.
- **Impacto:** un incendio/robo/ransomware/fallo de hardware del mini-PC = **pérdida total e
  irrecuperable** de los datos de producción. Protege contra fallo de un disco, no contra pérdida de
  la máquina.
- **Solución:** paso automatizado de copia cifrada **fuera de sitio** (rclone/B2/S3) encadenado tras
  la verificación local, en el timer de backup. *Prioridad:* **Crítica — antes del lanzamiento.**
- **(Buena práctica)** `restore-drill.sh` **prueba restauración real** semanal en BD desechable —
  disciplina excelente; solo falta la pata offsite.

---

## 5. RENDIMIENTO / MEMORIA / CONCURRENCIA

- **(Operativo)** `gradle.properties`: `org.gradle.parallel=false`, `workers.max=1`,
  `configureondemand=false`, `dependency.verification=off`. Trade-off de memoria que hace los builds
  **muy lentos** (coste operativo para hotfixes frecuentes en producción). *Prioridad:* Baja/Media.
- **Web:** bundle razonable con code-splitting; sin dependencias de peso evidentes mal cargadas.
- **Señalización/WS:** ver §4.6 (memoria in-memory sin cota superior absoluta; escalado horizontal
  roto). Voz en **malla (mesh)** con cap de **50** participantes (`VoiceRoomRepository.kt:84`) —
  irreal para mesh (límite práctico ~8); a partir de ~8 hay degradación severa/ANR en gama media.
  *Solución:* bajar el cap o introducir SFU. *Prioridad:* Media-Alta.

---

## 6. WEBRTC / LLAMADAS / VOZ / PANTALLA / CÁMARA (resumen)

- **(Buena práctica)** Android implementa **negociación perfecta completa** (makingOffer,
  ignoreOffer, rollback, glare-recovery, ICE-restart con backoff y cap). El commit `bb86121` alineó
  el tie-break polite/impolite del web con Android — **verificado simétrico**.
- **(Alto)** **Web sin rollback/ignore** en colisión de ofertas (`VoiceRoomPage.tsx:839-863`,
  `WebCallManager.ts:565-588`): solo evita glare por asignación de rol; cualquier renegociación
  futura (ICE restart, `addTrack`, multi-tab) dará `InvalidStateError` sin auto-recuperación.
  *Prioridad:* P1.
- **(Alto)** **Web sin ICE-restart/reconexión** tras `failed`/`disconnected` (solo etiqueta
  "Reconectando…"): audio muerto permanente hasta salir/reentrar. Android sí recupera. *Prioridad:* P1.
- **(Alto)** **Toggle de vídeo/screen-share en web via `addTrack` no renegocia** (no hay
  `onnegotiationneeded`) → el peer remoto nunca ve el vídeo. *Prioridad:* P1.
- **(Alto)** **STUN-only por defecto** en ambas plataformas si falla el fetch de TURN → usuarios en
  NAT simétrica/CGNAT se quedan en "CONNECTING". *Prioridad:* P2 (ligado a §4.8).
- **(Medio)** Live-namespace acepta host si `hostUserId` está vacío (`signaling-server/index.js:
  1276-1281`). *Prioridad:* P2.
- **(Medio)** Screen-share web no detiene la cámara reemplazada → **luz de privacidad encendida**
  durante todo el screen-share. *Prioridad:* P3.
- **(Bajo)** `useWebRTC.ts` es una 3ª implementación WebRTC **muerta** con un `ReferenceError` en
  detección de habla (línea 163). *Solución:* borrar. *Prioridad:* P3.
- **Cifrado:** DTLS-SRTP por defecto, **no se desactiva** en ningún sitio (verificado). Sin grabación
  server-side (arquitectura P2P/mesh, el servidor solo señaliza).

---

## 7. SEGURIDAD / DATOS / HIGIENE DEL REPO

### 7.1 PII de producción en git (Crítico)
- **Evidencia:** `IMPORTACION DE SUPABASE POR CSV/` (54 CSV **rastreados en git**) con exportaciones
  reales: `users_rows.csv`, `profile_login_emails_rows.csv` (emails), `profiles_rows.csv` (teléfono,
  nombre real, ciudad), `messages_rows.csv` (contenido de mensajes),
  `security_audit_events_rows.csv` (logs de seguridad). **Sin `.gitignore`** en esa carpeta (a
  diferencia de `infrastructure/local-fallback/imports/`, que sí lo tiene).
- **Causa raíz:** exportación de datos volcada en la raíz del repo y commiteada.
- **Impacto:** **fuga de PII en el historial de git** (persiste aunque se borre hoy). Riesgo GDPR/
  protección de datos y de credential-stuffing. Crítico.
- **Solución:** (1) `git rm` + añadir `IMPORTACION DE SUPABASE POR CSV/*.csv` al `.gitignore`; (2)
  **reescribir historia** (git filter-repo/BFG) para purgar los blobs; (3) **rotar** toda credencial/
  token que aparezca en esos datos. *Prioridad:* **Crítica.**

### 7.2 Bloat del repositorio (Alto)
- **(Crítico de higiene)** `tmp/` (**1.6 GB**, incluye **perfiles completos de Chrome**) y
  logcats/crash dumps multi-MB (`logcat_emulator.txt` 24 MB, `logcat_final_test.txt` 6.3 MB,
  `logcat_physical.txt` 5.1 MB, `logcat.txt` 2.3 MB, `crash_log.txt`) están **rastreados en git**.
- **(Alto)** Carpetas de backup rastreadas: `backup/` (127 archivos), `backups/` (9),
  `app_backup_before_restore/` (418). Sin rastrear pero en disco: `.worktrees/` (2.4 GB),
  `.codex-worktrees/` (470 MB), `.codex-backups/` (105 MB). `.git` = 220 MB.
- **Impacto:** cada clon arrastra decenas de MB de logs + 1.6 GB de `tmp/`; indexado de IDE y
  herramientas lento (varias búsquedas de esta auditoría expiraron por el árbol gigante).
- **Solución:** `git rm -r --cached tmp/` + logs; `.gitignore` para `tmp/`, `*.log`, `logcat*.txt`,
  `temp_*`, `hs_err_pid*.log`; eliminar del disco los worktrees/backups abandonados; considerar
  `git filter-repo`. *Prioridad:* Alta.

### 7.3 Clutter de raíz (Bajo)
- 43 `fix_*.ps1` (~64 KB) + `patch_*`/`temp_*` (varios sin rastrear, `temp_data-routes.js` 383 KB) +
  ~30 `AUDITORIA_*/SOLUCION_*/IMPLEMENTATION_*.md`. Señal de fixes ad-hoc reactivos. *Prioridad:* Baja.

### 7.4 Barrido de secretos (Informativo — limpio)
- **0 secretos hardcodeados** en código vivo (`app/src/main`, `Conectate-web/src`): sin `AKIA`,
  `sk_live`, `AIza`, JWTs, PEM. `secrets.properties`/keystores **gitignorados y no rastreados**.
- **(Informativo)** `Conectate-web/public/backend-config.json` apunta a un túnel efímero
  `*.trycloudflare.com` (fragilidad de infra, no secreto); `BACKEND_DISCOVERY_HOST` = duckdns de un
  individuo.

### 7.5 Código muerto/deprecado
- `firebase/MyFirebaseMessagingService.kt` es un stub `@Deprecated` no cableado (el real es
  `.data.service.MyFirebaseMessagingService`). Borrar. **TODO/FIXME reales: 0** en código vivo
  (buena señal). *Prioridad:* Baja.

---

## 8. PLAN DE ACCIÓN PRIORIZADO (para lanzamiento a miles de usuarios)

**Bloqueantes — antes de lanzar (Crítico):**
1. **C2** Cerrar el acceso sin auth a buckets privados (`report-evidence`/`chat-media`/`group-audio`). [§4.1]
2. **C3** Añadir backup **fuera de sitio** automatizado. [§4.10]
3. **C1** Purgar PII del historial de git + rotar credenciales. [§7.1]
4. **C4** Decisión producto/legal sobre el reproductor de streaming (riesgo de baneo de Play). [§3.9]
5. **C5** Confirmar por build que el fix de migración Room compila (Debug+Release). [§3.5] *(hecho/verificado en rama de estabilización)*

**Alto — antes de escalar / semana 1:**
6. Secreto interno en `realtime-gateway /internal/publish` + check admin en `admin:live`. [§4.6]
7. Contenedores no-root + límites de recursos. [§4.2]
8. Rechazar secretos placeholder y `CORS_ORIGIN=*` en arranque; quitar admins fail-open. [§4.3]
9. WebRTC web: rollback de negociación + ICE-restart + renegociación de `addTrack`. [§6]
10. TURN público real con failover (o las llamadas fallarán en CGNAT). [§4.8]
11. Deep link de recuperación por App Link verificado. [§3.3]
12. Limpieza de bloat de git (`tmp/`, logcats, backups). [§7.2]

**Medio — deuda técnica estructural:**
13. Test de migración Room en CI (previene regresión de §3.5). [§3.6]
14. Cabeceras de seguridad + rate limit en Nginx. [§4.5]
15. Adaptador Redis para escalado horizontal (o documentar 1-instancia). [§4.6]
16. Terminar/abortar migración Supabase; acotar reglas ProGuard; subir Kotlin/Compose; quitar
    librerías alpha/beta. [§1, §3.1, §3.2]
17. Cap realista de salas de voz (mesh ~8) o SFU. [§5]

**Bajo:** limpiar código muerto (buzzword dirs, stub deprecado, `useWebRTC.ts`), FileProvider
acotado, certificate pinning, retry de push. [§7.5, §3.3, §3.10, §4.7]

---

## 9. NOTA DE HONESTIDAD TÉCNICA

- Las categorías que la instrucción pedía "corregir primero" (WebRTC/R8/ProGuard/JNI/@CalledByNative/
  Foreground Services **como fuente de crash de Release**) se verificaron contra el código y los logs
  reales y resultaron **falsos positivos o ya-manejadas** — no hay crash reproducible de esas
  categorías. Los crashes reales son de **migración Room** (ya corregido/verificado) y **navegación
  de notificación** (guard aplicado). Detalle en `CORRECCIONES_REALIZADAS.md`.
- Las funciones que requieren **dos dispositivos físicos** (llamadas, salas de voz, screen-share
  reales) **no se declaran funcionando** — solo se auditó su código; requieren prueba real.
- No se modificó PostgreSQL, Nginx ni producción; no se desplegó nada; los hallazgos de backend son
  de análisis estático de código/config, no de un pentest en vivo.
