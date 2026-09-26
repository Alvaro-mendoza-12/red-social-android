# 07 — Seguridad y legal

Este documento no es asesoría legal profesional. Es una guía técnica y de producto para reducir riesgos antes de crecer en producción.

## Seguridad: reglas obligatorias

### Secretos

Nunca commitear:

- `.env`;
- `secrets.properties`;
- `keystore.properties`;
- archivos `.jks`/keystore;
- tokens DuckDNS;
- tokens Cloudflare;
- credenciales TURN;
- Firebase service account;
- claves privadas SSH;
- dumps de base de datos con datos reales;
- backups cifrados si la contraseña está documentada.

Si un secreto real fue subido al repo:

1. eliminarlo del repo;
2. rotar la credencial;
3. invalidar la anterior;
4. auditar accesos;
5. documentar incidente.

### Firebase

El backend usa Firebase Cloud Messaging.

Riesgos:

- service account expuesta;
- tokens FCM guardados sin limpieza;
- notificaciones enviadas a dispositivos antiguos;
- contenido sensible en push.

Recomendaciones:

- no poner mensajes privados completos si son sensibles;
- permitir borrar tokens inválidos;
- rotar service account si fue filtrada;
- validar permisos mínimos.

La ruta primaria de producción es `push-notifications.js` en la API Ubuntu. Si
durante la transición se despliega la Edge Function Supabase
`push-notification`, esta opera en modo fail-closed y exige tres secretos del
entorno: `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` y
`PUSH_WEBHOOK_SECRET`. El webhook debe enviar este último como bearer o en
`X-Novvin-Webhook-Secret`; nunca se admite una IP privada/HTTP codificada en la
función. El secreto debe ser aleatorio, distinto del JWT y rotarse coordinando
primero emisor y luego receptor. Los logs sólo contienen tabla, tipo e ID del
evento: no payloads, tokens FCM, cuerpos de mensajes ni respuesta completa del
proveedor.

### JWT

`JWT_SECRET` debe:

- tener mínimo 32 caracteres;
- ser aleatorio;
- no estar en código;
- rotarse si se filtra.

### Sesión web

La web Ubuntu no persiste el bearer en `localStorage`. El modelo vigente es:

- bearer web de 15 minutos, conservado sólo en memoria y renovado antes de
  llamadas, Live, realtime, consultas o uploads;
- secreto opaco de sesión en cookie `HttpOnly`, host-bound, `Secure`,
  `SameSite=None` y `Partitioned` en producción;
- ruta de cookie limitada a `/v1/auth/web-session`; no se envía con multimedia,
  REST, Socket.IO ni WebSocket;
- hash SHA-256 del secreto en `fallback_web_sessions`; el servidor nunca guarda
  el secreto reutilizable en claro;
- cada operación de cookie exige el header no simple
  `X-Novvin-Web-Session: web-v1` y un `Origin` incluido expresamente en
  `CORS_ORIGIN`;
- revocación inmediata validada en servidor para cada bearer mediante `sid`;
- cambio, recuperación o activación de contraseña revoca conjuntamente
  sesiones móviles y web antes de emitir la sesión sustituta.

Las instalaciones web antiguas migran una sola vez: leen el bearer legado,
eliminan inmediatamente su copia persistente y lo usan para crear la cookie
opaca. No debe volver a añadirse `accessToken`, `refreshToken` ni un objeto de
sesión completo a `localStorage`, IndexedDB, URL, logs o analytics.

La cookie particionada permite conservar el aislamiento cuando Vercel y la API
están en sitios distintos. La producción exige un hostname API estable mediante
un Cloudflare Tunnel nombrado; una rotación de hostname temporal no puede
transportar cookies al host nuevo y no es una arquitectura de sesión admisible.
Un BFF de primer nivel sigue siendo una posible evolución si la web se vuelve
cliente principal.

### Admin

El panel admin debe:

- estar protegido por token/rol real;
- no mostrarse a usuarios normales;
- validar permisos en backend, no solo UI;
- registrar acciones sensibles;
- distinguir creador/admin/moderador/usuario.

Nunca confiar en que “el botón no se ve” como seguridad.

### Rate limiting

Necesario en:

- login;
- registro;
- recuperación/cambio de contraseña;
- envío de mensajes;
- subida de archivos;
- llamadas/salas;
- endpoints admin.

### Uploads

Validar:

- tamaño;
- MIME real;
- extensión;
- duración de video;
- dimensiones;
- virus/malware si se escala;
- nombre seguro;
- ruta sin traversal.

No permitir:

```text
../../archivo
```

Las escrituras de objetos no se reintentan automáticamente. Un `PUT` puede
haber quedado confirmado aunque el cliente pierda la respuesta; repetirlo de
forma transparente crea duplicados o falsos conflictos. Los reintentos
automáticos quedan limitados a operaciones idempotentes de lectura
(`GET`/`HEAD`/`OPTIONS`), y las escrituras usan identificadores idempotentes o
reconciliación explícita cuando el dominio lo permite.

### Funciones SQL con privilegios

Toda función `SECURITY DEFINER` debe cumplir simultáneamente:

- `SET search_path = public, pg_temp` o una lista aún más restrictiva;
- identidad tomada de `auth.uid()`/`auth.role()`, nunca de un UUID confiado al cliente;
- límites y offsets acotados en búsquedas;
- filtros de privacidad reproducidos explícitamente, porque el propietario de la función puede omitir RLS;
- `REVOKE ... FROM PUBLIC, anon` y un `GRANT` mínimo y explícito;
- RPC económicas mutables ejecutables solo por `service_role`, salvo una operación autenticada diseñada expresamente para el usuario.

La migración final `20260824_security_definer_surface_hardening.sql` aplica
estas reglas a búsquedas/hashtags, salida de chats y contadores heredados. Las
definiciones antiguas permanecen como historial, pero no representan el estado
final que debe desplegarse.

### WebRTC

Riesgos:

- permisos cámara/micrófono/pantalla;
- IP leaks parciales propios de WebRTC;
- grabación sin consentimiento;
- salas públicas sin moderación;
- abuso/spam.

Buenas prácticas:

- pedir permisos claramente;
- mostrar indicador de mic/cámara/pantalla activos;
- detener streams al salir;
- auditar permisos de sala;
- permitir expulsar/reportar usuarios;
- no activar cámara/mic sin acción del usuario.

## Privacidad

Conéctate debe tener:

- Política de privacidad;
- Términos y condiciones;
- mecanismo de eliminación de cuenta;
- mecanismo de eliminación de datos;
- canal de contacto;
- explicación de notificaciones;
- explicación de contenido generado por usuarios;
- moderación/reportes;
- tratamiento de menores si aplica.

Datos sensibles posibles:

- correo;
- nombre;
- foto;
- mensajes;
- multimedia;
- tokens FCM;
- IPs/logs;
- presencia/última conexión;
- llamadas/salas.

### Alcance actualizado de datos (13 de agosto de 2026)

La política visible dentro de Novvin debe mantener correspondencia con las funciones reales de esta rama:

- avatar de imagen o video breve y enlaces sociales opcionales;
- publicaciones, videos, Momentos, audiencias personalizadas y destacados;
- mensajes, grupos, comunidades, llamadas, salas, LIVE, invitados, batallas, cámara, micrófono, pantalla y mezcla de música seleccionada por el creador;
- PlayMatch, juego seleccionado, disponibilidad, conexiones y chats;
- señales de recomendación y moderación de contenido sensible u ofensivo;
- infraestructura principal propia administrada por Novvin y proveedores auxiliares de notificaciones, conectividad, almacenamiento o seguridad;
- copia de preferencias iniciada por el usuario mediante Storage Access Framework. El archivo no incluye credenciales, sesiones, mensajes ni multimedia, y puede guardarse en un proveedor como Google Drive sin que Novvin reciba sus credenciales.

La implementación debe conservar una ruta dentro de la app para solicitar la eliminación de la cuenta y una ruta web pública funcional para Play Console. Los datos declarados en Data Safety deben coincidir con permisos, telemetría, SDK y tráfico observados en el APK final.

## Legal de producto y marcas

Se puede inspirar en patrones generales de redes sociales, pero no copiar identidad protegida.

Evitar:

- logos de WhatsApp, TikTok, Instagram, Facebook, Discord, Zoom, Meet;
- nombres de funciones idénticos si confunden marca;
- screenshots o assets de terceros;
- iconografía propietaria;
- claims como “mejor que X” en Play Store sin respaldo;
- UI demasiado similar a una app famosa al punto de confusión.

Permitido normalmente:

- patrones genéricos:
  - chat;
  - feed vertical;
  - stories/momentos;
  - llamadas;
  - comunidades;
  - reacciones;
  - notificaciones.

Recomendación:

- usar iconos open source con licencia compatible;
- documentar licencias;
- mantener branding propio “Conéctate”;
- no usar música, videos o stickers con derechos sin autorización.

### Cine, audio y contenido de terceros

El catálogo de cine integrado se limita a obras de dominio público/CC0 con
origen verificable y reproducción directa por HTTPS. No se admiten:

- agregadores de streaming no autorizados;
- torrents o resolutores Torrentio;
- Real-Debrid u otras credenciales de debrid;
- descarga/extracción de YouTube;
- reproductores WebView ocultos ni audio de YouTube en segundo plano;
- URLs privadas, locales o esquemas distintos de HTTPS.

Las músicas de Comunidad deben conservar prueba de licencia y moderación. El
hecho de que un archivo sea reproducible no concede derechos para redistribuirlo.

### Activos de marca usados en los enlaces sociales

La interfaz de perfil usa copias vectoriales locales de los glifos de marca de
Instagram, Facebook, TikTok y WhatsApp provenientes de **Font Awesome Free
Brands**. Font Awesome Free se distribuye bajo licencias compatibles según el
tipo de activo (iconos: CC BY 4.0; código: MIT; fuentes: SIL OFL 1.1). El glifo
de X se mantiene como un vector local separado y debe respetar las reglas de uso
publicadas en el kit oficial de marca de X.

Estos activos se usan únicamente para identificar el destino de un enlace. No
implican afiliación, patrocinio ni aprobación de Novvin por parte de esas
plataformas. No deben reutilizarse como logotipo propio ni incorporarse al logo
principal de Novvin.

Referencias:

- https://fontawesome.com/license/free
- https://about.x.com/en/who-we-are/brand-toolkit
- https://developers.tiktok.com/doc/getting-started-design-guidelines

## Play Store

Revisar:

- Data Safety;
- permisos declarados;
- política de contenido generado por usuarios;
- bloqueo/reporte de usuarios;
- moderación;
- política de eliminación de cuenta;
- target SDK;
- compatibilidad de dispositivos;
- crashes ANR.

Permisos sensibles:

- cámara;
- micrófono;
- notificaciones;
- foreground service;
- media projection/pantalla;
- lectura de archivos/media.

Cada permiso debe tener justificación funcional.

## Seguridad del servidor

### Autorización administrativa

- mínimo privilegio y denegación por defecto;
- `view` sólo lee, `write` modera, `full` administra servicios y sólo `owner`
  delega permisos o elimina cuentas;
- una insignia visible nunca concede acceso;
- la API comprueba capacidades nominales, incluso si el cliente fue modificado;
- borrado irreversible se vuelve a autorizar dentro de la transacción;
- JWT humano, token de sincronización y acceso SSH/Tailscale son credenciales
  distintas y no intercambiables.

La matriz canónica está en
`docs/empresa/06-roles-y-capacidades-administrativas.md`.

Checklist:

- SSH con llave, no solo password si es posible.
- Tailscale para administración remota.
- No exponer PostgreSQL públicamente.
- No exponer Docker socket.
- Firewall local si aplica.
- Backups cifrados para transporte.
- Logs sin secretos.
- Actualizaciones de seguridad.
- Monitoreo de disco.
- No ejecutar servicios críticos como root salvo necesario.

## Riesgos actuales por modelo casero

- Mini PC como único servidor.
- Internet residencial.
- CGNAT.
- Quick Tunnels manuales si el túnel nombrado aún no se ha instalado (riesgo de
  transición, no estado objetivo).
- TURN gratis puede fallar o tener límites.
- Cortes eléctricos.
- Suspensión por BIOS/firmware.

Mitigaciones:

- anti-suspensión Linux + BIOS;
- Tailscale;
- backups externos;
- restore drill;
- watchdog;
- discovery dinámico;
- logs claros;
- documentación de recuperación.

