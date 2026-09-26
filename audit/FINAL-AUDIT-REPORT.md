# Informe ejecutivo de auditoría — Novvin

Fecha: 2026-09-26. Rama: `feature/live-gifts` (`D:\red social - live-gifts`). **Estado: auditoría en curso; no se certifica preparación para producción general.** La evidencia posterior de despliegue, URL estable, recuperación por códigos y APK está en [`PRODUCTION-READINESS-2026-09-26.md`](PRODUCTION-READINESS-2026-09-26.md) y actualiza los hallazgos operativos antiguos de este informe. Este informe resume la tanda Android inmediata registrada en `docs/audit-2026-09-26-android-live.md`. Los documentos 00–16 detallan alcance, límites, hallazgos y acciones. Había más de mil cambios preexistentes en el árbol; no se descartaron.

## 1. Estado general

Android, API, web, signaling, gateway realtime y chatbot compilan o pasan sus suites locales; el usuario aún puede quedar sin recuperación de cuenta y depender de un origen HTTPS temporal. La API pública respondió con release del 25 de septiembre, anterior a las correcciones locales del 26. Cero avisos en los cinco `npm audit` posteriores a la corrección no equivalen a seguridad integral. Las pruebas en dos cuentas y en Moto G04 faltan en esta pasada.

## 2. Arquitectura

Clientes Android/web → túnel/edge Nginx → API, realtime y signaling; PostgreSQL, Redis, storage e IA quedan detrás. Hay límites de servicio útiles, pero API y algunas pantallas concentran mucha lógica. `02-architecture-audit.md` contiene los flujos trazados y sus huecos.

## 3. Frontend

Web: 87 tests, lint y build pasan tras actualizar transitivas del lock; el build requiere `VITE_FALLBACK_API_URL` HTTPS definido. No se recorrieron todas las rutas autenticadas en navegador.

## 4. Mobile

Android: APK debug y 844 unit tests pasaron; esta APK abrió en emulador Android 35, mostró bienvenida/registro y volvió desde registro sin excepción fatal. El App Bundle release de prueba compiló con firma efímera y pasó verificación de firma, R8 y página 16 KiB. Se corrigieron regreso desde registro, navegación de recuperación, incorporación de otra cuenta y parpadeo de bandeja. Falta prueba real en Moto G04 y de LIVE, mensajes/video y notificaciones con dos cuentas.

## 5. Backend

API: 232 tests pasan, uno omitido. Se actualizó Nodemailer de 7.0.13 a 10.0.10 por un aviso high; se mantuvo `createTransport` y los tests. El servidor de producción no entrega aún correo de recuperación.

## 6. Database

PostgreSQL 16, esquema inicial, 33 migraciones locales y SQL legado separado. Se ejecutó audit SQL sin nuevas violaciones de sus reglas; no se inspeccionó `pg_catalog` real, planes de consulta ni restore. Ver `06-database-audit.md`.

## 7. APIs

Se contaron 408 declaraciones de rutas en 29 archivos; todavía falta matriz de autorización por ruta y objeto. Hay tests de acceso denegado a media privada, pero no son exhaustivos.

## 8. Seguridad

HTTPS obligatorio en Manifest, backup selectivo, ruta Nginx `internal` para storage y logging sin query string son controles observados. Los audits estáticos SQL/runtime terminaron sin violaciones HIGH de sus reglas. BOLA/BFLA, Gradle, contenedores y evaluación dinámica permanecen pendientes.

## 9. Performance

`/ready` local en tanda previa: 2,7–6,6 ms; por Quick Tunnel antes 0,49–0,60 s y ahora HTTP 200 en 0,52–1,15 s en muestras pequeñas. El AAB de prueba mide 119,47 MiB y su descarga máxima comprimida estimada 59,78 MiB; pasó presupuesto. No son latencias del feed ni prueba de una reducción del 50 %. Faltan p95/p99 autenticados, perfiles de cliente y carga.

## 10. Escalabilidad

Nginx, Redis Streams, límites Compose y separación de servicios ofrecen puntos de escala. No se ensayó concurrencia, failover ni capacidad de video/LIVE. Ver `07-scalability-performance.md`.

## 11. UX/UI

Acceso mejorado visualmente en un emulador de 360 dp; error de recuperación ahora informa que el servidor no ha enviado código. Resto de pantallas y estados de error/reconexión requiere revisión sistemática.

## 12. Accesibilidad

No hay auditoría TalkBack/teclado/contraste completa; `04-accessibility-audit.md` define la matriz pendiente. No se declara conformidad WCAG.

## 13. DevOps

CI Android y release firmado tienen gates. Se corrigió dependencia PyYAML que rompía las 60 pruebas de herramientas en Python limpio y se añadió CI de web/API/realtime/signaling/chatbot; falta ejecutar los workflows remotos tras el cambio. Los scripts locales ofrecen `-ToolingOnly`/`--tooling-only` para validar herramientas Python sin repetir Gradle; en Windows el modo pasó con 60 tests y los dos audits estáticos. El script Bash pasó `bash -n`, pero no pudo ejecutarse en el WSL actual porque falta el paquete del sistema `python3-venv`. Una repetición del gate Android se interrumpió deliberadamente durante Lint al quedar 0,30 GiB de RAM libre en este host; la ejecución anterior de `:app:qualityGateDebug` sí pasó. El daemon Docker local no está disponible y SSH a la mini PC sigue rechazando la clave, así que no se validó imagen OCI ni despliegue. Endpoint público estable y SMTP de producción son dependencias operativas.

## 14. Observabilidad

API agrega tiempos de respuesta y advertencias lentas; Nginx registra tiempos y request ID sin query. No se verificó correlación completa ni despliegue del stack Prometheus separado.

## 15. Dependencias

Web/API/signaling/chatbot/gateway realtime reportan cero avisos npm de producción y desarrollo después de actualizar Nodemailer y transitivas del web. PyYAML queda fijado en `tools/requirements.txt`. Falta SBOM y escaneo Android/OCI.

## 16. Riesgos de licencia

Hay activos gráficos de LIVE/juegos y música comunitaria; falta manifiesto consolidado de procedencia/licencias. Los documentos del proyecto ya advierten que términos musicales son borrador. Riesgo pendiente de revisión jurídica, sin afirmar infracción.

## 17. Privacidad

Backup Android excluye sesiones por inclusiones explícitas y el edge omite argumentos de URL. Faltan pruebas de retención, exportación/borrado y trazas con datos reales desidentificados.

## 18. Estándares

ISO 25010, NIST SSDF, OWASP MASVS/API y WCAG se usan como listas de comprobación, no como certificación. Ver `11-standards-compliance.md` y `references.md`.

## 19. Testing

Local: Android 844 sin fallos/omisiones, `:app:qualityGateDebug` verde en la ejecución previa, bundle release **de prueba** construido y verificadores de firma/R8/16 KiB/presupuesto verdes, API 232+1 omitida, web 87, signaling 26+1 omitida, herramientas Python 60 también por el script `-ToolingOnly`; chatbot y gateway realtime `check`/`test` salida 0. Android Lint terminó con 0 errores y 219 advertencias (205 recomendaciones KTX, 12 recursos sin uso y 2 avisos de iconos). Faltan CI remoto, Redis integrado, dos dispositivos, SMTP y release firmado con la clave real.

## 20. Deuda técnica

Archivos de miles de líneas, migraciones/esquema distribuidos, falta de E2E y medición real. El riesgo operativo de túnel/SMTP precede al refactor. Ver `15-technical-debt.md`.

## 21. Riesgos principales

AUD-003 origen temporal, AUD-004 recuperación desactivada, AUD-012 release público anterior al código local, AUD-006 cobertura insuficiente de flujos, AUD-008 procedencia/licencia sin inventario y AUD-007 objetivo de latencia no validado. Ver `13-master-findings.md` para causa, impacto y estado.

## 22. Fortalezas

Tests de lectura por usuario, media privada, límites de regalos, recuperación sin exponer tokens y backup selectivo. Ver `14-strong-points.md`.

## 23. Mejoras implementadas

En esta pasada: dependencia PyYAML declarada e instalada en quality gates/workflows; Nodemailer 10.0.10 exacta; transitivas web actualizadas en el lock; CI web/servicios añadido; compatibilidad Android 7–9 corregida en exportación de documentos y cancelación de notificaciones; versión documental alineada a Gradle; 60 IDs S60 inventariados para aceptación. En la tanda anterior: correcciones de acceso, multicuentas, bandeja y LIVE detalladas en el registro Android.

## 24. Mejoras pendientes

Activar SMTP, dar origen estable, validar release y dos cuentas/dispositivos, revisar rutas y licencias, medir rendimiento y accesibilidad. `17-s60-traceability.md` lista los 60 ID: 7 tienen evidencia parcial y 53 aún carecen de prueba en esta pasada; ninguno se declara terminado por inferencia.

## 25. Arquitectura recomendada

Conservar módulos de servicio y protección de media; añadir origen HTTPS estable, observabilidad correlacionada y contratos de dominio para extraer gradualmente rutas/pantallas extensas. La base sigue siendo fuente de verdad para saldos, acceso y lectura.

## 26. Roadmap técnico

Orden y criterios de prueba en `16-remediation-roadmap.md`: primero CI/correo/origen, luego E2E y métricas, después modularización/DB/SBOM, finalmente cargas/failover y accesibilidad integral.

**Criterio de cierre:** pipeline remoto verde, bundle firmado verificable, recuperación completa, dominio estable, casos críticos en dos cuentas y evidencias de autorización/rendimiento antes de afirmar que la aplicación está lista para producción.
