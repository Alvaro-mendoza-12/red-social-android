# Rechazo de llamadas y diagnóstico de arranque

**Fecha:** 4 de octubre de 2026, America/Bogota. **Rama:** `feature/live-gifts`.

## Fallos reproducidos antes del cambio

El Motorola g04, Android 14, ejecutó las tres pruebas de
`IncomingCallRejectionTest` sobre el APK de producto `07e4989`. Las tres
fallaron en 8,773 segundos, sin omisiones. Las invitaciones son ficticias y
apuntan deliberadamente a una cuenta ausente; no se llama a contactos reales
ni se cambia la sesión del usuario.

1. Rechazar desde la pantalla cerraba la actividad, pero no registraba la
   cancelación local necesaria para bloquear una invitación tardía.
2. Rechazar desde la notificación no registraba esa cancelación ni garantizaba
   el cierre de la pantalla de respuesta.
3. Rechazar una notificación antigua detenía el servicio que estaba sonando
   para otra llamada.

[Evidencia de la ejecución previa](VALIDACION_RECHAZO_LLAMADAS_ROJO.json).
Las pruebas pertenecen al commit `a68f75c` y se mantienen sin cambios durante
la corrección.

## Corrección de producto

Commit `e729365d5a2358fde3207420c29621d94a9af42a`, tres archivos:

- `IncomingCallActionReceiver`: registra la cancelación y retira los controles
  de la llamada concreta antes de consultar la sesión o hacer trabajo remoto.
  Una acción desconocida ya no termina llamadas. El receptor interno conserva
  la tarea de respuesta mediante `goAsync()` cuando se cierra la actividad.
- `IncomingCallActivity`: Rechazar despacha la respuesta al receptor interno y
  cierra inmediatamente. El envío deja de depender del ciclo de vida de la
  pantalla; se conserva el aviso de fallo al usuario para esta acción.
- `CallRepository`: registra la cancelación incluso cuando falta sesión. Solo
  limpia la invitación y el socket preparados cuyo identificador coincide;
  la comparación y actualización de la invitación es atómica. Se elimina la
  parada indiscriminada de todos los tonos.

La versión se mantiene en código 117 / `9.0.20`. No se ha cambiado la API ni
el flujo de contestar para esta corrección.

## Validación posterior

Pruebas unitarias de `e729365`: **1.264 correctas en 278 suites**, cero fallos,
errores u omisiones. Se revisaron los XML de la ejecución nueva.

La compilación conjunta terminó correctamente en **17 min 48 s**: APK debug,
APK instrumentado y R8. No se produjo un AAB firmado. La APK instalada y
verificada en DEX contiene el commit `e729365`; SHA-256
`9536bf4d825f804c8d1e774a3c9732d813b4cce481c131abcbba4a278a64c38d`.
Se actualizó con `install -r -t`, sin desinstalar ni borrar datos; se conservó
la identidad del directorio privado.

Las **mismas tres pruebas** pasaron en el Motorola en **6,251 segundos**,
cero fallos y cero omisiones. Se conservaron los plazos de tres segundos y
las condiciones de la ejecución roja, sin cambiar las pruebas para obtener el
resultado. [Evidencia posterior](VALIDACION_RECHAZO_LLAMADAS_VERDE.json),
[compilación y hashes](VALIDACION_ANDROID_E729365.json).

La regresión de interfaz pasó sus 39 casos en 45,601 segundos sobre la misma
APK. También pasaron cinco casos de medios en 15,175 s y 18 de llamadas/RTC y
controles LIVE en 68,763 s. Los 65 casos de la ronda pasaron sin omisiones;
no se suman repeticiones históricas. Los resultados completos están en el
[informe consolidado](66-validacion-movil-e729365-2026-10-04.md).

Las tres pruebas con cuenta ausente ejercitan actividad, receptor, servicio y
guardas locales. No ejecutan el rechazo autenticado de `CallRepository` ni
acreditan la recepción remota por otro usuario. No se presenta ese límite como
cobertura completa de una llamada real.

## Arranque y conectividad

El checkpoint anterior registró primer fotograma COLD de 4.870 y 4.395 ms,
y una solicitud clasificada WARM de 4.310 ms. El objetivo de 2,5–3 segundos
sigue pendiente. [Informe físico anterior](64-validacion-moto-g04-2026-10-03.md).

La ronda sin trazado de `e729365` registró COLD de 4.924 y 4.396 ms y WARM de
4.504 ms. Se conserva por separado de las capturas realizadas más tarde.

Una primera captura Perfetto falló al leer su configuración por permisos;
pasar la configuración por la entrada estándar permitió capturar. Sin embargo,
el búfer circular de 32 MiB sobrescribió 26.480.640 bytes y 809 bloques de los
59.977.728 bytes escritos. El análisis no pudo identificar el proceso ni un
evento de arranque de Novvin; esta captura no sirve para atribuir la demora a
una función concreta. El primer fotograma bajo esa captura tardó 8.695 ms,
en una muestra separada aproximadamente seis horas después de las anteriores.
No se presenta esa diferencia como una regresión o efecto causal del trazado.
Una tercera captura conservó el inicio, pero el proceso nuevo siguió identificado
como `zygote64`; no se utilizó esa asociación incompleta para publicar un
diagnóstico por función.

La cuarta captura sí asocia el proceso nuevo con Novvin. Se añadieron sondeo de
procesos cada segundo, eventos de creación/renombrado y vaciado cada cinco
segundos; se conservaron los búferes de 64 MiB y 2 MiB, la escritura continua y
el procedimiento `am start -S -W`. El primer fotograma del framework tardó
**5.391 ms con trazado**. Perfetto obtiene **TTID 5.385,198 ms** y
**TTFD 14.679,368 ms**. Son definiciones diferentes y una muestra instrumentada;
no se mezclan con las tres mediciones sin trazado.

Dentro de esa muestra, `bindApplication` ocupa 3.028 ms e incluye 2.397 ms
abriendo DEX. El primer recorrido de la interfaz ocupa 1.271 ms e incluye
inicialización Compose de 505 ms, recomposición de 274 ms y medida de 281 ms.
Los intervalos anidados no se suman. El hilo principal ejecuta CPU durante
4.814 ms antes del primer fotograma; tras este aparece una recomposición de
3.366 ms. Esto localiza trabajo de carga y composición en el teléfono, pero
no identifica aún el constructor o componente concreto que debe cambiarse.
No demuestra que el servidor sea la causa del arranque ni mide el coste de
red por separado.

Esta APK es `debug`, sin minificación. Hace falta comparar una instalación
optimizada y medir el contenido utilizable antes de atribuir estas cifras a
la entrega de Google Play. La traza no informa sobrescrituras ni desbordamientos;
sí registra seis avisos de configuración ftrace y dos bloques descartados.
Se conservan esas limitaciones y las capturas anteriores en la
[evidencia de integridad](VALIDACION_INTEGRIDAD_TRAZA_E729365.json) y el
[análisis de arranque](VALIDACION_ARRANQUE_TRAZA_E729365.json).

Android distingue el primer fotograma (TTID) del contenido completamente
dibujado (TTFD); una medición de `am start -W` por sí sola no atribuye la
demora al servidor. [Referencia oficial de Android](https://developer.android.com/topic/performance/issues/launch-time).

En una lectura pública de esta ronda, `/ready` respondió HTTP 200 y confirmó
la entrega API `f42a349`: 3.158 ms totales, conexión TCP a 1.508 ms y TLS
completado a 2.150 ms desde el inicio. Son hitos acumulados de una solicitud
fría; no se suman y no constituyen una medición aislada de SQL. El acceso SSH
solicitó una comprobación adicional de Tailscale, de modo que no se repitió
la inspección interna de contenedores. No se modificó el servidor.

Una segunda ronda HTTP/1.1, en un único proceso de curl y con TLS verificado,
dio 1.746 ms con una conexión nueva; reutilizando esa conexión, 529 y 319 ms.
Se conserva también la primera muestra fría de 3.158 ms: no se selecciona
únicamente la más rápida. La prueba no modifica el pool de Android ni mide
audio, FCM o llamadas. [Muestras de conectividad](VALIDACION_READY_HOST_E729.json).

## Límites y trabajo abierto

La incidencia intermitente previa del temporizador de 45 segundos permanece
documentada por separado: esta corrección aborda los tres fallos de rechazo
reproducidos, no explica aquel cierre temprano. Continúan abiertos el arranque,
las llamadas entre dos usuarios reales, entrega física FCM, conexión de medios
en salas/LIVE, FPS sostenidos y la auditoría restante. Este documento no
certifica toda la aplicación para producción.
