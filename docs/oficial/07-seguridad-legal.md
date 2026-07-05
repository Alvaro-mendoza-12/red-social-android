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

### JWT

`JWT_SECRET` debe:

- tener mínimo 32 caracteres;
- ser aleatorio;
- no estar en código;
- rotarse si se filtra.

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
- Cloudflare Quick Tunnel sin SLA fuerte.
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

