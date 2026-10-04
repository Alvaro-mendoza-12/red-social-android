# Inicialización de novedades de Home

**Fecha:** 4 de octubre de 2026, America/Bogota. **Producto:** Android,
commit `741d88380ce7bbab0d58101284c4fc124793b3b0`.

## Cambio aplicado

Home recibía directamente los repositorios de vídeos, publicaciones y juegos
al construir su ViewModel, incluso cuando la primera pantalla era Mensajes.
Se revisaron sus constructores y dependencias antes de cambiar esa resolución.

Ahora esas tres dependencias son `dagger.Lazy`. Al consultar novedades,
`resolveHomeRepository` resuelve su instancia mediante `withContext(Dispatchers.IO)`.
La colección de datos y las actualizaciones de los indicadores continúan en
el contexto de la corrutina original. Se mantienen las guardas de cuenta y
cancelación, el plazo de doce segundos y la comprobación de errores de Social.
No se introduce una espera artificial para mostrar novedades.

Los repositorios de mensajes, grupos, comunidades, momentos, solicitudes y
notificaciones conservan su resolución. Los controles globales y la recepción
de llamadas no se retrasan mediante este cambio. La versión sigue en
código 117 / `9.0.20`.

La resolución perezosa conserva la instancia administrada por Dagger;
`withContext` devuelve el resultado al contexto original y respeta la
cancelación. [Dagger Lazy](https://dagger.dev/api/latest/dagger/Lazy.html),
[kotlinx.coroutines withContext](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html).

## Pruebas específicas añadidas

`HomeGraphInitializationTest` contiene cinco casos:

1. La instancia no se construye antes de solicitarla, se construye fuera del
   hilo llamante y la continuación vuelve a ese hilo.
2. Veinte solicitudes concurrentes comparten una sola instancia de Dagger.
3. Una excepción del constructor conserva tipo, mensaje y causa original.
4. Una corrutina cancelada no inicia la construcción.
5. Cancelar durante la construcción impide entregar su resultado al llamante.

El último caso no promete interrumpir un constructor sin operaciones
suspendibles: valida que su resultado no llega a una tarea cancelada. Sus
compuertas se liberan en `finally`. Las pruebas no simulan un HomeViewModel
completo ni acreditan por sí solas los indicadores de novedades de una cuenta.

## Validación de código y siguiente compilación

Las tres fuentes fueron revisadas por ambas IAs y congeladas antes de iniciar
la compilación conjunta: APK, instrumentación, pruebas unitarias y R8.
No se reutilizan las 1.264 pruebas ni las 65 comprobaciones del APK anterior
como evidencia del nuevo artefacto.

La primera ejecución alcanzó 1.269 casos y falló únicamente la nueva comprobación
de identidad de excepciones. El tipo y el mensaje coincidían, pero la recuperación
de trazas de corrutinas puede copiar la excepción al cruzar despachadores.
Se corrigió esa expectativa para exigir tipo, mensaje y presencia de la causa
original. No se cambió el código de producto ni una prueba heredada para este
ajuste; el resultado de esa primera ejecución se conserva.

La corrección de la prueba pertenece al commit `4990401`. La repetición de
la **suite completa** terminó en 1 min 21 s: **1.269 pruebas en 279 suites,
cero fallos, errores u omisiones**. Las cinco pruebas nuevas pasaron en
0,122 s; ambas IAs revisaron los XML nuevos, de las 12:09 UTC.

La primera compilación se detuvo en pruebas unitarias: no se declara R8 nuevo
correcto ni una instalación física de Home. A petición del usuario en el chat
coordinado, se integrará el ajuste de posición de música, letras y duración de
Momentos antes de la siguiente compilación conjunta y regresión del teléfono.
Ese era el checkpoint anterior a la compilación conjunta de Momentos.

El ajuste de Momentos quedó registrado en `e6de71c` y la siguiente compilación
completa usa explícitamente esa revisión como marca de producto. La comprobación
preliminar de Android terminó en 2 min 47 s: compilación de aplicación e
instrumentación y 26 pruebas en cinco suites, sin fallos, errores u omisiones.
Incluye letras, geometría, modelos, petición de creación y mapeo de destacados.
Es una comprobación focal: no acredita aún la nueva suite completa, R8,
instalación ni la última revisión de proporciones del lienzo.

### Checkpoint conjunto completado

La compilación de `e6de71c` terminó correctamente en 18 min 21 s. Sus XML
nuevos contienen **1.287 pruebas en 281 suites, sin fallos, errores ni omisiones**.
También terminó R8 de release y se verificó la conservación de los seis métodos
del puente de encuestas. Ese control no produjo un AAB firmado.

La APK debug de ese checkpoint fue instalada como actualización en el Motorola,
conservando sus datos. Pasaron **20 pruebas físicas del selector y editor**;
las capturas muestran la tarjeta y las letras separadas del comentario y los
controles, también con texto ampliado. Son pruebas del editor con medios locales:
no miden el arranque de pistas remotas ni acreditan todo el recorrido de subida.

El SHA-256 de esa APK es
`63d9b29bf6c19952259c50a0bda971c10fb5988c0adabf51a81af5db4e8c235a`.
Se conserva el primer intento de compilación que se detuvo por una exclusión
de tarea Gradle inexistente; la ejecución corregida es la que terminó correctamente.

Después se añadieron la importación nativa de archivos LRC y el ajuste al
recorte exacto en `403fc0d`. Su compilación fue interrumpida deliberadamente
para incorporar el pedido de reducir la altura del selector de fragmento.
La revisión `fed1546` fija esa zona en **48 dp**, frente a los 88 dp anteriores,
y añade una comprobación de esa proporción. Su nueva suite completa terminó
con **1.294 casos en 282 suites, sin fallos, errores ni omisiones**; ambas IAs
comprobaron los XML de las 13:20:36–13:21:19 UTC. Incluye seis casos nuevos de
importación LRC y uno del filtro de documentos.

Se instaló esa APK conservando datos y pasó una comprobación puntual de los
48 dp. La captura nueva del Motorola fue revisada por ambas IAs: marcas dentro
de los bordes, controles visibles y barra más delgada. La compilación conjunta
terminó en **21 min 49 s**, incluido release/R8. Conservó los seis métodos del
puente de encuestas y no produjo un AAB firmado. El SHA-256 de la APK debug es
`0baba93b840aa4f485bf26615d4416e2f34c0a0d92f334c9a977ac529d26be76`.
Pasaron 52 casos físicos de interfaz, tres de rechazo de llamadas y cinco
de medios. La primera tanda de 18 casos de llamadas registró 17 correctos y
un fallo en el vencimiento de una invitación; una segunda tanda, ya sin Gradle
activo, repitió ese mismo fallo. Los cierres sucedieron antes de los 45 segundos
de vencimiento: a los 10,6 y 37,8 segundos, respectivamente. La causa de esos
dos cierres no está confirmada y no se atribuye a la compilación concurrente.

Sin cambiar el código, el caso aislado pasó en 46,951 s. Una ejecución con
depurador pasó los 18 casos en 70,459 s y capturó el cierre correcto por
`closeIfCancelled`, desde el observador de cancelación, tras 45,679 s.
Finalmente, una tanda normal, sin depurador ni Gradle activo, pasó los
**18 casos en 78,238 s**. Esa repetición acredita el resultado observado;
no borra los fallos anteriores ni confirma que su causa esté resuelta.

Los 78 casos físicos del checkpoint tienen resultados aprobados en sus
últimas ejecuciones separadas. Usan invitaciones sintéticas propias y estado
RTC local: no son llamadas entre dos participantes, entrega FCM, medida de
latencia de voz ni FPS de LIVE. Los 52 casos de interfaz se ejecutaron mientras
R8 estaba activo y tampoco se usan como medida de rendimiento.

El contrato de música de la API pasó también una comprobación con PostgreSQL
local real y seis comprobaciones de HTTP/SQL, con almacenamiento, autenticación
y conversión de audio aislados. La suite de API terminó con 450 casos: 448
correctos y dos omitidos por requerir Bash/Linux. El nuevo código todavía
no está desplegado: Tailscale solicita una comprobación adicional de SSH.
[Evidencia del contrato y límites](../../VALIDACION_MOMENTOS_MUSICA_API_E6DE71C.json).

Sobre `fed1546` se repitieron las 18 comprobaciones específicas de API, sin
fallos ni omisiones. Los ocho archivos de contrato mantienen los hashes del
checkpoint anterior. El candidato para Linux se regeneró con
`core.autocrlf=false`: los **183 archivos** coinciden byte por byte con los
objetos de ese commit. El paquete previo, no desplegado, contenía conversiones
CRLF de Windows y se conserva como historial. La comprobación del paquete
no equivale a un despliegue; sigue pendiente el acceso SSH.
[Continuidad y paquete exacto](../../VALIDACION_CONTINUIDAD_MOMENTOS_API_FED1546.json).

## Referencia de rendimiento y límites

### Audio remoto: demora todavía abierta

Con Gradle inactivo, las pruebas de medios del Motorola observaron 158 ms
para cargar una foto sintética propia de Momentos, 5.270 ms para mostrar
«Pausar» en audio de Perfil y 3.445 ms en la vista previa de Comunidad.
La aparición de ese control corresponde al estado de reproducción de Media3;
no mide el primer sonido audible ni permite comparar estos tiempos con una
medición de salida acústica.

La traza de transporte muestra ambas solicitudes de audio sobre la misma
conexión HTTP/2, sin nueva resolución DNS ni negociación TLS. Desde el envío
de encabezados hasta los encabezados de respuesta pasaron 5.026 ms y 2.979 ms.
En estas dos muestras la espera está principalmente antes de recibir la
respuesta. No distingue por sí sola entre aplicación del servidor, base de
datos, proxy público y tránsito de red.

Tres lecturas públicas consecutivas de 4 KiB desde el escritorio recibieron
HTTP 206 y tardaron 6.049, 1.166 y 486 ms en recibir encabezados. Son muestras
de otro cliente, no del Motorola ni del origen local del servidor. Confirman
variación en esa ruta pública y no acreditan tiempos inferiores a un segundo.
La comparación con el origen requiere recuperar el acceso SSH. Los registros
publicables no contienen direcciones del medio, tokens ni credenciales.
[Mediciones y alcance](../../VALIDACION_CONTINUIDAD_MOMENTOS_API_FED1546.json).

Se añadió [un diagnóstico de almacenamiento](../../scripts/diagnose-storage-response-latency.py)
para comparar tres lecturas de ese mismo audio propio en cada ruta: API
local, Nginx local y URL pública. Descubre únicamente los puertos de contenedores
con enlace a loopback, usa GET de rango y limita la lectura a 4.097 bytes para
detectar una respuesta incorrecta. No sigue redirecciones ni descarga el archivo
completo. Registra conexión, encabezados, primer byte y el valor numérico
`app;dur` de `Server-Timing`, cuando exista. No modifica contenedores ni datos.

La API puede responder con cuerpo vacío y `X-Accel-Redirect`; en ese caso se
mide su autorización y búsqueda de metadatos, no la entrega del audio. El
diagnóstico registra esa diferencia. Nginx procesa ese encabezado como una
redirección interna; no se interpreta como una URL para abrir desde el cliente.
[Referencia oficial de Nginx](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_ignore_headers).
Docker se consulta con un formato reducido que excluye variables de entorno
y montajes. [Formato oficial de Docker](https://docs.docker.com/engine/cli/formatting/).

Pasaron ocho [pruebas aisladas del diagnóstico](../../scripts/test-storage-response-latency.py):
ruta propia, metadatos mínimos, métricas numéricas, lectura parcial, respuesta
delegada, rechazo de redirección/archivo completo, tamaño incorrecto y errores
sin mensajes privados. El modo `--plan` validó el archivo sintético sin Docker
ni red. **La comparación en Linux todavía no se ha ejecutado** por el acceso
SSH pendiente. Cada muestra abrirá una conexión HTTP/1.1 nueva; no replica
la conexión HTTP/2 reutilizada observada en Android.

La siguiente medición de audio debe distinguir solicitud, encabezados,
preparación y avance del audio. Media3 ofrece `onAudioPositionAdvancing`; su
marca de inicio usa tiempo de sistema, por lo que no debe restarse directamente
de un reloj monotónico. Este diagnóstico adicional todavía no está aplicado.
[Contrato oficial de AnalyticsListener](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener).

### Inicio de la aplicación

El APK anterior `e729365`, medido sin trazado, registró primer fotograma
COLD 4.924 ms, WARM 4.504 ms y COLD 4.396 ms. En una captura instrumentada
separada, TTID fue 5.385 ms y TTFD 14.679 ms, con carga DEX y composición
importantes. [Diagnóstico y evidencia anterior](65-rechazo-de-llamadas-y-arranque-2026-10-04.md).

El cambio elimina la construcción anticipada de tres grafos de Home, pero
todavía no se atribuye una reducción de tiempo ni el cumplimiento del objetivo
de 2,5–3 segundos. La traza anterior no identificó un constructor concreto.
La comparación debe conservar el mismo teléfono, el estado de sesión, los
datos y el procedimiento; se separarán las muestras con trazado.

Las pruebas físicas usan APK debug. La entrega optimizada de producción
necesita una medición propia. Los perfiles de inicio y Baseline Profiles son
un trabajo posterior para evaluar la carga de código y los recorridos
frecuentes; no se presentan como implementados en este commit.
[Referencia oficial de Android](https://developer.android.com/topic/performance/baselineprofiles/overview).

Continúan pendientes las llamadas reales entre dos participantes, entrega
física FCM, FPS sostenidos en LIVE, AAB firmado, auditoría restante y paridad
web/Vercel. Este documento no certifica toda la aplicación para producción.
