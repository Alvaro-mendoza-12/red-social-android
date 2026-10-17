# Mensajes, publicidad y sonidos de salas

Entrega del 17 de octubre de 2026. Web 9.0.51; Android 9.0.41, código 138.

## Ajustes de las capturas de mensajes

| Requisito | Implementación y aceptación |
| --- | --- |
| Foto del contacto | El avatar queda sobre el borde inferior de la portada y antes del nombre. Conserva ampliación y zoom. Escape cierra solamente el visor. |
| Contadores | La navegación cuenta conversaciones con novedades. Mensajes, Grupos y Comunidades muestran su propio número. Varias novedades de una misma conversación cuentan una sola vez; dentro de cada fila se conserva el número de mensajes. |
| Lectura inmediata | Abrir una conversación limpia el indicador local antes de esperar la red. Respuestas antiguas de consultas y confirmaciones de lectura anteriores no restauran el contador. |
| Último mensaje | El texto enviado actualiza la vista previa desde la cola persistente; los eventos autenticados del servidor actualizan mensajes recibidos y conversaciones nuevas. No se exige cambiar de chat o recargar. |
| Menús y reacciones | Se mantiene un único menú abierto, con cierre exterior/Escape y seis reacciones en una fila. La selección guarda la reacción en el servidor y cierra el menú. |
| Cerrar chat | En la vista dividida, cerrar vuelve al panel de selección sin abandonar la bandeja. |

La aceptación real utilizó dos cuentas temporales, un chat, tres grupos y una comunidad. Se comprobó que varias novedades produjeran **1 + 3 + 1 = 5 conversaciones**, y que los contadores bajaran al leerlas. Con la solicitud real de lectura demorada 1,8 segundos, la interfaz quitó el indicador en 112 ms y la vista previa enviada se actualizó en 69 ms. Son mediciones de esta ejecución, no un compromiso de latencia para todas las redes.

La recepción por WebSocket se comprobó estando en otra sección, antes de volver a Mensajes. También se verificaron la reacción persistida, la posición del avatar y su visor. Se eliminaron las dos cuentas y sus recursos mediante la API normal. Recibo: `inbox-live-browser-final.json`, en `audit/2026-10-07-integral`.

Una segunda aceptación real confirmó «Cerrar chat» y ausencia de desbordamiento horizontal a 320×800 y 844×390, además de repetir los casos anteriores. Sus dos cuentas se aprovisionaron como fixtures aislados de operador y se autenticaron con la API normal; los límites de registro de producción permanecieron activos. Ambas se eliminaron al terminar. Recibo: `inbox-close-responsive-live.json`.

## Salas de voz

Avisos originales de entrada, salida, mute y unmute en ambos clientes, con volumen y categorías por cuenta y dispositivo. Se evitan duplicados, entradas de la lista inicial y avisos antiguos tras reconectar. Los avisos son reproducción local y no se mezclan con la pista enviada por el micrófono.

El análisis previo, referencias oficiales, reglas y resultados están en [VOICE_ROOM_SOUND_REQUIREMENTS.md](VOICE_ROOM_SOUND_REQUIREMENTS.md).

## Publicidad

La aceptación de publicidad propia está documentada en [ADS_FUNCTIONAL_ACCEPTANCE.md](ADS_FUNCTIONAL_ACCEPTANCE.md): tres pasos, imágenes subidas y escaneadas, carrusel, orden, publicación, edición, pausa, permisos y apertura de Mensajes. Se corrigió el rechazo de imágenes propias de `post-media` sin omitir el antivirus. La API correspondiente está desplegada y mantiene su recibo de restauración y controles de salud.

## Verificación y publicación

- Web: 572 pruebas en 92 suites, compilación correcta y lint con cero errores; quedan ocho avisos anteriores fuera del código modificado.
- Android: 1.689 pruebas, 353 suites, cero fallos, errores u omisiones. APK firmado y verificado por SHA-256, probado sin modificar datos de un teléfono personal.
- Sobre ese APK también pasaron Presentación, fecha de cumpleaños propia, edición de perfil, pasos del gestor de anuncios y su botón de apertura de Mensajes. Se eliminaron las cuentas temporales de la comprobación.
- API de publicidad: 841 pruebas aprobadas y siete omisiones; las integraciones PostgreSQL de preguntas y sorteos pasaron por separado.
- Web publicada: <https://red-social-android.vercel.app>. Recibo de Vercel y pruebas reales conservados en el expediente local.
- APK: `artifacts/releases/Novvin-9.0.41-138-final/Novvin-9.0.41-138.apk`. SHA-256: `6da319c7e136412aed2bba85d118ba8a6af06d18c7b8a56187ed89ec42f4535d`.

## Alcance pendiente

El Motorola no estaba visible en ADB durante el cierre de estas comprobaciones. Su actualización debe conservar datos y comprobar primero la firma instalada; no se autoriza desinstalarlo ni borrar sus datos como alternativa.

Esta entrega acepta las funciones anteriores en los entornos indicados. La certificación integral de toda la plataforma permanece pendiente de los recorridos físicos y entre redes, navegadores adicionales, multimedia histórica, accesibilidad integral y operación/capacidad descritos en el expediente de producción. Publicar en Vercel no acredita por sí solo esos requisitos ni garantiza posiciones en buscadores.
