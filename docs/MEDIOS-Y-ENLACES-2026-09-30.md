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


## Regresión encadenada y liberación de memoria

La pasada completa de 9.0.14 aprobó pantallas, llamada real por FCM, captura de
llamada/directo/sala y reproducción de dos videos con avance/reanudación. Al
entrar después a pantalla compartida se produjo un SIGSEGV en el hilo nativo de
señalización. La prueba de pantalla aislada volvió a aprobar (25,451 s). Esa
pasada encadenada no se registra como aprobada.

La inspección encontró consultas de estadísticas y salud que accedían a objetos
nativos fuera del ejecutor de medios mientras podían liberarse. También había
pares retirados del mapa pendientes de liberación por el tiempo de separación
del renderizador: no estaban incluidos al liberar o recortar el motor.

Se serializan las consultas de estadísticas con el cierre, la UI lee estado
almacenado y cada objeto pendiente conserva un dueño hasta que el cierre o la
tarea retrasada lo reclame. El reset recoge los pares pendientes; el cierre y
recorte recogen también las fuentes de video diferidas antes de liberar la
fábrica. La biblioteca no contiene símbolos: no se atribuye la traza exacta a
una sola condición, aunque se corrigieron estas carreras y se repitió el ensayo.

### Repetición final en 9.0.15

- Compilación de app, APK de pruebas y 918 pruebas unitarias aprobadas, sin
  fallos, errores ni omisiones.
- Motorola: 16 ciclos con pares WebRTC nativos reales, salida rápida, reset,
  recorte de memoria y reentrada. Se comprobó que no quedaban pares pendientes,
  que la fábrica volvía a estar disponible y que las consultas concurrentes de
  nivel de audio/salud no cerraban el proceso. Este ensayo es de ciclo de vida;
  no transmite una conversación ni sustituye las pruebas de red.
- Secuencia completa en Motorola: 11 pruebas ejecutadas aprobadas y una omitida
  porque requiere coordinación de dos dispositivos. Incluye cuatro pruebas de
  interfaz, cámara/audio en sala y directo, llamada forzada por TURN, llamada
  real por FCM contestada fuera de la app, dos videos con avance/reanudación,
  pantalla compartida después de esas sesiones e historial aislado por cuenta.
  La pantalla compartida volvió a entregar imágenes cambiantes tras apagarse
  y encenderse. La secuencia terminó sin el cierre nativo observado en 9.0.14.
- Los primeros fotogramas de los dos videos llegaron en 776 y 241 ms. Son
  muestras con caché caliente; no se comparan como mejora de red frente a la
  primera reproducción.
- La prueba adicional de batalla se repitió entre Motorola y emulador. Los dos
  SDK dibujaron imagen y recibieron RTP de audio. En el anfitrión se mantuvo la
  captura original del directo y se comprobaron pausa de publicaciones, cámara,
  micrófono, sustitución por captura trasera y conexión repetida sin otra sala.

| Receptor, 9.0.15 | Imagen dibujada | RTP de audio | FPS recibidos | Inicio hasta primera imagen |
|---|---|---|---|---|
| Anfitrión, Motorola | Sí | Sí | 11,10 | 5,140 s |
| Invitado, emulador | Sí | Sí | 20,00 | 11,420 s |

El tiempo de arranque del invitado sigue siendo una observación pendiente.
Estos tiempos incluyen iniciar cámaras/SDK y esperar al otro dispositivo;
no son una medida del retardo entre lo capturado y lo escuchado/visto.
No se certifica audio escuchado por dos personas, mezcla de micrófono entre
P2P y SFU, alto número de participantes ni la totalidad de la aplicación.

Artefactos locales de la pasada: `build/media-lifecycle-final-build.log`,
`build/media-lifecycle-device-9.0.15.log`,
`build/communication-final-complete-9.0.15.log` y
`build/shared-capture-9.0.15-duet.log`. Los dos últimos se complementan: la
prueba de dos equipos necesita parámetros y se ejecuta por separado.

APK instalada en ambos equipos sin borrar datos: `9.0.15-debug`, código `112`.
SHA-256: `0737d52b6d68b6865256e0f4a6982bf48488b2cef9a231645abd8263e2a6e846`.

## Apertura de la app después de la auditoría

La reapertura de la app mostró una demora adicional, independiente de las
pruebas de medios. Tres arranques de proceso nuevos medidos con `am start -W`
tardaron 9,232, 8,106 y 8,294 s hasta la pantalla. En la última muestra se
capturaron los registros durante el arranque: `MainActivity.super.onCreate`
consumió 3,873 s, `setContent` se presentó a los 3,962 s y Android informó
261 fotogramas saltados. El sondeo HTTPS del mismo periodo aprobó diez
lecturas/autorizaciones en 186–325 ms y ocho servicios saludables; ese sondeo
no mide por sí solo todas las solicitudes de inicio de la app.

`AuthRepository` construía las dependencias de mensajes, perfiles, grupos,
comunidades, notificaciones, cuentas guardadas y segundo factor aunque solo
se consultara el flujo de sesión. La carga de cuentas y segundo factor abre
almacenes cifrados en sus constructores. Se difiere su creación mediante los
mismos singletons de Dagger hasta la operación que los necesite. La lista de
cuentas conserva su almacenamiento y sus reglas; no se reemplaza por una
lista vacía ni se rebaja la verificación del segundo factor.

La entrada en primer plano retira la burbuja de LIVE sin construir su repositorio;
la actualización de presencia resuelve perfil/red en el ejecutor de IO.
Se añadió una prueba en dispositivo que falla si leer el estado inicial de
autenticación resuelve cualquiera de las siete dependencias diferidas.

### Mediciones de 9.0.16

Compilación completa y 918 pruebas unitarias aprobadas, cero fallidas,
erróneas u omitidas. Actualización instalada en Motorola sin borrar datos.
Se repitieron tres arranques con el proceso cerrado antes de cada uno:

| Muestra | Primera pantalla (`am start -W`) | `super.onCreate` | `setContent` presentado |
|---|---|---|---|
| 1 | 6,510 s | 431 ms | 584 ms |
| 2 | 5,431 s | 508 ms | 636 ms |
| 3 | 4,395 s | 339 ms | 443 ms |

La creación de la actividad dejó de bloquear casi cuatro segundos. Son muestras
de una APK de depuración, con proceso nuevo pero cachés del sistema calientes;
no un percentil de producción ni el tiempo hasta cargar todos los mensajes.
La composición inicial aún produjo fotogramas saltados (88–137 en estas muestras).
Se conserva esa observación: la mejora no acredita una apertura instantánea.

Registros: `build/startup-9.0.16-measurements.json`,
`build/startup-9.0.16-logcat.log` y `build/startup-9.0.16-build.log`.
El intento adicional de traza por muestreo no produjo datos y no se usa como
evidencia de atribución de tiempos.

Referencia: [medición de la primera pantalla y de la carga completa en Android](https://developer.android.com/topic/performance/issues/launch-time).

### Repetición completa y caída de la entrada pública

La primera secuencia de 9.0.16 registró siete fallos y se conserva en
`build/communication-final-complete-9.0.16.log`. Uno pertenecía al ensayo:
un punto de entrada Hilt declarado solo en la APK de pruebas no existe en el
componente de producción. El ensayo reutiliza ahora un punto de entrada real.
Los otros seis fallos coincidieron con conexiones TLS cerradas en la entrada
pública de Funnel, sin solicitudes recibidas por la señalización del servidor.

Corrección de alcance de los sondeos anteriores: consultar el hostname HTTPS
desde una PC conectada a Tailscale puede usar su dirección privada. Esos
resultados comprueban los servicios, pero no acreditan disponibilidad pública.
Se forzaron ambas direcciones IPv4 obtenidas por DNS público conservando SNI
y verificación del certificado: ambas fallaron con HTTP 000/error TLS 35,
aunque los ocho contenedores estaban saludables y Funnel figuraba habilitado.

Se guardó la configuración de rutas en
`/home/alvaro/conectate-local-fallback/backups/funnel-before-public-recovery-20260930.json`
y se reinició únicamente `tailscaled`. Tras la reconexión del daemon, las dos
entradas públicas respondieron HTTP 200 con certificado válido en 883 y 998 ms.
La configuración de rutas y los contenedores permanecieron disponibles.
No se atribuye el incidente a una versión o defecto concreto sin evidencia.

La repetición posterior en el Motorola terminó en 135,637 s: trece pruebas
ejecutadas aprobadas, una omitida por requerir coordinación de dos equipos y
cero fallidas. Incluye la prueba nueva de dependencias diferidas, 16 ciclos
de creación/liberación WebRTC, cuatro ensayos de interfaz, salas, LIVE,
llamada por TURN, llamada real por FCM contestada fuera de la app, dos videos,
pantalla compartida con cambio de imagen y aislamiento del historial.
Registro: `build/communication-final-complete-9.0.16-retry.log`.

Motorola actualizado a `9.0.16-debug`, código `113`, sin borrar datos ni cambiar
la cuenta activa. SHA-256 de la APK instalada:
`ea6afa713e89a881ac320b641888c12ebbac47c7528b19f9c0b4134d3575a768`.
La repetición confirma los casos enumerados, no todos los apartados de la app,
escucha por dos personas ni disponibilidad futura del proveedor del túnel.

### Batalla de dos equipos con 9.0.16 y cierre de la pasada

Se instaló la misma APK y la APK de pruebas en el emulador, y se repitió el
ensayo coordinado con dos cuentas sintéticas nuevas. Ambas instrumentaciones
aprobaron. El anfitrión mantuvo su captura original mientras el SDK publicaba
video; micrófono/cámara, pausa, reemplazo por captura trasera y reconexión sin
reemplazar la sala aprobaron de nuevo.

| Receptor, 9.0.16 | Imagen dibujada | RTP de audio | FPS recibidos | Inicio hasta primera imagen |
|---|---|---|---|---|
| Anfitrión, Motorola | Sí | Sí | 11,20 | 5,281 s |
| Invitado, emulador | Sí | Sí | 20,00 | 14,735 s |

El tiempo incluye negociación, renderizado y espera del otro dispositivo;
no mide el retardo de un fotograma extremo a extremo. La primera imagen del
invitado conserva variación y sigue como observación. RTP recibido no acredita
escucha por dos personas ni mezcla audible entre los dos motores de audio.
El indicador de continuidad de captura se exige solo al anfitrión, que tenía
la cámara original del directo antes de iniciar la colaboración.

Registro: `build/shared-capture-9.0.16-duet.log`, complementado con los registros
individuales de anfitrión/invitado. La batalla terminó. El primer intento de
borrar el LIVE de prueba recibió la protección de directo todavía activo:
se verificaron dueño y título sintético, se cerró su estado y entonces se
eliminó. Las dos cuentas temporales se retiraron mediante su endpoint de
eliminación propio, comprobando antes identidad, correo sintético y nombre.
Se borraron las credenciales de prueba de las cachés de ambos equipos y del
archivo local. El emulador se cerró y el Motorola volvió a Mensajes, conservando
su cuenta activa y sus datos; se inspeccionó la captura de pantalla de entrada.

La caída pública y las falsas alarmas de diagnóstico recibieron además una
corrección de monitor desplegada y probada en el servidor. Detalles en
[entrada pública y monitor](ENTRADA-PUBLICA-Y-MONITOR-2026-09-30.md).

## Continuación: arranque del invitado, 9.0.17

La espera de 14,735 s de la tabla anterior se aisló publicando primero al
anfitrión. La referencia con ese protocolo fue 3,976 s en el emulador; las
correcciones de 9.0.17 y sus mediciones nativas/SFU se documentan en
[arranque de invitados LIVE](LIVE-ARRANQUE-INVITADOS-2026-09-30.md).
