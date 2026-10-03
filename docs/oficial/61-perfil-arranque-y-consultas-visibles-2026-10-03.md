# 61 — Perfil, visitas, arranque y consultas visibles

Fecha: 03/10/2026. Continúa las revisiones 59 y 60. Android conserva versión
117 / 9.0.20. Actualizaciones de APK con `install -r`, sin borrar datos ni sesiones.

## Cambios implementados

- Perfil: una carga al entrar o volver a la pantalla sustituye las dos cargas
  iniciales superpuestas. Una respuesta de otro perfil, cuenta o generación no
  sustituye el perfil actual. Las insignias se cargan aparte, con espera acotada;
  no retrasan la identidad ni la música ya disponibles.
- Visitas: el botón mantiene su nombre accesible aunque tenga avatares. Cuando
  falta una foto aparece la inicial. La lista diferencia carga, resultado vacío,
  error con reintento y privacidad desactivada. Mantiene datos de la misma cuenta
  al refrescar, cancela solicitudes anteriores y borra los datos al cambiar de
  cuenta. La lista permite desplazamiento, con altura limitada.
- LIVE: el descubrimiento se refresca mientras hay observadores de pantalla.
  Se eliminó una proyección sin consumidores que se suscribía permanentemente
  y anulaba esa condición. Videos, Social y las listas de directos dejan de
  observarla al pasar a segundo plano. Esto no cambia la señalización de una
  sesión activa ni demuestra una mejora de FPS o de conexión WebRTC.
- Arranque: decidir la ruta del invitado guardado lee sus preferencias sin crear
  su grafo de servicios. Se conservan la prioridad de recuperación pendiente,
  sesión registrada y vencimiento del invitado. La bandeja informa a Android
  cuándo deja el estado de carga mediante `ReportDrawnWhen`.
- Caché de perfil: Room 56 conserva una instantánea completa de los campos de
  identidad, música y privacidad recibidos del servidor. Las columnas compactas
  siguen siendo la base de la lista; si la instantánea antigua está dañada o no
  corresponde al usuario, se usa la representación segura anterior. El perfil
  propio recupera `username_origin` e ID numérico del servidor y no inventa un
  alias distinto del elegido.
- Enlaces de chat: el azul de enlaces mantiene contraste legible tanto en la
  burbuja propia como en la recibida, y los enlaces se pueden pulsar por separado
  del resto del mensaje.
- Audio de perfil y preescucha: al perder el foco de audio, el botón deja de
  mostrar una carga infinita y explica que se interrumpió la reproducción. Esto
  mejora la respuesta visible, pero no elimina por sí solo la latencia de red.
- Fragmentos de música: el selector puede reutilizar la respuesta HTTP inicial
  cuando Media3 lee una cabecera y pide de inmediato un salto adelante de hasta
  256 KiB. La retención máxima de reutilización es 250 ms, validada con reloj
  monotónico. Se comparan URI, cabeceras, credenciales, método y demás propiedades
  de solicitud. Un cambio de identidad, salto grande o salto atrás abre una
  solicitud normal. El cierre libera la respuesta y conserva los errores
  originales; la lectura comprueba interrupciones. No crea un catálogo offline.
- Encuestas de chat: la API impide modificar `message_poll_votes` por las rutas
  genéricas de PostgREST; los cambios pasan por las funciones transaccionales de
  votación. Se conserva la revocación de voto entre cuentas y la cardinalidad.

## Comprobaciones realizadas

- APK debug y APK instrumentada compiladas correctamente.
- Once regresiones nuevas de reutilización de audio: bytes exactos, longitud,
  cambios de credenciales, expiración, cierre, interrupción y dobles errores;
  **11 aprobadas, cero fallos/errores/omisiones**.
- **1.210 pruebas unitarias Android, 267 suites, cero fallos/errores/omisiones**
  en una copia aislada de `dfcacb2`. Incluyen las regresiones de identidad local,
  caché de perfil y color de enlaces.
- La regresión completa se repitió después en el checkout principal con la
  integración de Encuestas: **1.221 pruebas, 268 suites, cero fallos, errores u
  omisiones**. La APK debug y la instrumentada de esa combinación compilaron.
- Cuatro pruebas de Compose de visitas: carga sin falso resultado vacío,
  reintento, privacidad desactivada y desplazamiento hasta el visitante 50 con
  apertura del identificador correcto.
- Última ronda nativa tras la optimización: **16 aprobadas, cero fallos** entre
  audio/fotos, encuestas de chat, formulario de avisos y visitas. Foto lista a
  144 ms, audio de perfil a 3.113 ms y fragmento comunitario a 2.783 ms. Son
  componentes nativos con recursos propios del servidor en emulador API 35;
  quedan por ampliar los recorridos completos y la prueba física de audición.
- Repetición sobre la APK combinada: **24 casos nativos aprobados**, sin fallos,
  en 59,68 s. Incluye los 16 anteriores, tres del editor/respuesta de Encuestas,
  su contrato de documentos, tres de enlaces de chat y la migración Room 55→56.
  Perfil empezó a los 3.189 ms, fragmento comunitario a 1.766 ms y foto a 147 ms.
  Las pruebas del editor de Encuestas usan transporte sintético; no validan su
  API en producción. Los cambios posteriores de ese módulo necesitan otra ronda.
  SHA-256 de la APK probada: `4cdf2943f3490481cce8d777575f85828cf3171436048e738f6fb3617ffef686`.
- Recorrido real entre las cuentas propias de QA: el propietario ve nombre,
  @usuario, inicial y hora del visitante; tocarlo abre el perfil. Desde allí,
  Mensaje abrió el chat y mantuvo la aplicación activa.
- La regresión completa de la API tuvo **380 casos: 378 aprobados y dos omitidos,
  cero fallos**. Backend desplegado `31c571d`, nueve servicios saludables; se
  comprobó que el contenedor y el host ejecutan el código revisado. El cierre
  de la ruta genérica de votos sí modifica la API, sin migración de esquema.
  Otra lectura del contenedor a las 17:04 UTC confirmó 100/100 fuentes JS de
  `31c571d` y `/ready` correcto. La API nueva de Encuestas aún no está desplegada.

## Tiempos: no se ha cumplido aún el objetivo de arranque

Tres arranques en frío de la última APK debug, con un emulador API 35:

| Muestra | Primer dibujo, `am start -S -W` | Bandeja utilizable, `Fully drawn` |
|---|---:|---:|
| 1 | 3.649 ms | 5.497 ms |
| 2 | 3.703 ms | 5.740 ms |
| 3 | 3.633 ms | 5.638 ms |

Otra ronda anterior del mismo día tuvo 5.815–6.728 ms de primer dibujo. Las
condiciones de carga del host y la ejecución de la APK debug varían; estos
resultados no prueban una reducción causal determinada. El objetivo de 2,5–3 s
sigue abierto. Falta comparar una compilación optimizada en dispositivos físicos.

La traza Perfetto anterior mostró coste de carga del APK, inicialización y
primeras composiciones. Un splash visible no equivale a una bandeja utilizable.
Referencia: [medición de arranque de Android](https://developer.android.com/topic/performance/issues/launch-time).

## Servidor y demoras de audio

Tres lecturas por ruta, con cuenta propia de QA:

| Ruta | Mediana interna en la mini PC | Mediana HTTPS desde el host lector |
|---|---:|---:|
| Perfil | 73 ms | 1.078 ms |
| Grupos | 99 ms | 672 ms |
| Descubrimiento LIVE | 70 ms | 610 ms |
| Visitas | 62 ms | 375 ms |

Las lecturas HTTPS incluyen DNS/TLS/red y conexiones nuevas del cliente de
diagnóstico del host. En esa ronda no se forzó DNS público; no deben usarse para
certificar el recorrido de Funnel externo. No son medidas de latencia de voz
ni de todos los módulos.

La prueba de audio usa una actividad visible y con foco: Android 15 limita la
solicitud de foco de audio a aplicaciones visibles o con servicio apropiado.
Los ensayos recientes ya cumplen esa condición; los picos siguientes permanecen
sin errores ni supresión de audio. Referencia: [foco de audio de Android](https://developer.android.com/media/optimize/audio-focus).

El cliente compartido confirmó HTTP/2 y rangos de 32.768 bytes con respuesta 206.
En la ronda reciente el primer rango tardó 4.128 ms, con DNS terminado a 9 ms,
TLS a 2.395 ms y cabeceras a 3.117 ms. Los siguientes reutilizaron conexión:
1.591 y 2.624 ms. Una reproducción directa desde cero empezó a los 3.519 ms,
con primer byte a 3.132 ms y 20.480 bytes transferidos.

**La música todavía tiene demoras intermitentes.** La ronda instrumentada de 16
casos tuvo 14 aprobados y dos fallos por superar diez segundos en los inicios
de audio de perfil y fragmento comunitario. Al repetir solo esos dos recorridos,
perfil pasó a los 3.764 ms; el fragmento siguió fallando. Foto, encuestas de chat,
avisos y visitas pasaron en la ronda conjunta; la foto estuvo lista a los 272 ms.
No se amplió el límite para ocultar los fallos.

Un diagnóstico del fragmento desde 1.000 ms reprodujo el bloqueo en estado
`BUFFERING`, sin error ni supresión de foco. En otra repetición instrumentada se
observó una solicitud inicial desde byte cero (cabeceras a 1.651 ms), seguida
por otra desde byte 32.044 (solicitada a 1.802 ms y cabeceras a 7.695 ms). El
audio comenzó a los 7.810 ms. Esto identifica un coste de reapertura HTTP en
ese recorrido; no demuestra la misma causa en todos los audios.

La reutilización acotada se compiló y verificó en una copia aislada antes de
integrar sus seis archivos al proyecto principal. Dos muestras diagnósticas con
ella usaron **una solicitud HTTP**: 8.408 y 2.737 ms hasta reproducción. Dos
controles con el recorrido anterior usaron **dos solicitudes**: 6.814 y 8.718 ms;
el coste de cabeceras de la segunda fue aproximadamente 4,1 y 6,3 segundos.
La última prueba del selector visible pasó a los 2.783 ms. La muestra de 8.408 ms
permanece documentada: se eliminó una reapertura para este caso, pero sigue
variabilidad de transferencia. Estas pocas muestras no prueban un tiempo
garantizado ni una mejora porcentual global. Referencia del contrato:
[DataSource de Media3](https://developer.android.com/reference/androidx/media3/datasource/DataSource).

Tras un bloqueo de comprobación de Tailscale y un timeout de seis segundos,
SSH volvió a responder con el mismo acceso autorizado y límite de conexión de
15 segundos. Se comprobaron nueve servicios saludables. El NVMe ext4 montado en
`/mnt/conectate-fast` tiene 469 GiB de capacidad, 41 GiB usados y 424 GiB libres.

Una lectura desde el contenedor de API hasta Nginx entregó un rango de 32.768
bytes con HTTP 206 en **26 y 7 ms**. La ruta directa a API devolvió autorización
`X-Accel-Redirect`, con cuerpo vacío: la entrega de bytes corresponde a Nginx.
La misma URL HTTPS solicitada desde el servidor resolvía a su IP Tailscale,
por lo que sus 222 y 210 ms se clasifican como acceso desde la tailnet.

Para medir Funnel se resolvió el nombre por DNS público y se forzó esa dirección
en HTTPS, conservando hostname/SNI y validación TLS. Tres rangos tuvieron HTTP
206, 32.768 bytes exactos y **1.005, 455 y 339 ms** de entrega desde la mini PC.
Es un recorrido distinto del emulador; no prueba que todos los usuarios reciban
esos tiempos. El contrato de [Funnel](https://tailscale.com/docs/features/tailscale-funnel)
incluye límites de ancho de banda no configurables. Su contribución concreta a
los picos de Android queda por medir; no se atribuyen todos los retrasos a ese
servicio ni al almacenamiento.

## Revisión de servidor para el siguiente despliegue de Encuestas

Revisión coordinada con la otra tarea del proyecto: lecturas de estado y login
normal con una cuenta propia de QA. No se desplegó su módulo ni se ejecutaron
migraciones, cambios de configuración o modificaciones de volúmenes e imágenes.

- Compose: `/home/alvaro/conectate-local-fallback/docker-compose.yml`; API
  `conectate-local-fallback-api-main`, servicio `api`. PostgreSQL acepta conexiones.
- Recursos: 14.915 MiB de RAM total y 12.919 MiB disponibles en la muestra. El
  contenedor API tiene límite real de 4 GiB, 3 CPU y 384 procesos; el margen del
  host no elimina ese límite. Docker usa el bind NVMe ext4 `/var/lib/docker`.
- Antivirus: ClamAV 1.4.6, firmas 28142 del 03/10/2026; existen las bases main,
  daily y bytecode en el volumen persistente. Además se ejecutaron dos escaneos
  secuenciales por stdin como `novvin` (UID 10001), sin guardar ni subir archivos:
  PDF ficticio limpio, salida 0, 24.831 ms y pico residente de 985.596 KiB;
  cadena estándar EICAR, salida 1, 14.844 ms y 986.212 KiB. Ninguno agotó el
  timeout de 70 segundos. Esto confirma lectura de firmas y detección efectiva,
  pero no constituye una batería completa de documentos. El coste de cargar
  firmas en cada proceso sigue siendo alto. El módulo nuevo limita a un escaneo
  simultáneo y aplica SIGKILL al vencer su plazo; esa protección no elimina los
  15–25 segundos observados. Se propuso al responsable del módulo un escáner
  persistente con recursos y salud controlados antes de dar por fluido el envío.
  [ClamAV documenta el protocolo INSTREAM y sus límites de cola, hilos y tamaño](https://docs.clamav.net/manual/Usage/ClamdProtocol.html),
  que permiten enviar los bytes a un daemon con firmas previamente cargadas.
- Backup existente: metadatos del 03/10/2026 a las 08:25:45 UTC con verificación
  registrada como aprobada, dump de 3.737.261 bytes y storage de 2.217.236.907 bytes.
  En este preflight no se recalcularon esos hashes. Antes del despliegue se debe
  generar otra copia y verificar hashes, lectura del dump y archivos del storage.
- QA: login y `/v1/auth/me` con una cuenta propia marcada como creada por QA
  confirmaron la identidad esperada. `/v1/surveys/me` devuelve **404** en producción:
  las pruebas actuales del módulo no demuestran su funcionamiento en esa API.

Propuesta de ejecución, pendiente de revisión final del módulo:

1. Congelar sus fuentes y lockfile, completar regresión de API y pruebas de
   retención, límites, adjuntos y exportaciones. Repetir Android si cambian assets.
2. Hacer backup nuevo, preservar fuentes/Compose/marker y etiquetar la imagen API
   actual por su digest `sha256:83b2366a16e7aecf442bf2f5b31c9e04c18f33ab5ada34680f8029363f608a17`.
3. Construir y validar **solo API**. El script general del stack también recrea
   Redis y señalización; esa amplitud necesita revisión antes de usarlo para este
   cambio. Conservar ingreso HTTPS, secretos, TURN y almacenamiento existentes.
4. Aplicar las tablas nuevas de forma controlada y comprobar permisos, claves
   foráneas e idempotencia antes de exponer las rutas. La migración añade un campo
   a `fallback_posts`, por lo que se deben probar también publicaciones antiguas.
5. Activar API y comprobar salud, hashes y recorridos con las cuentas QA: crear,
   publicar, responder, editar, retirar y exportar, más control entre propietarios.
   Verificar también login, mensajes, medios y el guard de votos del backend anterior.
6. Ante fallo, volver a la imagen API preservada y su marker. Restaurar toda la
   base perdería escrituras posteriores: esa operación exige un plan de incidente
   separado. La reversión normal conserva las tablas añadidas y los datos nuevos.

Este plan no sustituye las comprobaciones de retención efectiva, moderación por
QR, adjuntos y límites que la otra tarea sigue implementando y revisando.

### Revisión independiente del módulo de Encuestas en desarrollo

Se revisaron `domain.js`, `routes.js`, `maintenance.js` y `resources.js` y se
comunicaron los siguientes defectos a la tarea responsable, sin modificar sus
fuentes. Estos hallazgos corresponden al código en desarrollo previo al arreglo:

1. Las imágenes del instrumento admitían activos ligados a respuestas. El acceso
   mediante otro instrumento podía eludir la caducidad del adjunto original.
   Se pidió separar imágenes del instrumento (`campaign_id IS NULL`) y adjuntos.
2. La eliminación de activos comprobaba referencias y borraba en operaciones
   separadas; una edición concurrente podía conservar una referencia rota.
   También faltaba considerar imágenes utilizadas solo en revisiones históricas.
3. Las conversiones con `sharp` no compartían un límite global de concurrencia;
   limitar PDF no protege los 4 GiB de la API ante muchas imágenes grandes.
4. La publicación bloqueaba una planilla, pero no todas las publicaciones del
   propietario: distintas planillas podían superar juntas el límite o provocar
   un error interno por una clave de publicación repetida.

La autorización de moderación reutiliza `appAdminAccess` y sus capacidades.
La lectura de borradores de participantes está restringida y las respuestas
caducadas se excluyen de las lecturas revisadas. Falta validar los arreglos
anteriores y repetir las pruebas sobre su nueva revisión antes de desplegar.

Actualización de revisión: la tarea responsable corrigió los cuatro hallazgos.
Se comprobaron nuevamente en las fuentes la restricción `campaign_id IS NULL`,
el bloqueo de activos compartido con las referencias, la eliminación transaccional
con revisiones históricas, el límite de conversiones y la publicación serializada
por propietario. Sus regresiones de API se registran en su informe del módulo.

### Antivirus persistente: implementación y prueba real aislada

Se añadieron el cliente `api/src/surveys/clamd-client.js`, nueve pruebas Node y
la configuración de `survey-antivirus`. Las nueve pruebas pasaron: protocolo y
fragmentación, envío de 4 MiB con backpressure, detección, errores, timeout,
desconexión, límites de respuesta y configuración inválida. El cliente exige
respuesta final válida, aplica un plazo absoluto de diez segundos y falla con
503 sin volver al escaneo costoso ni guardar un archivo sin verificar.

Se construyó una imagen temporal y se probó el daemon real en la mini PC, con
las firmas existentes montadas en solo lectura. El contenedor de QA tenía red
deshabilitada, ningún puerto publicado, UID 10001, límite de 2 GiB y una CPU,
filesystem de solo lectura y permisos restringidos. No reemplazó servicios de
producción. El arranque hasta PING tardó 18.641 ms; ese coste se paga al iniciar
el servicio, no en cada archivo. Pico residente del daemon: 986.344 KiB.

| Entrada por el cliente INSTREAM real | Resultado | Tiempo |
| --- | --- | ---: |
| PDF válido vacío, 431 bytes | Aceptado | 12 ms |
| Repetición del mismo PDF | Aceptado | 2 ms |
| Cadena estándar EICAR, 68 bytes | Rechazado, 400 | 7 ms |
| PDF cifrado, 828 bytes | Rechazado, 400 | 7 ms |

El contenedor y su imagen de QA se retiraron al terminar. `AlertEncrypted` y
`AlertExceedsMax` están activos; la recarga no duplica la base en memoria. La
tarea responsable integra el servicio privado y conserva el límite de una
validación simultánea en la API. El módulo permanece pendiente de despliegue.
Estas entradas pequeñas no prueban tiempo de escaneo de todos los PDF, cargas
concurrentes ni el recorrido de subida desde Android por internet. El antivirus
tampoco sustituye la comprobación de estructura o visualización del documento.

### Protección de renovaciones al cambiar de cuenta

Se encontró que una renovación HTTP pendiente podía terminar después de salir
o cambiar de cuenta e intentar persistir la sesión anterior. Se añadió un control
de generación y una sección crítica para el commit y publicación locales. La
salida y el cambio de cuenta invalidan las respuestas anteriores; también se
comprueban tokens antes de guardar y retornar una renovación. Las lecturas de
arranque tardías no pueden sustituir una sesión nueva, y la importación del SDK
se serializa comprobando el token actual, se verifica nuevamente después de la
importación suspendida y se limpia el JWT saliente antes de admitir otra
importación. El timestamp de renovación también queda protegido por el control.
Pasaron nueve regresiones JVM de generación, tokens, error tardío, lectura de
arranque, importación tardía y serialización del último commit frente a logout.
La primera compilación combinada completó Kotlin; tras la revisión independiente
se corrigieron los dos bordes adicionales y se repetirá la compilación completa.
Los nueve casos prueban el control de concurrencia, no una prueba integrada de
todo `AuthService` ni una instalación en el dispositivo del usuario.

### Límite del escaneo de música comunitaria

La auditoría encontró que el escaneo de música seguía iniciando un `clamscan`
por subida sin límite global. Ese proceso comparte los 4 GiB de la API con
exportaciones y demás operaciones. Se limitó a un escaneo simultáneo por proceso
de API; otro intento recibe 503 y puede reintentarse. El timeout utiliza SIGKILL
y el recurso se libera al finalizar, tanto ante éxito como detección o error.
No se omite el antivirus. Pasaron 17 pruebas de rutas, biblioteca y política de
música, incluida la nueva regresión de concurrencia y liberación del recurso.
Este cambio limita picos de memoria; no elimina el coste de carga de firmas de
la música ni certifica el tiempo total de publicar canciones. Sigue pendiente
su despliegue con verificación de fuentes y regresión en el servidor.

### Compatibilidad SQL con PostgreSQL de producción

Se ejecutaron los cinco archivos de pruebas de Encuestas sobre PostgreSQL
**16.15**, la versión del servidor: **48 pruebas aprobadas, cero fallos y cero
omisiones**. Incluyen la creación idempotente de tablas dos veces, HTTP con JWT
firmado y validación de sesiones, retención, privacidad, moderación y carreras
de publicación y activos. No se usaron registros ni credenciales productivos.

La base `novvin_surveys_test` estaba en un contenedor temporal, con datos en
tmpfs, 256 MiB de memoria, una CPU y puerto accesible solo por loopback mediante
un túnel SSH. Al terminar quedaron cero esquemas de prueba; se retiraron túnel,
contenedor y sus datos. Esta prueba confirma los recorridos ensayados sobre
PostgreSQL 16.15, pero no acredita una migración ni un despliegue sobre la base
real. Las fuentes combinadas quedaron registradas en `664b44a` (sesiones y
música) y `8c4493a` (módulo de Encuestas y antivirus).

### Fixtures Bash que Windows había omitido

Se copiaron únicamente dos archivos de prueba y sus dos scripts a un directorio
temporal privado de Linux. Pasaron tres pruebas con Node 22.23.1: una comprobación
estática y los dos fixtures que Windows omite. Verifican que el simulacro espera
el proceso PostgreSQL definitivo y conserva diagnósticos ante fallo, y que el
monitor distingue fallos públicos, tolera fallos aislados y alerta al tercero.

Los fixtures sustituyen Docker por un ejecutable ficticio y usan archivos
sintéticos. No se restauró una base real ni se ejecutó el monitor contra los
usuarios. Se retiraron el directorio y sus diagnósticos temporales. Esta ronda
cubre las dos omisiones de plataforma; su prueba estática se repetía y no debe
sumarse dos veces al recuento global.

## Comprobaciones adicionales de esta continuación

La tarea coordinada terminó la compilación debug y del APK de instrumentación
de `8c4493a`: **1.230 pruebas unitarias Android, cero fallos, errores u omisiones**.
Esto incluye las nueve regresiones de la protección de sesiones. La regresión
conjunta de 27 casos descubrió un fallo intermitente en el estado accesible de
carga de moderación; se corrigió en `0c3548b` y se repite la ronda completa. Los
resultados de la revisión anterior no se presentan como aprobados para el nuevo
artefacto hasta que termine esa repetición.

Se creó y verificó el respaldo
`/home/alvaro/conectate-backups/20261003T221229Z`: PostgreSQL de 3.795.584 bytes,
storage de 2.217.646.141 bytes y estado de IA de 4.695.842 bytes. Pasaron los
cuatro SHA256 del manifiesto, el listado de `pg_restore` y la lectura de los dos
archivos comprimidos. `RETENTION_DAYS=0` evitó podar respaldos anteriores. Esta
comprobación de integridad no equivale a una restauración completa ensayada.
Los nueve servicios productivos estaban saludables y todavía conservaban la
API de `31c571d`; no se había desplegado la nueva revisión.

La inspección de inicio encontró que `MainActivityContent` resuelve los
repositorios de llamadas, voz y LIVE durante la composición al restaurar una
sesión autenticada. `HomeViewModel` también recibe dependencias de varias
secciones antes de usar sus pantallas. Es un candidato de optimización que exige
traza y medición, no la demostración de que todos los segundos provienen de esas
dependencias. La entrega de llamadas entrantes debe conservarse al reorganizar
este trabajo. Se distinguirán primer dibujo y contenido utilizable, conforme a
la [guía oficial de inicio de Android](https://developer.android.com/topic/performance/appstartup/analysis-optimization).

En WebRTC la creación de un peer puede esperar hasta diez segundos cuando faltan
credenciales TURN válidas. Con credenciales vigentes evita esa espera. Acortar el
plazo sin revisar su obtención y la negociación puede impedir audio y video en
redes móviles: la conexión del socket de señalización no demuestra conexión de
medios. La revisión sigue el flujo de señalización y candidatos descrito en la
[documentación oficial de WebRTC](https://webrtc.org/getting-started/peer-connections).

Una prueba nueva forzó las direcciones IPv4 públicas de Funnel obtenidas por DNS
HTTPS, conservando TLS y SNI normales. Desde la mini PC, con una sesión propia de
QA, cinco peticiones TURN devolvieron credenciales válidas en 1.066, 298, 284,
287 y 287 ms. Los handshakes directos WebSocket de llamada, voz y LIVE tardaron
1.496, 1.708 y 775 ms; el transporte con polling tardó 2.566, 1.719 y 2.000 ms.
Las seis conexiones se establecieron. No se unió a salas ni se activaron medios;
estos resultados no demuestran audio, cámara ni latencia en un celular por datos
móviles. Tampoco cumplen la meta de menos de un segundo para todas las rutas.
El proceso temporal de la primera prueba quedó activo por handles del cliente;
se retiró ese proceso de QA y la repetición finalizó con limpieza explícita.

La segunda ronda de 27 pruebas tuvo dos fallos: una comparación del resultado
JavaScript en la prueba de moderación y un timeout al iniciar audio de perfil.
Este último ocurrió **antes** del reemplazo de `LifecycleOwner`, por lo que no
prueba un fallo de ese reemplazo. No se amplió el plazo para ocultarlo. El audio
usaba un WAV propio de doce segundos; se investigan sincronización, foco y red,
y se repetirá sin R8 simultáneo. Otro caso de esa ronda arrancó audio de perfil
en 4.635 ms, preescucha en 1.211 ms e imagen en 189 ms; el pico de audio permanece
registrado. La ronda conjunta todavía no está aprobada.

## Pendientes prioritarios

1. Reproducir y reducir los picos de inicio de audio; medir el recorrido completo
   del catálogo comunitario y publicación, además del componente de preescucha.
2. Arranque completo: constructor de servicios, trabajo inicial del Home y
   composición; comprobar la mejora con tiempos hasta contenido utilizable.
3. Conexión de medios en llamadas/salas/LIVE y FPS: siguen las restricciones y
   mediciones de las revisiones 47 y 53–57. No se certifican 60 FPS ni conexión
   inferior a un segundo.
4. Encuestas: cierre concurrente, escritura por rutas alternativas e historiales
   grandes; revisar también resultados de votantes tras cambiar de cuenta.
5. Entrega física FCM de avisos y más transiciones del cierre intermitente desde
   perfil. Las comprobaciones anteriores no cubren todas las causas posibles.
6. Auditoría continua de las demás opciones móviles y, después, paridad web/Vercel.

Los fixtures propios se conservan porque las pruebas siguen en curso. No se
publican credenciales, tokens ni logs privados. Este informe no certifica toda la
aplicación para producción.
