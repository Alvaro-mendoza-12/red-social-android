# 08 — Seguridad (primera pasada, no pentest)

Fecha: 2026-09-26. Se ejecutaron `tools/runtime_security_audit.py` y `tools/security_sql_audit.py` con umbral HIGH: ambos reportaron cero hallazgos/violaciones nuevas en sus reglas. Los resultados están en `build/reports/security/`. Esto **no** equivale a ausencia de vulnerabilidades.

| Control observado | Evidencia | Pendiente de validar |
|---|---|---|
| HTTPS Android | `AndroidManifest.xml` tiene `usesCleartextTraffic=false`; release prohíbe origen temporal. | Pinning/no pinning según amenaza, proxy corporativo y reconexión en red hostil. |
| Backup selectivo | `backup_rules.xml` y `data_extraction_rules.xml` incluyen cuatro preferencias de UX; no incluyen el repositorio de sesiones. | Inspección del backup real generado en dispositivos y versiones Android. |
| Solicitudes/API | `server.js` aplica Helmet, CORS, autenticación de sesión, capacidad de admin y límites distribuidos en áreas. | Matriz METHOD/PATH para las 408 declaraciones estáticas: BOLA/BFLA y rate limit por operación. [OWASP BOLA](https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/), [BFLA](https://api-security.owasp.org/editions/2023/en/0xa5-broken-function-level-authorization/). |
| Media privada | Nginx marca `/_novvin_storage/` como `internal`; la API autoriza antes de `X-Accel-Redirect`; tests cubren accesos denegados. | Probar todas las clases de media y URLs caducadas con dos cuentas reales. |
| Logs edge | `nginx/default.conf` registra `$uri` sin `$args` y tiempos/status, evitando tokens de query en access log. | Revisar logs de API/signaling y trazas para datos personales. |
| Recuperación | Producción rechaza modos que devuelven/imprimen tokens. | SMTP real, abuso/rate limit, correo sensible, expiración y revocación. |
| Contenedores | Redis/API usan secretos, `read_only`, `cap_drop`, límites de recursos y puertos de datos en loopback. | Imágenes, usuarios efectivos, escaneo OCI, firewall y backup/restore. |
| Dependencias npm de producción | Cuatro `npm audit --omit=dev` con cero después de actualizar Nodemailer. | Gradle, dev deps, imágenes y otros gestores. |

Para una conclusión MASVS se requiere una matriz por control, análisis dinámico de APK release y pruebas de plataforma, además de esta inspección. [OWASP MASVS](https://mas.owasp.org/MASVS/).
