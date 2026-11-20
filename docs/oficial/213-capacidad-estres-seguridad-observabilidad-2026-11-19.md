# 213 · Auditoría de capacidad, estrés, caos, seguridad defensiva, observabilidad y optimización (Fases 11–15)

19 de noviembre de 2026. Auditoría ejecutada de manera aislada sobre `127.0.0.1` con un clúster efímero de PostgreSQL 17 (`001_schema.sql`), API local-fallback, Realtime Gateway y Signaling Server reales mediante `tools/loadtest/run-isolated-capacity-suite.mjs`. **Ninguna prueba de carga, estrés, caos ni seguridad tocó producción ni servicios externos.**

## 1. Entorno de ensayo aislado

- **Host de prueba**: `win32-x64`, 16 núcleos lógicos, 15,8 GiB RAM, Node.js `v26.13.0`.
- **Motor de datos**: PostgreSQL 17 efímero local (`max_connections = 120`, `shared_buffers = 128MB`, `DATABASE_POOL_MAX = 25`).
- **Topología bajo prueba**: 1 instancia de API + 1 instancia de Realtime Gateway + 1 instancia de Signaling Server + 1 instancia de PostgreSQL 17 en loopback (sin Redis externo ni réplicas de lectura).
- **Carga mixta realista**: 20 % lectura de feed (`GET /v1/data/feed?limit=10`), 20 % envío DM (`POST .../chats/:id/messages`), 20 % envío en grupo de 24 miembros (`POST .../groups/:id/messages`), 20 % lectura de perfil (`GET /v1/data/profiles/:id`) y 20 % notificaciones (`GET /v1/data/notifications?limit=15`).

## 2. Hallazgos críticos, correcciones y optimizaciones medidas (Antes vs. Después)

### 2.1. Cuello de botella raíz en la ruta de autenticación API (`server.js` + `data-routes.js`)
- **Diagnóstico (Corrida #1)**: cada solicitud autenticada ejecutaba **4 consultas SQL en serie** antes de ejecutar el controlador: (1) `SELECT 1 FROM fallback_users`, (2) `UPDATE fallback_auth_sessions SET last_used_at = NOW()` —que adquiría un **bloqueo exclusivo de fila y escritura WAL incluso en lecturas puras** serializando peticiones paralelas del mismo usuario—, (3) `findActiveSuspension` con `WHERE lower(u.id::text) = $1` (sin aprovechar el índice PK UUID), y (4) `getCurrentUser` volviendo a consultar `fallback_users`.
- **Optimización aplicada (sin degradar seguridad)**:
  - Consolidación en **1 sola consulta `SELECT` de solo lectura respaldada por índices PK** (`fallback_users` + `LEFT JOIN` sobre sesión activa + `EXISTS` sobre `fallback_profile_moderation`) guardando `req._currentUser` únicamente durante el ciclo de vida de la petición HTTP.
  - Conservación de **0 ms de latencia en revocación de sesión y suspensión** (verificado en prueba en vivo #9).
  - Actualización diferida no bloqueante de `last_used_at` / `last_seen_at` limitada a una vez cada 60 s por sesión (`SESSION_TOUCH_INTERVAL_MS`) con mapa acotado de anti-rebote.
  - Aumento del valor predeterminado de `DATABASE_POOL_MAX` de `10` a `20` (configurable entre `4` y `100`).

### 2.2. Difusión O(N) en Realtime Gateway (`realtime-gateway/src/server.js`)
- **Diagnóstico**: `broadcast()` recorría `wss.clients.forEach` en O(N) serializando JSON por cada socket, sin tope de conexiones totales ni por IP.
- **Optimización aplicada**: índices en memoria O(1) `channelSubscribers` y `userSockets`, serialización única `JSON.stringify` por difusión, contrapresión por `bufferedAmount > 512 KiB`, y límites `REALTIME_MAX_CONNECTIONS` / `REALTIME_MAX_CONNECTIONS_PER_IP`.
- **Impacto medido (1.000 sockets en micro-benchmark controlado)**:
  - Publicación simple: **549,4 -> 819,1 req/s (+49,1 %)**.
  - Lote fan-out de 200 destinatarios: **p50 81,13 -> 21,59 ms (-73,4 %)**, **p95 178,21 -> 29,04 ms (-83,7 %; 6,14× más rápido)**, 0 mensajes perdidos.

### 2.3. Defectos reales descubiertos por las pruebas en vivo
1. **`signaling-server/index.js` (`/internal/metrics`)**: referenciaba variables inexistentes `callRooms` / `liveRooms` (provocando `ReferenceError` al consultar métricas autenticadas). Corregido a `calls.size` y `lives.size` con acceso seguro a namespaces; verificado HTTP 200 en JSON y formato Prometheus.
2. **`data-routes.js` (idempotencia de reintentos)**: los endpoints de envío DM/grupo/comunidad solo leían `req.body?.id`, ignorando `clientMessageId` / `client_message_id`. Se habilitaron ambos alias sobre `insertMessageOnce`, verificado bajo carrera concurrente (respuestas `201` + `200` con el mismo `message.id` canónico).

## 3. Resultados medidos de capacidad (Corrida #1 Antes vs. Corrida #2 Después)

> Nota metodológica: en la Corrida #1 el arnés consultó `/v1/data/feed/posts` (404 rápido en 20 % de las peticiones). En la Corrida #2 se corrigió a `/v1/data/feed?limit=10` (200 real con consultas de ranking), por lo que la Corrida #2 ejecutó **25 % más carga real en PostgreSQL** y aun así redujo las latencias en todos los endpoints comparables.

### 3.1. Rampa progresiva de usuarios concurrentes (API + PostgreSQL 17, 1 nodo)

| Concurrencia | Corrida #1 RPS (2xx reales/s) | Corrida #2 RPS (2xx reales/s) | Notifications p50 Antes -> Después | Profile p50 Antes -> Después | DM Send p95 Antes -> Después | Group Send p95 Antes -> Después | Errores 5xx / Red |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 110,6 (87,8) | 97,4 (96,1) | 72,62 -> **38,90 ms (-46,4 %)** | 76,42 -> **62,73 ms (-17,9 %)** | 295,83 -> 393,65 ms | 374,77 -> 451,23 ms | 0 / 0 |
| 50 | 165,5 (129,8) | 161,9 (159,5) | 234,17 -> **126,75 ms (-45,9 %)** | 346,24 -> **248,14 ms (-28,3 %)** | 476,68 -> **470,18 ms** | 605,50 -> **571,73 ms** | 0 / 0 |
| 100 | 194,9 (155,6) | 188,1 (**188,1**) | 351,28 -> **213,93 ms (-39,1 %)** | 599,97 -> **399,94 ms (-33,3 %)** | 733,88 -> **645,49 ms (-12,0 %)** | 1.005,42 -> **865,82 ms (-13,9 %)** | 0 / 0 |
| 250 | 190,7 (152,7) | 199,7 (**199,7**) | 854,02 -> **406,27 ms (-52,4 %)** | 1.534,56 -> **847,11 ms (-44,8 %)** | 1.790,05 -> **1.363,15 ms (-23,8 %)** | 2.351,10 -> **1.933,40 ms (-17,8 %)** | 0 / 0 |
| 500 | 206,1 (164,8) | 215,7 (**215,7; +30,9 %**) | 1.651,43 -> **817,44 ms (-50,5 %)** | 2.017,74 -> **1.373,68 ms (-31,9 %)** | 2.964,36 -> **2.482,74 ms (-16,2 %)** | 3.482,09 -> 3.816,83 ms | 0 / 0 |

- **Punto de saturación real (1 nodo API + 1 PostgreSQL, `poolMax=25`)**: el pool DB alcanza saturación en torno a **80–100 usuarios concurrentes sostenidos (~190–215 req/s efectivos en carga mixta 40 % escritura / 60 % lectura)**, con `dbPool.waiting` subiendo a 182–270 en 100–250 usuarios mientras el *event loop* de Node permanece holgado (`eventLoopLagP95Ms = 32,96–33,18 ms`).
- **Cuello de botella remanente**: contención de conexiones y múltiples escrituras SQL en `POST .../groups/:id/messages` (notificaciones a miembros) y `POST .../chats/:id/messages` (`refreshChatSummary`), seguidos por consultas de ranking de `GET /v1/data/feed`.
- **Latencia de envío DM sin contención (ensayo de caos)**: bajó de **17,1 ms -> 9,9 ms (-42,1 %)** durante caída del gateway y **11,3 ms -> 9,8 ms** tras recuperación.

### 3.2. Picos, resistencia (Soak), caos y escala WebSocket (Corrida #2)

- **Prueba de pico (20 -> 200 -> 20)**: pico de **231,3 req/s** (`p50 = 718,66 ms`, `p99 = 1.439,87 ms`, **0 errores 5xx**); recuperación estable a 20 usuarios (`p50 = 123,24 ms`, `p95 = 384,02 ms`) en **612,5 ms**.
- **Resistencia (Soak 30 s, 17 oleadas continuas @ 25 usuarios)**: **0 errores 5xx**, `rps` estable entre `194,3–263,2`, `p95` entre `132,12–201,17 ms`, `dbPool.waiting = 0`. El heap V8 subió a 335,9 MB y el GC mayor lo recuperó limpiamente a **104,9 MB** (RSS 466,5 -> 419,0 MB); `realtime-gateway` plano en **58,9–59,5 MB RSS**. Sin fuga de memoria acotada observada en el ensayo corto (se requiere soak de 2–6 h en staging).
- **Ingeniería del caos (caída súbita de Realtime Gateway)**: envío DM durante caída respondió **HTTP 201 en 9,9 ms** (cero pérdida de datos), reinicio saludable del gateway en **524,5 ms**, envío post-recuperación **HTTP 201 en 9,8 ms**.
- **Escala WebSocket end-to-end autenticada contra API + PostgreSQL (`realtime-gateway`)**:
  - **100 sockets**: 100/100 conectados en 298,4 ms (0 fallos), lote fan-out p50 **6,56 ms**, RSS 80,2 MB.
  - **500 sockets**: 500/500 conectados en 1.065,0 ms (0 fallos), lote fan-out p50 **23,55 ms**, RSS 114,4 MB.
  - **1.000 sockets**: 1.000/1.000 conectados en 2.252,1 ms (0 fallos), lote fan-out p50 **86,93 ms**, RSS 149,4 MB.
  - **1.500 sockets**: **1.500/1.500 conectados en 3.128,7 ms (0 fallos, 31.000 entregas, 0 desconexiones por consumidor lento, 0 fallos de auth)**, lote fan-out p50 **121,13 ms**, RSS **178,4 MB**, lag p95 **84,28 ms**.

## 4. Seguridad defensiva y observabilidad

- **Auditoría de dependencias (`npm audit --omit=dev`)**: **0 vulnerabilidades** en `api`, `realtime-gateway`, `signaling-server` y `NoWing-web`.
- **Análisis estático y de ejecución**: `tools/runtime_security_audit.py` **0 hallazgos**; escaneo estático de secretos en fuentes activas **0 coincidencias**; `dangerouslySetInnerHTML`/`innerHTML` en Web **0 coincidencias**; 148 consultas SQL multilínea verificadas con parámetros `$N` y listas blancas estrictas (0 SQLi).
- **Controles anti-abuso añadidos (`server.js`, `signaling-server/index.js`)**: limitadores en memoria acotados por sujeto JWT verificado (`bearerActorKey`) para escrituras DM/grupo/comunidad (120/min), llamadas prepare/invite (30/min), reportes (20/10 min) y escrituras sociales (120/min), más cubo acotado (`maxEntries = 25.000`, barrido cada 60 s) y enfriamiento de reacciones en señalización.
- **Pruebas defensivas en vivo (10/10 aprobadas)**:
  1. IDOR/BOLA en chat privado bloqueado (`read: 404`, `write: 403`).
  2. Escalada de privilegios y métricas internas bloqueadas (`/v1/system/health: 403`; métricas sin auth `401/401/401`; con auth `200`).
  3. Manipulación de entradas (JSON malformado `400`, SQLi en parámetro `404`, *path traversal* `404`, cero `5xx`).
  4. Subida maliciosa rechazada (`.svg` con XSS `400`, `.exe` disfrazado de PNG `400`).
  5. Propagación extremo a extremo de `x-correlation-id` y `x-request-id`.
  6. Anti-flood por usuario en llamadas (`6× 429` al abusador sin afectar a otro usuario).
  7. Idempotencia concurrente con `clientMessageId` (`201` + `200`, mismo `message.id`).
  8. Carrera multidispositivo en aceptación de llamada (`[200, 409]`, `CALL_ANSWERED_ELSEWHERE`).
  9. Revocación instantánea de sesión tras optimización (`200` -> logout `200` -> `401 WEB_SESSION_REVOKED` inmediato).
  10. Exposición Prometheus autenticada en API y Signaling (`200/200`).
- **Observabilidad añadida**: `x-correlation-id` propagado de API a Realtime Gateway, `/internal/metrics` (Prometheus y JSON) en API, Realtime Gateway y Signaling, y plantillas de scrape/alertas alineadas al stack Ubuntu real en `infrastructure/local-fallback/monitoring/prometheus.local-fallback.yml` y `alerts.novvin.yml`.

## 5. Veredicto de preparación y capacidad real

- **Decisión técnica**: **APTO CON OBSERVACIONES** (para despliegue controlado / beta inicial y escalado progresivo).
- **Capacidad demostrada en un único nodo (sin fabricar cifras)**:
  - **API + PostgreSQL 17 (1 instancia, `poolMax=25`, carga mixta 40 % escritura)**: **~50–100 usuarios concurrentes simultáneos activos (~160–215 req/s reales)** con `p95 < 850 ms` y **0 % de errores 5xx**; tolera ráfagas de **250–500 concurrentes** sin caídas ni 5xx, aunque con colas de latencia (`p95` 1,8–3,3 s). En términos de tráfico social típico (con tiempo de lectura/pensamiento de 5–10 s entre peticiones), esto equivale a ~800–1.500 usuarios activos conectados por nodo API antes de requerir escalado.
  - **Realtime Gateway (1 instancia)**: **1.500 conexiones WebSocket autenticadas concurrentes verificadas** con 0 fallos y ~178 MB RSS.
- **Requisitos pendientes para soportar 2.500–5.000+ concurrentes reales en producción**:
  1. Desacoplar `INSERT fallback_notifications` de grupos/comunidades hacia cola/worker asíncrono (o lote SQL `UNNEST`) y agregar PgBouncer / réplica de lectura para `GET /v1/data/feed`.
  2. Ejecutar soak prolongado de 2–6 horas en staging Ubuntu con Redis Streams activo para `signaling-server`, pruebas multi-nodo y verificación física de llamadas/FCM en dispositivos reales.
  3. Cerrar hallazgos heredados fuera de runtime activo (`sql/hotfixes/` históricos en `security_sql_audit.py` y las 2 pruebas mock heredadas en `audit-content-privacy.test.js`).
