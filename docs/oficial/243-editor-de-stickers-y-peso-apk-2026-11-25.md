# Editor de stickers y peso de la APK

## Editor Android

Al abrir una foto estática, el editor solicitaba la imagen a resolución completa y después creaba un segundo bitmap reducido. La vista previa usaba el mismo valor para una carga en curso y una decodificación fallida, por lo que podía mostrar el indicador indefinidamente.

Android 9 y posteriores solicitan el tamaño de salida al leer la cabecera. Android 7 y 8 consultan primero las dimensiones, decodifican con muestreo y aplican la orientación EXIF. El lado mayor de la vista previa queda limitado a 1.400 píxeles y las fotos pequeñas conservan su tamaño. El diálogo de error permite cerrar el editor y volver a elegir el archivo. Los stickers animados usan su ruta de envío original. [ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder#setTargetSize(int,%20int)) · [BitmapFactory.Options](https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inSampleSize)

Una foto JPEG de 4.000 × 3.000 produce una vista previa de 1.400 × 1.050. Para un bitmap ARGB de cuatro bytes por píxel, las superficies representan 45,78 y 5,61 MiB respectivamente. La comparación describe los píxeles de la imagen y la vista previa. El pico total del proceso requiere medición en dispositivo.

## Inventario del paquete anterior

El APK firmado 9.1.43 (183) de `a042d092` medía 168,50 MiB en universal y 72,50 MiB en ARM64. En ARM64, `libjingle_peerconnection_so.so` y `liblkjingle_peerconnection_so.so` sumaban 22,76 MiB. Sus huellas son distintas y los AAR locales los atribuyen a WebRTC 137.7151.05 y WebRTC con prefijo 144.7559.09, este último transitivo de LiveKit 2.27.0. Se encontraron consumidores `org.webrtc` y `livekit.org.webrtc` en las rutas Android.

También se identificaron `gift_anim_group_5_sheet` (2,17 MiB) y `gift_celestial_dragon` (2,03 MiB) en el mapa de recursos del APK ARM64. Son recursos de los regalos. La corrección del editor está dirigida al consumo de memoria al abrir una foto.

## Actualización en Google Play

Play Console registró que `https://novvinapp.com/privacidad` respondía 404. La misma incidencia se reprodujo en `/eliminar-cuenta`. Se comprobó que los dos documentos del origen público responden 200 sin iniciar sesión. Se guardó en Play Console la [política pública](https://conectate.tail9b267c.ts.net/privacidad) y se guardaron ambos enlaces de eliminación en Seguridad de los datos. Android abre esa política y la [solicitud de eliminación](https://conectate.tail9b267c.ts.net/eliminar-cuenta). Una publicación efectiva depende de la revisión de Google Play. [Política de Datos del Usuario](https://support.google.com/googleplay/android-developer/answer/10144311)

La entrega conserva la versión visible 9.2.1, aumenta de 185, confirmado en el catálogo de Play, a 186 y mantiene Android 16 como objetivo (`targetSdk = 36`). [Requisito de nivel de API](https://support.google.com/googleplay/android-developer/answer/11926878)

## Validación

La prueba JVM usa datos JPEG en memoria para ImageDecoder y archivos para BitmapFactory. La apertura mediante el selector de Android queda pendiente de prueba en dispositivo. La revisión móvil integral sigue abierta. Queda pendiente medir el pico de memoria y recorrer la selección y edición de fotos, el aviso y su recuperación en un teléfono físico.
