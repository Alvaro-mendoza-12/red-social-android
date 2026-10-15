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
- API Linux: 798 pruebas aprobadas y seis omitidas; 76 comprobaciones adicionales de PostgreSQL y 100 de membresía, permisos, avisos e historial con HTTP y bases desechables.
- Web: compilación TypeScript/Vite; 200 pruebas de componentes y lógica; ocho pruebas del intermediario de multimedia.
- Navegador real, datos ficticios y red simulada: cola de texto, imagen, video, audio y documento; separación de cuentas; cancelación; persistencia al recargar; entrega posterior sin duplicar identificadores.
- Sitio público: acceso QR con aprobación de una de las cuentas autorizadas; mensajes persistidos en ambos sentidos entre API y web (287 ms observados para recibir uno de ellos); vistas de mensajes sin desbordamiento horizontal entre 320 y 2560 píxeles. Es una medición puntual, no una garantía de latencia.

Estas comprobaciones tienen alcances diferentes. No constituyen una certificación integral de todos los módulos para producción ni sustituyen las pruebas físicas del escáner y de cada función móvil/web. El navegador requiere HTTPS y autorización para cámara, micrófono, notificaciones y ubicación. No se garantiza una latencia de cero ni actividad permanente con el navegador cerrado.

## Despliegue web

Vercel: proyecto `red-social-android`, raíz `NoWing-web`, salida `dist`. Las variables `VITE_FALLBACK_API_URL` y `VITE_BACKEND_DISCOVERY_PUBLIC_KEY` son públicas. No configurar secretos del servidor dentro del cliente web.
