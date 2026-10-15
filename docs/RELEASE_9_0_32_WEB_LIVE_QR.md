# Novvin 9.0.32 (129) — web, directos y vinculación

## Correcciones

- Android: la vista nativa de WebRTC conserva el cuadro completo cuando se solicita `FIT`, incluso para una pantalla horizontal dentro de un teléfono vertical. El estado de conexión tiene un solo avatar central y el contador de reacciones tiene fondo y texto legible.
- Android: la aprobación de Novvin Web acepta la sesión local vigente sin exigir un refresh token que ese proveedor no necesita. El proveedor heredado conserva su requisito de renovación.
- Web: códigos QR de mayor resolución, margen de lectura completo, retiro inmediato del código anterior, consultas sin solapamiento y consumo único de la aprobación. Cancelar el flujo impide guardar una aprobación tardía.
- Web: anclar, silenciar, archivar y marcar favoritos guardan la preferencia en la API. El listado ordena los chats anclados y refresca los cambios realizados desde una conversación.
- Web: enviar un mensaje propio vuelve al final de la conversación; el botón para llegar al último mensaje tiene una flecha centrada y una etiqueta accesible.
- Servidor: el detalle del chat devuelve las preferencias de su participante autenticado. Antes devolvía cuatro valores `false` constantes. La selección de preferencias locales no cambia el corte del historial de mensajes.
- Web: los stickers usan el mismo catálogo autorizado del móvil. El selector está conectado en chats, grupos y comunidades; admite búsqueda e importación desde el equipo.
- Web: encuestas y votos usan las rutas del servidor principal e incluyen el identificador de mensaje requerido. Se consultan sus resultados al cargar mensajes.
- Web: notificaciones con identidad de la persona, actualización en segundo plano y actualización del indicador al confirmar una lectura.
- Web: compartir pantalla se inicia desde un clic para conservar el permiso del navegador; la vista conserva la proporción, permite activar sonido y actualiza likes. Actualizar los datos de la misma cuenta no reinicia la conexión.
- Web: ajustes con avatar de video y portada mediante los lectores de multimedia; formularios, llamadas, directos y proximidad ajustados para pantallas estrechas.
- Web: stickers con marco uniforme de 160 × 160 y proporción conservada en chats, grupos y comunidades. Las fotos consecutivas del mismo remitente se agrupan en cuadrícula, con «+N», galería completa y acciones para cada mensaje original. Los archivos pendientes o fallidos conservan sus controles individuales; el álbum mantiene el estado de entrega.
- Web: Ajustes separados en cinco secciones, Momentos en tarjetas compactas, controles de voz al pie, pestañas y formularios de Juegos organizados, solicitudes de amistad con nombre y avatar, pestañas de perfiles sin comprimir «Publicaciones» y formularios de video adaptados a pantallas estrechas.
- Rondas: consulta de perfiles limitada a los ganadores. El ranking del servidor principal se identifica como clasificación de la ronda; no muestra filtros de periodo que ese proveedor no aplica.
- SEO: seis páginas públicas estáticas: inicio, `/novvin-web`, `/ayuda/vincular-web`, `/mensajes`, `/videos` y `/salas-de-voz`. Títulos, descripciones, nombre del sitio, logo, enlaces internos y sitemap. Las rutas privadas permanecen fuera del índice.

## Evidencia de esta revisión

- Android: 1.661 pruebas, 345 suites, cero errores; APK y AAB release firmados, compilación completa correcta. Instalación en emulador Android 35, arranque y lectura de contenido público del servidor comprobados.
- Web: 325 pruebas en 50 suites, ocho del trabajador de multimedia y nueve de prerenderizado/SEO.
- Navegador con datos sintéticos: 40 vistas principales, 30 de directos/llamadas/ajustes/notificaciones/proximidad y 15 públicas en anchos 320, 360, 768, 1440 y 2560. Sin desbordamiento horizontal. WebRTC con conexión real local de dos participantes, pantalla completa y cambio de contador de likes.
- Encuestas: creación, voto y persistencia tras recarga en chat privado, grupo y comunidad mediante las rutas del servidor principal simuladas en navegador.
- Revisión adicional de la web: 45 vistas privadas, 25 formularios y vistas de Rondas/ranking, y 30 vistas públicas en cinco anchos. Álbumes y stickers verificados en chat privado y grupo. Fragmento musical desplazado hasta 0:30 sin limitarlo a los seis segundos de la foto. Sin desbordamiento horizontal ni errores de página en esos casos.
- API Linux: 812 casos; 806 aprobados y seis omitidos por requerir entorno específico. Esos escenarios se ejecutaron por separado: 77 pruebas PostgreSQL y una suite de solicitudes/menciones, todas aprobadas. Prueba nativa de dependencias aprobada. Cuatro casos de preferencias con PostgreSQL y HTTP reales, aislados y con eliminación de sus recursos de prueba.
- Producción: envío real desde la computadora de TXT, PNG, PDF, WAV y MP4 entre las dos cuentas autorizadas; almacenamiento, visualización, descarga o reproducción comprobados según formato. Mensajes de prueba eliminados para todos y objetos de prueba retirados con comprobación de propiedad. Inicio con QR aprobado mediante API, lectura de contenido y reproducción en navegador comprobados; esto no sustituye la prueba de la cámara física.
- Las pruebas reales entre cuentas utilizan únicamente Álvaro y Novvin, autorizadas por el usuario. Sus recibos de ejecución y limpieza se guardan en `audit/2026-10-07-integral`, fuera de las fuentes de la aplicación.

## Límites y pendientes de certificación

Esta revisión no equivale a certificar todos los módulos para producción. Faltan pruebas físicas de cámara QR y directos entre teléfono y navegador en redes distintas, llamadas con la aplicación cerrada, controles de notificaciones reales del sistema, escenarios extensos de desconexión y recuperación de multimedia, y aceptación completa de opciones secundarias de voz, juegos y perfiles. Los escenarios de servidor omitidos en la suite general se comprobaron en ejecuciones PostgreSQL separadas. La indexación, los enlaces secundarios y la posición en buscadores dependen de cada buscador; publicar páginas y sitemap no garantiza aparecer inmediatamente.

La APK 9.0.32 necesita instalarse en el teléfono para recibir los cambios nativos. La actualización de la web y la API no sustituye esa instalación.
