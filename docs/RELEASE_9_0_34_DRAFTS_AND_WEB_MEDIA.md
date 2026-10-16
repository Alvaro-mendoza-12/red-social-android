# Novvin 9.0.34 — borradores y multimedia web

Android: versión 9.0.34, código 131. Web: versión 9.0.34.

## Borradores

- Texto local independiente por cuenta y conversación, para chats, grupos y comunidades.
- Android guarda en preferencias privadas y restaura al volver a abrir; la lista muestra «Borrador».
- La web guarda al escribir y restaura al navegar, recargar o volver a abrir el navegador.
- Enviar borra el borrador cuando la cola local acepta el mensaje; un fallo previo lo conserva. Escribir otro texto durante el envío no permite que una respuesta anterior lo borre.
- Editar un mensaje ya enviado conserva el texto que estaba sin enviar.
- Los borradores permanecen en el dispositivo donde se escriben. No se publica el texto ni se sincroniza un borrador entre móvil y navegador. Esta entrega guarda texto; los adjuntos todavía sin enviar no se conservan tras cerrar el proceso.

## Multimedia y mensajes web

- Editor al pegar PNG/JPEG con recorte rectangular libre, movimiento y esquinas, giro, reflejo, restablecimiento y texto. Los archivos animados mantienen su original.
- Fotos y videos de una conversación abren una galería común con flechas, teclado y tira inferior de miniaturas.
- Descargas explícitas de documentos, ZIP, imágenes, audios y videos, preservando nombre y extensión. Las lecturas autenticadas se hacen desde el cliente autorizado y se cancelan si cambia la cuenta; límite de 128 MiB por descarga web.
- Video del chat con miniatura y apertura en visor completo; tamaño contenido para el detalle de videos compartidos.
- Respuestas con etiqueta de medio y miniatura de la foto; enlaces con tarjeta del dominio y URL pulsable. No se descarga metadata de páginas externas ni se inventan títulos o miniaturas.
- Los comentarios ocupan un panel lateral y desplazan reacciones, navegación y controles; en pantallas pequeñas usan una hoja inferior.

## Corrección de cuentas migradas

El control de cuenta de las solicitudes web comparaba el identificador del perfil con el identificador local de autenticación. Las cuentas migradas pueden tener ambos distintos. Ahora se aceptan exclusivamente los dos alias que aporta la sesión actual; otras cuentas siguen rechazadas. Esto corrige la carga de comentarios y otras operaciones protegidas por esa comprobación sin modificar permisos del servidor.

## Evidencia y alcance

Las evidencias de pruebas, compilación, firma, publicación y comparación de archivos están en `audit/2026-10-07-integral/messaging-polish-*` del checkout principal.

Esta entrega no declara una nueva certificación global de producción. Conserva la aceptación manual que el usuario comunicó sobre el APK previo y documenta separadamente las pruebas automatizadas de esta versión.
