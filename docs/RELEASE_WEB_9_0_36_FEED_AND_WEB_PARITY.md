# Novvin Web 9.0.36

Continuación de la revisión de la web del 15 de octubre de 2026. Destino de publicación: https://red-social-android.vercel.app.

## Videos

- El feed deja de consultar siempre el primer lote cronológico. Utiliza las recomendaciones del servidor, con una sesión por visita y el cursor de continuación que devuelve la API.
- Se conserva el orden del servidor y se eliminan duplicados al incorporar páginas. No se reinicia automáticamente el catálogo al agotarlo.
- Las cuentas con el perfil de algoritmo desactivado mantienen el feed básico con sesión de navegación; no quedan bloqueadas por esa configuración.
- Se registran vistas tras dos segundos de reproducción visible, sin contar saltos del reproductor ni pestañas ocultas. La API aplica sus permisos para usar señales en las recomendaciones.
- Los comentarios siguen al video visible. Al sustituir el panel, el foco deja de desplazar el feed al botón del video anterior.
- Un error de carga inicial permite reintentar. Un cursor caducado permite comenzar una nueva continuación sin reutilizar el cursor inválido.

## Mensajes y archivos

- La lista y el chat usan el ancho disponible también en ventanas equivalentes a una reducción fuerte del zoom. El contenido del chat mantiene un margen limitado.
- Al pulsar un sticker recibido aparecen favoritos, responder, reenviar y descargar. Los favoritos usan el catálogo y los permisos existentes del servidor.
- Se conserva la animación del archivo y se respeta el indicador de animación del catálogo, incluidos los stickers estáticos.
- Se corrige una referencia a la variable de autor antes de inicializarla en los álbumes de fotos del chat.
- La subida pendiente admite cinco minutos para transferir y analizar el archivo. El plazo anterior de 25 segundos era insuficiente para algunas subidas.
- Los ZIP de Windows también se normalizan al reintentar archivos que ya estaban guardados en la cola.
- Se valida el límite real de 60 MB por archivo enviado. Las carpetas admiten hasta 500 entradas y 128 MB de origen, siempre que el ZIP final quede dentro de 60 MB. Un archivo que no puede caber se rechaza antes de entrar en una cola que nunca podría enviarlo.

## Ranking, solicitudes, rondas y asistente

- El ranking general dispone de personas, videos y publicaciones. Personas usa el ranking y la posición propia de la API; videos y publicaciones ordenan los 100 contenidos recientes devueltos por el servidor, como el repositorio móvil. El podio de retos conserva su ruta independiente.
- Solicitudes distingue recibidas y enviadas, muestra el destinatario correspondiente y permite actualizar. Solo las recibidas ofrecen aceptar o rechazar.
- Rondas agrupa presentación, fase, plazos, podio, resumen, participación y propuestas en una distribución adaptable. No ofrece enviar participaciones fuera de su fase y limita el selector de archivos al tipo de reto.
- El asistente conserva las últimas 40 entradas por cuenta en este navegador, admite detener, reintentar sin duplicar la pregunta, copiar respuestas y enviar con Enter o escribir otra línea con Mayús + Enter.
- La entrada explícita a una sala desde Mensajes no queda bloqueada por el acceso rápido guardado de otra sala.

## Momentos y publicación

- El visor de momentos tiene sus estilos completos: imagen o video contenido, progreso, navegación, pausa, sonido y respuesta, adaptados a pantallas pequeñas.
- Las respuestas tardías no borran el borrador de otra historia. Una búsqueda de chat que termina después de abandonar la historia no envía una respuesta fuera de su contexto.
- Cambiar de música cancela la lectura anterior de audio local y libera sus recursos.
- Publicar video y crear directo usan diálogos nativos, con foco contenido, encabezado visible y acciones accesibles. Durante el envío no se pueden cerrar ni editar accidentalmente.
- Los errores de preparación se muestran dentro del formulario y se conserva su contenido para reintentar.

## Evidencia y alcance

- Compilación TypeScript y producción Vite.
- 419 pruebas unitarias y de integración de la web, en 69 suites.
- Comprobaciones de navegador aisladas para recomendaciones, cursores, vistas, comentarios, álbumes, stickers, solicitudes, ranking, asistente, rondas, momentos y publicación.
- Revisión visual en 320, 390, 768 y 1440 px; Mensajes también en 1920 y 3840 px.
- Subida de ZIP con 26 segundos de demora en un navegador real y API aislada: un solo intento y estado enviado.
- Prueba contra la API pública entre las dos cuentas humanas autorizadas: ZIP enviado, recibido y descargado con bytes idénticos. Mensaje y objeto de prueba eliminados al finalizar.
- Consulta real de recomendaciones: una cuenta con cursor autoritativo y siguiente página sin duplicados; otra con perfil desactivado y 12 videos disponibles en el feed básico.
- Las comprobaciones de diseño usan datos sintéticos; las pruebas de la API real se identifican por separado. Este documento no certifica el APK ni sustituye las comprobaciones físicas pendientes de llamadas entre dispositivos y redes.
- APK y servidor sin cambios de versión en esta entrega. Ambas ramas locales conservan el mismo código revisado de la web.
