# 16 — Roadmap de remediación según dependencias

Fecha: 2026-09-26. El orden parte de los hallazgos de `13-master-findings.md`; ninguna fecha arbitraria implica compromiso de producción.

## Inmediato

1. Llevar la corrección de Nodemailer y de PyYAML a CI y comprobar ejecución remota (AUD-001/002). No publicar hasta que el pipeline firmado termine y los controles de seguridad sigan verdes.
2. Configurar SMTP transaccional en producción mediante secretos y probar recuperación completa con una cuenta de test: correo, código, nueva contraseña y acceso (AUD-004). Conservar la negativa a imprimir tokens en producción.
3. Dar un origen HTTPS estable al backend y a señalización; comprobar DNS firmado, `/ready`, reconexión, WebSocket y flujo de video desde dos redes/dispositivos (AUD-003). Un Quick Tunnel no es un endpoint final.
4. Preparar y desplegar un release acotado de los cambios validados, con backup, comparación de digest/capacidades de `/ready`, smoke desde clientes y rollback probado (AUD-012). El release público actual es del 25 de septiembre y no contiene las correcciones locales del 26.

## Corto plazo

5. Ejecutar matriz de flujos críticos con dos cuentas: login/cambio de cuenta, chat y documento, contador leído/no leído, publicación/reproducción de video, LIVE espectador/creador, regalos, notificaciones y baja de contenido. Adjuntar evidencia por ruta y dispositivo (AUD-006).
6. Instrumentar latencia p50/p95/p99 por etapa (cliente, TLS, edge, API, DB, media) y establecer presupuestos por acción, basados en mediciones autenticadas. Optimizar sólo el cuello identificado (AUD-007).
7. Revisar cada ruta API por identidad, propiedad de objeto, capacidad, rate limit, idempotencia y respuesta; priorizar medios privados, pagos, administración y mensajes.

## Mediano plazo

8. Extraer módulos de `data-routes.js`, `server.js`, `SocialScreen.kt` y `signaling-server/index.js` mediante contratos y pruebas de regresión; evitar un refactor masivo simultáneo con release.
9. Revisar índices y consultas PostgreSQL con `EXPLAIN (ANALYZE, BUFFERS)` sobre datos de escala representativa; probar restore de backup y migraciones desde una copia.
10. Completar SBOM, licencias de dependencias/assets y escaneo de imágenes OCI y Gradle; automatizar actualizaciones con revisión.

## Largo plazo

11. Ejecutar ensayos de carga de video/LIVE y recuperación ante fallas de red, Redis, API y DB; planificar capacidad y aislamiento horizontal de media, signaling y workers con SLO medidos.
12. Mantener matriz de accesibilidad (TalkBack, 48 dp, contraste, orientación y tamaños) y diseño responsive de acceso/LIVE con evidencia visual en varios dispositivos.

El estado de cada acción se actualiza en `01-coverage-ledger.md`; no se declara el sistema listo para producción hasta cumplir el cierre y las pruebas externas de los puntos 1–7.
