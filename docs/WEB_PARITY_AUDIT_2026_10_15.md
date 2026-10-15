# Auditoría de Novvin — 15 de octubre de 2026

## Estado

La versión móvil es 9.0.32 (129). Hay compilación release, firma original y pruebas aprobadas; **la certificación integral de producción sigue pendiente**. Una prueba de código, una captura de navegador y una prueba entre teléfonos comprueban aspectos distintos. Los resultados de abajo indican el alcance de cada comprobación.

Web pública: https://red-social-android.vercel.app . El código revisado se conserva en `main` y `feature/live-gifts`. Los recibos de ejecución están en `audit/2026-10-07-integral`; contienen evidencia de pruebas, sin incorporarse al producto.

## Lista de comprobación

| Nº | Apartado | Comprobado | Pendiente para aceptación completa |
|---|---|---|---|
| 1 | Compilación móvil y firma | APK/AAB release; 1.661 pruebas, 345 suites, cero errores. Instalación y arranque en emulador Android 35. | Instalación y aceptación de esta versión en un teléfono físico. |
| 2 | API y datos | 806 pruebas generales, 78 pruebas en ejecuciones PostgreSQL complementarias, dependencias nativas y cuatro casos HTTP de preferencias. Contenedor saludable tras despliegue. | Pruebas de carga representativas y recuperación ante fallos prolongados. Estas suites no equivalen a probar toda concurrencia real. |
| 3 | Mensajes y archivos web | TXT, PNG, PDF, WAV y MP4 enviados entre Álvaro y Novvin. Visor, descarga y reproducción según formato. Stickers uniformes y álbum de cinco fotos con galería y acciones comprobados en navegador. | Aceptación extensa entre móvil y web de grupos/comunidades, mensajes antiguos, permisos de historial, reingresos y recepción simultánea. |
| 4 | Vincular web con QR | QR de producción decodificado desde su imagen; aprobación y consumo por las rutas reales, sesión cerrada después de la prueba. | Cámara física, condiciones de iluminación y redes distintas. |
| 5 | Directos | Proporción completa de pantalla, contador de likes y conexión WebRTC local con dos participantes. Corrección de viewport Android y del avatar duplicado. | Emisión web y recepción física Android en redes distintas, permisos, cambio de fuente y reconexión prolongada. |
| 6 | Llamadas | Compilación y revisión de pantallas y controles. | Llamada real con proceso cerrado, contestación desde notificación, arranque desde el logo, audio bidireccional y reconexión. |
| 7 | Notificaciones | Identidad del actor, lectura y actualización de indicador en la web. | Responder, leído y silenciar en notificaciones Android reales; comprobar carreras al cargar el avatar y mensajes eliminados con el proceso cerrado. |
| 8 | Desconexión | La cola de salida existente se conserva; los archivos pendientes o fallidos no quedan ocultos en álbumes. | Modo avión, reinicio de proceso y recuperación automática de texto y multimedia, sin pérdida ni duplicación, entre dispositivos reales. |
| 9 | Voz, Juegos, Momentos y perfiles | Pantallas adaptadas; API real de catálogo, perfil de jugador, conexiones y salas consultada. Formularios de video/directo/momento, música desde 0:30, solicitudes y publicaciones de perfiles comprobados en cinco anchos. | Audio de una sala entre dispositivos y aceptación completa de conexiones/partidas, permisos y opciones secundarias. El ranking principal corresponde a la ronda actual. |
| 10 | Presentación en buscadores | HTML público legible sin JavaScript, seis páginas, títulos, descripciones, logo, nombre del sitio, enlaces internos, canonical y sitemap; rutas privadas no indexables. | Que cada buscador rastree, indexe y elija nombre, icono y enlaces secundarios. El posicionamiento no se puede certificar desde el código. |

## Cambios de presentación de la web

- Mensajes: stickers con el mismo marco, imágenes consecutivas reunidas en cuadrícula, contador de fotos adicionales y visor con todas las fotos. Se conserva cada mensaje y sus acciones originales.
- Momentos: una única cuadrícula de tarjetas, sin duplicar el mismo contenido en dos listas. Editor con encabezado y opciones de audio/texto ordenadas.
- Ajustes: navegación entre Perfil, Privacidad, Seguridad, Acceso y QR, y Apariencia; enlaces de imagen avanzados plegables.
- Voz: participantes y controles separados; micrófono, audio y salida juntos en la zona inferior.
- Juegos: pestañas adaptables, avatares mediante el componente común, formularios y mensajes de error organizados.
- Perfiles: cuadrícula de publicaciones y pestañas sin comprimir su texto en pantallas estrechas.
- Solicitudes: nombres y avatares, acceso al perfil y búsqueda de personas. Sin presentar identificadores técnicos como nombres.
- Rondas: tarjetas y formularios adaptados, búsqueda de clasificación y consulta acotada de ganadores.

La comprobación visual adicional cubre 100 combinaciones: 45 vistas privadas, 25 vistas de formularios/Rondas/ranking y 30 páginas públicas. Usa anchos de 320, 360, 768, 1440 y 2560 píxeles. Las pruebas con datos sintéticos no escriben en producción. Las pruebas reales de envío están restringidas a las dos cuentas autorizadas y sus archivos de prueba se eliminan después.
