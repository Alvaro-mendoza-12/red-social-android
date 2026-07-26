# Novvin: cierre de mensajería, Social y administración

Fecha de validación: 2026-07-26

## Causas corregidas

- El cliente conservaba una URL temporal de Cloudflare después de que el túnel vencía. La resolución del endpoint ahora se refresca de forma sincronizada antes de reintentar.
- Los mensajes multimedia no mantenían una cola optimista estable ni una miniatura reutilizable. Ahora se conservan en Room, se reintentan con idempotencia y los videos generan una miniatura local comprimida.
- El compositor de mensajes no permitía recorrer varios archivos, reproducir/recortar video ni recortar libremente imágenes. Se añadió paginación, vista previa, recorte temporal de video, recorte manual de imagen y controles adaptables al teclado.
- Las notificaciones existentes no se cancelaban al abrir un chat. La sesión activa ahora cancela la notificación asociada.
- Los audios largos dependían de un bucle AAC sin límite y no liberaban todos los recursos ante error. El codificador tiene límite de finalización, limpieza garantizada y bitrate adecuado para voz.
- La eliminación administrativa fallaba por una discordancia de parámetros al purgar datos importados. Se corrigió el borrado relacionado y se mantiene la revalidación de permisos, cuenta de prueba o suspensión.
- La recuperación de cuentas migradas podía devolver el permiso de migración sin comprobar la contraseña restaurada. Ahora una contraseña incorrecta responde 401 y no filtra tokens.
- Social barajaba resultados por bloques, registraba impresiones no visibles y no exponía reacciones completas. Ahora conserva el ranking, registra visibilidad real, permite reacciones tipadas y muestra quién reaccionó.
- El buscador de videos reutilizaba el reproductor vertical de un elemento. En modo búsqueda muestra una cuadrícula adaptable de vistas previas y abre el reproductor al tocar.
- Los errores técnicos podían mostrar JSON, nombres de host o cancelaciones internas. Los flujos modificados usan mensajes comprensibles para el usuario.

## Superficies actualizadas

- Chats privados, grupos y comunidades.
- Compositor y burbujas multimedia.
- Audios y notificaciones.
- Perfil y portada, creación/edición de grupos y comunidades, publicaciones, momentos, fondos de chat y portada de video.
- Social, pantalla completa y panel de reacciones.
- Búsqueda de videos.
- Panel de administración.
- API local de Ubuntu para mensajes, Social, autenticación migrada y borrado administrativo.

## Verificación

- Kotlin Debug: compilación correcta.
- Pruebas Android: 31 suites, 131 pruebas, 0 fallos, 0 errores.
- Android Lint Debug: correcto.
- APK Debug: generado y firmado con la clave Debug.
- Release: compilado correctamente con R8 y lint vital.
- APK Release oficial: queda sin firma porque la clave oficial de Play Store no está en el repositorio.
- APK Release para dispositivo: alineado y firmado únicamente con la clave local Debug para pruebas.
- API: `check`, `test:data-routes`, `test:admin` y `test:security` correctos.
- Eliminación: prueba integral únicamente con cuenta ficticia y limpieza verificada.
- Contraseña migrada: prueba integral con cuenta ficticia; contraseña incorrecta devuelve 401 sin exponer tokens.
- Servidor público: endpoint `/ready` correcto, base de datos y FCM disponibles.

## Límites de la validación

No había un dispositivo ADB conectado al cerrar este informe. La instalación, captura de logs del audio de un minuto y las pruebas visuales en varios tamaños quedan pendientes de dispositivo físico. La compilación y las pruebas automáticas no sustituyen esa comprobación.
