# Novvin 9.0.32 (129) — web, directos y vinculación

## Correcciones

- Android: la vista nativa de WebRTC conserva el cuadro completo cuando se solicita `FIT`, incluso para una pantalla horizontal dentro de un teléfono vertical. El estado de conexión tiene un solo avatar central y el contador de reacciones tiene fondo y texto legible.
- Android: la aprobación de Novvin Web acepta la sesión local vigente sin exigir un refresh token que ese proveedor no necesita. El proveedor heredado conserva su requisito de renovación.
- Web: códigos QR de mayor resolución, margen de lectura completo, retiro inmediato del código anterior, consultas sin solapamiento y consumo único de la aprobación. Cancelar el flujo impide guardar una aprobación tardía.
- Web: anclar, silenciar, archivar y marcar favoritos guardan la preferencia en la API. El listado ordena los chats anclados y refresca los cambios realizados desde una conversación.
- Servidor: el detalle del chat devuelve las preferencias de su participante autenticado. Antes devolvía cuatro valores `false` constantes. La selección de preferencias locales no cambia el corte del historial de mensajes.
- Web: los stickers usan el mismo catálogo autorizado del móvil. El selector está conectado en chats, grupos y comunidades; admite búsqueda e importación desde el equipo.
- Web: encuestas y votos usan las rutas del servidor principal e incluyen el identificador de mensaje requerido. Se consultan sus resultados al cargar mensajes.
- Web: notificaciones con identidad de la persona, actualización en segundo plano y actualización del indicador al confirmar una lectura.
- Web: compartir pantalla se inicia desde un clic para conservar el permiso del navegador; la vista conserva la proporción, permite activar sonido y actualiza likes. Actualizar los datos de la misma cuenta no reinicia la conexión.
- Web: ajustes con avatar de video y portada mediante los lectores de multimedia; formularios, llamadas, directos y proximidad ajustados para pantallas estrechas.
- SEO: páginas públicas estáticas `/novvin-web` y `/ayuda/vincular-web`, títulos y descripciones propios, enlaces internos y sitemap. Las rutas privadas permanecen fuera del índice.

## Evidencia de esta revisión

- Android: 1.661 pruebas, 345 suites, cero errores; APK y AAB release firmados, compilación completa correcta. Instalación en emulador Android 35, arranque y lectura de contenido público del servidor comprobados.
- Web: 311 pruebas en 48 suites, ocho del trabajador de multimedia y seis de prerenderizado/SEO.
- Navegador con datos sintéticos: 40 vistas principales, 30 de directos/llamadas/ajustes/notificaciones/proximidad y 15 públicas en anchos 320, 360, 768, 1440 y 2560. Sin desbordamiento horizontal. WebRTC con conexión real local de dos participantes, pantalla completa y cambio de contador de likes.
- Encuestas: creación, voto y persistencia tras recarga en chat privado, grupo y comunidad mediante las rutas del servidor principal simuladas en navegador.
- API Linux: 812 casos; 806 aprobados y seis omitidos por requerir entorno específico. Prueba nativa de dependencias aprobada. Cuatro casos de preferencias con PostgreSQL y HTTP reales, aislados y con eliminación de sus recursos de prueba.
- Las pruebas reales entre cuentas utilizan únicamente Álvaro y Novvin, autorizadas por el usuario. Sus recibos de ejecución y limpieza se guardan en `audit/2026-10-07-integral`, fuera de las fuentes de la aplicación.

## Límites y pendientes de certificación

Esta revisión no equivale a certificar todos los módulos para producción. Faltan pruebas físicas de cámara QR y directos entre teléfono y navegador en redes distintas, llamadas con la aplicación cerrada, controles de notificaciones reales del sistema, escenarios extensos de desconexión y recuperación de multimedia, y aceptación completa de opciones secundarias de voz, juegos y perfiles. Seis pruebas de servidor omitidas mantienen su estado pendiente. La indexación y la posición en Google dependen del buscador; publicar páginas y sitemap no garantiza aparecer inmediatamente.

La APK 9.0.32 necesita instalarse en el teléfono para recibir los cambios nativos. La actualización de la web y la API no sustituye esa instalación.
