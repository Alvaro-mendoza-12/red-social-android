# Arranque y desplazamiento de Videos en Android

El 8 de noviembre de 2026 el propietario reportó una pausa visible al entrar en Videos y al deslizar. Se separaron el catálogo, el transporte del video y el dibujo del primer cuadro. Las ramas estables comparten los cambios de este bloque.

## Transporte observado

Se pidió una sesión de invitado y se leyó el catálogo de trece clips. En la serie con ambos tiempos de servidor, el catálogo respondió en 45 ms desde la PC. Los tres clips muestreados entregaron el mismo rango inicial de 64 KiB y la misma huella por Worker y por Ubuntu, con HTTP 206. El Worker confirmó origen R2. En los tres MP4 el índice `moov` estaba al comienzo, antes de `mdat`.

| Muestra | Primer byte por Worker | Consulta del Worker a Ubuntu | Lectura del bucket | Primer byte directo de Ubuntu |
| --- | ---: | ---: | ---: | ---: |
| 1 | 935 ms | 286 ms | 160 ms | 106 ms |
| 2 | 2.590 ms | 1.890 ms | 195 ms | 613 ms |
| 3 | 1.275 ms | 616 ms | 157 ms | 373 ms |

Una serie anterior dio 1.998, 1.987 y 2.066 ms por Worker. Las cifras son mediciones puntuales desde la PC, con conexión HTTPS nueva por solicitud. OkHttp en Android puede reutilizar conexiones. La duración total del primer cuadro también depende del decodificador, la descarga de muestras y el dibujo. En esta red, el salto adicional hacia Ubuntu explicó una parte importante de la demora de R2. Estas series no justifican cambiar el origen para todas las redes.

La ruta de entrega conserva la autorización de Ubuntu y sus controles de revisión. No se copió contenido privado, no se publicaron videos ni se cambiaron banderas de entrega. Los ensayos usaron los endpoints públicos y una cuenta de invitado temporal. La primera solicitud de diagnóstico recibió 403 con el agente de usuario predeterminado de Python y las series siguientes usaron un agente de Novvin para QA.

## Cambios en el feed

- La preferencia de datos móviles se aplica aparte de la intención de reproducir. Al volver a Wi-Fi, un video permitido puede arrancar sin que la pausa automática se convierta en una pausa manual. Se conservan la pausa voluntaria, las superposiciones y el segundo plano.
- El vecino puede terminar una precarga que ya empezó mientras se desliza. Cambiar de video, cuenta u origen cancela la tarea. El búfer del clip visible se supervisa durante la descarga y una caída por debajo del margen detiene la tarea. En Wi-Fi el máximo sube a 3,5 segundos. En datos móviles sigue en 1,5 segundos. El límite de bytes y el ahorro de datos se conservan.
- La portada se controla con el aviso de primer cuadro del reproductor compartido. Un cambio de fuente vuelve a mostrar el estado de preparación. Repetir el mismo clip conserva el cuadro ya visible. Un solo indicador representa la carga de arranque y la espera por búfer.

La [guía de Media3 sobre precarga](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/manage-play) describe reutilizar la fuente preparada y limitar la ventana de vecinos. En [Media3 1.11.0](https://raw.githubusercontent.com/androidx/media/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/MediaPeriodQueue.java), la precarga de playlist elige su siguiente ventana con el modo de repetición activo. En la repetición del clip no apunta al siguiente video del gesto. La ruta actual usa un reproductor y la precarga de rangos con comprobación de permisos. No se añadió un segundo decodificador.

El siguiente contraste físico debe registrar el tiempo desde entrar al primer cuadro y desde soltar el gesto al siguiente cuadro, junto con esperas durante reproducción. Usar `auditRelease`, repetir con y sin precarga, y probar Wi-Fi y datos móviles. La [ubicación de Workers](https://developers.cloudflare.com/workers/configuration/placement/) es otra variable para un ensayo controlado del tiempo de autorización. Una entrega con calidades adaptativas se debe evaluar por separado con archivos y redes que demuestren su necesidad.

El estudio de Princeton sobre videos cortos muestra que los gestos rápidos cambian qué fragmento merece prioridad y que descargar por adelantado varios clips puede desperdiciar datos. Novvin empieza con el vecino inmediato y conserva su límite de bytes. Una ventana mayor tendría que demostrar menor espera y un gasto aceptable en una prueba propia. [Dashlet, NSDI 2023](https://www.usenix.org/conference/nsdi23/presentation/li-zhuqi).

## Verificación de la corrección

Las 113 pruebas JVM seleccionadas de Videos y la caché de reproducción pasaron en 24 clases, sin fallos ni omisiones. Cubren posición de entrada, reglas de reproducción, reconciliación de playlist, precarga, caché y recuperación. La selección usa el código Android de `3906a8c1` y el mismo árbol de aplicación de `main`.

La suite local del Worker pasó sus tres casos de autorización, CORS y retorno a Ubuntu, con el almacenamiento de prueba.

Las APK de depuración y de instrumentación se generaron correctamente. En el emulador pasaron dos casos de apertura en la posición pedida y una prueba con el decodificador de Media3 y un clip público servido por R2. El catálogo de Videos tardó 271 ms. Las tres preparaciones del mismo clip dieron 1.942, 1.043 y 646 ms hasta el primer byte de red y 4.394, 2.033 y 1.353 ms hasta el primer cuadro. Las tres dibujaron un cuadro sin error. Es un diagnóstico del transporte y la decodificación en el emulador, sin gesto automatizado, sin comparación antes y después y sin datos de un teléfono físico.
