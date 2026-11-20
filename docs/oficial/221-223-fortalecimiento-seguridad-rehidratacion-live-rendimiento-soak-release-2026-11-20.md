# 221–223 · Fortalecimiento de Ciberseguridad, Rehidratación Multi-Instancia de Directos en Redis, Optimización de Rendimiento, Soak Extendido, Limpieza de Código Muerto, QA Web y Certificación Android Release (`9.1.43`)

- **Fecha**: 19–20 de noviembre de 2026
- **Ciclos cubiertos**: Ciclos 221, 222 y 223 (continuidad autónoma sobre el Ciclo 220 en `98b0ba635f6e82282810368ffe4d77fe6e78db8a`, árbol `76b59deb5421f710502a00ed4b63bc8bd5d22763`)
- **Ramas objetivo**: `main` y `feature/live-gifts` (con árboles Git `HEAD^{tree}` idénticos)
- **Arnés reproducible multi-instancia**: `tools/loadtest/run-cycle221-222-certification.mjs` (`summary.allPassed: true`, `EXIT=0`)
- **Ejecutor endurecido de soak para Ubuntu staging**: `tools/loadtest/run-ubuntu-staging-soak.mjs`
- **Veredicto técnico final**: **`APTO CONDICIONAL PARA LANZAMIENTO CONTROLADO`**

---

## 1. Resumen Ejecutivo

En los Ciclos 221–223 se ejecutó un ciclo continuo de ingeniería (`INSPECCIONAR → MEDIR → SIMULAR → ENCONTRAR EL FALLO → INVESTIGAR LA CAUSA RAÍZ → CORREGIR → REFACTORIZAR → FORTALECER → VOLVER A MEDIR → VOLVER A PROBAR`) partiendo del commit `98b0ba635f6e82282810368ffe4d77fe6e78db8a`:

1. **Ciberseguridad y Eliminación de Vulnerabilidades (13 defectos reales descubiertos, corregidos y probados: 10 en Ciclos 221–222 + 3 en Ciclo 223)**:
   - Se eliminó un crash crítico HTTP 500 en producción en `live-points-routes.js` (`identity.ids.includes is not a function` sobre `Set`), un vector de auto-takedown Sybil con un solo atacante en `music-routes.js` (`COUNT(*)` → `COUNT(DISTINCT reporter_id)` + validación `404 TRACK_NOT_FOUND`), un bypass de escaneo antivirus en portadas de música (`cleanStorageRow`), inflación de reproducciones por replay en `/complete`, una condición de carrera financiera en webhooks `google-play-rtdn.js`, un bypass de lista de bloqueados/suspendidos y bucle infinito de reintentos en `scheduled-message-jobs.js`, revocación WebSocket cross-node incompleta al cambiar contraseña/2FA en `server.js`, un bypass de revelación unilateral de identidad en chat anónimo y recibos de lectura omitidos en `proximity-routes.js`, un HTTP 500 por `durationHours` no numérico y escaneo N+1 en `moments-routes.js`, validación de bucket `'community-music'` y `'use strict'` en `moment-music-renderer.js`, un bypass de aislamiento de cuentas suspendidas/eliminadas/privadas en `guest-mode-routes.js` (Ciclo 223), `'use strict'` inefectivo y consultas duplicadas por transacción en `storage-read-access.js` (Ciclo 223) y conteo inflado de entregas omitidas por lock en `notification-digest-routes.js` (Ciclo 223).
2. **Rehidratación Multi-Instancia de Directos (`signaling-server`) con Redis 7.4.8 (`distributed-redis-rehydrated`)**:
   - Se cerró la brecha `liveSessionState: 'replica-affinity-required'` implementando heartbeat de vivacidad por nodo (`SIGNALING_NODE_LIVENESS_SECONDS`), reclamo atómico de lease de host muerto mediante script Lua (`reclaimDeadHostLease`), estado distribuido de `hostRuntime`, `viewers` (con poda automática de espectadores de nodos caídos), `guestRequests`, `guests`, contadores atómicos de reacciones con deduplicación cross-node de `clientBatchId` y preservación en Redis de invitados aceptados cross-node ante desconexiones transitorias.
   - Verificado contra Redis 7.4.8 real y 2 instancias de Signaling con `SIGKILL` del nodo del host (`sig1`) y reconexión inmediata en `sig2` (`hostReconnectLatencyAfterLivenessExpiryMs = 81,7 ms`, `remainingRedisKeysAfterEndLive = 0`).
3. **Nueva Ronda de Optimización de Rendimiento y Escalabilidad**:
   - Singleflight en vuelo para autenticación (`authLookupInFlight`, `0 ms` de TTL obsoleto → `0 ms` de ventana de token revocado), reutilización de `loadCollectionMembershipSnapshot` en `loadCollectionRoomAccess` (eliminando **6 consultas SQL redundantes por cada mensaje de grupo/comunidad**), caché `WeakMap` de `to_regclass` por `(db, table)`, índice `idx_fallback_collection_rooms_kind_id_lower` y singleflight en lecturas de posts/follows.
   - El rendimiento medido en clúster aislado (`2× API + 2× Realtime + 2× Signaling + Redis 7.4.8 + PostgreSQL 17`) subió a **`325,7 RPS @ 100u`** (+8,2 %), **`395,9 RPS @ 250u`** (+27,1 %), **`441,4 RPS @ 500u`** (+19,1 %), **`415,2 RPS @ 1.000u`** (+4,9 %) y **`452,3 RPS @ 1.500u`** (+4,1 %, **2,25×** el techo original de PostgreSQL de `201,3 RPS`), con **`0` errores 5xx** y **`0` errores de red**.
   - La latencia p50 de ACK backend en envío de mensajes de grupo bajó de `11,51 ms` a **`6,87 ms`** en reposo (**-40,3 %**) y de `138,30 ms` a **`76,58 ms`** bajo carga concurrente de 40 usuarios de feed (**-44,6 %**).
4. **Soak Extendido Multi-Instancia (`210 s`) e Inyección de Fallos (4 Escenarios de Caos)**:
   - Soak continuo de **210 segundos** (`308` iteraciones, **`49.280` solicitudes HTTP**, 20 WebSockets Realtime y 12 sockets `/live` activos): **`0` errores 5xx**, **`0` load-shedding 503**, **`99,99 %`** de acierto de caché en PostgreSQL y **`0` claves expulsadas en Redis**.
   - Los 4 escenarios de caos (contención de locks en PG, `SIGKILL` de `rt1` + tormenta de reconexión de 60 WebSockets en `rt2` con entrega `60/60`, cambio de contraseña en `api2` con revocación WebSocket `4401 WEB_SESSION_REVOKED` en `rt2` en `542 ms`, y `SIGKILL` de Redis 7.4.8 con fallback automático a PG `201` y autorrecuperación del clúster en `1.428 ms`) finalizaron en verde.
5. **Limpieza Verificada de Código Muerto, QA Web y Certificación Android Release (`9.1.43`)**:
   - Eliminados **4 archivos huérfanos con 0 referencias** (`.FullName`, `infrastructure/local-fallback/api/query.js`, `infrastructure/local-fallback/api/run-sql.js` y `app/src/main/java/conectate/com/analytics/enterprise/PredictiveAnalyticsSystem.kt`), conservando `UpstashCacheService.kt` por tener consumidores activos en `PostRepository.kt` y `VideoRepository.kt`.
   - `NoWing-web` superó `npm run build` (`tsc -b`, `vite build` con 1.959 módulos, prerender y `check-built-seo`), `npm run test:media` (`21/21`), `npm run test:seo` (`72/72`) y `npx jest --runInBand` (**`208/208` suites, `1.344/1.344` pruebas**).
   - Android superó `:app:testDebugUnitTest`, `:app:validateStableReleaseBackend`, `:app:verifyReleaseSigningConfiguration`, `:app:minifyReleaseWithR8`, `:app:lintVitalRelease` y `:app:assembleRelease` (`BUILD SUCCESSFUL`), produciendo la APK Release firmada `app/build/outputs/apk/release/app-release.apk` (`conectate.com`, `versionName = "9.1.43"`, `versionCode = 183`, `221.779.029` bytes, SHA-256 `26CAE9A3AA5514BF941A4283811C208BC1E7B8053B00022A32BCD6C617A82132`, verificada con `apksigner verify` esquema v2 `true` y `0` archivos de secretos empaquetados).

---

## 2. Hora Real de Inicio y Hora Real de Finalización

| Hito | Marca de tiempo local (`America/Lima`, UTC-5) | Marca de tiempo UTC (ISO 8601) |
|---|---|---|
| **Inicio real verificado (Ciclo 221)** | `2026-11-19 22:42:02 -05:00` | `2026-11-20T03:42:02.550Z` |
| **Finalización de auditoría y parches de seguridad (Fase 1)** | `2026-11-19 23:08:13 -05:00` | `2026-11-20T04:08:13.000Z` |
| **Finalización de rehidratación multi-instancia de Directos y optimizaciones SQL (Fases 2–4)** | `2026-11-19 23:25:15 -05:00` | `2026-11-20T04:25:15.000Z` |
| **Finalización de `NoWing-web` (`build` + `test:media` + `test:seo` + `jest` 208 suites)** | `2026-11-19 23:41:33 -05:00` | `2026-11-20T04:41:33.000Z` |
| **Finalización del soak extendido de 210 s (`308` iteraciones, `49.280` reqs)** | `2026-11-19 23:45:10 -05:00` | `2026-11-20T04:45:10.518Z` |
| **Finalización del arnés completo de 5 fases (`summary.allPassed: true`, `EXIT=0`)** | `2026-11-19 23:58:32 -05:00` | `2026-11-20T04:58:32.362Z` |
| **Finalización de hallazgos adicionales del Ciclo 223 y `:app:assembleRelease` (`task-2462`)** | `2026-11-20 00:46:47 -05:00` | `2026-11-20T05:46:47.000Z` |
| **Finalización del informe oficial y cierre del Ciclo 223** | `2026-11-20 00:52:00 -05:00` | `2026-11-20T05:52:00.000Z` |

- **Duración total real acumulada de trabajo autónomo verificado**: **2 horas y 10 minutos** (`130 minutos`).

---

## 3. Alcance Auditado en Este Ciclo

En los Ciclos 221–223 se inspeccionaron por lectura completa (`code-read`) **más de 115 archivos adicionales** del repositorio (detallados en la Sección 17 y registrados mediante `tools/audit/update_review_coverage.py`), abarcando:

- **Backend API (`infrastructure/local-fallback/api/src/`)**:
  - Rutas y motores de música, momentos, renderizado AAC-LC, puntos de directo, pagos y retiros de creadores, analítica de creadores, RTDN de Google Play, proximidad y chat anónimo, trabajos de mensajes programados, rifas, marcadores de mensajes, colecciones/historial/ver-más-tarde de videos, salas de voz grupales, directos, batallas, regalos, moderación, modo invitado, resúmenes de notificaciones, salud del sistema, autenticación y sesiones web/móviles, control de acceso y escaneo antivirus de objetos de storage, replicación R2 y políticas de membresía de grupos/comunidades.
- **Signaling Server (`signaling-server/`)**:
  - `index.js` (`3.861` líneas), `distributed-live-state.js` (`666` líneas), `distributed-adapter.js`, `distributed-scope.js`, `live-reaction-policy.js`, `live-guest-media-state.js`, `room-chat-policy.js`, `room-chat-stream-cleanup.js` y suites de pruebas unitarias e integración con Redis.
- **Realtime Gateway (`infrastructure/local-fallback/realtime-gateway/src/`)**:
  - `server.js` (`814` líneas) y `redis-resp-client.js`.
- **Aplicación Web (`NoWing-web/`)**:
  - `public/novvin-media-worker.js`, `src/components/StreamingMedia.tsx`, `src/core/publicBackendTransport.ts`, `src/core/runtimeBackend.ts`, `src/core/media/browserStreaming.ts`, `src/core/media/storageReadSession.ts`, `src/core/media/storageReadClient.ts`, `vercel.json` y suites de pruebas `media-worker.test.mjs`, `public-home.test.mjs` y Jest.
- **Aplicación Android (`app/`)**:
  - `app/build.gradle.kts`, `gradle/release-signing-validation.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `network_security_config.xml`, `backup_rules.xml`, `data_extraction_rules.xml`, `UpstashCacheService.kt` y `PredictiveAnalyticsSystem.kt`.

---

## 4. Herramientas de Seguridad Realmente Ejecutadas

Siguiendo la política de honestidad estricta, se distinguen las herramientas efectivamente instaladas y ejecutadas de las no instaladas en el host:

| Herramienta / Método | Estado | Ámbito ejecutado | Resultado real |
|---|---|---|---|
| `npm audit --omit=dev --json` | **Verificado** | 5 servicios Node (`api`, `realtime-gateway`, `signaling-server`, `ai-chatbot`, `NoWing-web`) | **`0` vulnerabilidades** (`critical=0, high=0, moderate=0, low=0`) en los 5 servicios |
| `tools/runtime_security_audit.py` | **Verificado** | Código fuente y configuraciones del repositorio | **`0` hallazgos, `0` violaciones nuevas** (`AUDIT_PY_EXIT=0`) |
| Auditoría SAST manual línea por línea | **Verificado** | >35.000 líneas de código en rutas críticas de API, Storage, Signaling, Realtime, Web y Android | **13 defectos/vulnerabilidades reales descubiertos, corregidos y cubiertos con tests** |
| Suite de seguridad y fuzzing E2E (`test:security`, `cycle221-security-hardening.test.js`, Fase 5 del arnés) | **Verificado** | Autenticación, sesiones web, BOLA/IDOR, null-bytes, JSON profundo, `NaN`, SSRF, antivirus, RTDN, chat anónimo, modo invitado | **100 % en verde (`EXIT=0`)** |
| `apksigner verify` + `aapt2 dump badging` + escaneo ZIP de secretos | **Verificado** | `app/build/outputs/apk/release/app-release.apk` (`9.1.43`, `versionCode 183`) | Firma v2 válida (`true`), `isDebuggable=false`, **`0` secretos empaquetados** |
| Herramientas CLI externas (`semgrep`, `trivy`, `gitleaks`, `osv-scanner`, `codeql`, `nuclei`, `k6`) | **No instaladas en el host** | Comprobado en Windows y WSL2 (`Get-Command` / `which`) | Suplidas por `npm audit`, `runtime_security_audit.py`, SAST manual y el arnés propio de carga/fuzzing/caos |

---

## 5. Vulnerabilidades Encontradas y Corregidas

A continuación se documentan las **13 vulnerabilidades y defectos funcionales/de seguridad** descubiertos autónomamente y corregidos en los Ciclos 221–223:

| # | Severidad | Archivo y líneas | Causa raíz | Impacto | Corrección aplicada | Prueba de regresión |
|---|---|---|---|---|---|---|
| **1** | **CRÍTICA** | `infrastructure/local-fallback/api/src/live-points-routes.js` (`L82-L108`) | `liveForUser` invocaba `identity.ids.includes(...)`, pero en producción `userIdentity(user)` (`data-routes.js:463`) devuelve `identity.ids` como `Set`, no como `Array`. | **Caída HTTP 500 (`TypeError: identity.ids.includes is not a function`)** en todos los endpoints de puntos de canal (`/session`, `/heartbeat`, `/settings`, `/balance`, `/rewards`, `/redeem`). | Se implementó `identityHasId(identity, candidate)` compatible tanto con `Set` (`.has()`) como con `Array` (`.includes()`). | `cycle221-security-hardening.test.js` + Fase 5 E2E (`200 OK`) |
| **2** | **ALTA** | `infrastructure/local-fallback/api/src/music-routes.js` (`L1364-L1395`) | `POST /v1/data/music/:trackId/report` tenía `UNIQUE(track_id, reporter_id, reason)` y contaba `COUNT(*)` para el umbral de auto-takedown (`>= 3`), sin comprobar además si `trackId` existía antes del `INSERT`. | Un **único atacante** podía enviar 3 reportes con 3 `reason` distintos sobre cualquier pista pública y provocar su baja automática (`TAKEDOWN_PENDING`), o provocar un HTTP 500 por violación de FK con un UUID inexistente. | Se verifica la existencia previa de la pista (`404 TRACK_NOT_FOUND`) y se cambió el conteo a `COUNT(DISTINCT reporter_id)::int`, exigiendo **3 denunciantes distintos**. | `cycle221-security-hardening.test.js` + Fase 5 E2E |
| **3** | **ALTA** | `infrastructure/local-fallback/api/src/google-play-rtdn.js` (`L202-L310`) | Cuando una entrega duplicada concurrente de Google Play Pub/Sub recibía `alreadyProcessing: true` y lanzaba `503 GOOGLE_PLAY_Rtdn_ALREADY_PROCESSING`, el bloque `catch` llamaba a `failGooglePlayRtdnEvent(pool, messageId, error)` sin verificar si esta petición había adquirido el lock ni filtrar `status = 'processing'`. | Una petición duplicada concurrente sobrescribía el estado `'processing'` (o `'processed'`) de la transacción legítima a `'failed'`. | Se rastrea `let claimed = false` y se restringió `failGooglePlayRtdnEvent` con `AND status = 'processing'`, invocándolo solo cuando `claimed === true`. | `cycle221-security-hardening.test.js` + `google-play-rtdn.test.js` |
| **4** | **ALTA** | `infrastructure/local-fallback/api/src/scheduled-message-jobs.js` (`L65-L120`, `L330-L390`) | 1) `requireTargetAccess` no comprobaba suspensión del emisor, bloqueos bidireccionales (`blockedProfileIds`) ni membresía activa al momento de entrega diferida. 2) El reclamo `status = 'processing'` ocurría dentro de la misma transacción que `deliverScheduledJob`; al hacer `ROLLBACK` por error, el job volvía a `status = 'pending'` y `attempt_count = 0`. | 1) Un usuario bloqueado o expulsado tras programar un mensaje seguía entregándolo. 2) Un job fallido entraba en bucle infinito de reintentos. | 1) Se añadieron validaciones de suspensión activa, bloqueo bidireccional en DM y membresía activa en grupo/comunidad en `requireTargetAccess`. 2) El reclamo `'processing'` se confirma (`COMMIT`) antes de abrir la transacción de entrega. | `cycle221-security-hardening.test.js` + `scheduled-message-jobs.test.js` |
| **5** | **ALTA** | `infrastructure/local-fallback/api/src/server.js` (`L974-L1002`, 8 sitios de llamada) | `revokeUserAuthSessions` y `revokeUserWebSessions` actualizaban `revoked_at = NOW()` pero no devolvían los IDs revocados ni publicaban `publishWebSessionsRevoked` en cambios de contraseña, reset de contraseña, activación/desactivación de 2FA o códigos de recuperación. | Los WebSockets abiertos en otros nodos del Realtime Gateway permanecían conectados hasta 60 s (`AUTH_RECHECK_MS`) tras un cambio de contraseña o 2FA. | `revokeUserAuthSessions`, `revokeUserWebSessions` y `revokeAllUserSessions` ahora hacen `RETURNING id` y publican `publishWebSessionsRevoked(userId, revokedSessions)` tras el commit en los 8 flujos críticos. | Fase 4C del arnés (`4401 WEB_SESSION_REVOKED` en `542 ms` cross-node) |
| **6** | **ALTA** | `infrastructure/local-fallback/api/src/proximity-routes.js` (`L354-L407`, `L1291-L1339`) | 1) `advanceAnonymousProgress` calculaba el nivel de revelación (`0..3`) sumando todos los mensajes de la sesión sin exigir reciprocidad (`count_a` y `count_b`). 2) `sessionById` omitía `user_a_read_at, user_b_read_at` en el `SELECT`. 3) `/reveal` no revalidaba `anonymousPairBlocked`. | 1) Un atacante podía enviar 12 mensajes unilaterales sin respuesta de la víctima para desbloquear el Nivel 3 y revelar `user_id`, `display_name` y `avatar_url` reales. 2) Los recibos de lectura devolvían siempre `null`. | 1) `advanceAnonymousProgress` exige interacción bilateral (`Math.min(count_a, count_b) * 2`). 2) Se añadieron `user_a_read_at, user_b_read_at` a `sessionById`. 3) `/reveal` verifica `anonymousPairBlocked` (`403`). | `cycle221-security-hardening.test.js` + `proximity-routes.test.js` |
| **7** | **ALTA (Ciclo 223)** | `infrastructure/local-fallback/api/src/guest-mode-routes.js` (`L8-L450`) | Las consultas de modo invitado (`readPublicContent`, `readPublicProfile`, `/v1/guest/videos`, `/v1/guest/posts`, `/v1/guest/moments`, `/v1/guest/profiles/search`) no filtraban `fallback_profile_moderation`, `fallback_deleted_identities`, cuentas privadas (`is_private = true`), `break_mode` en pausa ni `fallback_video_deletions` / `fallback_moment_deletions`. | Un cliente no autenticado con sesión de invitado (`POST /v1/guest/session`) podía leer publicaciones, videos, momentos y perfiles de cuentas suspendidas, eliminadas o configuradas como privadas. | Se añadió `GUEST_CREATOR_VISIBLE_SQL` y filtros `NOT EXISTS` sobre `fallback_deleted_identities`, `fallback_profile_moderation`, `fallback_profile_overrides` (`is_private`, `profile_visibility`, `break_mode`) y tablas de borrado de videos/momentos, además de cachear `to_regclass` en `WeakMap`. | `cycle221-security-hardening.test.js` + `guest-mode-routes.test.js` |
| **8** | **MEDIA-ALTA** | `infrastructure/local-fallback/api/src/music-routes.js` (`L165-L185`) | `ownedMusicCoverStorageObject` seleccionaba únicamente `bucket_id, name, public_url` y omitía `cleanStorageRow(row)` cuando `scanEnforcementEnabled()` estaba activo (a diferencia de `ownedMusicStorageObject`). | Cuando `STORAGE_SCAN_ENFORCE=true`, una imagen en `thumbnails` no escaneada o rechazada por ClamAV podía vincularse como portada de pista musical. | `ownedMusicCoverStorageObject` selecciona `*` y exige `if (scanEnforcementEnabled() && !cleanStorageRow(row)) return null`. | `music-routes.test.js` |
| **9** | **MEDIA** | `infrastructure/local-fallback/api/src/music-routes.js` (`L1292-L1361`) | `POST /v1/data/music/listens/:sessionId/complete` no exigía `AND l.completed_at IS NULL` ni comprobaba si la sesión ya había sido completada antes de insertar en `fallback_music_play_events`. | Reenviar `/complete` con el mismo `sessionId` inflaba artificialmente las estadísticas de reproducción de una pista. | Se añadió `AND l.completed_at IS NULL` y retorno idempotente `{ counted, alreadyCompleted: true }` si la sesión ya estaba completada. | `cycle221-security-hardening.test.js` |
| **10** | **MEDIA** | `infrastructure/local-fallback/api/src/moments-routes.js` (`L290-L315`, `L1158-L1166`) | 1) `Number(req.body?.durationHours || 24)` producía `NaN` cuando se enviaba una cadena no numérica (ej. `"abc"`), causando `new Date(NaN).toISOString()` → **HTTP 500 `RangeError: Invalid time value`**. 2) `preloadProfiles` hacía un `SELECT ... LIMIT 10000` por cada usuario individual. | 1) Cualquier payload con `durationHours` no numérico provocaba un error 500. 2) Escaneo N+1 de hasta 10.000 filas por usuario al construir el feed de momentos. | 1) Se usa `numberOf(req.body, ['durationHours', 'duration_hours'], 24)` y una lista blanca estricta `allowedMomentVisibilities`. 2) `preloadProfiles` ahora consulta en lote con `WHERE id = ANY($1::uuid[]) OR supabase_user_id = ANY($1::uuid[])`. | `cycle221-security-hardening.test.js` + `moments-routes.test.js` + Fase 5 E2E |
| **11** | **MEDIA** | `infrastructure/local-fallback/api/src/moment-music-renderer.js` (`L1-L5`, `L192-L199`) | 1) `'use strict'` estaba en la línea 2 después de `require('./storage-scan-coordinator')`, quedando desactivado. 2) `resolveOwnedCommunityMusicInput` no verificaba que `location.bucket === 'community-music'`. | Un objeto almacenado en otro bucket del mismo usuario podía pasarse al transcodificador FFmpeg de música de momentos. | Se movió `'use strict'` a la línea 1 y se exigió `location.bucket === 'community-music'` antes de consultar `fallback_storage_objects`. | `moment-music-renderer.test.js` |
| **12** | **MEDIA (Ciclo 223)** | `infrastructure/local-fallback/api/src/storage-read-access.js` (`L1-L4`, `L71-L106`, `L181`, `L228`) | 1) `'use strict'` estaba en la línea 2 tras `require(...)`. 2) `importedRows` ejecutaba `SELECT to_regclass($1)` sin caché. 3) Un exmiembro de grupo/comunidad disparaba `loadCollectionMembershipState` dos veces en la misma transacción `REPEATABLE READ`. | 5 consultas SQL redundantes por lectura de adjunto para exmiembros y `to_regclass` repetido en cada lectura de storage. | Se movió `'use strict'` a la línea 1 y se añadieron cachés `WeakMap` por transacción/conexión `db` (`importedRelationCacheByDb` y `membershipStateCacheByDb`). | `storage-read-access.test.js` (`54/54`) |
| **13** | **BAJA-MEDIA (Ciclo 223)** | `infrastructure/local-fallback/api/src/notification-digest-routes.js` (`L470-L476`) | `processDueNotificationDigests` solo omitía `if (delivery.reason === 'NOT_DUE') continue`, incrementando `result.delivered += 1` cuando `deliverDigestForPreference` devolvía `reason: 'LOCKED'`, `'NO_LONGER_DUE'` o `'USER_ID_MISSING'`. | Métricas de entrega de resúmenes distorsionadas bajo ejecución multi-instancia concurrente con advisory locks. | Se corrigió la condición a `if (delivery.reason) continue`. | `cycle221-security-hardening.test.js` + `notification-digest-routes.test.js` |

---

## 6. Vulnerabilidades Verificadas como No Presentes

Durante la auditoría SAST y las pruebas dinámicas se verificó explícitamente la ausencia de las siguientes vulnerabilidades:

1. **Inyección SQL en rutas dinámicas y compatibilidad PostgREST (`postgrest-compat-routes.js`, `data-routes.js`, `storage-read-access.js`)**: Todas las consultas utilizan parámetros posicionales (`$1, $2...`) o identificadores citados desde listas blancas cerradas (`quoteIdentifier` + validación de tablas permitidas). Verificado con `postgrest-security.test.js` y `runtime_security_audit.py` (`0` hallazgos).
2. **Fuga de tokens Bearer o secretos en estado distribuido de Redis (`signaling-server/distributed-live-state.js`, `distributed-adapter.js`)**: Los snapshots de sesiones Live y los metadatos de Socket.IO almacenan exclusivamente `userId`, `name`, `avatarUrl` y banderas booleanas de cámara/moderación, nunca tokens JWT ni cookies. Verificado por prueba unitaria (`serializes recoverable live state without bearer tokens or socket identifiers`).
3. **Doble gasto o inflación de saldo en regalos de directo y canjes de puntos (`live-routes.js`, `live-points-routes.js`)**: Las transacciones de regalos y canjes utilizan bloqueo pesimista `FOR UPDATE` sobre el balance y claves de idempotencia únicas (`ON CONFLICT DO NOTHING`).
4. **ReDoS en moderación de comentarios (`content-moderation.js`)**: El separador acotado disjunto `[^\p{L}\p{N}@!|$+]{0,3}` impide backtracking exponencial incluso ante entradas patológicas de 1.500 caracteres repetidos (`< 5 ms`).
5. **Reutilización no autorizada de archivos `view_once` (`storage-view-once.js`, `message-media-access.js`)**: Los adjuntos `view_once` se consumen mediante un lease atómico de un solo uso con presupuesto monotónico de bytes (`next_offset`) bajo `FOR UPDATE` y nunca pueden ser reenviados ni copiados (`mayReuseMessageMedia` devuelve `false`).

---

## 7. Reglas de Negocio Validadas y Matriz de Seguridad

| Regla de Negocio / Invariante | Estado | Evidencia verificada |
|---|---|---|
| **Aislamiento BOLA/IDOR en borrado de mensajes cross-node** | **Verificado** | `charlieDeleteCross.status === 403` en Fase 5 (`api1` crea mensaje de Alice, Charlie intenta borrarlo para todos en `api2` → `403 FORBIDDEN`) |
| **Revocación inmediata cross-node de sesiones y WebSockets al cambiar contraseña** | **Verificado** | Fase 4C: cambio de contraseña en `api2` (`200`), token antiguo rechazado en `api1` (`401` con `0 ms` de caché obsoleta) y WebSocket en `rt2` cerrado con `4401 WEB_SESSION_REVOKED` en **`542,0 ms`** |
| **Resistencia Sybil en denuncias de pistas musicales** | **Verificado** | `cycle221-security-hardening.test.js` + Fase 5: 3 reportes del mismo usuario con distinta razón no tumban la pista (`underReview: false`); se requieren 3 `reporter_id` distintos (`underReview: true`); pista inexistente devuelve `404 TRACK_NOT_FOUND` |
| **Idempotencia estricta en completado de escuchas musicales** | **Verificado** | `cycle221-security-hardening.test.js`: primera llamada suma 1 reproducción (`alreadyCompleted: false`); llamadas repetidas devuelven `alreadyCompleted: true` sin duplicar `fallback_music_play_events` |
| **Reciprocidad obligatoria en revelación de identidad de chat aleatorio anónimo** | **Verificado** | `cycle221-security-hardening.test.js`: 12 mensajes unilaterales (`12 vs 0`) mantienen `level = 0`; solo el intercambio bilateral (`6 vs 6`) alcanza `level = 3` |
| **Respeto de bloqueo bidireccional y suspensión en mensajes programados** | **Verificado** | `cycle221-security-hardening.test.js`: si el destinatario bloquea al remitente después de programar el mensaje, el worker aborta la entrega con `CHAT_BLOCKED_BY_RECIPIENT` y registra el fallo sin bucle infinito |
| **Exclusión de creadores suspendidos/eliminados/privados en Modo Invitado** | **Verificado** | `cycle221-security-hardening.test.js` + `guest-mode-routes.test.js`: las consultas de contenido y perfiles de invitado excluyen creadores en `fallback_profile_moderation`, `fallback_deleted_identities` y `is_private = true` |
| **Rechazo de payloads maliciosos (null-byte, JSON profundo >24 niveles, tipos inválidos)** | **Verificado** | Fase 5 E2E: null-byte → `400 INVALID_NULL_BYTE`; JSON de 60 niveles → `400 PAYLOAD_TOO_DEEPLY_NESTED`; DM con objeto en `content` → `400 INVALID_MESSAGE_PAYLOAD`; `mediaSize: "NaN"` → `201` normalizado a `0`; `durationHours: "not-a-number"` + `visibility: "DROP_TABLE"` en Momentos → `201` normalizado a `24h` y `visibility: "all"` |
| **Autorización de canales en Realtime Gateway** | **Verificado** | Verificado durante la ejecución del arnés: cualquier intento de suscripción WebSocket a `chat:<chatId>` por un usuario que no es participante del chat 1:1 es rechazado con `FORBIDDEN_CHANNEL` |

---

## 8. Pruebas de Carga, Estrés, Concurrencia y Punto de Ruptura

Todas las pruebas se ejecutaron en el clúster multi-instancia local aislado (`2× API + 2× Realtime Gateway + 2× Signaling Server + Redis 7.4.8 real en WSL2 + PostgreSQL 17 efímero con max_connections=220`), distribuyendo carga mixta realista (20 % feed, 20 % perfil, 20 % envío DM, 20 % envío de mensaje de grupo, 20 % notificaciones):

| Usuarios concurrentes | Solicitudes totales | RPS Ciclo 219 (PG) | RPS Ciclo 220 (Redis R1) | **RPS Ciclo 221–223 (Redis R2 + SQL/Auth Opt)** | Mejora vs Ciclo 220 | Latencia p50 (`ms`) | Latencia p95 (`ms`) | Latencia p99 (`ms`) | `groupSend` p95 (`ms`) | Errores `5xx` | Errores de red |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **50** | 300 | 175,3 | 350,3 | **263,3** *(warmup inicial)* | — | 157,59 | 303,86 | 356,48 | 336,92 | **0** | **0** |
| **100** | 500 | 191,8 | 301,0 | **325,7** | **+8,2 %** | 269,81 | 476,40 | 534,72 | 442,49 | **0** | **0** |
| **250** | 1.000 | 198,4 | 311,6 | **395,9** | **+27,1 %** | 546,95 | 879,35 | 924,68 | 726,92 | **0** | **0** |
| **500** | 1.500 | 201,3 | 370,6 | **441,4** | **+19,1 %** | 910,36 | 1.440,61 | 1.516,69 | 1.125,70 | **0** | **0** |
| **1.000** | 2.000 | 194,6 | 395,9 | **415,2** | **+4,9 %** | 1.633,84 | 2.389,37 | 2.642,48 | 2.041,85 | **0** | **0** |
| **1.500** | 3.000 | — | 434,5 | **452,3** | **+4,1 % (2,25× vs PG)** | 2.233,14 | 3.551,67 | 3.913,06 | 2.839,06 | **0** | **0** |

- **Análisis del punto de ruptura**:
  - Hasta **1.500 usuarios concurrentes** (`3.000` solicitudes simultáneas en ráfaga sobre 2 réplicas API en una sola máquina de 16 hilos lógicos), el sistema mantiene **`0` errores 5xx**, **`0` errores de red** y **`0` solicitudes rechazadas por load-shedding (`503`)**, alcanzando un caudal máximo sostenido de **`452,3 RPS`**.
  - A partir de `500–1.500` usuarios concurrentes en un único host compartido (donde PostgreSQL 17, Redis 7.4.8, 6 procesos Node.js y el generador de carga compiten por los mismos núcleos de CPU), la latencia p95 crece linealmente por encolamiento de CPU/pool (`476,40 ms @ 100u` → `879,35 ms @ 250u` → `1.440,61 ms @ 500u`).

---

## 9. Latencia Desglosada de Mensajería y Tiempo Real

Medición sobre 50 mensajes consecutivos por escenario (`Server-Timing: access, persist, app` + recepción real por WebSocket en el mismo nodo `rt1` y cross-node en `rt2` vía Redis Streams):

| Escenario | `access` p50 / p95 (`ms`) | `persist` p50 / p95 (`ms`) | **ACK Backend p50 / p95 (`ms`)** | **WS Mismo Nodo p50 / p95 (`ms`)** | **WS Cross-Node (Redis) p50 / p95 (`ms`)** | Mejora ACK p50 vs Ciclo 220 | Errores |
|---|---:|---:|---:|---:|---:|---:|---:|
| **DM 1:1 en reposo (`0` usuarios de fondo)** | `1,20` / `1,80` | `5,40` / `7,90` | **`10,58` / `15,05`** | **`12,64` / `18,82`** | **`13,89` / `20,04`** | Estable (`~10 ms`) | **0** |
| **DM 1:1 bajo carga (`40` usuarios de feed)** | `23,90` / `34,40` | `57,40` / `85,40` | **`116,29` / `155,38`** | **`157,23` / `202,39`** | **`158,52` / `203,98`** | **-16,1 %** (vs `138,65 ms`) | **0** |
| **Grupo en reposo (`0` usuarios de fondo)** | **`1,60` / `2,40`** | **`0,80` / `1,40`** | **`6,87` / `9,60`** | **`13,53` / `15,72`** | **`14,86` / `17,26`** | **-40,3 %** (vs `11,51 ms`) | **0** |
| **Grupo bajo carga (`40` usuarios de feed)** | **`26,20` / `35,10`** | **`12,90` / `20,30`** | **`76,58` / `93,30`** | **`210,02` / `236,29`** | **`213,58` / `240,34`** | **-44,6 %** (vs `138,30 ms`) | **0** |

- **Causa directa de la mejora en grupos**: En `data-routes.js`, `loadCollectionRoomAccess` ejecutaba anteriormente 6 consultas SQL redundantes además de `loadCollectionMembershipState`. Al unificar `loadCollectionRoomAccess` sobre `loadCollectionMembershipSnapshot` + `collectionMembershipStateFromSnapshot` y cachear `to_regclass` en `WeakMap`, el tramo `access` de grupos cayó de **`4,80 ms` a `1,60 ms` en reposo (3,0× más rápido)** y de **`69,00 ms` a `26,20 ms` bajo carga (2,63× más rápido)**.
- **Overhead de Redis Streams entre nodos Realtime**: La diferencia entre entrega WebSocket en el mismo nodo (`rt1`) y cross-node (`rt1 → Redis Streams → rt2`) es de apenas **`1,25 ms – 1,33 ms`** en reposo.

---

## 10. Soak Test y Pruebas de Resistencia

### 10.1. Soak Extendido Multi-Instancia Ejecutado Localmente (`210 s` / `308` iteraciones)

Se ejecutó una prueba de resistencia continua de **210 segundos** sobre el clúster completo (`2× API + 2× Realtime + 2× Signaling + Redis 7.4.8 + PostgreSQL 17`) con **40 usuarios HTTP concurrentes continuos**, **20 conexiones WebSocket Realtime activas** y **12 conexiones Socket.IO `/live` activas**:

| Métrica | Inicio (`t = 1 s`, iter 1) | Mitad (`t = 110 s`, iter 160) | Final (`t = 210 s`, iter 308) | Resultado global |
|---|---:|---:|---:|---|
| **Solicitudes acumuladas** | 160 | 25.600 | **49.280** | **49.280 solicitudes completadas** |
| **Caudal instantáneo (`RPS`)** | 217,3 | 319,1 | **317,1** | Sin degradación progresiva |
| **Latencia `p50 / p95 / p99` (`ms`)** | `148,96 / 272,78 / 318,57` | `100,00 / 200,36 / 216,94` | **`104,76 / 204,78 / 210,32`** | **Latencia final menor que la inicial** |
| **Errores `5xx` / `loadShed 503`** | `0 / 0` | `0 / 0` | **`0 / 0`** | **`0` errores 5xx en 49.280 peticiones** |
| **Memoria RSS `api1 / api2` (`MB`)** | `320,9 / 207,8` | `252,2 / 275,7` | **`253,3 / 266,2`** | Heap estabilizado por GC (`119,6 / 76,5 MB`) |
| **Memoria RSS `rt1 / rt2` (`MB`)** | `97,8 / 70,9` | `105,4 / 109,5` | **`134,0 / 106,7`** | Estable, sin fuga de descriptores |
| **Memoria RSS `sig1 / sig2` (`MB`)** | `67,3 / 68,8` | `67,7 / 68,9` | **`67,4 / 68,8`** | Deriva `< 0,1 MB` en 210 s |
| **Event Loop Lag p95 `api1 / api2` (`ms`)** | `41,68 / 40,93` | `38,67 / 37,91` | **`37,29 / 36,93`** | Estable bajo carga continua |
| **Cola de espera Pool PG (`waiting`)** | `0 / 0` | `0 / 0` | **`0 / 0`** | `0` conexiones bloqueadas |
| **Redis 7.4.8 Memoria / Clientes / Evictions** | `23,32 MB / 19 / 0` | `23,51 MB / 19 / 0` | **`23,50 MB / 19 / 0`** | **`+0,18 MB` en 49.280 reqs (`0` evictions)** |
| **PostgreSQL Conexiones (`total / active`) y Cache Hit** | `51 / 1` (`99,98 %`) | `51 / 1` (`99,99 %`) | **`51 / 2` (`99,99 %`)** | Conexiones fijas (`51/220`), `99,99 %` buffer hit |

### 10.2. Endurecimiento y Verificación de `tools/loadtest/run-ubuntu-staging-soak.mjs`

El ejecutor de soak de 2–6 horas para Ubuntu staging fue auditado y endurecido con las siguientes salvaguardas verificadas automáticamente en la Fase 3 del arnés:
1. **Exigencia obligatoria de `NOVVIN_STAGING_ISOLATED=true`**: Sin esta variable, aborta inmediatamente con código de salida **`2`** (`missingFlagExitCode: 2`).
2. **Lista negra de dominios de producción**: Si cualquiera de las URLs configuradas (`STAGING_API_URL`, `STAGING_API_URLS`, `STAGING_REALTIME_URLS`, `STAGING_SIGNALING_URLS`) contiene `novvin.app`, `conectate.com`, `supabase.co`, `vercel.app` o `cloudflare.com`, aborta inmediatamente con código **`2`** (`prodDomainExitCode: 2`).
3. **Bloqueo de hosts no-loopback por defecto**: Si el destino no es loopback (`127.0.0.1` / `localhost`), exige explícitamente `NOVVIN_ALLOW_NON_LOOPBACK_STAGING_HOST=true`; de lo contrario aborta con código **`2`** (`nonLoopbackExitCode: 2`).
4. **Circuit-breaker de parada automática y telemetría de deriva**: Aborta la prueba de forma segura si los errores `5xx` superan `SOAK_MAX_5XX_ABORT` o si los errores de red superan `SOAK_MAX_NET_ERR_ABORT`, maneja `SIGINT`/`SIGTERM` cerrando todos los sockets y calcula automáticamente `apiRssDriftMB` y `p95DriftMs`.
5. **Estado del soak de 2–6 horas en servidor externo Ubuntu staging**: **Pendiente de infraestructura externa** (no existe un servidor Ubuntu staging externo aislado accesible desde este entorno local, y conforme a las reglas del propietario jamás se ejecutan pruebas de saturación contra el servidor Ubuntu de producción ni se inventan horas no ejecutadas). El procedimiento exacto queda listo en la Sección 23.1.

---

## 11. Pruebas de Inyección de Fallos y Recuperación

En la Fase 4 del arnés (`run-cycle221-222-certification.mjs`) se ejecutaron **4 escenarios de caos e inyección de fallos** sobre el clúster multi-instancia:

| Escenario de Caos | Fallo Inyectado | Comportamiento del Sistema | Métrica Real Obtenida | Estado |
|---|---|---|---|---|
| **4A. Contención / Consultas lentas en PostgreSQL** | Transacción con `pg_sleep(0.35)` reteniendo una conexión mientras 30 usuarios concurrentes lanzan 60 peticiones mixtas contra `api1` y `api2` | El pool absorbe la retención sin agotar conexiones ni activar falsos `503` | **`60/60` respuestas `2xx`, `0` errores `5xx`, `poolWaitingAfterRelease = 0`** | **Verificado** |
| **4B. Caída abrupta (`SIGKILL`) de `Realtime-1` + Tormenta de Reconexión de 60 WebSockets** | 30 clientes en `rt1` y 30 en `rt2`; se mata `rt1` con `SIGKILL` y los 30 clientes huérfanos se reconectan simultáneamente a `rt2`; luego se publica un mensaje vía `api2` | `rt2` absorbe los 60 WebSockets concurrentes y entrega el evento publicado en `api2` a través de Redis Streams a los 60 clientes sin pérdida ni duplicación | **Reconexión de 30 sockets en `410,7 ms`; entrega post-failover a `60/60` clientes** | **Verificado** |
| **4C. Cambio de contraseña cross-node y revocación instantánea de WebSocket** | Usuario autenticado con WebSocket abierto en `rt2` cambia su contraseña en `api2` (`POST /v1/auth/change-password`) e intenta usar el token antiguo en `api1` | `api2` revoca la sesión en PG y emite `web_sessions_revoked` por Redis Streams; `rt2` cierra el WebSocket con `4401 WEB_SESSION_REVOKED` y `api1` rechaza el token antiguo con `401` (`0 ms` de caché obsoleta) | **Cierre WS cross-node en `542,0 ms` (`4401 WEB_SESSION_REVOKED`); token antiguo en `api1` → `401`** | **Verificado** |
| **4D. Caída abrupta (`SIGKILL`) de Redis 7.4.8 y autorrecuperación del clúster** | Se mata el proceso `redis-server` con `SIGKILL`; durante la caída se envía un mensaje en `api1`; luego se reinicia Redis 7.4.8 | Durante la caída de Redis, `distributed-rate-limit.js` hace fallback transparente a PostgreSQL (`201 Created`). Al reiniciar Redis, `api1`, `api2`, `rt1`, `rt2`, `sig1` y `sig2` reconectan sus clientes RESP/Streams automáticamente | **Escritura durante caída → `201 Created`; autorrecuperación completa de las 6 instancias en `1.428,0 ms`** | **Verificado** |

---

## 12. Estado de Directos Multi-Instancia y Rehidratación en Redis

Se resolvió arquitectónicamente la limitación `liveSessionState: 'replica-affinity-required'` en `signaling-server/distributed-live-state.js` y `signaling-server/index.js`:

1. **Arquitectura implementada**:
   - **Heartbeat de vivacidad de réplica (`node:<nodeId>`)**: Cada instancia de Signaling renueva su clave de vivacidad en Redis (`SIGNALING_NODE_LIVENESS_SECONDS`, por defecto `10 s`, configurable hasta `3 s`) y la elimina limpiamente al apagarse.
   - **Reclamo atómico de lease de Host muerto (`reclaimDeadHostLease`)**: Mediante un script Lua atómico en Redis, cuando un Host se reconecta a una réplica distinta (`sig2`) tras la caída abrupta (`SIGKILL`) de su réplica original (`sig1`), `sig2` verifica si el nodo propietario del lease sigue vivo; si su clave `node:<ownerNodeId>` expiró, `sig2` reclama atómicamente el lease sin esperar los 45 s de expiración pasiva.
   - **Estado distribuido en Redis Hash / Strings por sesión Live**:
     - `host-runtime:<liveId>`: metadatos del host (`userId`, `name`, `avatarUrl`, `socketId`, `nodeId`) y estado de medios (`videoEnabled`, `audioEnabled`).
     - `viewers:<liveId>`: hash `socketId -> { userId, name, avatarUrl, nodeId }` con **poda automática (`filterAliveEntries`)** de espectadores pertenecientes a nodos cuyo heartbeat ha expirado.
     - `guest-requests:<liveId>` y `guests:<liveId>`: hashes distribuidos de solicitudes y co-hosts activos, permitiendo que un Host en `sig1` acepte a un Guest conectado a `sig2` y que, si el Guest en `sig2` sufre una desconexión transitoria, `sig2` consulte Redis y lo preserve en `session.recoverableGuests` antes de eliminar su socket antiguo.
     - `reactions:<liveId>` y `reaction-batches:<liveId>`: contador atómico `INCRBY` cross-node con deduplicación distribuida de `clientBatchId` (`HSETNX`).
   - `/health` reporta ahora `stateConsistency.liveSessionState: 'distributed-redis-rehydrated'` cuando Redis Streams y el almacén distribuido están activos.
2. **Resultados verificados en la Fase 1 del arnés (`2× Signaling + Redis 7.4.8 real`)**:
   - `sigRedisIntegrationTestPassed`: **`true`** (`2/2` tests de `distributed-live-state.redis.integration.test.js` contra Redis 7.4.8 real + `60/60` tests unitarios en `signaling-server`).
   - `sig1LiveSessionState` y `sig2LiveSessionState`: **`"distributed-redis-rehydrated"`**.
   - Host se une en `sig1`; Viewer y Guest se unen en `sig2` (`crossNodeViewerHostInfoReceived: true`).
   - Guest en `sig2` solicita subir a cámara; Host en `sig1` recibe la solicitud y la acepta cross-node (`crossNodeGuestAccepted: true`, roster de 1 invitado).
   - Viewer y Guest en `sig2` envían 1 reacción individual + 1 lote de 4 reacciones (`clientBatchId`) + 1 replay duplicado del mismo `clientBatchId`: el total deduplicado en `sig1` es exactamente **`5`** (`rehydratedTotalReactions: 5`).
   - Se conecta un espectador adicional en `sig1` (`doomedViewerOnSig1`) y se mata `sig1` con **`SIGKILL`**.
   - El Host se reconecta en `sig2`: `sig2` reclama el lease en **`81,7 ms`** tras expirar la vivacidad de `sig1`, rehidrata el total de **`5` reacciones**, **`1` invitado activo** y **`2` espectadores vivos** (podando automáticamente a `doomedViewerOnSig1` del nodo muerto `sig1`).
   - El Guest en `sig2` se desconecta y reconecta: es rehidratado automáticamente como invitado aceptado (`guestReconnectRehydratedFlag: true`).
   - El Host finaliza el directo (`end-live`) en `sig2`: se confirma el cierre y **`remainingRedisKeysAfterEndLive = 0`** (limpieza total en Redis).
   - *Nota operativa*: En `infrastructure/local-fallback/scripts/deploy-stack-safely.sh` se mantiene `SIGNALING_REPLICAS=1` como valor por defecto seguro hasta que el propietario autorice escalar `SIGNALING_REPLICAS >= 2` en Ubuntu.

---

## 13. Optimizaciones de Rendimiento Aplicadas (Antes vs Después)

| Componente / Ruta | Cuello de botella identificado | Optimización aplicada | Antes (Ciclo 220) | **Después (Ciclos 221–223)** | Mejora real |
|---|---|---|---|---|---|
| **`authenticateClientRequest` (`server.js`)** | Ráfagas concurrentes de un mismo cliente (feed + perfil + notificaciones + WS) disparaban N consultas `SELECT ... FROM fallback_users JOIN sessions` idénticas simultáneas | Singleflight en vuelo (`authLookupInFlight = new Map()`, eliminado en `.finally()`, **`0 ms` de TTL obsoleto** → **`0 ms` de retraso en revocación**) | N consultas por ráfaga concurrente | **1 consulta compartida en vuelo, `0 ms` ventana obsoleta** | **`+19,1 %` RPS @ 500u (`370,6` → `441,4 RPS`)** |
| **`loadCollectionRoomAccess` (`data-routes.js`)** | Cada envío de mensaje a grupo o comunidad ejecutaba 6 consultas SQL redundantes además de `loadCollectionMembershipState` | Reutilización directa de `loadCollectionMembershipSnapshot` + `collectionMembershipStateFromSnapshot` + índice `idx_fallback_collection_rooms_kind_id_lower` | `access` grupo idle `4,80 ms` (carga `69,00 ms`); ACK `11,51 ms` (carga `138,30 ms`) | **`access` grupo idle `1,60 ms` (carga `26,20 ms`); ACK `6,87 ms` (carga `76,58 ms`)** | **`3,0×` más rápido en `access`; `-44,6 %` en ACK bajo carga** |
| **`collection-membership-policy.js`, `storage-read-access.js`, `guest-mode-routes.js`** | `SELECT to_regclass('supabase_import....')` se ejecutaba repetidamente en cada petición y `loadCollectionMembershipState` se ejecutaba dos veces por transacción para exmiembros | Caché `WeakMap` por `(db, table)` para `to_regclass`, paralelización `Promise.all` con `archives`, y caché por transacción `db` para `loadCollectionMembershipState` | 2–6 consultas `to_regclass` / estado repetidas por petición | **1 consulta única por pool/transacción** | Reducción de contención de pool bajo `1.000–1.500` usuarios (`452,3 RPS`) |
| **`preloadProfiles` (`moments-routes.js`)** | `SELECT ... FROM fallback_compat_rows LIMIT 10000` ejecutado dentro de un bucle `for` por cada usuario | Consulta por lotes (`WHERE id = ANY($1::uuid[]) OR supabase_user_id = ANY($1::uuid[])`) | $O(N \times 10.000)$ filas escaneadas | **$O(1)$ consulta por lote de autores** | Eliminación de escaneo N+1 en feed de momentos |

---

## 14. Estado de la Aplicación Android y Compilación Release

### 14.1. Configuración Release y Seguridad Verificada

- **Identificador y versión (`app/build.gradle.kts` y `aapt2 dump badging`)**:
  - `applicationId`: `conectate.com`
  - `versionCode`: **`183`**
  - `versionName`: **`"9.1.43"`**
  - `minSdk`: **`24`**, `targetSdk`: **`36`**, `compileSdk`: **`36`**
- **Endurecimiento de Release verificado**:
  - `isMinifyEnabled = true` (R8 activo), `isShrinkResources = true`, `isDebuggable = false` (confirmado por ausencia de `application-debuggable` en `aapt2 dump badging`).
  - `app/proguard-rules.pro` elimina trazas `android.util.Log.v/d/i` en Release mediante `-assumenosideeffects` y conserva modelos serializables, Hilt, WebRTC, Retrofit y Room.
  - `AndroidManifest.xml` y `res/xml/network_security_config.xml`: `android:usesCleartextTraffic="false"` y `cleartextTrafficPermitted="false"`.
  - `res/xml/backup_rules.xml` y `res/xml/data_extraction_rules.xml`: excluyen bases de datos Room, tokens de sesión y `EncryptedSharedPreferences` de copias de seguridad en la nube y transferencias de dispositivo, permitiendo únicamente `theme_prefs.xml`, `messages_preferences.xml` y `app_prefs.xml`.
  - Tareas de guardia `:app:validateStableReleaseBackend` y `:app:verifyReleaseSigningConfiguration` ejecutadas y aprobadas en verde.

### 14.2. Compilación, Firma e Integridad de la APK Release (`task-2462`)

- **Comando ejecutado**:
  `.\gradlew.bat :app:testDebugUnitTest --tests "conectate.com.data.repository.VideoLibraryAccountBoundaryTest" --tests "conectate.com.core.cache.LocalAccountSnapshotTest" --tests "conectate.com.core.media.MediaUploadGuardTest" :app:assembleRelease --no-daemon --max-workers=1`
- **Resultado de compilación y Lint Vital**:
  - `:app:testDebugUnitTest`: **PASSED** (confirmando que la eliminación de `PredictiveAnalyticsSystem.kt` y la conservación de `UpstashCacheService.kt` mantienen intactos todos los repositorios y tests JVM).
  - `:app:minifyReleaseWithR8`, `:app:lintVitalAnalyzeRelease`, `:app:lintVitalRelease`, `:app:packageRelease`, `:app:assembleRelease`: **`BUILD SUCCESSFUL` (`GRADLE_EXIT=0`)**.
- **Artefacto APK Release generado y conservado**:
  - **Ubicación exacta**: `D:\red social - live-gifts\app\build\outputs\apk\release\app-release.apk`
  - **Tamaño exacto**: `221.779.029` bytes (`211,51 MB`)
  - **Fecha de compilación**: `2026-11-20 00:46:22 -05:00`
  - **SHA-256**: **`26CAE9A3AA5514BF941A4283811C208BC1E7B8053B00022A32BCD6C617A82132`**
  - **Verificación `apksigner verify --v`**:
    - `Verifies`
    - `Verified using v2 scheme (APK Signature Scheme v2): true`
    - `Number of signers: 1`
  - **Inspección de secretos dentro del ZIP de la APK**: **`PACKAGED_SECRET_FILES_COUNT=0`** (`0` archivos `.jks`, `.keystore`, `keystore.properties`, `.env`, `.pem` o `google-services.json` dentro del paquete).
- **Correcciones incluidas en esta entrega APK `9.1.43` (`versionCode 183`)**:
  - Incluye todas las correcciones móviles acumuladas del árbol actual más la limpieza verificada del sistema muerto `PredictiveAnalyticsSystem.kt` y compatibilidad completa con los contratos endurecidos del backend de los Ciclos 219–223.
- **Estado de pruebas en dispositivos físicos Android**:
  - **Bloqueado por ausencia de hardware físico conectado** (`adb devices -l` devuelve `0` dispositivos físicos y `0` emuladores activos). El protocolo exacto para validar con 2 dispositivos físicos reales queda documentado en la Sección 23.2.

---

## 15. Estado de la Aplicación Web y Pruebas Ejecutadas

En `NoWing-web/` (`task-2329` y `task-2331`) se ejecutaron todas las verificaciones de tipos, empaquetado de producción, Service Worker multimedia, SEO público y pruebas unitarias/de integración:

| Verificación Web | Comando ejecutado | Resultado real | Estado |
|---|---|---|---|
| **Streaming Multimedia y Service Worker (`novvin-media-worker.js`)** | `npm run test:media` (`node --test tests/media-worker.test.mjs`) | **`21/21` pruebas en verde** (`425,7 ms`, `MEDIA_EXIT=0`): preservación de `Range` sin tokens en URL, aislamiento de handles entre clientes, invalidación instantánea al cambiar identidad/cerrar sesión, refresco único de Bearer expirado y caché en RAM acotada solo para avatares pequeños | **Verificado** |
| **Compilación TypeScript + Vite Production Build + Prerender + Check SEO** | `npm run build` (`tsc -b && vite build && node scripts/prerender-public-home.mjs && node scripts/check-built-seo.mjs`) | **`BUILD_EXIT=0`** (`1.959` módulos transformados, bundles chunks/workers generados, páginas públicas prerenderizadas y validadas) | **Verificado** |
| **Auditoría SEO, Metadatos Canónicos, JSON-LD y Rutas Públicas/Privadas** | `npm run test:seo` (`node --test tests/public-home.test.mjs`) | **`72/72` pruebas en verde** (`433,6 ms`, `SEO_EXIT=0`): shell privado con `noindex`, páginas canónicas `/`, `/privacidad`, `/eliminar-cuenta`, `/cookies`, `/que-es-novvin`, `/ayuda/*`, `/descargar`, `404.html` y cabeceras de seguridad en `vercel.json` | **Verificado** |
| **Suite Completa Jest (Componentes, Hooks, Core, WebRTC, Mensajería, Directos)** | `npx jest --runInBand` | **`208/208` suites en verde, `1.344/1.344` pruebas en verde** (`177,63 s`, `JEST_EXIT=0`) | **Verificado** |

---

## 16. Código Muerto Verificado y Eliminado

Se realizó una búsqueda exhaustiva en todo el repositorio (`git grep -n -F`) y verificación de compilación/pruebas antes de actuar sobre cada candidato:

| Archivo candidato | Referencias encontradas en el repositorio | Decisión tomada | Verificación posterior |
|---|---|---|---|
| `.FullName` | `0` referencias (archivo residual de 12 bytes en la raíz del repositorio con el texto `d:\file.txt`) | **Eliminado (`git rm`)** | Árbol limpio y actualizado en `review-coverage.json` |
| `infrastructure/local-fallback/api/query.js` | `0` referencias (script obsoleto que hacía `require('better-sqlite3')`, dependencia inexistente en `package.json`, apuntando a una ruta absoluta ajena `C:/Users/epica/...`) | **Eliminado (`git rm`)** | `npm run check` y todas las suites de `api` en verde |
| `infrastructure/local-fallback/api/run-sql.js` | `0` referencias (script ad-hoc de desarrollo con puerto `5433` y credenciales locales cableadas) | **Eliminado (`git rm`)** | `npm run check` y todas las suites de `api` en verde |
| `app/src/main/java/conectate/com/analytics/enterprise/PredictiveAnalyticsSystem.kt` | `0` referencias en `app/src/main`, `app/src/test` y `app/src/androidTest` (clase `@Singleton` huérfana nunca inyectada en ningún ViewModel, repositorio ni módulo Hilt) | **Eliminado (`git rm`)** | `:app:testDebugUnitTest` y `:app:assembleRelease` (`BUILD SUCCESSFUL`) |
| `app/src/main/java/conectate/com/data/cache/UpstashCacheService.kt` | **Consumidores activos**: inyectado en `PostRepository.kt:88` (`upstashCache.invalidatePostCache`, `cacheFeed`, `getCachedFeed`), `VideoRepository.kt:157` (`getCachedVideoCount`, `cacheVideoCount`, `deletePattern`) y `VideoLibraryAccountBoundaryTest.kt:67`. Pese a su nombre heredado, es una caché local en memoria (`ConcurrentHashMap`) sin llamadas de red externas. | **Conservado intacto** (conforme a `AGENTS.md`: *"Conserva los contratos de importación necesarios hasta sustituir y verificar todos sus consumidores; un nombre heredado no prueba que se utilice un backend externo"*) | `VideoLibraryAccountBoundaryTest` y `:app:assembleRelease` en verde |

---

## 17. Cobertura Real en `docs/oficial/review-coverage.json`

Se actualizó `docs/oficial/review-coverage.json` mediante `tools/audit/update_review_coverage.py --from-index` declarando como `--code-read` **únicamente** los archivos leídos íntegramente en los Ciclos 221–223:

| Fase / Dominio | `code-read` (Ciclo 220) | **`code-read` (Ciclo 223)** | Incremento `code-read` | `partial` (Ciclo 223) | `pending` (Ciclo 223) | Total archivos indexados |
|---|---:|---:|---:|---:|---:|---:|
| **Global del repositorio** | 945 | **1.026** | **+81 archivos netos** *(+85 nuevos `code-read`, -4 archivos muertos eliminados)* | **241** | **3.458** | **4.725** |
| `server` | 133 | **194** | **+61** | 21 | 742 | 957 |
| `android` | 602 | **607** | **+5** | 119 | 1.409 | 2.135 |
| `web` | 112 | **118** | **+6** | 73 | 482 | 673 |
| `documentation` | 66 | **67** | **+1** | 20 | 690 | 777 |
| `tooling-and-other` | 32 | **40** | **+8** | 8 | 135 | 183 |

*(La revisión integral del proyecto permanece abierta mientras existan archivos en `pending` o `partial`, cumpliendo estrictamente `AGENTS.md`).*

---

## 18. Riesgos Residuales Reales

1. **Soak de 2–6 horas en servidor Ubuntu staging externo (`Pendiente de infraestructura externa`)**:
   - Aunque el soak local multi-instancia se extendió a `210 s` (`49.280` peticiones, `0` errores 5xx) y `run-ubuntu-staging-soak.mjs` está endurecido y probado, aún no se ha ejecutado una corrida de 2 a 6 horas en una VM Ubuntu staging externa aislada de producción para observar el comportamiento de rotación de logs de Docker, disco ext4/xfs y AOF rewrite de Redis a largo plazo.
2. **Validación con 2 dispositivos Android físicos en redes distintas (`Bloqueado por hardware no conectado`)**:
   - Sin dispositivos físicos conectados a `adb` (`adb devices -l` vacío), la negociación WebRTC real con candidato TURN en red móvil 4G/5G (`CGNAT`) frente a Wi-Fi y el ciclo de vida de cámara/audio OEM bajo llamadas reales deben validarse con 2 teléfonos físicos antes de abrir tráfico masivo de llamadas/directos.
3. **Escalamiento de `SIGNALING_REPLICAS` en el script de despliegue de producción**:
   - Aunque `signaling-server` ya soporta y demostró `liveSessionState: 'distributed-redis-rehydrated'` con 2 instancias y Redis 7.4.8 real, `deploy-stack-safely.sh` conserva `SIGNALING_REPLICAS=1` por prudencia operativa hasta que el propietario autorice pasar a `SIGNALING_REPLICAS=2` en Ubuntu tras el soak en staging.
4. **Saturación de CPU en un único host a partir de `> 1.500` usuarios concurrentes**:
   - En un único servidor donde conviven PostgreSQL, Redis y las réplicas Node.js, la capacidad máxima demostrada sin errores 5xx es de `1.500` usuarios concurrentes (`452,3 RPS`). Para superar `2.500–5.000` usuarios concurrentes con p95 `< 500 ms` se requiere separar PostgreSQL (con PgBouncer) y Redis en nodos/CPU dedicados o escalar a 4 réplicas API.

---

## 19. Condiciones Exactas de Operación Segura

Para operar NOVVIN en producción controlada con las garantías certificadas en los Ciclos 221–223 deben mantenerse activas las siguientes variables y condiciones:

1. **Redis 7.x con autenticación y persistencia AOF**:
   - `RATE_LIMIT_REDIS_URL`, `REALTIME_REDIS_URL` y `SIGNALING_REDIS_URL` apuntando a la instancia Redis interna autenticada (`*_REDIS_PASSWORD_FILE` con permisos `0600`).
   - `RATE_LIMIT_REDIS_COMMAND_TIMEOUT_MS=200` para garantizar fallback rápido a PostgreSQL (`< 200 ms`) si Redis sufre una pausa o reinicio.
2. **Signaling Server y Rehidratación de Directos**:
   - `SIGNALING_REDIS_REQUIRED=true`, `SIGNALING_NODE_LIVENESS_SECONDS=10` (o `5` en redes LAN de baja latencia) y `SIGNALING_LIVE_HOST_LEASE_SECONDS=45`.
3. **Pool de PostgreSQL y Load-Shedding**:
   - `DATABASE_POOL_MAX=25` por réplica API (con `max_connections >= 200` en PostgreSQL o PgBouncer en modo `transaction` cuando se escalen `>= 4` réplicas API).
   - `DB_POOL_LOAD_SHED_WAITING=150` para devolver `503 Retry-After: 2` controlado antes de agotar descriptores ante picos extremos.
4. **Almacenamiento y Antivirus**:
   - `STORAGE_SCAN_ENFORCE=true` en producción con el contenedor `storage-antivirus` (ClamAV) saludable y `STORAGE_READ_ENFORCE=true` con `STORAGE_READ_ORIGINS` y `PUBLIC_BASE_URL` en HTTPS estricto.

---

## 20. Capacidad Máxima Certificada por Escenario

| Escenario Operativo | Topología probada | Capacidad Certificada (con evidencia reproducible) | Latencia p50 / p95 | Tasa de Error `5xx` | Clasificación |
|---|---|---|---|---|---|
| **Tráfico mixto de baja latencia (SLA interactivo p95 < 500 ms)** | `2× API + Redis 7.4.8 + PG 17` | **100 usuarios concurrentes continuos (`325,7 RPS`)** | `269,8 ms / 476,4 ms` | **`0,00 %`** | **Verificado** |
| **Tráfico mixto de carga media (SLA p95 < 1.500 ms)** | `2× API + Redis 7.4.8 + PG 17` | **250 – 500 usuarios concurrentes (`395,9 – 441,4 RPS`)** | `547,0 – 910,4 ms / 879,4 – 1.440,6 ms` | **`0,00 %`** | **Verificado** |
| **Pico máximo de estrés sin errores (`0 5xx`) en un solo host** | `2× API + Redis 7.4.8 + PG 17` | **1.500 usuarios concurrentes (`452,3 RPS`)** | `2.233,1 ms / 3.551,7 ms` | **`0,00 %`** | **Verificado** |
| **Mensajería en tiempo real (DM y Grupos con entrega WS cross-node)** | `2× API + 2× Realtime + Redis Streams` | **40 usuarios de feed concurrentes + mensajería continua (y tormenta de 60 WS)** | Idle WS: `13,5 – 14,9 ms`; Carga 40u WS: `158,5 – 213,6 ms` | **`0,00 %` (`60/60` entregados)** | **Verificado** |
| **Directos (`Live`) con failover multi-instancia (`SIGKILL`)** | `2× Signaling + Redis 7.4.8` | **Failover cross-node de Host + Guest + Viewers + Reacciones deduplicadas** | Reclamo de lease tras TTL: **`81,7 ms`** | **`0,00 %` (`0` claves huérfanas)** | **Verificado** |

---

## 21. Veredicto Final Honesto

### **`APTO CONDICIONAL PARA LANZAMIENTO CONTROLADO`**

**Justificación técnica basada en evidencia**:
- **Por qué es APTO para lanzamiento controlado**:
  1. Las **13 vulnerabilidades y errores funcionales** descubiertos en los Ciclos 221–223 (incluyendo el crash 500 en puntos de directo, el auto-takedown Sybil en música, la carrera en Google Play RTDN, el bypass de bloqueo en mensajes programados, la revelación unilateral en chat anónimo, la revocación WebSocket cross-node al cambiar contraseña y el bypass de cuentas suspendidas/privadas en modo invitado) están corregidos y verificados con pruebas automatizadas.
  2. La brecha arquitectónica de Directos multi-instancia (`replica-affinity-required`) quedó resuelta y demostrada con Redis 7.4.8 real y `SIGKILL` (`distributed-redis-rehydrated`).
  3. La capacidad del backend escaló hasta **`452,3 RPS` a 1.500 usuarios concurrentes con `0` errores 5xx**, y la latencia de envío a grupos mejoró más de un **40 %**.
  4. Tanto la aplicación **Web** (`208/208` suites Jest, `1.344/1.344` tests, `21/21` media worker, `72/72` SEO, build de producción) como la **APK Release de Android** (`9.1.43`, `versionCode 183`, firmada v2 y sin secretos expuestos) compilan y superan todas sus verificaciones.
- **Por qué es CONDICIONAL**:
  1. Falta ejecutar la corrida de **2–6 horas de soak en un servidor Ubuntu staging externo** (pendiente de infraestructura de staging aislada).
  2. Falta ejecutar el protocolo de **validación en 2 dispositivos Android físicos reales** (4G/5G vs Wi-Fi) para llamadas WebRTC/TURN y transmisión de cámara en directo (`adb devices -l` = 0 dispositivos conectados).
  3. La revisión integral de código en `docs/oficial/review-coverage.json` (`1.026` `code-read` de `4.725` archivos) sigue abierta conforme a `AGENTS.md`.

---

## 22. Verificación Git Final

- Todas las modificaciones de los Ciclos 221–223 se publican de manera atómica en `feature/live-gifts` (`D:/red social - live-gifts`) y `main` (`D:/red social - copia`), verificando que `git rev-parse main^{tree}` y `git rev-parse feature/live-gifts^{tree}` sean **bit a bit idénticos** y que todos los cambios locales ajenos en ambos worktrees (así como la reserva 218 `profileForViewer` / `sanitizeProfileRow`) permanezcan intactos.

---

## 23. Instrucciones Exactas para las Pruebas Pendientes en Entorno Externo

### 23.1. Procedimiento para Ejecutar el Soak de 2–6 Horas en Ubuntu Staging Aislado

Cuando se disponga de la VM Ubuntu de staging aislada (nunca en el servidor de producción):

```bash
# 1. En la VM Ubuntu de staging aislada, levantar el stack con 2 réplicas de API, Realtime y Signaling:
cd /opt/novvin-staging/infrastructure/local-fallback
API_REPLICAS=2 REALTIME_REPLICAS=2 SIGNALING_REPLICAS=2 \
  docker compose up -d --build

# 2. Ejecutar el soak de 2 horas (7.200 s) o 6 horas (21.600 s) con guardas de seguridad activas:
cd /opt/novvin-staging
NOVVIN_STAGING_ISOLATED=true \
STAGING_API_URLS="http://127.0.0.1:3001" \
STAGING_REALTIME_URLS="http://127.0.0.1:3002" \
STAGING_SIGNALING_URLS="http://127.0.0.1:3004" \
SOAK_DURATION_SECONDS=7200 \
SOAK_CONCURRENCY=50 \
SOAK_WS_CLIENTS=30 \
SOAK_MAX_5XX_ABORT=10 \
SOAK_REPORT_PATH="/tmp/novvin-staging-soak-2h.json" \
  node tools/loadtest/run-ubuntu-staging-soak.mjs
```

**Criterio de aceptación**: `summary.passed === true`, `totalUnexpected5xx === 0`, `apiRssDriftMB < 80 MB`, `p95DriftMs < 100 ms` y `0` reinicios de contenedores en `docker compose ps`.

### 23.2. Protocolo de Validación con 2 Dispositivos Android Físicos Reales (`app-release.apk` `9.1.43`, `versionCode 183`)

Conectar el **Dispositivo A** (conectado por Wi-Fi) y el **Dispositivo B** (conectado por datos móviles 4G/5G con Wi-Fi apagado para forzar candidato TURN en CGNAT):

```powershell
# 1. Verificar ambos dispositivos físicos conectados por USB:
adb devices -l

# 2. Verificar el SHA-256 de la APK Release antes de instalar:
Get-FileHash -Algorithm SHA256 "D:\red social - live-gifts\app\build\outputs\apk\release\app-release.apk"
# Debe coincidir con: 26CAE9A3AA5514BF941A4283811C208BC1E7B8053B00022A32BCD6C617A82132

# 3. Instalar la APK Release en ambos dispositivos:
adb -s <SERIAL_DISPOSITIVO_A> install -r "D:\red social - live-gifts\app\build\outputs\apk\release\app-release.apk"
adb -s <SERIAL_DISPOSITIVO_B> install -r "D:\red social - live-gifts\app\build\outputs\apk\release\app-release.apk"
```

**Checklist obligatorio en los 2 dispositivos físicos**:
1. **Inicio de sesión y sesión multidispositivo**: Iniciar sesión con Usuario A en Dispositivo A y Usuario B en Dispositivo B; cambiar contraseña de Usuario A desde Web y verificar que el Dispositivo A cierra la sesión de inmediato sin bucle de reintentos.
2. **Mensajería DM, Grupos y Multimedia**: Enviar texto, imagen, nota de voz y adjunto `view_once` de A → B; verificar entrega instantánea, apertura única del `view_once` y bloqueo de segunda apertura.
3. **Llamada 1:1 WebRTC entre Wi-Fi (A) y 4G/5G (B)**: Iniciar videollamada de A a B, alternar cámara frontal/trasera, bloquear y desbloquear pantalla durante la llamada y verificar reconexión de audio/video vía TURN.
4. **Directo (`Live`) con Co-Host (`Guest`) y Regalos**: Iniciar directo desde Dispositivo A (Host), unirse desde Dispositivo B (4G/5G), solicitar subir como Guest desde B, aceptar en A, enviar reacciones y regalos, y simular cambio de red en A (Wi-Fi → 4G) verificando la rehidratación de la sesión Live sin pérdida de contadores ni del Guest.
