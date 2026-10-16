# Novvin Web 9.0.35 — voz, llamadas, stickers y archivos

Esta entrega actualiza la web. Android permanece en 9.0.34, código 131.

## Llamadas y salas de voz

- La cámara que llega después del audio se muestra sin volver a entrar a la pantalla. Se observan los cambios de pistas del mismo flujo, incluyendo silencio, reactivación y finalización.
- La llamada conecta el flujo al reproductor al montar su pantalla, incluso cuando el audio/video ya estaba disponible antes.
- La vista local muestra la pantalla que realmente se está compartiendo y vuelve a la cámara al terminar. Los participantes nuevos reciben la fuente activa.
- Se reutiliza el emisor de video negociado. Al detener el video se renegocia su dirección para que el receptor oculte la última imagen, y se permite reactivarlo después.
- Si cambiar una fuente falla, se intenta restaurar las pistas anteriores. Los permisos concedidos después de salir de la sala o colgar liberan inmediatamente la captura tardía.
- Se utiliza el evento `video-state` que ya existe en señalización para mostrar el avatar cuando el otro participante apaga la cámara. También se actualiza el indicador remoto de micrófono.
- La cámara local aparece en una vista pequeña sobre el video principal. Los controles tienen nombre, estado visible, límites adaptados al ancho y pantalla completa.
- Ajustes de audio/video en un diálogo compacto, con cierre por Escape y devolución del foco. En salas se puede cambiar micrófono y cámara en uso; la lista responde a conexiones/desconexiones de dispositivos.
- Salida de audio seleccionable cuando el navegador permite `setSinkId`; los demás navegadores indican que se use el selector del sistema. Si se bloquea la reproducción automática, aparece una acción explícita para escuchar.

## Stickers

- Marco consistente de 160 × 160 en mensajes; las imágenes animadas usan su archivo original.
- El filtro Animados respeta el dato del catálogo móvil, incluso si la URL no tiene extensión. Ese dato se conserva al guardar un favorito.
- Actualizar vuelve a consultar el mismo catálogo autorizado para recoger favoritos guardados desde otro dispositivo. La sesión utiliza la selección común del backend Ubuntu.

## Archivos

- Los documentos que no admite directamente la API, incluidos formatos antiguos y archivos de texto especiales, se empaquetan en ZIP conservando su nombre y bytes dentro del archivo.
- Los formatos admitidos de imagen, video, audio y documentos se conservan directamente; los ZIP de Windows mantienen su contenido y nombre.
- Se comprueba el límite de 128 MiB antes de leer o comprimir un archivo. Las restricciones existentes de carpetas y la validación del servidor se mantienen.

## Comprobaciones

Los registros están en `audit/2026-10-07-integral/web-av-*` del checkout principal.

- Suite web completa, compilación de producción y lint de los componentes/utilidades modificados. El gestor de voz conserva su exclusión histórica del lint; su compilación y pruebas se comprueban aparte.
- Navegador con datos sintéticos: llamadas a 320, 360, 768, 1440 y 2560 px; ajustes de salas a 320, 768 y 1440 px; cambio de fuente, salida de audio, marco de stickers, dos fotogramas distintos de un GIF en bucle, actualización del catálogo y preparación ZIP.
- WebRTC real local entre dos conexiones del navegador: paquetes de audio, fotogramas de video, cámara añadida a una sesión de audio existente, sustitución por pantalla, parada y reactivación. La recepción se comprobó mediante estadísticas RTP y colores de fotogramas recibidos.

Las capturas y los dispositivos del navegador de estas pruebas son sintéticos. No se afirma que esta entrega pruebe llamadas por el servidor público, llamadas entre redes distintas, cámaras/micrófonos físicos ni conexión con un teléfono. No hubo cambios de backend ni una nueva certificación integral de producción.
