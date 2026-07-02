# Conectate — Seguridad, privacidad y legal para producción

Fecha: 2026-06-28  
Aviso: esto es una guía técnica y de cumplimiento razonable; no sustituye asesoría legal profesional.

## 1. Estado legal general

Riesgo actual estimado: medio.

No se detecta una infracción obvia por “tener funciones parecidas” a apps conocidas. En software, ideas generales como chat, feed vertical, stickers, llamadas o comunidades suelen existir en muchas apps. El riesgo aparece si se copian:

- marcas, logos o nombres comerciales;
- interfaz visual demasiado idéntica;
- textos, sonidos, íconos o assets protegidos;
- código propietario;
- contenido de usuarios sin permisos;
- claims engañosos en Play Store.

Regla interna: se puede decir “feed vertical” o “estilo red social”, pero evitar promocionar como “igual que WhatsApp/TikTok/Facebook” en Play Store, capturas, descripción o onboarding.

## 2. Políticas oficiales de Google Play relevantes

Fuentes principales:

- Google Play Developer Program Policies: https://play.google/developer-content-policy/
- User Generated Content policy: https://support.google.com/googleplay/android-developer/answer/9876937
- User Data policy: https://support.google.com/googleplay/android-developer/answer/10144311
- Data Safety section: https://support.google.com/googleplay/android-developer/answer/10787469

Implicaciones para Conectate:

- Debe tener política de privacidad pública y dentro de la app.
- La ficha Data Safety debe coincidir con lo que realmente recolecta la app.
- Si hay cuentas, debe existir mecanismo de eliminación o solicitud de eliminación de cuenta.
- Al tener UGC, debe haber reporte, bloqueo y moderación razonable.
- No usar nombres, logos o diseños que parezcan afiliación con terceros.

## 3. Datos que la app trata

Datos probables:

- correo electrónico;
- nombre de usuario/perfil;
- foto de perfil/portada;
- mensajes y multimedia;
- tokens FCM para notificaciones;
- datos técnicos de sesión;
- contenido generado por usuarios;
- logs de errores si Crashlytics está activo;
- metadatos de llamadas/salas de voz.

Acciones obligatorias:

- declararlo en Data Safety;
- explicar finalidad en política de privacidad;
- no vender datos;
- limitar acceso de administradores;
- mantener backups protegidos;
- no subir secretos reales a Git.

## 4. UGC: reporte, bloqueo y moderación

La app debe mantener:

- reportar usuario;
- reportar mensaje/publicación/video/comentario;
- bloquear usuario;
- eliminar contenido propio;
- moderación/admin para retirar contenido abusivo;
- canal de contacto para reclamos.

Si una ruta todavía no tiene reportar/bloquear, debe priorizarse antes de escalar usuarios.

## 5. Seguridad técnica mínima

Servidor:

- SSH solo con llave, no contraseña si es posible.
- Tailscale para administración remota.
- No exponer PostgreSQL a internet.
- Docker con servicios internos en `127.0.0.1` cuando aplique.
- Backups diarios y copia en disco externo.
- Logs sin contraseñas/tokens.
- Rotación de secretos si se filtró `.env`, Firebase JSON, tokens DuckDNS o llaves.

Android:

- HTTPS obligatorio.
- No incluir secretos privados en el APK.
- Crashlytics debe ser defensivo y no crashear la app.
- Mensajes de error humanos, sin stack traces al usuario.
- Permisos mínimos: cámara, micrófono, notificaciones, storage/media solo cuando se use.

Backend:

- Validar ownership antes de editar/eliminar datos.
- Rate limiting para auth, mensajes y TURN credentials.
- Sanitizar uploads y limitar tamaños/duración.
- No confiar en IDs enviados por el cliente sin verificar membresía.

## 6. Riesgos abiertos conocidos

### TURN/cámara/pantalla

Con CGNAT, Coturn en casa no garantiza relay público. Para que cámara/pantalla funcione de forma confiable entre usuarios externos se necesita TURN público real o una alternativa equivalente.

Riesgo: llamadas con audio parcial pero video/pantalla fallando.

Mitigación:

- mantener audio como estable;
- ocultar o marcar como beta cámara/pantalla si falla;
- conseguir VPS/TURN externo cuando haya presupuesto.

### Cloudflare Quick Tunnel

Quick Tunnel gratuito puede cambiar URL y no es SLA de producción empresarial.

Mitigación:

- watchdog;
- discovery por DuckDNS TXT;
- comandos de recuperación documentados;
- considerar Cloudflare Tunnel con dominio propio cuando haya presupuesto.

### Legal/UGC

El contenido lo suben usuarios. Siempre habrá riesgo de contenido infractor.

Mitigación:

- reportes;
- bloqueo;
- eliminación;
- términos claros;
- moderación rápida.

## 7. Lenguaje recomendado para Play Store

Usar:

- “mensajería, comunidades, videos y salas de voz”
- “comparte contenido con tus amigos”
- “notificaciones en tiempo real”

Evitar:

- “como WhatsApp”
- “tipo TikTok”
- “igual que Facebook”
- logos o nombres de marcas en capturas promocionales.

## 8. Checklist antes de cada release

- [ ] Política de privacidad accesible desde app y Play Console.
- [ ] Data Safety actualizado.
- [ ] Cuenta se puede eliminar o solicitar eliminación.
- [ ] Reportar/bloquear funciona en UGC.
- [ ] No hay tokens o secretos en Git.
- [ ] `~/bin/conectate-status` OK.
- [ ] AAB compila.
- [ ] Prueba real en celular.
- [ ] Login, mensajes, grupos, comunidades, videos y notificaciones OK.
- [ ] No se promete cámara/pantalla global si TURN público no está resuelto.

