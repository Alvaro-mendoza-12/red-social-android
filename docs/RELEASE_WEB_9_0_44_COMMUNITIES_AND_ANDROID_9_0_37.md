# Web 9.0.44 y Android 9.0.37 (134)

## Cambios

- Borradores de Android: una ruta de navegación vacía conserva el texto restaurado. La clave estable del chat migra los borradores anteriores sin mezclarlos entre cuentas.
- Burbujas: la hora y el nombre del remitente dejan de forzar el ancho completo. El texto mantiene espacio interior; las fotos y los álbumes conservan su presentación multimedia.
- Fotos de grupos y comunidades: las rutas relativas se resuelven en su contenedor correspondiente, en web y Android. Las referencias a archivos históricos ausentes requieren recuperar los originales.
- Sorteos: solo el creador del grupo o comunidad puede crearlos. Los participantes pueden consultar los resultados. La web incorpora creación y resultados mediante la misma API del móvil.
- Preguntas: el creador publica una pregunta y elige un máximo de 20 a 40 caracteres por respuesta. Cada persona puede guardar o actualizar una respuesta. Se muestran respuestas rotativas, contador y lista con nombre, usuario, fecha, votos positivos y negativos.
- Permiso opcional «Solo el creador publica»: se verifica en el servidor para mensajes y encuestas, incluidas las rutas de compatibilidad del móvil. Reacciones, votos y respuestas siguen disponibles.
- Publicidad propia en Social: anuncios de Novvin identificados como publicidad, con opción de ocultarlos. Los administradores autorizados pueden crearlos; la web admite imágenes verificadas y destinos internos. Pausar un anuncio retira también el acceso a su imagen desde esa publicación.
- Correcciones de estados y carga en PDF, QR, perfil, momentos, radar y medios. No se desactiva la comprobación de permisos ni el antivirus.

## Evidencia de esta revisión

- Web: 547 pruebas de 86 suites; compilación de producción correcta; lint sin errores (ocho advertencias preexistentes sobre limpieza de referencias).
- API: suite Linux y comprobaciones con PostgreSQL desechable, con permisos de creador, persistencia, Unicode, votos y acceso revocado.
- Interfaz web: comprobaciones independientes con API simulada para preguntas, contador, votos, permisos y anuncio propio; tamaños 390×844, 844×390, 1440×960 y 2866×1433.
- Sticker: dos fotogramas observados en capturas del navegador. Canvas dibuja el fotograma predeterminado de GIF y no sirve para comprobar su animación.
- Envío real anterior: el ZIP de Física contiene 355 archivos; la descarga coincide byte por byte con los originales. El antivirus revisó el archivo antes de entregarlo.

Las pruebas visuales simuladas no sustituyen las comprobaciones con el servidor real. Las cuentas de auditoría se eliminan al terminar. Las evidencias locales se conservan en `audit/2026-10-07-integral` y no se publican como datos de usuarios.

## Alcance de producción

La publicación se realizará después de verificar la imagen exacta, el respaldo y `/ready`. Android se entrega como AAB para Google Play: la instalación del teléfono tiene la firma de Play y no admite sustituirse por una APK firmada con la clave de subida. No se borra esa instalación ni sus datos.

La certificación integral sigue dependiendo de las comprobaciones reales pendientes de esta ejecución y del alcance detallado en los informes anteriores. El posicionamiento y la presentación final en buscadores dependen también de sus índices; no se promete un puesto ni un plazo de indexación.
