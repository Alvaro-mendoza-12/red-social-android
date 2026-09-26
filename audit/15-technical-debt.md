# 15 — Deuda técnica por riesgo

Fecha: 2026-09-26. No toda deuda exige un refactor inmediato; se prioriza lo que impide validar seguridad, operación y cambios futuros.

| Tipo | Observación con evidencia | Riesgo y tratamiento |
|---|---|---|
| Infraestructura/operaciones | Quick Tunnel temporal y SMTP de recuperación desactivado (AUD-003/004). | **Peligrosa para publicación**. Resolver antes de anunciar disponibilidad y recuperación. |
| Testing | Suites unitarias amplias, sin E2E completo con dos cuentas/dispositivos y servidor público (AUD-006). | **Peligrosa para publicación**. Matriz por flujo y evidencia reproducible. |
| Dependencias | Nodemailer antiguo y PyYAML implícito (AUD-001/002), ambos corregidos localmente. | Reducida; falta confirmar CI remoto y SBOM global. |
| Arquitectura/código | `data-routes.js`, `server.js`, `SocialScreen.kt` y `signaling-server/index.js` concentran miles de líneas. | Tolerable a corto plazo con pruebas; extraer por dominio sin Big Bang. |
| Datos | Esquema distribuido entre init, migraciones y `schema-initializer.js`; no se comparó con DB viva. | Riesgo de drift; inventario de migraciones aplicadas y restore en staging. |
| Rendimiento | Hay tiempos locales y públicos de `/ready`, no p95/p99 de acciones reales (AUD-007). | Riesgo de optimización a ciegas; instrumentar antes de prometer mejoras. |
| UX/accesibilidad | Acceso probado en un tamaño; otros recorridos y TalkBack incompletos. | Tolerable para desarrollo, riesgo alto de inclusión/release. |
| Documentación | Versión oficial discrepaba de Gradle; corregida (AUD-005). | Generar datos desde fuente de verdad. |
| Licencias/privacidad | Procedencia de activos y términos de música sin revisión completa (AUD-008); retención/exportación de datos no auditadas aquí. | Riesgo de lanzamiento; inventario y revisión jurídica/técnica. |

La deuda se cierra con prueba de comportamiento y operación, no sólo moviendo archivos o reduciendo líneas.
