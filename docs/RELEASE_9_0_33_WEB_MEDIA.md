# Novvin 9.0.33 — multimedia y experiencia web

Fecha: 15 de octubre de 2026. Android: versión 9.0.33, código 130.

## Cambios

- Documentos dentro del chat: lector PDF con páginas y zoom; texto seguro, lectura de contenido de DOCX/PPTX/OpenDocument, hojas XLSX y listado de ZIP. Los formatos antiguos o no compatibles conservan la descarga del original. La vista de Office muestra contenido, sin prometer conservar toda su maquetación. El lector autorizado tiene un límite de 32 MB; los archivos mayores conservan la descarga por transferencia.
- Carpetas arrastradas: ZIP con subcarpetas y directorios vacíos, hasta 500 entradas y 128 MB. Se preparan en el borrador antes de enviar. Los documentos de texto grandes se comprimen cuando reduce su tamaño; los formatos que ya están comprimidos no se vuelven a comprimir automáticamente.
- Descargas de almacenamiento propio mediante transferencia por partes con nombre de archivo, identidad de sesión y revocación al cambiar de cuenta.
- Galería del historial cargado del chat, navegación anterior/siguiente y álbumes. Panel lateral de contacto en escritorio y adaptación a pantallas pequeñas.
- Editor de imágenes pegadas o adjuntas: giro, reflejo, recorte cuadrado y texto. Los GIF y WebP animados mantienen el original.
- Grabación con tiempo, detener/descartar y reproducción del borrador antes de enviar.
- Biblioteca de stickers con animación original, favoritos mediante el mismo servicio que Android y selector de emojis en chats, grupos y comunidades.
- Videos: sonido compartido entre elementos del feed, comentarios en panel lateral con presentación limitada y carga de anteriores; conexión de comentarios de la página de detalle con la API principal.
- Momentos: controles de sonido y conservación de la elección al pasar a otra historia.
- Salas de voz: cámara, compartir pantalla y selección de micrófono/cámara. Las capturas se detienen al abandonar la sala.
- Directos: conexión SFU compatible con el servidor, solicitudes para participar, audiencia, categoría, pantalla completa y controles del invitado. Corazones animados interpretando el protocolo del servidor. Ajuste de colores de corazones en Android.
- Avisos de nuevos directos a seguidores: activados después de corregir privacidad. Las emisiones privadas o inactivas no generan avisos; antes de entregar se vuelve a comprobar audiencia, seguimiento, bloqueos y preferencias.
- Juegos: catálogo visible como entrada, búsqueda de jugadores por juego y plataforma. Se trata del catálogo social de PlayMatch, no de ejecutar juegos externos en el navegador.
- Rondas: información en español y fechas plegables para reducir saturación.
- Entrada del asistente conectada a la API de IA y mensajes del radar con recepción voluntaria. La disponibilidad de IA depende del servicio del servidor.
- Ajustes con detalles opcionales plegables, regreso a la pantalla anterior, filtros de chats exclusivos, ajustes de presentación de llamadas.

## Comprobaciones de esta versión

- Android: compilación release, APK/AAB firmados con la firma existente; 1.661 pruebas en 345 suites sin fallos.
- Web: 347 pruebas en 54 suites; nueve pruebas de transferencia multimedia; compilación de producción y auditoría de dependencias de producción sin vulnerabilidades informadas.
- Navegador aislado: ZIP real, compresión, PDF real de dos páginas, DOCX, texto inerte, galería, panel de perfil, imagen pegada editada, grabación, sonido entre videos, panel de 30 comentarios, asistente y corazones de un directo con WebRTC real. Cincuenta vistas en 320, 360, 768, 1440 y 2560 píxeles sin desbordamiento horizontal.
- Controles adicionales: cámara/pantalla de voz y liberación de captura al salir; música desde el segundo 12 y sonido conservado entre dos historias.
- SFU remoto: publicación hacia la web y publicación desde la propia web hacia otra sesión; imágenes en movimiento y propagación del apagado de cámara. Salas sintéticas aisladas, sin emisiones públicas ni avisos a usuarios reales.
- API Linux: 806 pruebas generales aprobadas y seis omitidas por requisitos de entorno; siete casos adicionales con PostgreSQL aislado para privacidad, entrega y deduplicación de avisos de directos. Las omisiones no se cuentan como aprobadas.
- Despliegue API con copia de PostgreSQL comprobada, imagen inmutable, comparación del contrato de ejecución, comprobaciones de salud y conservación de los otros once servicios.

## Aceptación pendiente

Esta versión no constituye una certificación completa de producción. Siguen pendientes las pruebas físicas de Android, cámara QR y llamadas con proceso cerrado, notificaciones reales, recuperación de envíos sin conexión entre dispositivos, audio humano e interoperabilidad en redes distintas, aceptación completa de opciones secundarias y carga/recuperación prolongadas. Las pruebas sintéticas no sustituyen esas comprobaciones.

Evidencia de trabajo: `audit/2026-10-07-integral/nowing-9033-audit-latest.json` y capturas en `output/playwright/web-parity-9033` dentro de esa carpeta.
