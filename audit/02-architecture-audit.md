# 02 — Auditoría de arquitectura (primera pasada)

Estado: parcial, 2026-09-26. Ver inventario y diagrama en `00-system-inventory.md`.

La separación por procesos entre Android/web, edge Nginx, API Express, realtime, signaling, chatbot, PostgreSQL y Redis da límites operativos claros. La API concentra autenticación, datos, media, transacciones y coordinación. El cliente Android combina caché Room con servicio remoto; la fuente de verdad de mensajes y operaciones sensibles debe seguir siendo el servidor. El web usa React Router y verificación del descubrimiento de backend firmado.

| Flujo | Camino trazado en código/configuración | Fallo o hueco comprobable |
|---|---|---|
| Arranque Android | `SocialApp` → `MainActivity` → navegación de acceso/principal → repositorios Hilt → origen HTTPS descubierto | Compilación y arranque básico en emulador probados; aún no se midió tiempo al primer contenido autenticado ni ciclo completo multicuenta. |
| Solicitud HTTP | Cliente → Cloudflare Tunnel → Nginx `default.conf` → API → PostgreSQL/storage/servicio externo | Quick Tunnel temporal rotó antes; origen estable pendiente (AUD-003). Nginx distingue `/socket.io/`, `/realtime/` y almacenamiento. |
| Media privada | API verifica acceso y devuelve `X-Accel-Redirect` → Nginx `/_novvin_storage/` interno, con Range | Diseño correcto en configuración; tests de media privada pasan. Falta ensayo de todas las variantes de objetos/publicación. |
| Recuperación | UI → rutas API → token → entrega SMTP → confirmación y nueva sesión | La implementación SMTP existe, pero producción está en `disabled` (AUD-004). |
| LIVE | Android/web → API para metadatos/regalos; signaling/LiveKit/WebRTC para medios y eventos | Tests de reglas de regalos y señalización, sin ensayo multicliente en esta pasada. |

El riesgo de mantenimiento más visible es concentración de lógica: `infrastructure/local-fallback/api/src/data-routes.js` ronda 13.921 líneas y `server.js` 3.026; `SocialScreen.kt` ronda 3.809 y `signaling-server/index.js` 2.800. El tamaño por sí solo no demuestra fallo, pero dificulta delimitar propietarios, revisar autorización por ruta y evitar regresiones. Recomendación: extraer por dominio mediante contratos y pruebas ya existentes, uno a uno, sin alterar simultáneamente esquemas y API pública.

No se ha dibujado todavía un diagrama de datos por entidad ni verificado la versión desplegada de cada servicio. La documentación histórica de `docs/` no sustituye la comparación ejecutable con producción.
