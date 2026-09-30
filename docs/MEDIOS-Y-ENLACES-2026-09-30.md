# Auditoría de medios y enlaces

Proyecto exclusivo: `D:\red social - live-gifts`, rama `feature/live-gifts`. Fecha: 30/09/2026.

## Correcciones

- El emisor que responde a la negociación adopta primero los canales del otro equipo y después conecta cámara y micrófono. Evita canales duplicados sin negociar y conexiones ICE que terminaban sin imagen.
- La reconfirmación del micrófono compara la identidad nativa del transmisor. Comparar objetos Java distintos desconectaba la pista de audio. La identidad se obtiene antes de volver a consultar los transmisores: WebRTC libera las referencias de la consulta anterior.
- La fuente de audio se libera antes que la fábrica que posee su hilo. Las pruebas encontraron y reprodujeron un cierre nativo al terminar una sesión con el orden anterior.
- Una renovación de TURN no bloquea el inicio cuando las credenciales guardadas aún sirven. Una configuración ausente o vencida sí debe esperar su actualización.
- Los enlaces de salas de voz muestran progreso, error y reintento, permiten volver atrás y cerrar sin abandonar la sala. Se impide acceso a archivos/contenido local y contenido mixto. Una carga que supera 20 segundos ofrece recuperación.

## Pruebas reales

Las pruebas usaron cuentas sintéticas y el servidor HTTPS activo. Se actualizó el Motorola sin borrar sus datos ni cambiar su cuenta.

| Flujo entre Motorola y emulador | Imagen recibida | Audio recibido en ambos equipos | Primer fotograma en el receptor | FPS recibidos | RTT |
|---|---|---|---|---|---|
| Llamada forzada por TURN | Sí | Sí | 3,095 s | 9,73 | 268 ms |
| Sala de voz | Sí | Sí | 2,280 s | 9,66 | 4 ms |
| Directo | Sí | Sí | 3,137 s | 9,60 | 286 ms |

Estos tiempos son muestras de inicio, no retardo audiovisual medido. Las pruebas de audio verifican RTP entrante; no sustituyen escuchar una conversación entre dos personas. La cámara del Motorola entregó unos 10 FPS en estas condiciones; no se certifican 24/30 FPS ni ausencia de retardo.

- Motorola, llamada por push real: llegó estando fuera de la app, apareció el control para contestar, se pulsó desde la notificación de Android y el repositorio real alcanzó `CONNECTED`. La cuenta personal siguió siendo la misma. Notificación: 1,962 s; preparación de pulsación y conexión: 6,023 s (incluye abrir el panel de Android). La prueba canceló su llamada y eliminó su propia notificación remota.
- Motorola, prueba de medios con relay: cámara apagada/encendida y cambio de cámara transmitieron nuevos fotogramas, primer fotograma 2,205 s, candidatos relay/relay y audio en ambos sentidos.
- Dos videos publicados realmente en el servidor se reprodujeron, adelantaron y reanudaron con el reproductor, caché y perfil de carga usados por la app. Primeros fotogramas: 2,322 y 3,108 s en la primera muestra.
- Cuatro pruebas visuales: recuperación por correo, llamada entrante, tarjetas de rondas y error/reintento/cierre de enlaces. Aprobadas en el Motorola. Las rondas se comprobaron con fuente al 160 % y temas claros/oscuros.
- 905 pruebas unitarias Android aprobadas. Backend: 275 aprobadas de 276, cero fallidas y una omitida por plataforma.
- Ocho servicios del mini-PC `running/healthy`. Nueve lecturas/autorizaciones HTTPS aprobadas: 195–307 ms en la repetición posterior al despliegue.

El ensayo con dos motores en un solo teléfono mostró una ausencia intermitente de audio de retorno. Esa medición se registra sin presentarla como audio bidireccional; la prueba entre equipos separados sí exige recepción en ambos y pasó. Se conserva la comprobación obligatoria de imagen y audio entrante en el ensayo de un teléfono.

## Heurísticas aplicadas al visor de enlaces

1. Estado: progreso visible y final de carga.
2. Lenguaje comprensible: mensaje de conexión sin códigos técnicos.
3. Control: atrás y cerrar.
4. Consistencia: controles y colores Material.
5. Prevención: esquemas restringidos y espera acotada.
6. Reconocimiento: acciones visibles, sin pasos que recordar.
7. Eficiencia: reintento dentro de la misma sala.
8. Presentación: barra compacta y host abreviado si no cabe.
9. Recuperación: error explicado y botón de reintento.
10. Ayuda contextual: indicación de comprobar conexión o cerrar y seguir en la sala.

Esta revisión cubre los cambios y las pruebas enumeradas; no declara toda la aplicación certificada ni comparada bajo carga con otras redes sociales. Las verificaciones adicionales de pantalla y SFU están descritas debajo. Sigue pendiente comprobar concurrencia alta y una conversación escuchada por dos personas, incluyendo una batalla completa con varios invitados.

Referencias: [ciclo de vida de transmisores WebRTC](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnection.java), [diez heurísticas de Nielsen](https://www.nngroup.com/articles/ten-usability-heuristics/).

## Segunda pasada: cierre de sesiones, SFU y pantalla

APK 9.0.11-debug, código 108. Instalación sin borrar datos en Motorola y emulador.

- Los eventos de un socket anterior, la observación de otro motor y las tareas de audio retrasadas se descartan al cerrar o sustituir la llamada. El cierre invalida la sesión antes del reset; el periodo de espera por un participante tampoco puede terminar una llamada posterior.
- Se repitió la llamada real por FCM, contestando desde SystemUI: conectó y mantuvo la cuenta. Tras colgar y esperar dos segundos, el repositorio quedó sin llamada y sin pares ICE. La muestra previa de esta pasada midió 2,226 s hasta la notificación y 5,294 s desde pulsar hasta conexión. Son muestras, no garantía de latencia.
- El puente LiveKit libera la sala y retira sus renderizadores antes de liberar EGL. Al desconectar un participante retira sus pistas por SID; dos nombres visibles iguales ya no son una identidad compartida.
- Prueba con SDK LiveKit real y servidor activo: imagen decodificada/dibujada, 20,00 FPS recibidos y reconexión de la misma instancia del puente a una nueva sesión/pista. Inicio combinado de anfitrión, cámara, receptor y renderizador: 8,977 s. La primera muestra fue 10,639 s; no se atribuye esa diferencia a una optimización de red.
- API real de colaboración: solicitud de invitado, aceptación, tokens con roles host/guest/viewer, inicio/final de batalla y rechazo de duplicado aprobados. Esto comprueba el flujo del servidor; no certifica la imagen/audio de una batalla completa con varias personas.
- Pantalla compartida en una sala real, mediante consentimiento MediaProjection y servicio de la app: imagen cambiante recibida (15 valores de brillo), audio entrante, apagado/encendido y nuevos fotogramas después. Primer fotograma 4,213 s; 11,01 FPS recibidos. La prueba controla explícitamente el reloj de animación de Compose; no interpreta una pantalla estática como un fallo de transmisión.
- Ocho pruebas instrumentadas ejecutadas de interfaz, cámara/directos/sala/relay y videos aprobadas, con tres ensayos especiales omitidos por requerir parámetros. Los especiales de push, pantalla y SFU se ejecutaron por separado y aprobaron; la prueba de dos dispositivos ya descrita también es independiente.

### Cámara antes de la red

Se midió captura local a 640×480, sin espectador, servidor ni codificador de envío:

| Cámara Motorola | Solicitud | FPS capturados |
|---|---|---|
| Trasera | 24 | 10,00 |
| Trasera | 30 | 10,00 |
| Delantera | 24 | 20,00 |
| Delantera | 30 | 20,00 |

En estas condiciones el límite aparece antes de la red. Aumentar el valor solicitado de 24 a 30 no mejoró la captura; no se cambió a ciegas el bitrate ni se certifica una causa concreta (iluminación, exposición o controlador). Debe repetirse con iluminación conocida y medir retardo con una referencia visible. La prueba no graba ni publica imágenes.

Referencia de pantalla: [MediaProjection y consentimiento por sesión en Android](https://developer.android.com/media/grow/media-projection).

## Historial por cuenta y versión 9.0.12

Se detectó una lista local de llamadas común a todas las cuentas del teléfono. Ahora
la lectura, escritura, borrado individual y borrado completo usan el ID de la cuenta.
El registro asíncrono de una llamada conserva el dueño capturado al terminar; no
puede almacenarse como llamada de una cuenta seleccionada después. La lectura
observa los cambios de sesión y de DataStore.

Los registros anteriores no guardaban el dueño: permanecen archivados en su clave
original, pero no se muestran ni se migran a una cuenta sin poder atribuirlos.
No se borraron los datos personales ni las sesiones. Solo se retiraron registros
de QA cuyo interlocutor era una de las dos cuentas sintéticas de esta auditoría.

- 908 pruebas unitarias aprobadas, cero fallidas/omitidas.
- Prueba en Motorola con DataStore real: una entrada de otra cuenta no fue visible;
  una entrada del dueño sí; se retiraron únicamente entradas de las cuentas QA y
  se comprobó que la sesión personal conservó su identidad.
- Llamada real por FCM repetida en 9.0.12: contestación, conexión y cierre sin
  reaparición de pares ICE aprobados.
- Diez consultas y controles de acceso por HTTPS público aprobados: 189–469 ms.
  Es una muestra de lecturas/autorización, no una prueba de carga.

APK instalada: `9.0.12-debug`, código `109`.
SHA-256: `555b7b3a88c892dd79a87f6c0648a68b7df726f5e7308b800f33450c52b95eb4`.

La primera prueba de batalla no aprobó: el emulador con renderizado por software
limitó la imagen y las muestras de audio no estaban sincronizadas. Se investigó
y repitió el ensayo como se describe a continuación; ese resultado inicial no se
utiliza para afirmar un fallo confirmado de audio en producción.

## Batalla con dos dispositivos y versión 9.0.13

El SDK ajusta ahora captura y publicación al perfil del equipo: básico 640×360,
24 FPS solicitados y 450 kbps sin simulcast; medio 960×540/24/900 kbps; alto
1280×720/30/1,7 Mbps. Los perfiles medio y alto usan simulcast. Se comprueba el
resultado de activar cámara/micrófono y se explica el fallo si no pudieron
publicarse. Los FPS solicitados no garantizan la capacidad real de la cámara.

Se preparó una solicitud de invitado aceptada y una batalla activa en el servidor
real. Motorola y emulador conectaron el SDK con roles anfitrión/invitado,
publicaron micrófono y cámara, decodificaron y dibujaron la imagen del otro.
El ensayo espera a ambos antes de medir y antes de desconectar. Sin esas barreras,
un equipo terminaba mientras el otro medía, dejando estadísticas sin audio
entrante aunque hubiera recibido video. La exigencia de audio en ambos se mantiene.

| Receptor | Imagen dibujada | RTP de audio recibido | FPS recibidos | Inicio hasta primera imagen |
|---|---|---|---|---|
| Anfitrión, Motorola | Sí | Sí | 11,20 | 3,888 s |
| Invitado, emulador | Sí | Sí | 19,90 | 13,385 s |

Ambas pruebas aprobaron y la batalla se finalizó. El inicio lento del invitado
sigue siendo una observación: incluye arranque del SDK y cámaras, no es una
medición del retardo audiovisual. El emulador se reinició con GPU del equipo;
no se atribuye el cambio de FPS solo al código. Este ensayo conecta el SDK desde
cero; la transición de un directo P2P existente a una batalla requiere otra prueba.

911 pruebas unitarias aprobadas, cero fallidas/omitidas. APK instalada en ambos:
`9.0.13-debug`, código `110`, sin borrar los datos ni la sesión del Motorola.
SHA-256: `8fc67ad35a91bf0265c2be5c6fae83751a7ebc54ff511c93d306ce63bd59d4d2`.

No se certifica conversación escuchada por personas, carga alta ni cero retardo.
Referencia: [opciones de captura del SDK LiveKit Android](https://github.com/livekit/client-sdk-android/blob/v2.27.0/livekit-android-sdk/src/main/java/io/livekit/android/room/track/LocalVideoTrackOptions.kt).


## Transición con captura de directo ya activa

Se amplió el ensayo de dos equipos iniciando antes la captura nativa del anfitrión,
como ocurre al pasar de directo a batalla. Con la implementación anterior se
reprodujo una interrupción: la pista original dejó de entregar fotogramas cuando
el SDK abrió su propia cámara. El invitado sí veía la cámara nueva; eso ocultaba
el fallo a los espectadores que seguían usando la transmisión original.

La colaboración recibe ahora las imágenes de la captura existente mediante un
capturador de SDK que conserva rotación, planos I420 y la propiedad de sus buffers.
No abre una segunda cámara ni libera la pista del directo. Sigue los cambios de
pista al apagar/encender o cambiar de fuente. El cierre retira el observador.

Los controles de pausa, video y micrófono del anfitrión actualizan también las
publicaciones de batalla. Los cambios durante la conexión conservan el último
estado solicitado y los fallos muestran un aviso. La aceptación de una batalla
respeta un micrófono apagado y un directo pausado. La preparación para recibir
invitados conserva sus publicaciones apagadas. Pedir conexión dos veces para
la misma sala, rol y captura conserva la instancia ya conectada.

La repetición aprobó en ambos dispositivos. La captura original continuó
entregando fotogramas durante la colaboración y durante el silencio de sus
publicaciones. Apagar/encender cámara y micrófono actualizó las publicaciones;
una nueva captura trasera reemplazó la pista original y llegó al capturador
compartido. Pedir conexión de nuevo conservó la misma instancia de sala.

| Receptor | Imagen dibujada | RTP de audio | FPS recibidos | Inicio hasta primera imagen |
|---|---|---|---|---|
| Anfitrión, Motorola | Sí | Sí | 11,10 | 5,405 s |
| Invitado, emulador | Sí | Sí | 20,00 | 14,370 s |

La prueba comprueba continuidad de la captura nativa; no añade otro espectador
P2P durante la batalla ni acredita mezcla audible de ambos transportes. La
negociación P2P entre dos equipos se verificó por separado. El arranque frío del
invitado sigue siendo una observación; no se afirma cero retardo ni una mejora
de ese tiempo. El primer intento de repetición esperaba solo un segundo antes
de comprobar la captura inicial; se corrigió la espera a fotogramas reales.

915 pruebas unitarias aprobadas, cero fallidas/omitidas. Compilación completa
aprobada. APK instalada: `9.0.14-debug`, código `111`, sin borrar datos ni cambiar
la sesión del Motorola.
SHA-256: `fabb92ac7773975ef292cecf5c57497f54a9d407a4fef655a5dcf4b71bf6d2f2`.
