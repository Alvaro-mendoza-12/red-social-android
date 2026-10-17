# Audio, video y navegación: revisión del 17 de octubre de 2026

## Reglas de aceptación

- La presencia, el chat y los avisos son señalización; no prueban recepción RTP.
- Web y Android deben aceptar ofertas y respuestas según la misma comparación ordinal de identidad. El rol anfitrión no impide responder una oferta del espectador.
- Una pista recibida sin MediaStream también debe reproducirse. Las pistas de audio y video posteriores pertenecen a un mismo flujo por participante.
- Compartir o detener cámara/pantalla publica `media-status`, incluido el estado inicial al reconectar. Android usa ese estado para mostrar el video.
- El estado de silencio recibido por socket prevalece sobre una instantánea REST anterior.
- Navegar dentro de la web minimiza la sala, conserva conexiones y captura, y ofrece silenciar, salir y volver. Cerrar sesión destruye la sesión de voz.
- Las vistas previas mudas no cambian la salida de audio. Una salida no disponible permite continuar por la predeterminada.
- El feed mantiene orden y cursor del servidor durante la sesión; la actualización solicitada por la persona crea una sesión nueva.
- La carpeta conserva rutas y contenido en ZIP estándar. Varias carpetas arrastradas juntas comparten archivo, con partes ZIP independientes cuando corresponde. El resumen informa cantidad y tamaños. El límite de 500 MB por archivo y los límites de revisión del servidor se mantienen, también dentro del ZIP.
- La insignia de administrador procede del perfil autorizado, independiente de apodos. Una reconstrucción de caché no debe descartar ese dato. La etiqueta no concede permisos.

## Hallazgos de código

1. La web no emitía `media-status` de salas; Android condiciona la celda de video a ese estado.
2. El directo web anfitrión descartaba ofertas de Android. Android negocia según identidad, y puede iniciar como espectador.
3. Una recarga REST de participantes reemplazaba socket/estado de silencio.
4. El componente de sala era propiedad de la ruta y destruía los medios al cambiar de apartado.
5. Las vistas previas mudas intentaban cambiar salida incluso sin salida elegida.
6. La caché simplificada de contactos Android reconstruía `User` sin `is_admin` y `is_verified`.

## Fuentes primarias

- [MDN: negociación de WebRTC](https://developer.mozilla.org/en-US/docs/Web/API/WebRTC_API/Perfect_negotiation).
- [MDN: añadir pistas y renegociación](https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/addTrack).
- [LiveKit: puertos y cortafuegos](https://docs.livekit.io/transport/self-hosting/ports-firewall/).

## Alcance de validación

Los informes locales de auditoría registran cada prueba y dispositivo efectivo. Un contenedor saludable, comentarios entregados o avisos reproducidos no certifican audio/video. La matriz física entre redes y la instalación en Motorola deben verificarse expresamente; no se infieren de pruebas Chromium o de un emulador. Esta revisión no certifica ausencia general de código malicioso ni producción completa.
