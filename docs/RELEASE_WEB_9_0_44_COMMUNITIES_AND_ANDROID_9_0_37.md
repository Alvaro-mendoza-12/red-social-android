# Web 9.0.44 y Android 9.0.37 (134)

## Cambios

- Borradores de Android: una ruta de navegación vacía conserva el texto restaurado. La clave estable del chat migra los borradores anteriores sin mezclarlos entre cuentas.
- Burbujas: la hora y el nombre del remitente dejan de forzar el ancho completo. El texto mantiene espacio interior; las fotos y los álbumes conservan su presentación multimedia.
- Fotos de grupos y comunidades: las rutas relativas se resuelven en su contenedor correspondiente, en web y Android. Se recuperaron 26 referencias históricas a imágenes existentes que apuntaban a dominios temporales vencidos. Se verificaron procedencia, archivo original y antivirus antes de sustituir las direcciones.
- Sorteos: solo el creador del grupo o comunidad puede crearlos. Los participantes pueden consultar los resultados. La web incorpora creación y resultados mediante la misma API del móvil.
- Preguntas: el creador publica una pregunta y elige un máximo de 20 a 40 caracteres por respuesta. Cada persona puede guardar o actualizar una respuesta. Se muestran respuestas rotativas, contador y lista con nombre, usuario, fecha, votos positivos y negativos.
- Permiso opcional «Solo el creador publica»: se verifica en el servidor para mensajes y encuestas, incluidas las rutas de compatibilidad del móvil. Reacciones, votos y respuestas siguen disponibles.
- Publicidad propia en Social: anuncios de Novvin identificados como publicidad, con opción de ocultarlos. Los administradores autorizados pueden crearlos; la web admite imágenes verificadas y destinos internos. Pausar un anuncio retira también el acceso a su imagen desde esa publicación.
- Correcciones de estados y carga en PDF, QR, perfil, momentos, radar y medios. No se desactiva la comprobación de permisos ni el antivirus.

## Evidencia de esta revisión

- Web: 547 pruebas de 86 suites; compilación de producción correcta; lint sin errores (ocho advertencias preexistentes sobre limpieza de referencias).
- API: 832 pruebas Linux, 825 aprobadas y siete omitidas, sin fallos; 16 pruebas de sorteos con PostgreSQL desechable y una suite de integración de preguntas, con permisos de creador, persistencia, Unicode, votos y acceso revocado.
- Interfaz web: comprobaciones con Chrome, Brave y Firefox para preguntas, contador, votos, permisos y anuncio propio; tamaños 390×844, 844×390, 1440×960 y 2866×1433. Las comprobaciones visuales de estos formularios utilizan respuestas simuladas de la API.
- Servidor real con dos cuentas de prueba: mensajes en ambos sentidos, importación y reenvío de sticker animado, imagen de grupo y comunidad visible para el participante, pregunta y respuesta persistidas, rechazo de publicaciones restringidas y de sorteos creados por un participante.
- Android: 1.676 pruebas de 349 suites, sin fallos ni omisiones; APK y AAB de la versión 9.0.37, código 134, compiladas y con firma verificada. Prueba con la APK final en emulador: borrador conservado al salir del chat y al reiniciar el proceso; creador publica preguntas en grupo y comunidad; participante responde y ve el contador actualizado; participante sin opción para publicar preguntas.
- Fotos históricas: 25 originales volvieron a pasar por el antivirus; cinco declaraban JPEG aunque contenían PNG y se corrigió esa declaración tras validar los bytes. La entrega HTTP descubrió dos errores de permisos del sistema de archivos. Se corrigió el propietario de 25 archivos de entrega y dos directorios mediante la utilidad existente de preparación del almacenamiento, conservando bytes, inodos y modos. No se tocaron los originales ni los controles administrativos.
- Permisos de las 26 referencias: el creador actual obtiene acceso a 11; las otras 15 pertenecen a salas inactivas y permanecen denegadas. La comprobación HTTP HEAD con una sesión real ajena a las salas obtuvo tres respuestas 200 para imágenes públicas y 23 respuestas 404 para las privadas o inactivas, sin errores 500 ni descarga de archivos privados. La comprobación del acceso de los creadores emplea la política real de base de datos con identidad simulada, no sus contraseñas.
- SEO: 44 pruebas locales y 48 comprobaciones HTTP de la publicación aprobadas, incluyendo metadatos públicos, robots, sitemap y exclusión de pantallas privadas.
- Respaldo: restauración transaccional del volcado anterior a la actualización en una base desechable; 252 definiciones de tablas comprobadas, sin ausencias. La base de producción no se sustituyó.
- Sticker: dos fotogramas observados en capturas del navegador. Canvas dibuja el fotograma predeterminado de GIF y no sirve para comprobar su animación.
- Envío real anterior: el ZIP de Física contiene 355 archivos; la descarga coincide byte por byte con los originales. El antivirus revisó el archivo antes de entregarlo.

Las pruebas visuales simuladas no sustituyen las comprobaciones con el servidor real. Las cuentas de auditoría se eliminan al terminar. Las evidencias locales se conservan en `audit/2026-10-07-integral` y no se publican como datos de usuarios.

## Alcance de producción

Web 9.0.44 publicada en https://red-social-android.vercel.app, despliegue `dpl_AYxN8Zys4PnsLak6Br4H6rLupZqJ`. La API publicada utiliza la imagen `sha256:1a4fec9f63731d01c1a05b442f0c4526963ada92cb2fc540ca02655f89533695`; `/ready` responde 200 y el proceso sirve con UID 10001. Se conservaron la configuración y los demás servicios.

Android se entrega como AAB para Google Play en `artifacts/releases/Novvin-9.0.37-134`. La instalación del teléfono tiene la firma de Play y no admite sustituirse por una APK firmada con la clave de subida. No se borró esa instalación ni sus datos. La AAB no se ha publicado en Google Play.

La entrega está publicada y comprobada para el alcance anterior. No constituye una certificación integral de todos los apartados: no se repitieron todas las llamadas, videollamadas y directos entre dispositivos físicos con esta APK; no se comprobó Safari real ni la creación de publicidad con una cuenta administradora real. Los anuncios propios y sus permisos cuentan con revisión de código y pruebas; su formulario web tiene comprobación visual simulada. Las salas sin una foto original conservan el avatar de iniciales. El posicionamiento y la presentación final en buscadores dependen también de sus índices; no se promete un puesto ni un plazo de indexación.

## Evidencias locales

- `api-9044-avatar-url-repair.json`, `api-9044-avatar-mime-repair.json`, `api-9044-avatar-runtime-ownership.json`, `api-9044-avatar-access.json` y `api-9044-avatar-http-access.json` documentan la recuperación y la entrega de imágenes.
- `android-9037-native-chat-e2e.json`, `android-9037-native-question-e2e.json` y `android-9037-release-artifacts.json` documentan la APK probada y los archivos firmados.
- `production-9044-final-health.json`, `api-9044-cleanup-rollout.json` y `api-9044-backup-restore-latest.json` documentan salud, despliegue y restauración.

Las operaciones administrativas de recuperación generan archivos privados que debe poder leer el proceso de servicio. Tras ejecutar una recuperación como operador, se debe utilizar `scripts/prepare-runtime-storage.js` con el UID/GID del proceso y comprobar la entrega HTTP. No basta con comprobar el estado `clean` del antivirus. La utilidad valida rutas e inodos y limita su operación a los directorios de trabajo del servicio.
