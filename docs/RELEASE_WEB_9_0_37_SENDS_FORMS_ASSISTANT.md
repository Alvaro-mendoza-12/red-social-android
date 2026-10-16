# Novvin Web 9.0.37 — envíos, sesiones, formularios y asistente

## Cambios

- La cola conserva el orden de cada chat, grupo y comunidad, y procesa hasta tres conversaciones en paralelo. Una subida lenta o el tiempo de espera de un reintento ya no bloquean las demás conversaciones. Relee la cola después de cada entrega para recoger los mensajes nuevos.
- Las pestañas comparten bloqueos por conversación. Se mantiene un bloqueo compartido de cuenta compatible con la versión anterior. Los reintentos conservan el identificador del mensaje y reutilizan el archivo ya subido.
- La importación de stickers guarda primero el archivo localmente. Al recuperar conexión lo sube, guarda el favorito con su URL real y envía el mensaje. Cada paso queda guardado para que un reintento no repita la subida. Se corrige el intento anterior de guardar una URL local pendiente en el servidor.
- Los PDF, archivos Office y medios sin MIME suministrado por Windows respetan también el máximo de 60 MiB por archivo. La selección completa admite hasta 500 archivos y 128 MiB de origen. Las carpetas y formatos no admitidos se empaquetan conservando sus rutas y bytes; el ZIP final debe caber en 60 MiB.
- Las subidas inmediatas de publicaciones, momentos y perfiles disponen del mismo margen de cinco minutos que las subidas de mensajes, incluido el análisis del servidor.
- Se evita que dos pestañas renueven la sesión entre sí indefinidamente. Una respuesta tardía de renovación no puede sustituir una cuenta nueva ni deshacer un cierre de sesión. Los tokens siguen exclusivamente en memoria; cambiar de cuenta, borrar la sesión o recibir metadatos inválidos invalida la identidad anterior.
- Grupos y comunidades conectan sus reacciones, recibos de lectura y marcado como leído a las rutas existentes del servidor. Marcar como leído no bloquea la apertura y solo se intenta con conexión, conversación visible y membresía activa.
- El asistente usa el mismo formato de historial que Android y el servidor: `Usuario:` y `Novvin:`. Se aplanan los saltos de línea de cada mensaje para que una respuesta no se convierta en una intervención del usuario. Se añaden identificador de conversación, detalle paso a paso, sugerencias recibidas del servidor y borrador local por cuenta. Conserva detener, reintentar y copiar. Las respuestas separan sus párrafos.
- Ajustes presenta 17 campos con etiquetas persistentes y ayudas, espaciado consistente, campos de al menos 44 píxeles, secciones plegables y acciones de guardado separadas. La selección de apartados y los campos se adaptan desde 320 píxeles. Se eliminan huecos de secciones ocultas.
- Crear grupo, comunidad o sala usa los colores del tema, campos y controles adaptables. Mientras se crea el espacio se deshabilitan campos, cambio de tipo y cancelación, y la solicitud queda vinculada a la cuenta que la inició.

## Comprobaciones realizadas

| Área | Evidencia |
| --- | --- |
| Automatización web | 438 pruebas en 71 suites, sin fallos |
| Compilación | Compilación de producción y HTML público correctos |
| Lint | Sin errores ni avisos en las fuentes modificadas |
| Multimedia y páginas públicas | 9 + 9 pruebas aprobadas |
| Dependencias de producción | Cero vulnerabilidades notificadas por npm audit |
| Cola en navegador real | 33 mensajes pendientes entre chat, grupo y comunidad; persisten al recargar y se entregan al reconectar. Texto, imagen, video, audio, archivo, sticker, una sola vista, respuestas y reenvíos. API aislada sintética; IndexedDB y Web Locks reales |
| Carreras y reintentos | Archivo lento sin bloquear otro grupo; conservación del orden; archivo subido reutilizado; favorito de sticker con fallo transitorio; cancelación; dos pestañas con diez mensajes únicos; aislamiento entre cuentas |
| Formularios y asistente | 21 comprobaciones agrupadas, incluidas anchuras 320/390/768/1440, guardado de perfil, creación pendiente, sugerencias, borrador, historial y controles visibles |
| Regresión web | 11 comprobaciones del feed, comentarios activos, chat hasta 3840 píxeles, álbumes, stickers, ZIP con subida demorada 26 segundos, ranking, solicitudes, asistente y rondas |
| API real de archivos | PNG, GIF animado, WAV, MP4, PDF y ZIP enviados desde Novvin a Álvaro. El receptor pudo consultar el mensaje y descargar el archivo. Cinco formatos conservaron los bytes. El MP4 pasó por la reorganización fast-start existente y conservó los 48 fotogramas decodificados. Todos los mensajes de prueba se eliminaron para todos y todos los objetos se borraron; permanecen los avisos normales de eliminación |
| API real de asistente | Primera respuesta y seguimiento correctos, ambos HTTP 200; el seguimiento reconoció el contexto. No se cambió el modelo ni el servicio de IA |
| Servicio público | Liveness y readiness por TLS correctos; ruta protegida anónima rechazada con 401 |

Los artefactos detallados están en la auditoría local `audit/2026-10-07-integral/web-9037-*`, `web-send-9037.log` y `web-forms-9037.log`. Las pruebas de navegador usan API y cuentas sintéticas; las pruebas reales indicadas usan únicamente las dos cuentas autorizadas. No se exportaron sus tokens.

## Alcance y trabajo que sigue abierto

Esta entrega es una mejora web verificable; no certifica toda la aplicación para producción. Android y backend no cambian de versión aquí. No se repitió la aceptación física de móvil ni llamadas/notificaciones entre dispositivos y redes diferentes.

La cola verificada cubre mensajería. Crear encuestas, publicar en Social, publicar momentos y programar mensajes siguen siendo acciones que requieren servidor; no se afirma que funcionen sin conexión. La entrada desde cero sin red depende además de que la aplicación y la sesión estén disponibles en el navegador. Los formatos Office descargables no se convierten en un editor Office.

Continúa pendiente completar la comparación de opciones secundarias de grupos/comunidades con Android (incluida la selección inicial de participantes), permisos de cámara y grabación en los navegadores compatibles y aceptación prolongada de voz/directos. La advertencia existente de un bloque JavaScript de aproximadamente 1,09 MB continúa pendiente de optimización. Las mejoras anteriores de SEO permanecen; la posición y los enlaces que el buscador muestra dependen de su indexación.
