# 12 — Observabilidad (primera pasada)

Fecha: 2026-09-26. La API implementa `Server-Timing` y `X-Response-Time` al enviar cabeceras, y advierte solicitudes que superan un umbral configurable (por defecto 1.500 ms) con `requestId`, método, plantilla de ruta, estado y duración. En producción `safeErrorLogDetails` usa una referencia hash y no registra el mensaje completo. Nginx registra `$request_id`, método, `$uri` sin query, status, bytes, `request_time` y `upstream_response_time`. Hay reglas Prometheus y un Compose de monitoreo separado bajo `infrastructure/monitoring/`.

Esto proporciona señales parciales; no se verificó que Prometheus/Grafana estén activos en la mini PC ni que el `requestId` de edge se propague a API, signaling y dispositivo. Tampoco hay una medición p95 autenticada por pantalla. Un error de red en UI debe llevar ID correlacionable, mensaje entendible y reintento seguro sin repetir pagos o publicaciones.

Pruebas siguientes: provocar un 401, un 500 controlado, desconexión de Redis y un upload cancelado en entorno de prueba; correlacionar cliente → edge → API → DB; comprobar que logs no contengan tokens, emails, URLs firmadas ni contenido de chat; medir retención y alertas de error-rate/latencia/disponibilidad. No activar captura sensible sólo para obtener trazas.
