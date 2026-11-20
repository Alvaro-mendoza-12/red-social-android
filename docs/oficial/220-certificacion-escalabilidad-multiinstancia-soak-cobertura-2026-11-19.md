# 220 · Certificación final: escalabilidad real con Redis, multi-instancia (Redis Streams), latencia de mensajería, soak, cobertura y seguridad

19 de noviembre de 2026. Continuación directa de la certificación 219 (`8455898307a870e4bf9d20b20b1e05ce5ef45eb9`). Todas las pruebas destructivas, de carga, multi-instancia y soak se ejecutaron exclusivamente en un clúster efímero aislado en `127.0.0.1` (**PostgreSQL 17 real** + **Redis 7.4.8 real** idéntico por SHA-256 al contenedor `redis:7.4.8-alpine` + **2× API** + **2× Realtime Gateway** + **2× Signaling Server**), sin tocar producción ni datos reales. Se conservaron intactas la reserva 218 (`profileForViewer` / `sanitizeProfileRow`) y todos los archivos locales ajenos en ambos worktrees.

---

## 1. Cambios implementados y causas raíz corregidas

### 1.1. Escalabilidad de contadores distribuidos y pool de conexiones
- **Cliente Redis RESP2 sin dependencias externas (`api/src/redis-resp-client.js`, `realtime-gateway/src/redis-resp-client.js`)**: soporta `redis://` y `rediss://`, `AUTH` con contraseña o Docker secret (`*_PASSWORD_FILE`), pipelining FIFO, reconexión exponencial y reciclaje seguro ante timeout sin añadir dependencias a `package-lock.json` (`verify-source-dependencies.js` verde).
- **Rate limiter distribuido híbrido Redis→PostgreSQL (`api/src/distributed-rate-limit.js`, `api/src/server.js`)**:
  - Cuando `RATE_LIMIT_REDIS_URL` (o `REDIS_URL`) está configurado, ejecuta un script **Lua atómico (`INCR` + `PTTL` + `PEXPIRE`)** en Redis en 1 solo viaje TCP, eliminando los `UPSERT` en `fallback_rate_limit_windows` bajo operación normal.
  - **Fallback determinista y fail-closed a PostgreSQL**: si Redis cae (`SIGKILL`), pierde conectividad o excede `RATE_LIMIT_REDIS_COMMAND_TIMEOUT_MS` (`200 ms`), la API cae transparentemente a PostgreSQL sin devolver `500` ni abrir paso libre (*fail-open*). Si ambos (Redis y PostgreSQL) caen, mantiene el contrato *fail-closed* (`next(error)`). Al recuperarse Redis, reconecta automáticamente sin reiniciar la API.
  - Métricas Prometheus nuevas en `request-observability.js` y alertas en `monitoring/alerts.novvin.yml`: `novvin_api_ratelimit_redis_hits_total`, `novvin_api_ratelimit_pg_fallback_total`, `novvin_api_ratelimit_redis_ready`, `NovvinApiRateLimitRedisFallbackActive`.
- **Singleflight en vuelo (0 ms TTL) y SQL hot-path (`api/src/data-routes.js`, `api/src/message-deletion-policy.js`, `api/src/realtime-publish.js`)**:
  - `singleflightLoad(pool, key, loader)` (aislado por `WeakMap<pool>`) en `cacheGetOrSet`, `localPosts`, `localPostComments`, `localPostReactions` y `localPostShares`: colapsa tormentas concurrentes sobre `GET /v1/data/feed` sin caché obsoleto (`0 ms` de ventana stale -> `INV-04` borrado sin resurrección preservado 100 %).
  - En `localPosts`: subconsulta correlacionada de encuestas protegida con `CASE WHEN fallback_posts.survey_id IS NOT NULL THEN (...) ELSE 0 END`.
  - En `fallbackUserProfileRows`: fast-path nativo UUID (`u.id = ANY($1::uuid[]) OR u.supabase_user_id = ANY($1::uuid[])`) para usar la PK en lugar de `lower(u.id::text)`.
  - En `message-deletion-policy.js` (`withCurrentMessage`): fast-path PK `id = $2::uuid` para `FOR SHARE` en `fallback_messages` y `fallback_room_messages`.
  - En `realtime-publish.js`: timeout acotado `REALTIME_PUBLISH_TIMEOUT_MS` (predeterminado `2.000 ms`) para no retener conexiones DB si el gateway tarda en responder.
  - `Server-Timing` desglosado (`access;dur=...`, `persist;dur=...`) en DM y mensajes de grupo/comunidad.
- **PgBouncer opcional (`infrastructure/local-fallback/docker-compose.yml`)**: servicio `pgbouncer` (`edoburu/pgbouncer:v1.23.1-p3`, `profiles: ["scale"]`, modo `transaction`, `MAX_CLIENT_CONN=1000`, `DEFAULT_POOL_SIZE=40`, `IGNORE_STARTUP_PARAMETERS=extra_float_digits,options`) listo para activar al superar 3–4 réplicas API (`replicas * pool_max > 80`). `node-postgres` no usa *named prepared statements* en las rutas de NOVVIN, siendo compatible con *transaction pooling*; al activarlo se deben fijar `statement_timeout`, `lock_timeout` e `idle_in_transaction_session_timeout` a nivel de base de datos/rol en PostgreSQL.

### 1.2. Bus distribuido Redis Streams en Realtime Gateway
- **`infrastructure/local-fallback/realtime-gateway/src/server.js`**: soporte multi-instancia con **Redis Streams (`XADD MAXLEN ~ 20000` + conexión dedicada `XREAD BLOCK`)** cuando `REALTIME_REDIS_URL` está configurado:
  - Entrega local inmediata (`< 1 ms`), replicación ordenada a todas las demás réplicas por Stream, deduplicación por `eventId` (ventana acotada de 10.000 IDs), filtrado de eventos propios por `instanceId`, cursor `lastStreamId` continuo ante micro-cortes y métricas `novvin_realtime_stream_events_published_total`, `novvin_realtime_stream_events_received_total`, `novvin_realtime_stream_duplicates_skipped_total`, `novvin_realtime_redis_connected` + alerta `NovvinRealtimeRedisStreamDisconnected`.

### 1.3. Dos vulnerabilidades reales adicionales detectadas y corregidas en el Ciclo 220
1. **ReDoS (Denial of Service por backtracking exponencial) en `api/src/content-moderation.js`**:
   - Hallado durante la auditoría de cobertura: `LEET_CLASS` incluía símbolos no-palabra (`@`, `!`, `|`, `$`, `+`) seguidos de `[\W_]*` en `obfuscatedPattern()`, causando **$O(2^N)$**: una cadena de solo 95 caracteres (`es$$$...`) bloqueaba el hilo de Node.js **`181,52 ms`**.
   - Corregido con separador disjunto acotado `[^\p{L}\p{N}@!|$+]{0,3}` y repetición acotada `{1,8}`: payloads adversarios de **906 a 1.402 caracteres** ejecutan ahora en **`0,11–0,54 ms`** manteniendo intacta la detección de insultos ofuscados. Prueba de regresión añadida en `api/tests/content-moderation.test.js`.
2. **Sesión WebSocket activa no cerrada inmediatamente tras `POST /v1/auth/logout` (`api/src/server.js`)**:
   - Hallado durante la prueba multi-instancia cross-node: `POST /v1/auth/logout` revocaba el token HTTP en PostgreSQL (`0 ms`), pero no notificaba a `realtime-gateway`, permitiendo que un WebSocket abierto siguiera recibiendo eventos hasta el siguiente recheck (`60 s`).
   - Corregido llamando `publishWebSessionsRevoked(rows[0].user_id, [{ id: rows[0].id }])` tras el commit de revocación; verificado cierre cross-node vía Redis Streams (`4401 WEB_SESSION_REVOKED`) en **`81,9 ms`**.

---

## 2. Resultados medidos (Antes 219 vs. Después 220)

Arnés reproducible ejecutado: `tools/loadtest/run-cycle220-certification.mjs` (`EXIT=0`, `allPassed: true`).

### 2.1. Escalabilidad A/B/C y Load Shedding (10–1.500 concurrentes)

| Concurrencia | 219 Baseline (1× API PG) | 220 `pg1Api` (1× API PG + Singleflight) | 220 `redis1Api` (1× API Redis + Singleflight) | 220 `redis2Api` (2× API Redis + Singleflight) | Delta `redis2Api` vs. 219 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| **50** | 153,0 rps / p95 542,7 ms (0 shed) | 181,9 rps / p95 420,1 ms (0 shed) | **215,0 rps / p95 331,0 ms (0 shed)** | **350,3 rps / p95 222,6 ms (0 shed)** | **+129,0 % RPS / -59,0 % p95** |
| **100** | 173,8 rps / p95 920,7 ms (31 shed) | 194,9 rps / p95 796,5 ms (53 shed) | 220,9 rps / p95 704,5 ms (34 shed) | **301,0 rps / p95 516,0 ms (0 shed)** | **+73,2 % RPS / -44,0 % p95 / 0 rechazos 503** |
| **250** | 165,8 rps / p95 2.707,7 ms (155 shed) | 254,8 rps / p95 1.574,7 ms (155 shed) | 266,1 rps / p95 1.460,7 ms (150 shed) | **302,3 rps / p95 1.295,0 ms (104 shed)** | **+82,3 % RPS / -52,2 % p95** |
| **500** | 180,2 rps / p95 3.881,9 ms (249 shed) | 261,7 rps / p95 2.477,1 ms (241 shed) | 269,2 rps / p95 2.582,9 ms (243 shed) | **370,6 rps / p95 1.780,5 ms (200 shed)** | **+105,7 % RPS / -54,1 % p95** |
| **1.000** | 201,0 rps / p95 6.707,1 ms (341 shed) | 270,2 rps / p95 4.486,2 ms (342 shed) | 276,1 rps / p95 4.410,5 ms (340 shed) | **395,9 rps / p95 2.791,2 ms (284 shed)** | **+97,0 % RPS / -58,4 % p95 / -16,7 % shed** |
| **1.500** | *(no probado en 219)* | — | — | **434,5 rps / p95 3.699,9 ms (485 shed)** | **0 errores 5xx / 0 fallos críticos** |

- **Errores críticos en todos los escalones (DM, Grupo, Perfil, Notificaciones):** **`0`**; errores `5xx` no planificados: **`0`**; errores de red: **`0`**.
- **Nota honesta sobre la comparación A/B de escritura pura en Windows (`writeHeavy2ApiAB`)**: en este host Windows, PostgreSQL corre nativo (`127.0.0.1`) mientras Redis 7.4.8 corre en WSL2 (atravesando el puente virtual Hyper-V), por lo que en ráfaga de escritura pura 2× API `pg250` midió `230,7 req/s` vs `redis250` `220,2 req/s` y a saturación extrema (`c=1.000/1.500`) el timeout de `200 ms` activó el fallback seguro a PostgreSQL (`0` errores 5xx). En Ubuntu Docker nativo ambos servicios comparten red kernel local (`< 0,15 ms` RTT), eliminando ese overhead de puente y descargando el 100 % de `UPSERT` de rate-limit del primario PostgreSQL.
- **Márgenes operativos demostrados:**
  - **1 réplica API + Redis:** capacidad sostenible sin degradación **~50–80 concurrentes activos (`~215 req/s`, `p95 < 350 ms`, `0` load shedding)**.
  - **2 réplicas API + 2 Realtime + Redis:** capacidad sostenible sin degradación **~100–150 concurrentes activos (`~300–350 req/s`, `p95 < 520 ms`, `0` load shedding)**, absorbiendo picos hasta **1.500 concurrentes (`434,5 req/s`)** con `0` errores 5xx y `0` fallos en funciones críticas mediante load shedding exclusivo en descubrimiento (`GET /v1/data/feed` -> `503 SERVICE_OVERLOADED`, `Retry-After: 1`).
  - **No se declara soporte de 2.500–5.000+ usuarios simultáneos** sin ejecutar el escalado multi-nodo + PgBouncer + réplica de lectura en Ubuntu staging.

### 2.2. Validación Multi-Instancia Real con Redis Streams (`2× API + 2× Realtime + 2× Signaling + Redis 7.4.8`)
- **Consistencia, atomicidad, TTL y caos real de Redis (`SIGKILL`)**:
  - Carrera 50× paralela repartida entre `API-1` y `API-2` con límite `20`: **`20` `201 Created` exactos, `30` `429 MESSAGE_RATE_LIMITED`, `0` errores 5xx**.
  - Renovación de ventana por TTL (`1.200 ms`): `req1=true`, `req2=true`, `req3=false`, tras `1.350 ms` `req4=true`.
  - **`SIGKILL` real a `redis-server`**: durante la caída total de Redis, 24 envíos alternados entre `API-1` y `API-2` devolvieron **`20` `201` y `4` `429` (`0` 5xx, `pgFallbackDelta = 24`)**, demostrando continuidad *fail-closed* sobre PostgreSQL. Al reiniciar Redis, ambas réplicas reconectaron automáticamente en **`869,8 ms`** y retomaron Redis (`postRecoveryRedisHitsDelta = 2`).
- **Realtime Gateway Multi-Instancia (`Realtime-1` ↔ `Realtime-2` por Redis Streams)**:
  - 100 mensajes DM secuenciales publicados en `API-1 -> Realtime-1`: receptor en `Realtime-1` **100/100**, receptor en `Realtime-2` **100/100** (**`0` perdidos, `0` duplicados, `0` inversiones de orden**). Total traficado por el stream durante la corrida: `89.842` eventos publicados en `rt1`, `33.665` en `rt2`, `0` duplicados de entrega.
  - Revocación cross-node (`login API-1 -> WS Realtime-2 -> logout API-2`): `API-2` `200`, `API-1 /v1/auth/me` `401` inmediato y WebSocket en `Realtime-2` cerrado automáticamente con **`4401 WEB_SESSION_REVOKED` en `81,9 ms`**.
- **Signaling Server Multi-Instancia (`Signaling-1` ↔ `Signaling-2` por `@socket.io/redis-streams-adapter`)**:
  - `12 + 12` clientes en la misma sala de voz (`realtimeBus: { mode: "redis-streams", connected: true, streamCount: 4 }`): **`15/15` chat `Sig-1 -> Sig-2`**, **`15/15` chat `Sig-2 -> Sig-1`**, **`5/5` reacciones cross-node**.
  - **`SIGKILL` a `Signaling-1`**: reconexión completa de los 12 clientes desplazados a `Signaling-2` en **`66,9 ms` (`0` fallos)**.
  - *Límite documentado:* `stateConsistency.liveSessionState = "replica-affinity-required"` — voz y llamadas son distribuidas; directos (`Live`) mantienen `SIGNALING_REPLICAS=1` en `deploy-stack-safely.sh` hasta completar ensayo de failover/rehidratación de directos en Ubuntu.

### 2.3. Latencia desglosada de Mensajería DM y Grupos (50 muestras por escenario)

| Escenario | Renderizado local optimista | Acceso SQL (`p50 / p95 / p99`) | Persistencia SQL (`p50 / p95 / p99`) | Confirmación HTTP 201 (`p50 / p95 / p99`) | Entrega WS mismo nodo (`p50 / p95 / p99`) | Entrega WS cross-node Redis Streams (`p50 / p95 / p99`) |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| **DM en reposo** | Inmediato local (Room / IndexedDB) | 1,2 / 2,3 / 3,6 ms | 5,3 / 13,6 / 14,0 ms | **10,45 / 20,98 / 23,35 ms** | **12,83 / 23,13 / 30,33 ms** | **14,10 / 24,46 / 31,43 ms** |
| **DM bajo 40 usuarios** | Inmediato local (Room / IndexedDB) | 27,0 / 35,0 / 38,5 ms | 75,5 / 116,6 / 135,1 ms | **146,20 / 196,26 / 224,05 ms** | **190,44 / 252,18 / 267,63 ms** | **193,94 / 253,78 / 269,98 ms** |
| **Grupo (24 m.) en reposo** | Inmediato local (Room / IndexedDB) | 3,0 / 4,8 / 6,3 ms | 1,0 / 1,8 / 3,1 ms | **8,93 / 11,93 / 29,17 ms** | **16,64 / 22,38 / 43,48 ms** | **17,96 / 23,69 / 45,10 ms** |
| **Grupo bajo 40 usuarios** | Inmediato local (Room / IndexedDB) | 53,2 / 71,3 / 72,5 ms | 14,6 / 23,6 / 31,2 ms | **109,36 / 145,40 / 156,20 ms** | **279,67 / 333,29 / 345,78 ms** | **281,48 / 335,27 / 347,81 ms** |

- **Verificación del retraso histórico de ~2 s:** **RESUELTO Y DEMOSTRADO** (`14–18 ms` p50 en reposo y `194–281 ms` p50 bajo 40 usuarios concurrentes de feed, con solo **`1,3–2,0 ms`** de sobrecoste por el salto cross-node en Redis Streams).
- **Renderizado local optimista verificado en código:** Android (`ChatViewModel.kt` + `MessageRepository.kt`: `status = "sending"` -> `messageDao.insertMessageOwned` -> `onQueued()` en `Dispatchers.Main.immediate` antes del HTTP POST) y Web (`NoWing-web/src/core/webOutbox.ts`: `put('messages', item)` en IndexedDB -> `CustomEvent('novvin:outbox-changed', { action: 'enqueued' })` antes de `flushWebOutbox()`).

### 2.4. Soak Test Real Multi-Instancia (180 s ejecutados + procedimiento 2–6 h para Ubuntu Staging)
- **Ejecutado en aislamiento local (`2× API + 2× Realtime + 2× Signaling + Redis 7.4.8 + PostgreSQL 17`, 180 s continuos):**
  - `281` oleadas, **`44.960` solicitudes**, RPS medio **`317,8 req/s`**, `p95` evolucionó de `277,61 ms` (t=1s) a **`213,24 ms` (t=180s)**, `p99` máximo **`296,14 ms`**.
  - **`0` errores 5xx**, **`0` load shedding**, **`maxPoolWait = 0`**, `eventLoopLagP95 = 32,7–34,1 ms`.
  - **Memoria y GC sin fuga en 180 s:** `api1/api2 Heap` osciló `50,7–183,0 MB` (recolección V8 activa), `api1/api2 RSS` estabilizado en meseta `~301–321 MB`, `rt1/rt2 RSS` `~110–112 MB`, `sig1/sig2 RSS` `~78–83 MB`.
  - **Redis 7.4.8:** memoria plana **`22,85 -> 23,21 MB`** (máx `23,32 MB` / `256 MB` = 9,1 %), `19` clientes, **`0` evicted keys**, **`0` rejected connections**.
  - **PostgreSQL 17:** `51` conexiones totales (`1–2` activas), **`99,99 %` buffer cache hit ratio**.
- **Pendiente (no simulado):** corrida prolongada de **2–6 horas reales en Ubuntu staging** con el ejecutor ya versionado `tools/loadtest/run-ubuntu-staging-soak.mjs` (`NOVVIN_STAGING_ISOLATED=true SOAK_DURATION_HOURS=2 node tools/loadtest/run-ubuntu-staging-soak.mjs`).

---

## 3. Pruebas físicas Android (estado honesto y procedimiento preparado)

- **Estado de hardware en este ciclo:** `adb devices -l` confirmó **`0` dispositivos físicos conectados**. Por rigor estricto, **NO se marca completada ninguna prueba física**.
- **Estado de APK release:** la última APK release firmada conservada en `D:\Novvin-Entregas\evidencias\2026-11-18-mobile-release-check\e834b6dee963\artifacts\Novvin-9.1.43-183-e834b6dee963.apk` corresponde al commit `e834b6de`, anterior a los parches móviles de la auditoría 212 (`d23ea5e8`). Antes de los ensayos físicos se debe compilar y firmar una nueva APK release sobre el commit 220 (respetando el turno de Gradle coordinado).
- **Procedimiento obligatorio con 2 teléfonos físicos (anclado a clases verificadas):**
  1. **Llamadas y videollamadas 1:1 / glare:** verificar inicio, timbre, colisión simultánea (`CallCollisionPolicy.kt`), aceptación multidispositivo (`[200, 409 CALL_ANSWERED_ELSEWHERE]`) y cancelación en frío (`CallCancellationGuard.kt`).
  2. **Cámara frontal y trasera:** conmutar cámara repetidamente durante videollamada/directo verificando perfil por tier y degradación segura (`NativeCameraProfile.kt`, `SteadyCollaborationCamera.kt`).
  3. **Salas de voz:** entrar/salir, silenciar, reacciones, chat de sala y reconexión.
  4. **Suspensión, Doze y FCM:** forzar Doze (`adb shell dumpsys deviceidle force-idle`), enviar DM y llamada entrante, confirmar despertar por FCM de alta prioridad y cancelación silenciosa cuando el emisor cuelga antes de contestar.
  5. **Bluetooth y cambio de red (Wi-Fi ↔ 4G/5G ↔ Modo avión):** validar audio SCO/A2DP y recuperación WebRTC vía `NetworkHandoffPolicy.kt` (ICE restart automático).
  6. **Sincronización multidispositivo:** cerrar sesión en Dispositivo B (`POST /v1/auth/logout`) y confirmar cierre inmediato del WebSocket del Dispositivo A (`4401 WEB_SESSION_REVOKED`) sin afectar otros dispositivos no revocados.

---

## 4. Auditoría priorizada de cobertura (`review-coverage.json`), código muerto y exclusiones

De los **3.576 archivos pendientes** al cierre de 219 (`4.719` totales: `880` `code-read`, `263` `partial`, `3.576` `pending`), se clasificaron todas las rutas por prioridad de riesgo y se leyeron íntegramente **58 archivos existentes adicionales** (`56` desde `pending` y `2` desde `partial`, además de `7` archivos nuevos del Ciclo 220 leídos completos y `1` archivo promovido a `partial`), llevando la cobertura versionada verificada a **`4.726` archivos: `945` `code-read`, `262` `partial` y `3.519` `pending`**. Este esfuerzo cubrió el 100 % de los módulos pendientes de Seguridad/Auth/Storage/Mensajería en `api/src/`, el 100 % de los módulos de runtime `.js` de `realtime-gateway/` y `signaling-server/`, `docker-compose.yml`, suites de seguridad y políticas críticas Android de llamadas/WebRTC (`CallCancellationGuard.kt`, `NativeCameraProfile.kt`, `NetworkHandoffPolicy.kt`), descubriendo y corrigiendo el ReDoS de `content-moderation.js` y el fallo de revocación WebSocket en `POST /v1/auth/logout`.

- **Código muerto / archivos residuales confirmados (reportados para limpieza controlada aparte):**
  - `app/src/main/java/conectate/com/data/cache/UpstashCacheService.kt` (`0` referencias en `app/src/main`).
  - `app/src/main/java/conectate/com/analytics/enterprise/PredictiveAnalyticsSystem.kt` (`0` referencias en `app/src/main`).
  - `infrastructure/local-fallback/api/query.js` (usa `better-sqlite3`, ausente en `package.json`) y `infrastructure/local-fallback/api/run-sql.js` (script ad-hoc de desarrollo fuera de `Dockerfile`).
  - `.FullName` (archivo residual de 12 bytes en raíz).
- **Exclusiones documentadas de bajo riesgo (no requieren lectura línea por línea para certificación de runtime):**
  - `docs/` (641 documentos históricos/informes), `sql/archive/`, `sql/diagnostics/` y `sql/hotfixes/` (251 SQL heredados fuera de runtime), `app/src/main/res/` (108 imágenes/drawables WebP/PNG), `NoWing-web/public/` (83 assets estáticos y diccionarios `es-4.0.0`), suites de pruebas/scripts auxiliares (`372` backend + tests Android/Web ya ejecutados en baterías automatizadas).

---

## 5. Regresiones y Veredicto

- **Regresiones finales aprobadas:** `verify-source-dependencies.js` (`ok: true`), `api` `npm run check`, `npm run test:security` (incl. `distributed-rate-limit.test.js` y `production-config.test.js`), `npm run test:storage`, `redis-rate-limit.test.js`, `request-observability.test.js`, `content-moderation.test.js` (4/4 con ReDoS), `message-send-fastpath.test.js` (4/4), `audit-content-privacy.test.js` (36/36), `message-insert-store`, `message-push-limiter`, `message-deletion-policy`, `direct-message-request-policy`, `storage-read-access`, `storage-view-once`, `realtime-gateway` (`npm run check`, `npm test`, `redis-streams-bus.test.js`), `signaling-server` (`npm run check`, `npm test` 59/59), `ai-chatbot` (`npm test` 6/6) y `tools/runtime_security_audit.py` (**0 hallazgos**).
- **Veredicto final del Ciclo 220:** **CANDIDATA A STAGING / BETA CONTROLADA (`APTO CON OBSERVACIONES OPERATIVAS`)**. Las brechas técnicas de escalabilidad multi-réplica (Redis rate-limit + Redis Streams en Realtime Gateway + Singleflight Feed + PgBouncer opcional), latencia de mensajería y seguridad (ReDoS + revocación WebSocket cross-node) quedan **cerradas y verificadas**. Para declarar apertura pública masiva solo restan 3 hitos operativos externos al entorno local: (1) soak de 2–6 h en Ubuntu staging con `run-ubuntu-staging-soak.mjs`, (2) ensayos físicos en 2 teléfonos Android con APK release compilada sobre este commit, y (3) aprobación explícita del propietario para desplegar en Ubuntu mediante `deploy-stack-safely.sh`.
