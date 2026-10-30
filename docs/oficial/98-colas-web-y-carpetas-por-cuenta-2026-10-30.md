# 98 — Colas web y carpetas de chats por cuenta

Fecha: 2026-10-30, Bogotá. Nueva ronda posterior al cierre de las revisiones 96 y 97. Se revisan concurrencia, acciones pendientes y cambio de cuenta en web y Android; no es una repetición de todas las pruebas históricas ni una certificación de ausencia de errores.

## Acciones web que se perdían durante la sincronización

La cola de marcados de lectura leía una copia de localStorage, esperaba las peticiones y finalmente sustituía la cola completa por sus resultados. Una acción añadida durante esa espera desaparecía al terminar la petición anterior. Además, los refrescos simultáneos podían ejecutar el mismo elemento más de una vez.

La regresión inicial reproduce cuatro fallos en cinco casos: pérdida al completar, pérdida junto a un reintento, ejecución simultánea repetida y continuación de una cuenta desactivada. El caso de cuentas distintas ya pasaba. No se atribuyen estos fallos a la red ni al switch.

Ahora cada intento reconcilia solamente su identificador contra la cola vigente y guarda el resultado antes de continuar. Las llamadas de una misma cuenta comparten una ejecución en la pestaña; Web Locks coordina los consumidores de distintas pestañas compatibles. Se conserva el límite por pasada, la separación por cuenta y la política anterior de ocho intentos. El cambio de cuenta detiene las siguientes acciones y no consume un intento por una operación ya obsoleta. Se consulta la identidad activa directamente, además de la limpieza del efecto, para no depender de que React haya terminado de actualizar la pantalla. Los marcados de notificaciones llevan la cuenta esperada hasta la resolución/renovación del token, evitando ejecutar un marcado global pendiente con la cuenta siguiente.

La prueba adicional usa Chromium y dos pestañas del mismo origen local, con el módulo real reliability.ts compilado para ese fixture. La segunda pestaña no ejecuta mientras la primera mantiene el bloqueo, añade otra acción durante la espera, la primera termina conservando esa acción y la segunda la procesa después. También se comprueba que desactivar la cuenta deja el segundo elemento pendiente. No se usan credenciales, cuentas ni escrituras de producción en ese fixture. No se afirma atomicidad entre versiones antiguas de la web ni exclusión entre pestañas en navegadores sin Web Locks.

## Cancelación y cambios de cuenta en la sincronización Android

SyncManager propagaba la cancelación de las dos primeras etapas, pero las siguientes podían contar un Result.failure(CancellationException) como error ordinario y seguir con otros repositorios. El identificador de la telemetría también se resolvía antes de esperar el mutex.

AccountSyncStage comprueba la cuenta y la cancelación antes y después de cada una de las siete etapas; una cancelación encapsulada también se propaga. Los fallos ordinarios conservan la política de errores/reintentos existente. La cuenta del ciclo se resuelve después de obtener el mutex. Las pruebas cubren cambio de cuenta antes/durante la operación, cierre de sesión, cancelación lanzada y encapsulada, cancelación real de la corrutina, éxito normalizado y conservación del error de red. Son límites entre etapas; no sustituyen las autorizaciones del servidor ni todas las comprobaciones internas de cada repositorio.

## Carpetas de chats y confirmaciones de cambios

AdvancedMessagingRepository mantenía una lista global y una sola clave folders_json, sin propietario; no formaban parte de la limpieza de repositorios al cambiar de cuenta. Esto permitía reutilizar nombres e identificadores locales de carpetas de otra sesión. Añadir/quitar un chat también conservaba un cambio local como exitoso aunque el servidor lo rechazara, y algunas respuestas de creación sin identificador generaban un identificador ficticio.

ChatFolderStore guarda y observa las carpetas de cada cuenta por separado. Las respuestas tardías no pueden publicar ni guardar la lista de una cuenta anterior. El dueño de una edición se captura antes de esperar otras operaciones: un toque pendiente de A no se transforma en una edición de B. Las lecturas y mutaciones se serializan para que una actualización anterior no borre una creación recién confirmada. Al cambiar de cuenta se reinician el filtro de carpeta, la búsqueda y los chats ocultos de esa pantalla.

Los cambios sobre carpetas del servidor se publican localmente cuando la operación se confirma; un rechazo conserva el estado anterior y devuelve un mensaje comprensible. No se inventan identificadores al recibir una creación incompleta. Una actualización con error o JSON inválido no sustituye el caché por una lista vacía. Las carpetas locales de la cuenta se conservan al refrescar y su edición no necesita pedir un token de red.

La lectura y decodificación del caché observado se ejecuta en IO, aunque la pantalla recoja el flujo desde su hilo de interfaz. Una prueba verifica esa separación de hilos. Es una medida para evitar trabajo local en la interfaz; no se publica un porcentaje de aceleración sin medirlo. Se conserva la distinción entre el identificador público de una cuenta migrada y el identificador local usado como subject del token; la operación se vincula a la cuenta capturada, respetando la protección de renovación de AuthService.

La clave antigua sin dueño **se conserva y no se asigna automáticamente a quien entre después**. Las carpetas remotas se recuperan mediante la consulta autenticada; las carpetas exclusivamente locales del formato antiguo permanecen retenidas sin mostrarse hasta poder acreditar su propietario. No se borra esa información ni se afirma haber migrado automáticamente esos casos. Tampoco se introduce una nueva cola de sincronización para carpetas creadas solo en el dispositivo.

Las regresiones prueban separación A/B, reapertura del almacenamiento, conservación del formato antiguo, rechazo de respuesta tardía, cierre de sesión, normalización, selección de cuenta en el flujo, caché corrupto, edición en espera durante cambio de cuenta, actualización concurrente, rechazo de edición y cancelación.

## Verificaciones de esta ronda

- Web: 875 pruebas Jest en 161 suites y 96 pruebas Node aprobadas.
- TypeScript y build web aprobados; 19 páginas públicas verificadas sin JavaScript de aplicación. Continúa la advertencia de un chunk grande.
- Lint web: cero errores y siete advertencias anteriores.
- Auditoría del lockfile web con dependencias de producción: cero vulnerabilidades conocidas reportadas por npm en esta consulta. No incluye las dependencias exclusivas de desarrollo ni acredita por sí sola la seguridad de toda la aplicación.
- Prueba de navegador local con dos pestañas y Web Locks reales aprobada.
- Android: **2.000 pruebas unitarias en 419 suites**, sin fallos, errores ni omisiones; lint **cero errores/311 advertencias con K1**, sin desactivar reglas. APK/AAB **9.1.33 / 173 firmados**, con firma, R8, alineación de 16 KiB y actualización normal de 172 a 173 en AOSP 35 comprobados.

La primera ejecución completa web encontró un mock antiguo sin la lectura de sesión incorporada; se actualizó el fixture y la repetición completa aprobó. El primer control de tipos también detectó el tipo de retorno del callback asíncrono de Web Locks; se corrigió antes del build aprobado. Dos ejecuciones Android se iniciaron mientras se añadían las nuevas fuentes de auditoría y fallaron al compilar tests contra entradas anteriores; se conservan esos registros y no se interpretan como crashes de la aplicación. Solo se acepta el resultado del código final.

El análisis Android con K2 dedicó más de veinte minutos a resolver módulos y rutas de archivos en el detector de tamaño de pantalla de Compose, según las capturas de hilos. Se conserva esa incidencia y se selecciona temporalmente el motor anterior mediante la opción oficial de AGP 8.13.2 `android.lint.useK2Uast=false`, comprobada en la versión instalada, manteniendo las reglas y sus severidades. La opción se pasa solamente a esta validación; no se modifica el compilador de producción ni se introduce una línea base. Un primer intento separó incorrectamente el argumento con puntos en PowerShell y falló al configurar la herramienta; se corrigió entrecomillando el argumento completo. Los resultados finales deben leerse junto con el motor utilizado, sin presentar diferencias de advertencias entre motores como una mejora de la aplicación.

## Separación del trabajo simultáneo

Durante el cierre aparecieron ediciones de otro chat en la misma carpeta y una llamada nueva incompatible interrumpió la compilación. El propietario confirmó ese trabajo y delimitó la responsabilidad: esta auditoría maneja `main` y `feature/live-gifts`; la rama `correciones` queda para el otro chat.

Se trasladaron los cambios pendientes a **D:/red social - correciones**, en la rama local `correciones`. La comparación binaria del parche confirma los cuatro archivos con cambios semánticos idénticos antes y después del traslado. Se conservan además un respaldo de los trece archivos originales, incluidos cambios de formato, y el stash `ed662e35500cbea6eaee23002849e8b866ae25da`. Ese trabajo queda pendiente, sin corregirlo, comitearlo ni publicarlo en esta auditoría. El otro chat debe continuar desde esa carpeta para mantener la separación.

**D:/red social - live-gifts** vuelve a `feature/live-gifts`, con las fuentes auditadas, y **D:/red social - copia** permanece en `main`. Las fuentes comprometidas de ambas ramas coinciden. La llamada incompatible permanece únicamente en el trabajo separado y no forma parte del candidato Android de esta ronda.

## Publicación y recorrido web

Publicación final en https://red-social-android.vercel.app con despliegue dpl_7nugCQYJUYTys9AAFdGzJyihiFe8, estado READY y alias de producción confirmado. Se preparó desde git archive del commit funcional d66bf7cc8cd0, con NoWing-web, configuración pública y reglas Vercel versionadas, sin subir archivos personales ni credenciales locales. Sustituye la primera publicación de esta ronda, dpl_Hqn3ePtdWvMfuJWh4CxBj8BUoanK, incorporando la comprobación directa de cuenta activa.

Aprueban 90 controles HTTP/SEO/cabeceras/recursos después de cada publicación. La repetición de navegador en la publicación final termina a las 08:35 de Bogotá: restaura una sesión sintética auténtica obtenida mediante el acceso público de la API y su cookie de sesión y recorre Mensajes, Videos, Social, Juegos, Perfil, Notificaciones y Salas de voz. Las siete rutas conservan su destino, muestran navegación, no presentan alertas visibles y sus peticiones de datos observadas responden 200, sin excepciones de página. La sesión nueva se revoca al terminar y su estado temporal queda sin credenciales. No se envían mensajes, reacciones ni avisos a usuarios reales. Esta observación acotada no es un recorrido de todas las acciones internas ni una medición de latencia o reproducción de todos los medios.

## Servidor y oportunidades siguientes

Las inspecciones de solo lectura de las 07:48 y 09:37 de Bogotá confirman disponibilidad, marcador novvin-profile96-20261030 y doce servicios principales saludables. No se encontraron los patrones buscados de errores no capturados, falta de memoria, objetos SQL ausentes o FATAL en treinta minutos de registros de la API en cada muestra. No se exportaron cuerpos de registros ni configuración privada, no se desplegó otro backend y no se saturó producción. Son muestras acotadas, no una prueba de capacidad.

El build web sigue señalando un chunk de aproximadamente 1,10 MB antes de gzip. Una siguiente mejora de rendimiento debe identificar qué dependencias se descargan al entrar en cada sección y medir su contribución antes de dividirlo; bajar su tamaño por sí solo no demuestra mejorar el primer video ni la latencia del servidor. Se conserva como oportunidad medida del build, sin cambiar dependencias durante esta corrección de concurrencia.

El refresco de los contadores generales solicita en paralelo notificaciones y tres bandejas completas. El recorrido confirma consultas de estas rutas también en secciones distintas de Mensajes. Son solicitudes de tipos distintos y reconciliaciones tras conectarse; no se califican todas como duplicados por compartir la ruta. Se identifica como siguiente oportunidad un contrato autorizado de contadores que evite transferir las bandejas completas cuando solo se necesitan totales, comprobando su equivalencia con lecturas, archivos y eventos antes de sustituir el mecanismo actual. Ese endpoint todavía no está implementado ni desplegado.

Siguen pendientes las pruebas entre teléfonos físicos de llamadas, LIVE y FCM, y la huella de firma administrada por Play. Las mediciones de SQL de la revisión 96 no representan el tiempo completo entre cliente y servidor. No hay compras, integración de retiros ni publicación en Play en esta ronda.

## Referencias contrastadas

- [Android: buenas prácticas de corrutinas y cancelación](https://developer.android.com/kotlin/coroutines/coroutines-best-practices).
- [Kotlin: cancelación cooperativa](https://github.com/Kotlin/kotlinx.coroutines/blob/master/docs/topics/coroutines-cancellation.md).
- [React: limpieza y respuestas asíncronas](https://react.dev/reference/react/useEffect).
- [MDN: coordinación por origen con Web Locks](https://developer.mozilla.org/en-US/docs/Web/API/Web_Locks_API).

Los hallazgos y resultados proceden del código y las pruebas del proyecto; estas referencias fundamentan los mecanismos y no certifican Novvin.

## Entrega Android y alcance nativo

Paquetes normales de producción, no debuggable, mínimo API 24 y objetivo API 36. Se generan con las fuentes Android de `4ce131b4e31b`; el equivalente en main es `0b6d8e81`. La publicación web final procede de `d66bf7cc8cd0` (main: `86b911c6`). El trabajo separado de correciones queda fuera de estos paquetes. Se excluye explícitamente el envío del mapping de la versión aún no publicada, conservándolo localmente. No se realiza una publicación en Play.

La firma coincide con el certificado de subida de las entregas anteriores. R8 conserva los 17 componentes de manifiesto y PushRegistrationWorker, y excluye MediaAuditEntryPoint del release. Catorce bibliotecas ELF de 64 bits mantienen segmentos con alineación de al menos 16 KiB; zipalign, validación del AAB y PAGE_ALIGNMENT_16K aprueban. Esto no sustituye la huella administrada por Play ni una prueba en hardware de páginas de 16 KiB.

| Paquete | Bytes | SHA-256 |
| --- | ---: | --- |
| APK 173 | 221169437 | `a854429b25de58f449ac8bfaefb0bc53233576cb821f879fb0afe3a993618b69` |
| AAB 173 | 131970636 | `cd3b8437644ac0d612d449e3a40681ed300b2f1368bbec48ebeb14699f2ce193` |

En un AVD propio AOSP 35 se instala el APK 172 normal y se actualiza con el 173 firmado sin borrar datos. Se revisa visualmente el arranque, se comprueba la reapertura y se inspeccionan los registros de crash de la aplicación. Es una comprobación de instalación/entrada sobre un AVD nuevo: **no acredita conservación de una sesión autenticada**, comunicaciones físicas ni todas las pantallas. Las 70 pruebas nativas de la revisión 96 conservan su alcance anterior y no se presentan como repetidas en esta ronda.

## Cómo revertir esta ronda

La reversión de fuentes se hace con commits nuevos, sin reescribir las ramas publicadas. En `feature/live-gifts`, el orden inverso de los cambios funcionales es `d66bf7cc`, `4ce131b4` y `0f677e11`; en `main`, sus equivalentes son `86b911c6`, `0b6d8e81` y `71c4e6d3`. Debe comprobarse de nuevo que los árboles de ambas ramas coincidan y actualizar esta documentación para reflejar la reversión. No se incluyen los cambios pendientes de `correciones`.

La web requiere además volver a publicar las fuentes revertidas o restaurar un despliegue anterior conocido y comprobar sus alias y rutas. No se cambió el esquema ni el backend en esta ronda. El APK 173 no se publicó en Play; si llegara a distribuirse y fuera necesario revertir su comportamiento, se genera y prueba un paquete con un código de versión superior, manteniendo la firma. No se fuerza una bajada de versión ni se borran datos de usuarios para instalar el 172.
