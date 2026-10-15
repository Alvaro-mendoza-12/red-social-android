# Corrección de multimedia y distribución web — 15 de octubre de 2026

Seguimiento de la versión 9.0.33. No modifica el APK ni los contratos del servidor.

## Problema reproducido

En una sesión real de una cuenta autorizada, los videos cargaban inicialmente. Tras reemplazar el Service Worker sin cerrar la página, entrar a Videos mediante la navegación interna dejaba los dos primeros reproductores con `src=null`, `readyState=0` y ancho de video cero. El servidor seguía disponible.

La página guardaba permanentemente el controlador antiguo del Service Worker. Los registros posteriores se enviaban a ese controlador retirado; el componente ocultaba el error y nunca volvía a solicitar una fuente.

## Cambios

- Seleccionar el controlador vigente en cada registro y recuperar una sustitución durante una solicitud.
- Recuperar fallos temporales de registro, imágenes, video y audio con reintentos limitados. Volver a intentar al recuperar conexión, conservando la separación entre cuentas.
- Mantener la posición del contenido saludable al volver a la pestaña. Los avatares que fallaron pueden recuperarse al volver la conexión.
- Resolver las rutas relativas y los campos alternativos del avatar y portada del panel del contacto igual que en el perfil completo. No crear una imagen sin URL para perfiles sin portada.
- Separar el botón de sonido de la búsqueda en Videos; mostrar estado de carga y una acción de reintento si falla el archivo.
- Centrar Social, separar tarjetas y proteger los nombres y avatares frente a la compresión del encabezado.
- Organizar el perfil de Juegos en identidad, preferencias y privacidad, con formularios adaptables.
- Añadir etiquetas y descripciones a Privacidad y mejorar el espacio y contraste de Apariencia.

## Comprobación

Las evidencias de esta corrección están en `audit/2026-10-07-integral/media-recovery-*` del checkout principal. Incluyen reproducción anterior, pruebas de recuperación y aislamiento de sesión, pruebas del trabajador multimedia, compilación y capturas de siete vistas en seis anchos (320, 360, 768, 1100, 1440 y 1920).

La verificación final de la publicación debe comprobar el mismo cambio de controlador en una sesión real sin recargar la página. Esta corrección no sustituye los pendientes de aceptación de producción registrados en `WEB_PARITY_AUDIT_2026_10_15.md`.
