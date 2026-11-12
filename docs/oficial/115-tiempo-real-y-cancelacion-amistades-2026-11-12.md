# Tiempo real por sesión y cancelación segura de solicitudes

Fecha: 12 de noviembre de 2026. Continúa los dos bloques diferidos de la [selección 114](114-comparacion-correcciones-dot-2026-11-12.md), por instrucción expresa del propietario de corregirlos, validarlos e incorporarlos en `main` y `feature/live-gifts`.

## Alcance y evaluación

Bases: `feature/live-gifts` **`9744f3b182416b9cc79c72a1a8452720f1107408`**, `main` **`0d715109b6479c949fab7ad452d4465da9bf5582`**, árbol común `dd5b902b7791ff9aba72de73e7b2d1af934cfbb7`. Referencia de la otra rama: **`a9b67814e1fd123ea66b86cc57264eb235e5ff5d`**. Se parte de sus bloques diferidos, pero se corrigen sus límites; no se fusiona toda la rama.

La selección contiene 33 archivos de código, pruebas, dependencias de desarrollo y documentación, más la matriz de cobertura regenerada. Se examinan completamente 17 de esos archivos y parcialmente 16; no se atribuye lectura integral a los módulos extensos por haber revisado sus cambios.

Riesgo de la modificación: **alto por las áreas afectadas**, autenticación, recepción persistida y operaciones destructivas. Recomendación: publicar el código comprobado en ambas ramas estables, con despliegue Ubuntu y pruebas físicas separados. No se certifica el cierre integral de Android, web o servidor.

| Riesgos concretos tratados | Crítico | Alto | Medio | Bajo |
| --- | --- | --- | --- | --- |
| Invalidación por suscripción repetida, cola reiniciada por renovación y cancelación confundida con eliminar amistad | 0 | 0 | 3 | 0 |

Son riesgos de integración y pérdida de estado, no un recuento de vulnerabilidades confirmadas en producción. La confianza se apoya en barreras controladas, Room y PostgreSQL real, además de las suites de regresión. No se hicieron ataques contra producción.

Secuencia: selección `9744f3b1`/`0d715109` → corrección de los bloques diferidos → pruebas locales → publicación de la misma selección en los dos worktrees existentes. No se modifica `D:/red social - correciones`, ni la rama experimental `codex/experiencias-con-proposito`, ni se trasladan sus diseños o funciones.

## Tiempo real Android

### Suscribir otra pantalla no termina la sesión

La candidata `a9b67814` incrementaba una generación global y reconectaba en cada `replaceUserSubscription`, incluso para la misma cuenta. Las líneas 87 y 99 de su `UbuntuRealtimeClient.kt` proceden de `3a4dc8ef`, concebidas para aislar visitas distintas. Sin embargo, la operación tiene **ocho consumidores**: comunidades, grupos, mensajería, marcadores, mensajes guardados, colecciones de video, historial y ver después.

Ahora `UbuntuRealtimeClient.replaceUserSubscription` (`115`) registra el canal de forma idempotente: repetir la misma cuenta no reconecta, no duplica la suscripción y no invalida eventos admitidos. Se separan la generación de conexión y la visita autenticada. El cambio real de visita limpia canales y credenciales de la visita anterior; la renovación del token conserva los eventos ya admitidos.

`AuthSessionLease.sameVisit` (`AuthSessionMutationGuard.kt:9`) compara el guardián, la generación capturada y la cuenta, sin adquirir otro bloqueo. No confunde A→B→A con la visita anterior aunque el observador asíncrono no haya procesado B. Los callbacks de conexión verifican la vigencia del propietario antes de admitir un evento. Una llamada atrasada con credenciales antiguas no sustituye las credenciales actuales.

Se conserva un orden de bloqueo: guardia de autenticación → monitor de transporte. La consulta que decide si una limpieza pertenece a una visita revocada ocurre antes de adquirir el monitor de transporte. El cierre de un consumidor antiguo no limpia el transporte de una cuenta nueva ni una renovación de la cuenta actual.

### La cola y Room pertenecen a la visita inicial

`MessageRealtimeSync.start` (`109`) mantiene una cola, procesador y listener por visita. Una renovación de la misma visita actualiza el transporte sin destruir la cola. `MessageRepository` distingue la parada por cambio de cuenta de una limpieza explícita de datos.

Si el primer listener terminó porque aún no había credenciales, una llamada posterior con el token listo reinicia esa escucha conservando la cola y su procesador. Una prueba de la cadena de recepción comprueba que el mensaje posterior llega a Room.

- El collector se registra antes de abrir/suscribir el transporte mediante `eventsOnSubscription`; también se adaptan los dos consumidores de LIVE que dependían del tipo `SharedFlow` anterior.
- Los mensajes encolados conservan su sobre y propietario originales. Una nueva visita usa otra cola; no consume mensajes pendientes de A.
- `withRealtimeAccountTransaction` (`425`) comprueba cancelación y vigencia dentro de la transacción Room, antes y después de escribir. El mensaje y la metadata local se confirman juntos o se revierten.
- Las confirmaciones de lectura reciben la misma lease de la escucha, en lugar de capturar una sesión nueva después de esperar.
- La publicación de eventos y la lista de chats se comprueban bajo la guardia de su propietario. Se conservan las identidades de mutex mientras los trabajos antiguos pueden estar esperando en ellas.

La dependencia interna `RealtimeAccountAccess` adapta las operaciones ya existentes de `AuthService`. Permite ejercitar el listener, la cola y Room reales con un transporte sin red, sin añadir una librería de simulación a Android. El constructor de inyección sigue conectando los servicios reales.

### Escenarios comprobados

Las pruebas del cliente cubren registro previo al hook, filtro de eventos antiguos, ocho suscriptores de la misma cuenta, ausencia de reconexiones y suscripciones duplicadas, buffer y despacho pendientes durante renovación, callbacks atrasados, cierre de sesión, credenciales obsoletas y A→B→A sin esperar al observador.

Las pruebas de la cadena real de recepción detienen la escritura antes de adquirir el almacenamiento. Con el mensaje esperando se renueva el token y se registran otros consumidores: debe conservarse el mismo procesador y persistirse el mensaje. En otro caso se realiza A→B→A sin reiniciar el observador: el procesador antiguo debe terminar sin insertar la fila. Las pruebas transaccionales adicionales comprueban revocación durante espera, revocación entre lectura e inserción y cancelación después de insertar, con reversión completa de Room.

Esto no constituye una medición de entrega por red real, FCM en segundo plano ni de todos los recorridos de salas/LIVE.

## Solicitudes de amistad

### Decisiones serializadas por pareja y procedencia

Se incorpora `friend-request-state.js`, junto con sus consumidores de rutas, compatibilidad, política de mensajes y purga de cuentas. El esquema añade dos tablas de estado histórico y funciones de resolución de identidad; no elimina tablas importadas ni reescribe sus datos de origen. La inicialización es idempotente y forma parte de la preparación de la política de mensajes existente.

`withPair` (`friend-request-state.js:55`) utiliza una transacción con cliente dedicado, bloqueo por pareja canónica y orden de bloqueo de tablas compatible con la purga administrativa. Alias locales e importados de las mismas personas comparten el bloqueo.

- Solo el destinatario puede aceptar o rechazar. La decisión es de una sola dirección: pendiente → aceptada/rechazada. Repetir la misma decisión no la reescribe; intentar invertirla devuelve conflicto.
- Cancelar no elimina una solicitud que acaba de convertirse en amistad aceptada. El estado se vuelve a comprobar dentro de la transacción.
- Las solicitudes importadas y de compatibilidad conservan su procedencia. Una invitación histórica cancelada no reaparece ni adopta una invitación nativa posterior por compartir pareja.
- Eliminar explícitamente una amistad sigue siendo una operación distinta, con resolución de alias y supresión de instantáneas antiguas. No impide una invitación nativa posterior legítima.
- La política de mensajes deja de confiar en relaciones históricas suprimidas. Los registros nuevos se eliminan con la cuenta y participan en la transacción de purga.

### Una acción nueva evita el peligro de mezclar versiones

La nueva web utiliza **`POST /v1/data/friends/:requestId/cancel`** (`data-routes.js:14105`). Esta acción exige emisor y estado pendiente. El antiguo `DELETE` queda como contrato de compatibilidad, ahora protegido frente a amistades aceptadas; el borrado explícito por persona conserva su ruta propia.

**No existe fallback del POST a DELETE.** Si la web se publica antes que la API y el servidor anterior no reconoce la acción, falla sin borrar una amistad. La función aún requiere desplegar el código API para estar operativa en el Ubuntu activo. Publicar una rama no equivale a haber desplegado ese servidor.

El botón de cancelar solo aparece en solicitudes enviadas pendientes y, para Ubuntu, cuando la respuesta de la API anuncia `capabilities.cancelOutgoingRequests: true`. Un servidor anterior deja el botón oculto; la protección adicional del POST no depende de esa presentación. La mutación captura la identidad de acceso en el transporte; los resultados y bloqueos de interfaz pertenecen también a esa visita. Una respuesta antigua no desbloquea una cancelación nueva al regresar a la misma cuenta. En el contrato heredado se exige ID, emisor y estado pendiente, y se comprueba que el borrado devuelva una fila.

### Concurrencia y autorización comprobadas

Se creó un clúster **PostgreSQL 18.1** desechable bajo `tmp/realtime-friend-pg-20261112`, enlazado únicamente a `127.0.0.1:55439`, sin usar servicios, cuentas o datos productivos. Las pruebas destructivas verifican expresamente el nombre de sus bases aisladas.

Las carreras retienen el primer bloqueo y comprueban en `pg_locks` que otra conexión realmente espera; no se basan en un retraso arbitrario. Se validan los dos órdenes: cancelar primero impide aceptar después; aceptar primero impide cancelar después y conserva la amistad. También se prueban rechazo/aceptación de importaciones, eliminación explícita, invitación nueva y orden de bloqueos con una operación administrativa.

La prueba HTTP levanta Express en loopback con las rutas reales y PostgreSQL multiconexión. Comprueba emisor permitido, destinatario prohibido, tercero sin acceso, solicitudes resueltas, fuentes importada/compat y ambas carreras nativas. Su autenticación de entrada es una fixture que asigna identidades sintéticas; no sustituye las pruebas del autenticador JWT productivo. Los actores comprobados son usuarios ordinarios autenticados intentando cancelar una solicitud propia, ajena o ya aceptada; el resultado esperado es cancelación autorizada, 403/404 o conflicto 409, respectivamente.

Las pruebas PGlite comprueban además persistencia histórica, alias enlazados después de una supresión, no resurrección de filas, atomicidad ante fallos y purga limitada al propietario. Se añade **solo la dependencia de desarrollo** exacta `@electric-sql/pglite@0.5.16`, con su lockfile; no se actualizan dependencias productivas ni se cambian requisitos de Node.

## Resultados de validación

| Comprobación | Resultado |
| --- | --- |
| Android, selección de recepción y sesiones | **64 pruebas aprobadas en ocho clases**, cero fallos, errores y omisiones. La suite final incluye además el caso posterior de arranque sin token. |
| Android, suite completa | **2.747 pruebas aprobadas en 516 clases**, cero fallos, errores y omisiones. Incluye los tres casos del listener/cola/Room. |
| Android, Lint K1 y compilación instrumentada | **Aprobados** junto con la suite completa en 32 min 13 s. XML de Lint: cero errores, cero fatales y **321 avisos**. Hay una advertencia de API Compose de pruebas obsoleta. Compilar las pruebas no equivale a ejecutarlas en dispositivo. |
| Web, selección de amistades | 20 pruebas aprobadas, tres suites. |
| Web, suite completa Jest | **1.176 pruebas aprobadas en 185 suites**, cero fallos, con los casos de servidor sin capacidad de cancelación. |
| Web, TypeScript/Vite/prerender/SEO | Aprobados con origen ficticio de prueba. 19 páginas públicas, cero bytes de JavaScript público; persiste el aviso de chunk superior a 1.000 kB. |
| Web ESLint | Cero errores, siete avisos de hooks; sin desactivar reglas. |
| API, cuatro suites específicas de amistades | 22 pruebas aprobadas sin omisiones, con PostgreSQL real en concurrencia/transiciones y PGlite en las pruebas secuenciales. |
| API, HTTP + PostgreSQL real | Aprobado: permisos, estados resueltos, fuentes antiguas y ambos órdenes de aceptación/cancelación. |
| API, suite completa | **1.081 aprobadas de 1.100**, cero fallos; **19 omitidas** por requisitos de entorno/plataforma no cubiertos. No cuentan como aprobadas. Las pruebas nuevas de concurrencia y HTTP sí se ejecutaron, incluida la capacidad anunciada en el listado. |
| API, comprobación de sintaxis | Aprobada. |
| Dispositivo, emulador, APK/AAB y Ubuntu productivo | No ejecutados/generados/desplegados en esta tanda. |

Entorno local: Windows, Node 26.13.0, JDK 21 y PostgreSQL 18.1. No se presenta como un ensayo de la imagen Ubuntu productiva. Los logs están en `tmp/realtime-friends-*`; el clúster y los datos sintéticos permanecen ignorados. Se detiene únicamente el servidor PostgreSQL creado para esta revisión.

La primera compilación de las pruebas Android detectó que se había utilizado una librería de simulación no instalada; se sustituyó por un transporte de registro y un adaptador interno, sin añadir esa dependencia. Una invocación posterior detectó una anotación de parámetro incompatible con el constructor secundario, corregida antes de la ejecución final. No se eliminaron aserciones para aprobar.

## Cobertura, publicación y límites

La [matriz de cobertura](review-coverage.json) se actualiza con `tools/audit/update_review_coverage.py`. Las clases pequeñas y nuevas examinadas completamente se marcan como lectura completa; en los repositorios y módulos extensos se mantiene lectura parcial de las secciones afectadas. Las suites aprobadas no convierten archivos inventariados o no leídos en `code-read`.

Se corrige también la herramienta de cobertura para que una lectura renovada explícitamente apunte al informe indicado en esa ejecución y no conserve la evidencia de una huella anterior. Un ensayo de CLI en un repositorio Git desechable comprobó lectura completa, lectura parcial de otra revisión e invalidación posterior sin renovación; no altera las declaraciones de archivos no examinados.

El destino autorizado es `main` y `feature/live-gifts`, con los mismos árboles versionados, conservando sus historiales distintos y los archivos ajenos de sus worktrees. La confirmación de referencias remotas se entrega después del push y su verificación. No se incluyen `anuncio/`, ZIP, claves, configuración privada, respaldos o temporales. La reversión exige un commit de reversión coordinado en ambas ramas y un plan separado para el servidor; no borrar estado histórico ni reiniciar ramas destructivamente.

Pendientes operativos: desplegar la API en Ubuntu con respaldo y verificación de inicialización; probar recepción por red/dispositivo, renovación y cambio de cuentas físicos; verificar los despliegues web automáticos que pudiera iniciar Git. La revisión integral documentada en 101 sigue abierta. Los otros cambios de CI, dependencias y diseño diferidos por 114 siguen fuera de esta entrega.
