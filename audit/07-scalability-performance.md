# 07 — Escalabilidad y rendimiento (línea base parcial)

Fecha: 2026-09-26. No hay resultado de prueba de carga ni SLO validado. Un objetivo de «todo en milisegundos» no se puede garantizar en redes móviles sin medir distancia, radio, DNS, TLS y peso de media.

| Medición de la tanda previa | Muestra | Interpretación limitada |
|---|---:|---|
| `/ready` por proxy local en mini PC | 2,7–6,6 ms (5 solicitudes) | Salud del proceso local; no mide una pantalla. |
| `/ready` desde laptop por Quick Tunnel | 0,49–0,60 s (6 solicitudes) | Incluye internet/túnel; no separa DNS, TLS ni API. |
| `GET /v1/data/live?limit=1`, `/videos?limit=1` sin sesión | 401 en 0,46–0,74 s | La autorización responde; no representa feed autenticado. |
| `/ready` público, comprobación nueva de esta pasada | HTTP 200 en 1,15 s, 0,52 s y 0,57 s | Sigue operativo en ese momento, con una primera solicitud más lenta; origen temporal. |

La configuración tiene Nginx con keepalive, `gzip` sólo para metadatos, transferencia directa para subidas y Nginx `sendfile`/Range para media autorizada; Compose limita CPU, memoria y procesos. Son medidas positivas, aunque no demuestran capacidad concurrente. Signaling usa Redis Streams; una prueba Redis quedó omitida localmente.

El puerto API `192.168.18.22:3001` no acepta conexión desde la laptop (3 intentos ~2 s). Es coherente con el mapeo Compose a `127.0.0.1`; no se trata como caída del servicio. Las mediciones locales de la tanda previa se hicieron desde la mini PC por el proxy.

La APK **debug** existente pesa 257,7 MiB: una inspección ZIP atribuye 163,5 MiB comprimidos a `lib/` con cuatro ABI y dos familias WebRTC (`libxeno_native`, `liblkjingle_peerconnection_so`, `libjingle_peerconnection_so`). El App Bundle **release de prueba**, con R8/resource shrinking y firma efímera, mide 119,47 MiB; el verificador de presupuestos estima hasta 59,78 MiB de descarga comprimida por dispositivo con bundletool. Pasaron sus límites configurados de AAB (200 MiB), descarga (100 MiB), DEX comprimido (25 MiB) y mayor biblioteca nativa por ABI (30 MiB). No es tamaño instalado ni prueba de fluidez. Se conservaron los activos originales sin borrar archivos no seguidos por Git.

Siguiente experimento: endpoint estable y datos de prueba, carga escalonada por acciones reales (chat, feed de video, LIVE, regalo) con p50/p95/p99 y errores; correlacionar con request ID y tiempo upstream de Nginx, tiempo API y PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`. Comparar antes/después con la misma red y dispositivos. Optimizar el mayor contribuyente antes de prometer reducción del 50 %.
