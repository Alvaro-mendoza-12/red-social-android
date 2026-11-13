# Matriz de aceptación QA — Novvin

No marcar PASS sin evidencia reproducible, build/commit, entorno y fecha.

| ID | Área | Escenario | Criterio |
|---|---|---|---|
| AUTH-01 | Auth | Login/logout/expiración | Sesión y revocación correctas, sin secretos en logs |
| CHAT-01 | Chat | DM online | Persistencia y entrega única |
| CHAT-02 | Chat | Offline/reconexión | Cola/reintentos sin pérdida ni duplicado |
| MEDIA-01 | Stickers | GIF/WebP animado | Conserva fotogramas o explica incompatibilidad |
| CALL-01 | Calls | Llamada 1:1 | Audio/vídeo/estados/cierre correctos |
| CALL-02 | Calls | Añadir tercero | Llamada sigue activa y el tercero entra autorizado |
| CALL-03 | Calls | Cambio Wi-Fi/datos | Reconexión o fallo recuperable y estado correcto |
| CALL-04 | Calls | Micrófono denegado | UI explica y no finge conexión |
| LIVE-01 | Live | Crear directo | Backend confirma sesión activa |
| LIVE-02 | Live | Invitar co-host | Aceptar/rechazar/expirar sin duplicados |
| LIVE-03 | Live | Usuario bloqueado entra | Backend deniega |
| LIVE-04 | Live | Host pierde red | Política de recuperación documentada y probada |
| LIVE-05 | Live | Spam | Rate limit y moderación efectivos |
| LIVE-06 | Live | Encuesta | Voto y resultados consistentes |
| GIFT-01 | Gifts | Envío | Débito atómico, recibo correcto |
| GIFT-02 | Gifts | Reintento | Idempotencia, no doble cobro |
| PLAY-01 | Live Play | Evento autorizado | Solo acción allowlisted ejecutada |
| PLAY-02 | Live Play | Evento falsificado | Backend rechaza |
| PLAY-03 | Game | Gift en ranked | No ventaja injusta no declarada |
| ROOM-01 | Rooms | Solicitud de speaker | Host/moderador aprueba o rechaza |
| ROOM-02 | Rooms | Expulsión | Revocación real de media/acceso |
| WEB-01 | Web | Responsive | Sin overflow ni controles inaccesibles |
| AND-01 | Android | Background/foreground | Sin sesiones fantasma ni permisos rotos |
| SEO-01 | SEO | Crawl | Canonical, metadata, sitemap y robots coherentes |
| SEC-01 | Security | IDOR/BOLA | No se accede a recursos ajenos |
| SEC-02 | Security | Secret scan | No hay secretos en repo/bundle/logs |
| OPS-01 | Ops | Rollback | Reversión probada y datos protegidos |

Estados: NOT_STARTED, IN_PROGRESS, BLOCKED, PASS, FAIL, NOT_APPLICABLE.
Cada fila incluye responsable, entorno, fecha, commit/build, evidencia y defecto asociado.
