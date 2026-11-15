# 155 · Recuperación por correo, persistencia y salida

Estado del 15 de noviembre de 2026: **58 casos JVM aprobados; revisión integral abierta**. Continúa A03,
A04 y parte de A05 del [plan 148](148-acceso-recuperacion-y-validacion-por-modulo-2026-11-15.md),
después de [152](152-cuentas-guardadas-y-salida-de-ajustes-2026-11-15.md).
El módulo de acceso y la revisión móvil integral siguen abiertos.

Contribuye a `AND-01`, `UX-19` y `REQ-295` de la trazabilidad vigente; sus estados
globales no se cierran. Se preservan los contratos del servidor propio Ubuntu y
las importaciones necesarias para sus consumidores existentes.

## Hallazgos y corrección

La limpieza del borrador ejecutaba su callback incluso después de resetear la
entrada, cerrar el ViewModel o fallar la escritura. «Volver» podía entonces
actuar sobre otra pantalla. La restauración capturaba su generación al ejecutar
la coroutine, por lo que un inicio en cola podía volver a publicar un borrador
que ya se había pedido borrar.

También había publicaciones después de E/S local protegida con `runCatching`:
la cancelación podía convertirse en otro resultado y sobrescribir el formulario
nuevo con `Idle`, un error antiguo o el final de recuperación/segundo factor.
Un error al persistir el acuse del correo quedaba oculto tras `Idle`.

La corrección captura la generación de restauración al construir el ViewModel e
invalida las operaciones de correo en el reset común, antes de cancelar. Tras
cada espera relevante comprueba la generación y el borrador esperado. Reutiliza
`authEntryOperation` para propagar la cancelación sin convertirla en error de
usuario ni publicar resultados de una entrada anterior.

Borrar muestra el estado ocupado y solo completa al confirmar la escritura. Si
falla, conserva el borrador anterior y muestra un error con reintento; los
borrados repetidos conservan el borrador original de esa misma visita. Una
limpieza antigua no libera la entrada siguiente ni ejecuta su navegación.

Los cinco consumidores de limpieza de `LoginScreen` esperan su confirmación
antes de cambiar de formulario o salir. El callback comprueba además la misma
entrada del controlador de navegación. Se conservan los mensajes de proveedor
deshabilitado y del contrato anterior de recuperación, y la dirección/reintento
cuando se pierde el acuse de envío. Un fallo anterior al primer guardado evita
solicitar un correo.

La guía de [cancelación de Kotlin](https://kotlinlang.org/docs/exception-handling.html)
y las categorías de [OWASP MASVS](https://mas.owasp.org/MASVS/03-Using_the_MASVS/)
de autenticación y almacenamiento orientan esta revisión. No se acredita una
certificación MASVS ni una vulneración del backend mediante estas pruebas de
estado local. El almacén cifrado existente no cambia y no guarda contraseñas;
los ensayos de este bloque sustituyen sus métodos para controlar la E/S.

## Ensayos y límites de la evidencia

Se ejecutan el ViewModel real, su scope, la E/S `Dispatchers.IO` y el mutex real.
Solo el transporte y el almacén se controlan. Las puertas de E/S permiten
resetear o cerrar durante una escritura; se espera la terminación del Job
observado. El caso de arranque usa un dispatcher de prueba y espera la tarea de
restauración, sin asumir que un tiempo fijo basta para recibir la respuesta.
No se envían correos, no se cambian contraseñas reales y no se crean usuarios.

| Tanda | Casos | Fallos de aserción | Errores/omisiones |
| --- | ---: | ---: | --- |
| Referencia inicial, producción sin modificar | 10 | 6 | 0 / 0 |
| Referencia ampliada, producción sin modificar | 24 | 14 | 0 / 0 |
| Corrección y regresiones | 58 en 7 clases | 0 | 0 / 0 |

La ampliación repite los diez casos iniciales: hay **24 casos distintos**, no
34. Sus diez controles comprueban limpieza actual, permisos de recuperación,
acuse, error local, respuesta perdida, fallo antes del envío y las respuestas
normal, deshabilitada, anterior y de segundo factor. Los catorce fallos
reproducen los recorridos cancelados, restauración/borrado y persistencia fallida.

Las referencias conservaron HEAD `f874e682a774`, su marcador Debug real y los
2.041 archivos enumerados de Android/configuración. Usan JDK 21, Robolectric API 34,
`:app:testDebugUnitTest`, `--offline --max-workers=1 --no-daemon`, sin imponer un
marcador antiguo ni excluir tareas. La repetición añade las seis clases de 152:
24 casos nuevos más 34 regresiones previstas. No hubo emulador ni ADB paralelo.

El inventario privado de estas tandas registra `local.properties`,
`keystore.properties` y `app/google-services.json`. No incluye
`secrets.properties` ni todo el entorno del proceso: la igualdad comprobada se
limita a los archivos enumerados y no acredita una reproducción binaria completa
del entorno. El transporte de las pruebas permanece sustituido.

La repetición terminó `2026-11-15T18:10:18.068173+00:00` con 58 casos en siete clases,
cero fallos, errores u omisiones y los 2.041 archivos enumerados sin cambios.
HEAD inicial `f874e682a774`, final `f874e682a774`
y marcador Debug real `f874e682a774` se conservan en
[`155-email-recovery-persistence.json`](../quality/evidence/155-email-recovery-persistence.json),
con las huellas de XML, logs, parche y fuentes. La publicación en ambas ramas se
comprueba con un recibo separado, no por haber compilado. No se ha generado ni
instalado una APK con 155.

## Pendientes que no se cierran

Las pruebas de este bloque no comprueban Keystore ni escrituras cifradas reales,
SMTP, bandeja de entrada, vencimiento/segundo factor del servidor, muerte física
del proceso, toques/animaciones de navegación o el dispositivo completo. La
comprobación del origen de `LoginScreen` tiene lectura parcial y compilación;
no se presenta como una prueba instrumental nueva.

El tratamiento completo de un fallo de limpieza local **después** de que el
servidor haya confirmado una contraseña requiere otra revisión de recuperación
y reintento. En esa confirmación no cancelada se conserva el comportamiento
previo; no se promete revertir el cambio remoto ni se valida aquí ese fallo de
almacenamiento. Tampoco se acredita atomicidad global de cuentas y medios, ni
se cierran los demás callbacks de acceso o los criterios A01–A09.

También queda por precisar el mensaje de un fallo de persistencia anterior al
envío: el mapeo genérico todavía puede tratar un `IOException` local como un
problema de conexión. Se comprobó esa clasificación en la referencia controlada;
no se presenta como un fallo medido de disco del teléfono ni como un criterio de
errores personalizados completamente cerrado.

El mutex de persistencia examinado pertenece a cada ViewModel. Falta contrastar
los escritores de distintas instancias o entradas de navegación que comparten
el mismo almacén; estos ensayos usan una instancia por caso y no prueban esa
coordinación global. Se registra como pendiente de lectura y reproducción.

La lectura de `AuthService` confirma restauración local y renovación Ubuntu en
segundo plano; el nombre heredado de su fachada no demuestra un backend externo.
Esta lectura parcial no mide latencia de arranque ni cambia la política R2/Ubuntu.
Los tiempos, la reproducción sostenida y el desplazamiento de vídeo siguen
identificados por [145](145-medicion-movil-avatares-y-rutas-de-video-2026-11-15.md).
Su QA 9.1.40/180 contiene código Android de 148 y carece de 152 y 155. El host
mantiene separado el [plan administrativo 154](154-plan-host-vigente-tras-api153-2026-11-15.md).
