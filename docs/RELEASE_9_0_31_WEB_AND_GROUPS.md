# Novvin 9.0.31 (128): grupos y cliente web

## Cambios

- Salir de un grupo o comunidad, o ser expulsado, conserva los intervalos de historial autorizados. El chat queda de solo lectura. No se entregan mensajes futuros ni mensajes del periodo de ausencia.
- Borrar un chat abandonado afecta a la cuenta que lo borra. Otra cuenta del mismo dispositivo conserva su acceso.
- Si sale el creador, se transfiere la administración sin ampliar el historial del sucesor.
- El escáner de Mi QR usa la actividad de captura vertical.
- `NoWing-web` sustituye a la carpeta eliminada `Conectate-web`. Usa las cuentas, API y canales de tiempo real de Android. Incluye acceso por contraseña/QR, mensajes, grupos, comunidades, publicaciones, momentos, videos, perfiles y los demás módulos existentes del cliente web.
- Los adjuntos de conversación se guardan en IndexedDB antes de enviarse. Se entregan al recuperar conexión, con identificadores estables y separación por cuenta. Los pendientes pueden cancelarse o reintentarse.
- La reproducción web usa solicitudes autenticadas por rangos y recursos temporales por pestaña; no agrega credenciales a las URL. Los avatares de video se reproducen sin sonido y en bucle.

## Comprobaciones de esta revisión

- Android: compilación release firmada y 66 pruebas seleccionadas sin fallos. Incluyen tres pruebas con Room sobre conservación de chats por cuenta.
- API Linux: 801 pruebas aprobadas y seis omitidas; 77 comprobaciones adicionales de PostgreSQL y 100 de membresía, permisos, avisos e historial con HTTP y bases desechables.
- Web: compilación TypeScript/Vite; 271 pruebas de componentes y lógica; ocho pruebas del intermediario de multimedia; tres pruebas del contrato CORS de entrega y 13 comprobaciones con Nginx y archivos ficticios.
- Navegador real, datos ficticios y red simulada: cola de texto, imagen, video, audio y documento; separación de cuentas; cancelación; persistencia al recargar; entrega posterior sin duplicar identificadores.
- Sitio público: acceso QR con aprobación de una de las cuentas autorizadas; mensajes persistidos en ambos sentidos entre API y web (287 ms observados para recibir uno de ellos); vistas de mensajes sin desbordamiento horizontal entre 320 y 2560 píxeles. Es una medición puntual, no una garantía de latencia.
- Entrega pública: Nginx conserva la decisión CORS de la API después de autorizar un archivo. Se comprobó reproducción del avatar en bucle y del feed de videos, con 43 respuestas de archivos, incluidas 31 parciales, sin errores. Los chats largos muestran primero los últimos 80 mensajes y permiten cargar los anteriores; los adjuntos fuera de la vista esperan a ser visibles.

Estas comprobaciones tienen alcances diferentes. No constituyen una certificación integral de todos los módulos para producción ni sustituyen las pruebas físicas del escáner y de cada función móvil/web. El navegador requiere HTTPS y autorización para cámara, micrófono, notificaciones y ubicación. No se garantiza una latencia de cero ni actividad permanente con el navegador cerrado.

## Despliegue web

La web incluye tarjetas de videos compartidos y selección de chats, grupos y comunidades, además de QR del perfil e historial de visitas con las preferencias de privacidad del servidor. La acción de compartir un video en el perfil se guarda en la API. El estado inicial de Personas cerca indica que se debe activar el radar; no presenta un error por una función todavía desactivada.

El acceso como invitado permite leer contenido público en `/explore/videos`, `/explore/posts`, `/explore/moments` y perfiles públicos. Usa las rutas de invitado ya existentes del servidor; la credencial permanece en memoria y se invalida al salir o cambiar de identidad. Se comprobaron los cambios de cuenta durante solicitudes pendientes, la renovación limitada de la sesión y la navegación adaptable con errores simulados. Las comprobaciones públicas de reproducción se registran por separado en el informe de la revisión.

Las galerías históricas con objetos JSON ahora tienen el mismo tratamiento en los analizadores JavaScript y PostgreSQL. Se repararon cinco enlaces de una publicación que todavía apuntaban a un túnel retirado, después de comprobar propietario, estado del análisis y SHA256 de los archivos. El cambio conservó los destinatarios y los orígenes autorizados. Los 22 objetos de la muestra pública posterior tenían archivo, metadatos, análisis válido y acceso de invitado autorizado. El descubrimiento firmado puede cambiar de dirección del mismo servicio sin dejar al invitado sin credenciales de multimedia.

Vercel: proyecto `red-social-android`, raíz `NoWing-web`, salida `dist`. Las variables `VITE_FALLBACK_API_URL` y `VITE_BACKEND_DISCOVERY_PUBLIC_KEY` son públicas. No configurar secretos del servidor dentro del cliente web.

## Editor y reproducción de momentos web

La selección de archivos ya no publica automáticamente. El editor conserva la selección y descripción ante un fallo de publicación. Ofrece música aprobada de comunidad, audio del dispositivo, búsqueda paginada, elección del fragmento sobre la duración completa de la canción, volúmenes, fundidos, presentación musical, texto superpuesto y audiencia. Las cargas y la publicación quedan vinculadas a la cuenta original.

El visor respeta seis segundos para fotos y la duración real del video hasta veinticinco segundos. Pausa los elementos multimedia al pausar, responder o cambiar de pestaña, y no solicita otra vez el catálogo al cambiar de momento. Reproduce el fragmento de audio compatible del servidor y evita duplicar los sonidos ya mezclados en Android. Ofrece reintento ante archivos no disponibles y conserva el momento visible si falla solo la música.

La prueba de navegador con red simulada seleccionó un fragmento 2:00–2:06 de una canción de tres minutos, reprodujo la vista previa, comprobó recuperación del borrador, publicación privada y pausa real del audio. Editor y visor no desbordaron entre 320 y 2560 píxeles. Estas pruebas no certifican todas las herramientas creativas de Android ni las publicaciones sin conexión.


## Revisión visual y publicación web del 15 de octubre de 2026

Se sustituyó la base de estilos heredada por componentes y colores de la APK. Mensajes usa lista y conversación simultáneas en escritorio, y pantallas separadas en móvil. Se eliminaron las tarjetas de resumen, métricas y atajos que no correspondían a la aplicación. Los grupos usan el mismo editor compacto y muestran su avatar en el encabezado; buscar una persona permite crear el chat mediante la API existente.

Videos mantiene un escenario vertical, reproducción del elemento visible, pausa al salir de la vista, sonido explícito, navegación con flechas y carga anticipada de los elementos cercanos. Social incorpora las pestañas de la APK, composición explícita, momentos y acciones de publicación. Los archivos seleccionados se previsualizan antes de publicar. Perfil concentra nombre, verificación, seguidores, usuario, ID e insignia administrativa, con truncamiento de nombres largos.

El visor multimedia abre imágenes de chat y galerías de Social sin abandonar la web; permite ampliar, cambiar de archivo y cerrar con Escape. Los PDF conservan lectura autorizada, vista ampliada y descarga. El visor se cierra y retira las fuentes al invalidarse la identidad. Se conservan los límites de tamaño, el aislamiento de documentos desconocidos y la transmisión autenticada. La política del navegador permite únicamente marcos del propio origen y Blob para el lector PDF.

Salas de voz y Juegos recibieron estilos para espaciado, contraste, participantes, controles y formularios adaptables. Los avatares de video en voz usan el componente de reproducción en bucle. La revisión visual no certifica una conversación de voz entre dos dispositivos reales.

### Evidencias de esta revisión

- Compilación TypeScript, Vite y portada HTML estática aprobadas.
- 289 pruebas de lógica y componentes en 43 suites; ocho del intermediario de multimedia; cuatro de portada, sitemap y metadatos.
- Navegador con datos ficticios: ocho pantallas en 320, 360, 768, 1440 y 2560 píxeles, además de conversaciones individuales, grupos, perfil de jugador, nombres largos, QR, visitas, menús y visor de imágenes. Sin desbordamiento horizontal en la muestra. Se revisaron también las cinco pantallas principales seleccionadas en tema claro.
- Invitado contra el servidor público: video 1080×1920 en reproducción, imágenes públicas decodificadas, perfil y momentos disponibles, inbox privado rechazado, sin credenciales persistidas ni escrituras de prueba.
- Auditoría de dependencias de ejecución: cero vulnerabilidades informadas. El conjunto de herramientas de desarrollo conserva avisos de dependencias que requieren una revisión independiente.

### Acceso público y buscadores

La dirección estable es https://red-social-android.vercel.app/ . La portada se entrega como HTML legible antes de ejecutar JavaScript. Incluye acceso por contraseña y QR, registro, exploración pública, nombre oficial Novvin y una aclaración visible para las variantes Novvyn y NoWin. El sitemap publica únicamente la portada. Las pantallas privadas y de acceso llevan `noindex`; los datos de conversaciones no se prerenderizan.

Se añadieron título, descripción, URL canónica, metadatos para compartir, datos estructurados WebSite, robots.txt y archivo de verificación IndexNow. La solicitud a IndexNow, cuando sea aceptada, confirma recepción de la URL, no su inclusión en los resultados. No se agregó una lista masiva de palabras repetidas a las páginas. Google decide el rastreo, la indexación y la posición; no se dispone en esta sesión de una propiedad verificada de Search Console para solicitar indexación manual.

Referencias de producto: [WhatsApp Web](https://www.whatsapp.com/download/windows), [experiencia web de TikTok](https://newsroom.tiktok.com/new-features-bring-tiktok-magic-to-desktop?lang=en). Referencias de buscadores: [sitemaps de Google](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap), [palabras repetidas y spam](https://developers.google.com/search/docs/essentials/spam-policies#keyword-stuffing), [protocolo IndexNow](https://www.indexnow.org/documentation).

La certificación integral de producción sigue pendiente de las pruebas físicas y funcionales registradas en el informe general. Esta revisión entrega el rediseño web y las comprobaciones descritas, sin declarar equivalencia total entre todas las herramientas creativas de Android y del navegador.
