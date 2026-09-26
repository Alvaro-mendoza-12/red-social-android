# 14 — Decisiones que conviene preservar

Fecha: 2026-09-26. Estos puntos están respaldados por código o pruebas; su alcance es el indicado.

| Decisión | Evidencia y por qué conservarla | Límite |
|---|---|---|
| Quality gate Android con compilación y unit tests | `.github/workflows/android-ci.yml` y tareas Gradle; 844 tests Android pasaron en la tanda previa. Detecta regresiones de compilación antes de release. | No garantiza recorrido real con servidor. |
| Secretos de sesión separados por cuenta | `AccountSessionRepository.kt` y pruebas de cambio rápido; reduce mezcla accidental de cuentas. | Falta alternancia con dos cuentas reales y tokens expirados. |
| Lectura de mensajes con cursores/visibilidad por usuario | Pruebas API de read status, incluidos grupo/comunidad, otra cuenta y mensajes fuera de pantalla. Evita limpiar contadores de mensajes no vistos. | Falta prueba multi-dispositivo/push. |
| Negación explícita para media privada | Tests API de chat media, storage ownership y acceso no autorizado. Da una base contra acceso horizontal. | Hay que revisar todas las rutas y objetos. |
| Recuperación sin revelar tokens en producción | `server.js` rechaza `console`/`response` si `isProduction`; protege el secreto aunque la entrega SMTP aún no esté lista. | Con modo `disabled` el usuario no puede recuperar cuenta. |
| Descubrimiento firmado del backend y URL temporal prohibida en release | Configuración Android y flujo de release; reduce riesgo de aceptar un origen arbitrario. | Un origen estable y una prueba de campo siguen pendientes. |
| Separación de API, realtime, signaling, IA, Redis, PostgreSQL y edge | Compose y módulos por servicio permiten escalar/diagnosticar componentes por separado. | No implica alta disponibilidad ni capacidad comprobada. |
| Límites de regalos y rechazo de LIVE sin regalos | Prueba de transacción y guardas API existentes; mantiene saldo cuando el creador desactiva regalos. | Requiere prueba real de compra, animación y reconexión. |
