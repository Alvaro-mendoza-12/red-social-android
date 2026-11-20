# 219 · Certificación técnica integral: punto de ruptura (1.000 concurrentes), caos, backup/restore, reglas de negocio y fuzzing

19 de noviembre de 2026. Continuación iterativa sobre `d23ea5e8` (auditorías 212 y 213) ejecutada en ciclos de inspección, medición, corrección de causa raíz y reverificación aislada en `127.0.0.1` (PostgreSQL 17 efímero, API, Realtime Gateway y Signaling Server reales; cero impacto en producción). Conserva intactas la reserva 218 (`profileForViewer` / `sanitizeProfileRow`) y todos los archivos locales ajenos en ambos worktrees.

## 1. Optimizaciones y endurecimientos aplicados en esta certificación

### 1.1. Rendimiento SQL, índices faltantes y eliminación de fan-out N+1
- **`infrastructure/local-fallback/api/src/push-notifications.js`**: verificación temprana de `initializeFirebase()` en `sendPushForNotification` antes de adquirir transacciones `withCurrentMessage` y cupos de `limitMessagePush` cuando FCM no está configurado (preservando el 100 % del contrato de `message-push-limiter.test.js`).
- **`infrastructure/local-fallback/api/src/data-routes.js`**:
  - Pre-filtro en lote `filterRecipientsEligibleForMessagePush()` para mensajería grupal, comunitaria y DM: reemplaza N consultas paralelas de `break_mode` + N encolados por **1 sola consulta SQL** que excluye usuarios en pausa (`mute`/`hidden`) y filtra destinatarios con token FCM activo.
  - Índices funcionales faltantes creados de forma idempotente en `ensureLocalDataTables`: `idx_fallback_collection_members_room(room_kind, lower(room_id), joined_at ASC)` e `idx_fallback_chat_hidden_chat_user(lower(chat_id::text), lower(user_id))`.
  - Consultas UUID nativas (`id = ANY($1::uuid[])`, `chat_id = ANY($1::uuid[])`, `u.id = $1::uuid`) en `localChatsByIds`, `localChatParticipants`, `localCollectionRoomsByIds`, `createLocalNotification` y `message-deletion-policy.js` (`refreshChatSummary`) para explotar los índices de clave primaria.
  - Paralelización de `loadCollectionMembershipState` dentro de `loadCollectionRoomAccess` y reutilización de perfiles cargados en `GET /v1/data/feed` (evita la doble llamada a `publicProfilesByIds`).

### 1.2. Backpressure, limitadores distribuidos, seguridad defensiva y trazabilidad
- **Degradación controlada (*Load Shedding*) (`server.js`, `request-observability.js`, `alerts.novvin.yml`)**: cuando `pool.waitingCount >= DB_POOL_LOAD_SHED_WAITING` (predeterminado `max(120, poolMax * 6)`), las lecturas no críticas de descubrimiento (`/v1/data/feed`, `/search`, `/recommendations`, `/socialization`, `/videos`, `/proximity`) responden limpiamente `503 SERVICE_OVERLOADED` con `Retry-After: 1` (contabilizado en `novvin_api_load_shed_total`), preservando capacidad del pool para mensajería, llamadas, autenticación y salud.
- **Limitadores anti-abuso multi-réplica (`server.js`)**: migración de los 4 limitadores de escritura autenticada (`data:message-write`, `data:call-initiate`, `data:report-submit`, `data:social-write`) a `createDistributedRateLimitMiddleware` respaldado por PostgreSQL (`fallback_rate_limit_windows`), cumpliendo el contrato fail-closed de `production-config.test.js`.
- **Dos fallos reales HTTP 500 descubiertos por fuzzing y corregidos desde su causa raíz**:
  1. **Byte nulo `\u0000` y anidamiento profundo (`server.js`)**: middleware `inspectInputSafety` sobre `req.body` y `req.query` que rechaza `\u0000` (cuya escritura provocaba PostgreSQL `22021` -> HTTP 500) con `400 INVALID_NULL_BYTE` y anidamiento > 48 niveles con `400 PAYLOAD_TOO_DEEPLY_NESTED`.
  2. **Tipos inválidos y `mediaSize: "NaN"` en mensajería (`data-routes.js`)**: DM y grupos/comunidades rechazaban incorrectamente o enviaban `NaN` a `BIGINT` (`Math.max(0, Number(x || 0))`) causando HTTP 500; ahora validan `typeof === 'string'` (`400 INVALID_MESSAGE_PAYLOAD`), sanean `mediaSize` con `Number.isFinite` y acotan `offset` contra `Infinity`.
- **Defensa en profundidad en borde (`nginx/default.conf`)**: bloqueo explícito `location ^~ /internal/ { return 404; }` para impedir el enrutamiento público a `/internal/metrics`, conservando íntegros los 9 requisitos de `deploy-stack-safely.sh`.
- **Trazabilidad W3C OpenTelemetry (`request-observability.js`, `server.js`, `realtime-publish.js`, `realtime-gateway/src/server.js`)**: propagación y generación de cabecera `traceparent` (preservando `traceId` con nuevo `spanId` hijo y eco en CORS) junto con `x-correlation-id`.
- **Corrección de fixture heredada (`tests/audit-content-privacy.test.js`)**: filtrado `group_id IS NOT NULL` en el mock de `fallback_voice_rooms` -> **36/36 casos aprobados**.

## 2. Resultados medidos en ciclos aislados (`tools/loadtest/run-certification-deep-suite.mjs`)

### 2.1. Impacto SQL/Índices/Batch-Push (Corrida #2 vs. Corrida #3 en `run-isolated-capacity-suite.mjs`)
- **@50 concurrentes**: `161,9 -> 215,2 req/s (+32,9 %)`, `p95 482,71 -> 331,63 ms (-31,3 %)`, `groupSend p95 571,73 -> 357,13 ms (-37,5 %)`, `slowRequestsTotal` (`>1,5 s`) de **1.022 -> 0**.
- **@250 concurrentes (con limitadores en memoria)**: `199,7 -> 299,1 req/s (+49,8 %)`, `dbPool.waiting 270 -> 0`, `RSS soak 419 -> 252 MB (-40 %)`.
- **Trade-off medido tras migrar los 4 limitadores de escritura a PostgreSQL distribuido**: el techo mixto en 1 nodo pasa de `299,1` a `201,0 req/s` por el `UPSERT` por escritura en `fallback_rate_limit_windows`, pero garantiza consistencia estricta entre múltiples réplicas API (recomendación futura: mover solo el contador caliente a Redis cuando se escale por encima de 200 req/s sostenidos).

### 2.2. Punto de ruptura (10 -> 1.000 concurrentes, limitadores distribuidos + Load Shedding = 150)

| Concurrencia | Solicitudes | RPS | p50 (ms) | p95 (ms) | 2xx | Load-Shed 503 (solo feed) | 5xx inesperados | Fallos críticos (DM/Grupo/Perfil/Notif) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 100 | 92,0 | 76,65 | 341,12 | 99 | 0 | **0** | **0** |
| 25 | 200 | 150,6 | 150,02 | 256,83 | 200 | 0 | **0** | **0** |
| 50 | 300 | 153,0 | 314,09 | 542,72 | 300 | 0 | **0** | **0** |
| 100 | 500 | 173,8 | 578,67 | 920,69 | 469 | 31 | **0** | **0** |
| 250 | 1.000 | 165,8 | 1.264,75 | 2.707,70 | 845 | 155 | **0** | **0** |
| 500 | 1.500 | 180,2 | 2.025,61 | 3.881,88 | 1.251 | 249 | **0** | **0** |
| 1.000 | 2.000 | 201,0 | 2.907,11 | 6.707,05 | 1.659 | 341 | **0** | **0** |

### 2.3. Resistencia (Soak), Caos controlado y Backup/Restore
- **Soak 60 s (Corrida #2, 29 oleadas @ 30 concurrentes)** y **Soak 20 s (Corrida #3, 10 oleadas @ 30 concurrentes)**: **0 errores 5xx**, **0 load shedding**, `dbPool.waiting = 0`, GC V8 recuperó heap de `230,3 -> 47,4 MB` y RSS de `369,1 -> 151,8 MB`; Realtime RSS estable en `57,2–63,3 MB`.
- **Caos #1 (`SIGKILL` a Realtime Gateway)**: DM durante caída `201` en `23,3 ms`, grupo `201` en `34,7 ms`, reinicio RTO **`461,3 ms`**, DM post-recuperación `201` en `11,5 ms`.
- **Caos #2 (12× `pg_sleep(2.5)` concurrentes en PostgreSQL)**: `225,5 req/s`, `p50 = 65,96 ms`, `p95 = 97,71 ms`, **0 errores 5xx** durante el fallo y tras recuperación.
- **Caos #3 (Signaling Server Socket.IO 40 clientes + tormenta de reconexión + spam de reacciones)**: 40/40 conectados en `359,9 ms`, **19/20 reacciones spam frenadas** por el servidor (`reactionThrottledObserved = 19`), `SIGKILL` + reinicio RTO **`661,8 ms`**, tormenta de reconexión 40/40 en **`250,2 ms`** (0 fallos).
- **Simulacro real de Backup & Restore (`pg_dump -Fc` -> `pg_restore` en BD nueva)**: `dump = 406,0 ms` (`627 KB`), **`restore RTO = 1.968,9 ms`**, **100 % coincidencia exacta en conteos de 6 tablas (2.674 mensajes) y SHA-256 canónico bit a bit**.

### 2.4. Reglas de negocio (14/14), Fuzzing (42/42) y auditoría móvil/web
- **Invariantes de negocio en vivo**: **14/14 aprobados** (idempotencia 5× paralela, BOLA edición/borrado, bloqueo/desbloqueo DM, borrado de post sin resurrección por caché, grupo privado y sala de voz con contraseña, aceptación multidispositivo de llamada `[200, 409]`, revocación instantánea 0 ms, inmunidad a *Mass Assignment*, aislamiento sin autenticación, carrera 10× de reacciones, suspensión instantánea 0 ms, propagación W3C `traceparent`, rechazo `.svg`/`.html`/`.exe` `415`, métricas Prometheus autenticadas).
- **Fuzzing automatizado**: pasó de **2 fallos HTTP 500** (`null_byte_dm`, `wrong_types_dm`) a **0 fallos 5xx sobre 42 vectores adversarios**.
- **Auditoría Android (`AndroidManifest.xml`, `network_security_config.xml`, `backup_rules.xml`, `data_extraction_rules.xml`, `IntentRouter.kt`, `SurveysScreen.kt`, `VoiceRoomLinkDialog.kt`, `InternalDocumentDialog.kt`)**: cleartext deshabilitado, backup limitado a 3 archivos de preferencias no sensibles, servicios/receivers sensibles `exported="false"`, WebViews endurecidos sin acceso a archivos arbitrarios y deep links saneados sin registrar credenciales en logs.
- **Baterías de regresión final**: `npm run test:security` (incl. `production-config.test.js`), `npm run test:storage`, `npm run check`, `audit-content-privacy.test.js` (36/36), 10 suites dirigidas API (67/67), `realtime-gateway` (`npm test`), `signaling-server` (59/59), `ai-chatbot` (6/6), `tools/runtime_security_audit.py` (0 hallazgos).

## 3. Veredicto y riesgos pendientes honestos

- **Veredicto**: **APTO CON OBSERVACIONES** para activación controlada en staging/Ubuntu y beta inicial.
- **Límites demostrados en 1 nodo**: capacidad sostenible **~50–100 concurrentes activos sostenidos (`150–175 req/s` con limitadores distribuidos en PostgreSQL, `0` errores 5xx)**, absorbiendo ráfagas hasta **1.000 concurrentes (`201 req/s`)** sin caídas ni fallos en mensajería gracias al *load shedding* en feed; Realtime Gateway verificado hasta **1.500 WebSockets autenticados**.
- **Pendiente para escala masiva (2.500–5.000+ concurrentes) y cierre operativo**:
  1. Ensayo físico multi-dispositivo de llamadas y cancelación silenciosa FCM en segundo plano/Doze.
  2. Soak prolongado de 2–6 h en staging Ubuntu con Redis Streams activo, evaluación de failover multi-réplica de `signaling-server` y migración futura de contadores calientes de rate-limit a Redis + PgBouncer / réplica de lectura para `GET /v1/data/feed`.
  3. Despliegue controlado en Ubuntu previa aprobación del propietario (`deploy-stack-safely.sh`) y firma/entrega de APK release actualizada; limpieza aparte de SQL heredados fuera de runtime en `sql/hotfixes/`.
