# Arranque de invitados LIVE — 2026-09-30

Rama: `feature/live-gifts`. Directorio: `D:/red social - live-gifts`.
Versión compilada e instalada: `9.0.17-debug`, código `114`.

## Alcance y corrección de la medición anterior

Los 14,735 segundos registrados en 9.0.16 incluían esperar a que el otro equipo
publicara su cámara. No eran una medición aislada del botón para iniciar como
invitado. Se añadió un marcador de publicación y se repitió con el anfitrión
publicando antes de arrancar la instrumentación del invitado.

La referencia 9.0.16 con ese protocolo registró en el emulador: token HTTP
918 ms, conexión SDK 2.266 ms y primera imagen dibujada 3.976 ms desde comenzar
la conexión; 1.710 ms corresponden a suscripción/renderizado posterior.
Se conservan los archivos `build/baseline-9.0.16-sfu-duet-guest.json` y
`build/live-startup-baseline-9.0.16.log`. Una muestra no constituye un percentil.

## Correcciones implementadas

- El invitado de la pantalla usada por navegación (`FigmaLiveViewerScreen`)
  mantiene el transporte WebRTC nativo. La vista compartida de batalla usa
  LiveKit como receptor. No se intercambian los motores ni se abre otra cámara
  física del anfitrión.
- Al promover a un espectador conectado a invitado se conserva su conexión
  sana. Los snapshots de invitados sin cambios tampoco reinician ICE.
  Un socket reemplazado o una conexión interrumpida conserva su recuperación.
- La captura nativa del invitado usa el presupuesto del dispositivo: 640×360 a
  24 FPS solicitados en gama baja, 960×540 en gama media y hasta 1280×720 en gama
  alta. Se respeta una calidad solicitada menor. El controlador puede escoger
  una resolución compatible próxima: este Motorola entregó 640×480. Antes
  abría siempre 720p, incluso al recibir un LIVE simultáneamente.
- Los callbacks de cámara comprueban generación, sesión, autorización, manager
  y última elección. Apagar, salir del asiento o cerrar LIVE invalida el trabajo
  pendiente. Los controles de micrófono se coordinan con ese cierre.
- En colaboración SFU, audio y video solapan sus esperas suspendidas con
  concurrencia estructurada. Un dispatcher de un solo ejecutor serializa las
  actualizaciones del mapa de publicaciones del SDK 2.27.0. Ambos hijos terminan
  o se cancelan antes de liberar la sala. Se exige que existan ambas publicaciones.
- Credenciales, conexión y confirmación de participante tienen un único dueño.
  Cancelar se propaga como cancelación; una respuesta HTTP tardía no inicia cámara.
  La limpieza conserva la cuenta y sala originales para no usar otra sesión.
- Una conexión fallida permite reintentar explícitamente o continuar viendo.
  El estado del transporte no es una clave que cancele su propia conexión.
  Al terminar el plan de batalla se libera su colaboración.
- El sondeo de invitaciones es independiente de los datos opcionales; las otras
  lecturas se agrupan sin solapar ciclos. Esto acelera listas, pero no se atribuye
  a ese sondeo el inicio nativo: su aceptación llega por Socket.IO.

## Validación terminada

### Compilación y regresión

- `assembleDebug`, `assembleDebugAndroidTest` y `testDebugUnitTest` aprobaron.
- 964 pruebas unitarias: cero fallos, errores u omisiones. Incluyen publicación
  concurrente, cancelación, sesión reemplazada, limpieza por cuenta, promociones
  sin reiniciar ICE, callbacks tardíos, presupuesto de cámara y reintento.
- Ensayo específico en Motorola: 2 pruebas aprobadas; cancelación de señalización
  y arranque del invitado desde el repositorio usado por la app.
- Regresión final en Motorola: 16 pruebas aprobadas, cero fallos y 1 omitida por
  no activar su coordinador nativo entre dos dispositivos. Ese caso omitido no
  se da por certificado por probar un transporte diferente. La colaboración
  SFU se comprobó aparte con dos equipos y tres salas nuevas.
- La regresión incluye LIVE nativo, salas de voz, pantalla compartida, llamada
  con TURN obligatorio, llamada entrante fuera de la app, reproducción/búsqueda
  de videos, ciclo de vida de medios, autenticación e historial aislado.
- Tres ciclos reales de cancelar durante señalización, liberar la sala y
  conectar nuevamente aprobaron. No quedaron sala ni pistas remotas del intento.
- El diálogo Compose aprobó fallo → reintento explícito → conexión → cierre
  cuando termina su plan. La captura en Motorola tiene texto y acciones legibles,
  botones separados y ningún solapamiento en el tamaño ensayado.

Registros: `build/live-startup-9.0.17-camera-budget-build.log`,
`build/live-startup-motorola-targeted-9.0.17.log`,
`build/live-startup-motorola-final-9.0.17.log`,
`build/sfu-cancellation-final-9.0.17.json` y
`build/live-collaboration-retry-9.0.17.png`.

### Invitado nativo, pantalla usada por navegación

Se usa el repositorio real del Motorola sin cambiar su cuenta. Un anfitrión
sintético, ejecutado en el mismo dispositivo con otra cuenta temporal, recibe
la solicitud persistida y la acepta mediante el servidor público. El usuario
ya era espectador con ICE conectado antes de solicitar su asiento. El ensayo
comprueba que promoverlo conserva esa conexión, abre la cámara, recibe frames
reales y mantiene los controles y la salida. No es una medición nativa entre
dos teléfonos físicos.

| Pasada | Entrar al LIVE, HTTP | Solicitud + aceptación | Iniciar cámara → primera imagen recibida | FPS recibidos durante 8 s |
|---|---:|---:|---:|---:|
| Específica | 892 ms | 1.545 ms | 2.915 ms | 20,00 |
| Regresión completa | 738 ms | 1.424 ms | 2.410 ms | 18,75 |

La cuenta activa se conservó en ambas. Apagar/encender cámara funcionó; salir
cerró el asiento y un control tardío de micrófono no lo reabrió.
Se conservan `build/native-guest-startup-targeted-9.0.17.json` y
`build/native-guest-startup-final-9.0.17.json`.

La primera pasada física de esta versión falló con 7,25 FPS al capturar a 720p;
no se rebajó el umbral de 8 FPS. Se ajustó la carga de cámara al dispositivo y
las dos repeticiones anteriores aprobaron. En esa primera pasada también falló
un ensayo por solicitar el token antes de activar su LIVE de prueba; se corrigió
la preparación, manteniendo las comprobaciones de cancelación. El registro con
ambos fallos queda en `build/live-startup-motorola-9.0.17.log`.

### Colaboración SFU, dos dispositivos

Motorola anfitrión y emulador invitado; el anfitrión publica antes de lanzar al
invitado. Los tres ensayos recibieron y dibujaron ambas imágenes, verificaron
RTP de audio en ambos equipos, ambas publicaciones locales, reuso de sala,
continuidad de la captura original del anfitrión, controles y reemplazo de esa
captura por cámara trasera. Todas las batallas terminaron y sus LIVE se cerraron
y eliminaron.

| Invitado emulador | Token HTTP | Crear/conectar/publicar SDK | SDK → primera imagen dibujada, incluye conexión | Suscripción + render posterior | FPS recibidos |
|---|---:|---:|---:|---:|---:|
| Referencia 9.0.16 | 918 ms | 2.266 ms | 3.976 ms | 1.710 ms | 20,00 |
| 9.0.17, muestra 1 | 948 ms | 3.355 ms | 5.545 ms | 2.190 ms | 20,00 |
| 9.0.17, muestra 2 | 910 ms | 1.969 ms | 4.104 ms | 2.135 ms | 20,00 |
| 9.0.17, muestra 3 | 929 ms | 2.036 ms | 3.501 ms | 1.465 ms | 20,00 |

El token se pide antes de medir SDK → primera imagen: para incluirlo en ese
recorrido deben sumarse ambas columnas. La mediana de primera imagen de 9.0.17
es 4.104 ms; tres muestras no acreditan un percentil ni una mejora estadística
frente a una sola muestra anterior. La primera muestra nueva fue más lenta.
La reducción de carga y mejora de fluidez comprobadas corresponden al invitado
nativo. En SFU se corrigieron propiedad de conexión, concurrencia y recuperación,
sin presentar esta tabla como una reducción garantizada de latencia.

Los tiempos de recepción del anfitrión SFU fueron 19.906, 12.729 y 13.590 ms:
incluyen la espera mientras ADB inicia la instrumentación del invitado. Por eso
no se usan como duración de arranque del anfitrión. Recibió unos 11,1–11,3 FPS
del emulador, mientras este recibió unos 20 FPS del Motorola.

Archivos de cada muestra: `build/optimized-9.0.17-{1,2,3}-sfu-duet-{host,guest}.json`
y `build/live-startup-optimized-9.0.17-{1,2,3}.log`.

### Servidor, instalación y limpieza

- Ocho servicios comprobados por SSH: API, base de datos, señalización, Redis,
  gateway realtime, edge, TURN y chatbot, todos `running/healthy`.
- Entrada pública probada forzando las dos IPv4 públicas, validando certificado
  y hostname TLS. Health, readiness y handshake Socket.IO: 6/6 respuestas 200,
  entre 852 y 1.077 ms. Los ensayos anteriores comprobaron operaciones
  autenticadas y medios; el sondeo público por sí solo no las certifica.
- No se requirió modificar ni desplegar backend para estas correcciones Android.
- Misma APK `9.0.17-debug`, código `114`, instalada en Motorola y emulador sin
  borrar datos. SHA-256:
  `4D22FE41A3E7E8F923518A686DDEA5BDECF26CE7F7F24A4229E53B36C6575A83`.
- Las dos cuentas sintéticas se eliminaron usando el endpoint de cada dueño,
  comprobando previamente ID, correo `media-fixture.*@example.invalid` y nombre
  de prueba. Se retiraron sus credenciales privadas de ambas cachés y del archivo
  local. La cuenta activa del Motorola y sus datos se conservaron. El emulador
  se cerró; se inspeccionó la captura final del Motorola, abierto en Mensajes
  con la cuenta original «Auditoria Espectador».

Evidencia: `build/live-startup-public-probe-final.json`,
`build/live-startup-server-containers.json` y
`build/live-startup-cleanup-9.0.17.json`.

## Límites de lo verificado

Estas pruebas no miden retardo audiovisual boca/oído ni acreditan escucha por
personas, redes móviles distintas, todos los dispositivos o disponibilidad
futura del túnel público. Recibir RTP de audio no equivale a comprobarlo audible.
El presupuesto de cámara reduce carga, pero el hardware, iluminación, red y
carga del dispositivo siguen afectando resolución y FPS. No se promete arranque
instantáneo, cero retardo ni una certificación de toda la aplicación.
