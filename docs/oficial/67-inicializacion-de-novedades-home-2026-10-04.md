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
La regresión física completa continúa abierta: pasaron 52 casos de interfaz,
pero la primera tanda de 18 casos de llamadas registró 17 correctos y un fallo
en la comprobación de vencimiento de la invitación. Se conserva ese resultado
y se investiga su causa; no se presenta la tanda completa como aprobada.
Ese caso puntual ocurrió mientras Gradle estaba activo; no es una medición
de rendimiento ni sustituye la tanda física completa.

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
