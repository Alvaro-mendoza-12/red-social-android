# Auditoría incremental de correcciones-dot — 10 de noviembre de 2026

## Alcance y estado

Esta tanda parte de `7f25192bad3eb9f2fd8297e84909e101886bfde3`. La autorización actual limita las publicaciones a `correcciones-dot`; no se actualizan las ramas estables ni se despliega en producción. Conserva las correcciones previas. Es una auditoría incremental, no el cierre integral de Android, web o backend.

## Defectos reproducidos y correcciones

- **P2, iconos del menú web:** Ayuda usaba Ajustes, reportes usaban Notificaciones, llamadas usaban cámara, personas cercanas usaban el globo de Social y otros destinos repetían símbolos ajenos. Se separó `SecondaryNavigation` y se asociaron once rutas con iconos explícitos; siete cambian. Se mantienen nombres accesibles, rutas y cierre al seleccionar. Commit `f5fe52312a5a783072316c3f5e702ad46aa02320`.
- **P3, sección activa incorrecta:** `primarySection` consideraba `/app/messages-other` y `/app/messagesarchive` parte de Mensajes. Se exige el límite de segmento, como en los demás apartados. Commit `3a6cfffc6f7cc9b665f8e52fb1e65b26b537d031`.
- **P1, resultados antiguos en solicitudes de amistad:** aceptar una solicitud y cambiar a Enviadas antes de su respuesta volvía a cargar Recibidas dentro de la pestaña Enviadas, incluso enlazando al perfil equivocado. Cambiar de cuenta conservaba el bloqueo de la acción anterior. Se invalidan los resultados de una vista anterior al cambiar de contexto o desmontar, se conserva el bloqueo de escritura de cada cuenta entre pestañas, se impide el envío duplicado de la acción vigente y se recarga solo la vista actual de la misma cuenta. Las pruebas controlan respuestas pendientes, cambios de pestaña y cuenta, una operación nueva y reintento tras error. Commit `11902007b225cb7b0f1b024e966680136fc63b87`. Esto valida el estado de la pantalla; no certifica por sí solo autorización del servidor.

## Evidencia local

- Regresión inicial de navegación: nueve fallos esperados antes de corregir (siete símbolos y dos rutas); después 34/34 comprobaciones focalizadas aprobadas.
- Regresión de amistad: fallos reproducidos de perfil incorrecto en Enviadas y botón bloqueado tras cambiar de cuenta; la revisión añadió ida/vuelta de pestañas y cuentas, el contrato legado Supabase, desmontaje y recarga de estado confirmado. Se conserva el bloqueo de escritura por cuenta entre vistas y se actualiza únicamente la vista vigente.
- Batería final después de la revisión: **183 suites Jest, 1.067 pruebas aprobadas**, incluidas nueve de solicitudes de amistad. Lint, build aislado y 106 pruebas Node se repitieron satisfactoriamente sobre este código.
- Primera batería completa: 181 suites Jest, 1.058 pruebas aprobadas.
- TypeScript `tsc -b`: aprobado.
- ESLint: cero errores, siete avisos de dependencias/limpieza de efectos en LIVE, Momentos y solicitudes. No se silencian ni se presentan como defectos demostrados por el aviso solo.
- `npm run build`: aprobado con `VITE_FALLBACK_API_URL=https://ci.novvin.invalid`, el origen aislado que ya usa el workflow Android. El candidato **no sirve para producción**. Puerta SEO: 19 páginas, JavaScript público cero, controles de metadatos, CSP, rutas, assets y privacidad aprobados.
- `npm run test:node`: 106/106 aprobadas después del build. El intento anterior sin `dist` encontró archivos ausentes; no se contabiliza como aprobado.
- La compilación sin variable de API falla explícitamente. No se añadió una dirección supuesta ni se alteró la configuración real.
- La instalación inicial de dependencias no pudo crear la caché de npm fuera del espacio de trabajo. Se completó con caché temporal explícita. No cambia el lockfile.

## Móvil e integración: cobertura limitada

Se leyeron los resolutores de notificaciones de conversación, retorno de llamada, enlaces de salas y enlaces de contenido compartido, y la prueba de destino de notificaciones. Se contrastaron tipos y destinos de navegación; no se identificó en esa lectura un defecto nuevo confirmado que justifique cambiar Android. No se afirma que Android esté libre de errores.

En este entorno no se encontró SDK, adb ni emulador Android. No se ejecutaron pruebas instrumentadas, pruebas JVM Android ni un build APK/AAB local. La evidencia histórica de las revisiones 101–113 sigue siendo histórica y no se reutiliza como aprobación de esta tanda.

## Prioridad siguiente y criterios observables

Estos son criterios técnicos de auditoría propuestos, no funciones nuevas aprobadas ni resultados ya comprobados:

1. **P1, identidad y permisos:** entrada, renovación y salida de cuenta; A→B con cargas y acciones pendientes. Ningún dato/error/acuse de A debe sobrescribir B. Comprobar también el servidor, no solo componentes simulados.
2. **P1, amistad y mensajería:** descubrir una persona, invitar, aceptar/rechazar, abrir conversación, enviar texto/archivo, perder y recuperar red. Conservar borradores, evitar duplicados y verificar destinatario y confirmación real en ambos clientes.
3. **P1, privacidad y moderación:** bloquear/reportar, contenido privado y enlaces compartidos. Verificar permisos con cuentas de prueba separadas y registrar denegación, no inferirla de ocultar botones.
4. **P2, publicación y reproducción:** crear/editar contenido, fallo de subida, reintento, regresar y cambiar pestaña. Conservar el trabajo y medir tiempos reales con red/dispositivo identificados. El editor web no acredita paridad completa con Android.
5. **P2, accesibilidad y apariencia:** comprobar iconos renderizados, foco, Escape/Tab, zoom, contraste, temas y anchos móviles/escritorio en navegador. Las pruebas DOM no sustituyen comprobación visual.
6. **P2, capacidad y mantenimiento:** investigar el chunk de más de 1 MB señalado por Vite antes de cambiar límites; medir transferencia y carga en lugar de prometer fluidez. Separar deuda de efectos React de errores funcionales reproducidos.

La web ya dispone de modo claro/oscuro/sistema, acentos y escala de texto (`core/webPreferences.ts`); el siguiente trabajo es verificar sus recorridos y usabilidad, no duplicar esas opciones. La audiencia inicial 18–30 años aún no define un nicho: no se justifica añadir funciones masivas sin demostrar que los recorridos básicos funcionan.

## Límites de la primera entrega web

No se usaron cuentas reales para enviar invitaciones/mensajes, hacer pagos ni modificar datos. El bloqueo es de esta instancia de pantalla: la idempotencia y las transiciones de estado atómicas del servidor frente a múltiples pestañas/dispositivos siguen pendientes de auditoría. No se cambió el servidor, no se publicó una web y no se generó APK nueva. Publicar commits en esta rama no equivale a desplegar ni aprobar CI. El estado de GitHub Actions debe consultarse para el SHA entregado.

## Continuación: decisión de amistad en la API nativa

Se reprodujo contra el handler real y SQL de PostgreSQL embebido que un segundo PATCH contrario recibía 200 y reemplazaba una decisión ya confirmada. El candidato ahora permite `pending → accepted/rejected` mediante un UPDATE condicional atómico. Repetir la misma decisión devuelve 200 con la fila existente **sin reescribirla**; una decisión contradictoria devuelve 409 `FRIEND_REQUEST_ALREADY_RESOLVED`. El receptor ajeno y el ID inexistente siguen recibiendo 404, también después de resolverse la solicitud. Se mantienen el formato de éxito y las rutas, sin migración.

La regresión nueva usa PGlite 0.5.16 como dependencia de desarrollo fijada en el lockfile y se ejecuta por defecto con `npm test`. Prueba decisiones en ambos sentidos, reintentos sin cambio de versión de fila, handlers simultáneos, entrada inválida y autorización del receptor. Puede ejecutarse con PostgreSQL local externo solo en la base aislada `novvin_friend_request_test`, usando `FRIEND_REQUEST_TEST_DATABASE_URL`.

**Límite importante:** PGlite y la modalidad externa de esta prueba usan una sola sesión. Los handlers se intercalan, pero esto no mide contención entre dos conexiones/servidores PostgreSQL ni latencias de producción. Esa comprobación queda pendiente. No había PostgreSQL nativo ni Docker disponible en este entorno.

La suite de API terminó con 1.064 pruebas aprobadas, 16 omitidas y cero fallos; `npm run check` y la validación en seco del lockfile también pasaron. El primer intento tuvo un fallo de preparación en `production-config.test.js`: faltaban las dependencias del proyecto vecino `signaling-server`. Tras instalarlas desde su lockfile, la suite pasó sin alterar esa prueba.

Esta primera corrección cubre el PATCH nativo. Las rutas PostgREST y DELETE Android se revisan por separado en la continuación siguiente. No se desplegó el código de API ni se modificaron datos reales.

## Continuación de cancelaciones y compatibilidad PostgREST

El DELETE nativo de solicitud por ID permite borrar únicamente `pending` o `rejected`, tanto al emisor como al receptor. Una aceptación confirmada devuelve 409 y no se elimina mediante una cancelación/rechazo tardío. Se conserva la limpieza de rechazadas usada por clientes existentes; repetir un DELETE exitoso devuelve 404. Eliminar una amistad deliberadamente sigue disponible en la ruta explícita por usuario, sin cambio contractual. Se añadieron pruebas de participantes, actor ajeno, estados, unfriend explícito y la intercalación PATCH/DELETE.

La respuesta de amistad por PostgREST conserva el origen nativo de la lectura en metadatos internos. Si la solicitud era nativa, actualiza únicamente su ID y pareja mientras siga pendiente: una eliminación intercalada no puede recrearla ni responder una solicitud nueva de la misma pareja. Repetir una decisión confirmada no reescribe la fila; la contraria devuelve 409. Para filas importadas, el UPSERT solo puede actualizar una pareja nativa todavía pendiente, y se consulta la decisión nativa antes de acusar éxito sobre un importado resuelto.

Las siete regresiones nuevas de compatibilidad ejecutan el handler real y SQL con PGlite. Incluyen importaciones obsoletas, autorización, reintentos sin cambio de versión y una eliminación inyectada exactamente después de la lectura nativa. Esta última prueba falló antes de separar UPDATE nativo y materialización importada. La batería final de API pasa **1.071 pruebas, con 16 omitidas y cero fallos**; `npm run check` y `git diff --check` también pasan. La revisión independiente del diff final no halló bloqueantes.

**Pendiente P1, cancelación importada:** el DELETE genérico de compatibilidad no deja una marca persistente de eliminación para `supabase_import.friends`. Una fila importada `pending` puede seguir visible y materializarse otra vez después de eliminar su copia nativa. Esta entrega no resuelve esa cancelación ni afirma que todas las rutas de amistad estén cerradas. Se necesita diseñar la marca de eliminación y su relación con nuevas solicitudes, probar permisos y lecturas, y revisar migración/retención antes de desplegar.

Todas estas pruebas locales siguen usando una sola sesión: reproducen intercalaciones, no contención PostgreSQL entre conexiones independientes. No se probó Android en dispositivo/emulador ni se generó APK; tampoco se desplegó API o web ni se tocaron datos reales. Los commits y el estado de CI son evidencias distintas.

## Seguimiento de CI y dependencia XML

El job backend-contracts del commit `897fbdf5` terminó en rojo por `npm audit --omit=dev --audit-level=low`, después de pasar los scripts de contratos. El aviso [GHSA-474x-hrwr-fgx4](https://github.com/advisories/GHSA-474x-hrwr-fgx4), revisado el 10 de noviembre, afecta fast-xml-parser hasta 5.11.2 y señala 5.12.0 como corregida. Es una dependencia transitiva de Firebase Admin mediante Google Cloud Storage. La vulnerabilidad descrita requiere la opción no predeterminada stopNodes; no se ha demostrado su explotación en Novvin.

Se actualizó el lockfile a 5.12.0 con sus cuatro dependencias necesarias, conservando el rango del consumidor y los metadatos de plataformas. Instalación limpia aprobada; auditoría de producción: cero vulnerabilidades. Comprobación sintáctica y suite: 1.071 aprobadas, 16 omitidas, cero fallos. No se desactivó la auditoría ni se rebajó su umbral.

La inspección de `android-ci.yml` identificó que su job backend-contracts recorre scripts `test:*`, no `npm test`. `service-ci.yml` ya contempla `npm test` con descubrimiento general. Se añadió `test:friendship` para ejecutar también en backend-contracts las dos pruebas SQL nuevas; su ejecución local pasa ocho casos. También pasó localmente el bucle completo de scripts `test:*` del job. Hasta que se complete el nuevo run no se declara CI verde. El job web/client del run anterior sí pasó; Android seguía ejecutándose al registrar esta evidencia.

## Viabilidad visual y Android

La vista previa local pudo iniciarse, pero el navegador de esta sesión no pudo conectarse a ella (`ERR_CONNECTION_REFUSED`), incluso tras iniciar el servidor fuera del aislamiento. Se detuvieron ambos procesos de vista previa. No hay captura visual comprobada de esta tanda ni se publicó un sitio para resolver el acceso.

El workflow de calidad Android contiene pruebas JVM y lint mediante `qualityGateDebug`, además de ensamblado y auditorías. El workflow instrumentado separa tres pruebas de cuentas en `correcciones-dot`, requiere despacho manual y trata el acceso temporal KVM como autorización independiente. No se modificaron permisos ni se lanzó esa ejecución. La falta de SDK/emulador local sigue impidiendo afirmar validación de dispositivo.

La investigación posterior reprodujo el P1 importado incluso secuencialmente: DELETE de importado pending seguido de PATCH accepted crea una fila nativa nueva. La revisión del diseño detectó lectores adicionales de permisos (`fallback_request_pair_trusted` y friendsOnly) y escritores de consentimiento de chat. Por ello no se publicó una marca parcial que solo ocultara la solicitud. El diseño exige vínculo por origen/ID histórico, supervivencia al borrado nativo, transacciones coordinadas y pruebas con dos conexiones reales, sin bloquear invitaciones nuevas de la misma pareja.

## Solicitudes nativas con identidad local y migrada

Se reprodujo un 404 al aceptar una solicitud nativa cuyo receiver_id era el ID local de una cuenta que también tenía ID migrado. GET ya reconocía ambos, pero PATCH/DELETE comparaban exclusivamente con el preferido. La escritura condicional y las consultas posteriores ahora usan únicamente los alias verificados que devuelve el usuario autenticado en servidor; no aceptan un conjunto de IDs enviado por el cliente.

Las pruebas cubren aceptación y rechazo, reintento, respuesta contraria, cancelación por ambos participantes, emisor intentando responder su propia solicitud, actor ajeno y privacidad de la consulta posterior sobre una amistad aceptada. Se mantiene el estado pending como condición atómica y accepted protegido frente a DELETE tardío. La suite de API pasó 1.071 pruebas/16 omitidas; el grupo SQL pasa ocho casos. La revisión independiente no encontró bloqueantes.

Este cambio se limita a PATCH/DELETE de solicitudes por ID. Unfriend por usuario todavía requiere revisar resolución de los alias del actor y del destinatario; el P1 de cancelaciones importadas sigue abierto. No se aplicaron migraciones ni despliegues. En el run de `2401e2e3`, backend-contracts ya terminó aprobado después de corregir la dependencia y añadir las regresiones; esto no certifica el resto de jobs ni el siguiente commit.

## Eliminación explícita de amistad y alias

Se reprodujo que unfriend devolvía éxito con cero eliminaciones cuando la fila nativa conservaba IDs locales. La ruta por usuario ahora usa los alias verificados del actor y resuelve el destinatario exclusivamente por ID local o migrado exacto. No busca por correo ni nombre. Si no existe la cuenta, mantiene el ID exacto para permitir limpiar una relación histórica. Si el identificador coincide con dos cuentas distintas, devuelve 409 `FRIEND_TARGET_AMBIGUOUS` antes de borrar nada.

Pruebas SQL: ambos alias del destinatario, ambos sentidos de la relación, actor ajeno sin eliminación, repetición con cero cambios, destino inexistente, histórico sin cuenta y colisión cruzada de IDs que conserva la fila. El DELETE sigue exigiendo actor y destinatario en los dos extremos. Se validó la transición roja→verde tanto del defecto de alias como de la ambigüedad.

Este cambio no elimina importados ni resuelve su persistencia de permisos. Tampoco establece orden temporal entre unfriend por pareja y una nueva invitación concurrente; eso pertenece al diseño de ciclos de solicitud pendiente. No se modifica la vinculación histórica de cuentas ni se despliega la API.

## Candidato: cancelación persistente de solicitudes importadas (11 de noviembre)

Se añade un registro por origen e ID histórico, separado de la fila nativa, y barreras de pareja aplicables exclusivamente a snapshots legacy. La respuesta de una solicitud importada pendiente materializa una fila nativa y guarda su vínculo en la misma transacción. Nunca adopta por pareja una solicitud nativa sin procedencia demostrada: devuelve 409. Cancelación, respuesta, nueva invitación y unfriend usan el mismo bloqueo transaccional de pareja; los escritores de consentimiento de chat también lo toman. Las marcas sobreviven a eliminar la fila nativa y no impiden crear una invitación nativa nueva.

Las lecturas de amigos, compatibilidad, vídeos friendsOnly y permisos SQL de mensajes respetan las marcas. Se mantiene la materialización para los consumidores nativos de aceptación/rechazo, incluidos cumpleaños. La identidad interna del bloqueo y del registro es local estable; las filas nuevas conservan IDs públicos preferidos para no romper Android/web. Se comprobaron enlaces posteriores de alias y alta posterior de una cuenta histórica. Las identidades ambiguas no se eligen arbitrariamente.

**Cambio contractual deliberado:** DELETE de amistad aceptada en PostgREST requiere `status=eq.accepted`; sin ese filtro solo permite cancelar solicitudes pending/rejected. Android distingue ahora rechazo (`eq.pending`) de unfriend (`eq.accepted`). Clientes legacy ya instalados que envíen unfriend sin estado recibirán 409 y necesitan actualización. Así un rechazo tardío no puede convertirse en eliminación de una amistad aceptada. No se afirma compatibilidad total con esos clientes.

Se ejecutó PostgreSQL 17.11 oficial en un directorio temporal aislado, sin instalar un servicio del sistema. La prueba multiconexión verifica en `pg_locks` que un segundo backend espera el bloqueo y prueba ambos órdenes de aceptación/cancelación, unfriend frente a respuesta histórica y unfriend seguido de una nueva invitación. A diferencia de las pruebas PGlite previas, esta sí prueba contención entre sesiones reales. El job backend de Android incorpora PostgreSQL 17 desechable para repetirla; la prueba se omite fuera de ese entorno si no se proporciona su URL dedicada. Nunca apunta a producción.

**Límites abiertos:** esta corrección no certifica eliminación/recreación de cuentas con otro ID local, cambios simultáneos de enlaces de identidad ni reimportaciones administrativas concurrentes. Esos procesos necesitan coordinación e historial de identidad adicionales. Tampoco se midió el coste de resolver identidades de marcas con el volumen real; se requiere ensayo aislado de migración/retención y rendimiento antes de desplegar. No se ha aplicado el esquema ni desplegado código en el servidor activo. El P1 global no se declara cerrado mientras estos ciclos sigan pendientes.

Validación local del candidato: `npm run check` aprobado; suite completa 1.100 casos, 1.083 aprobados, 17 omitidos, cero fallos. El grupo `test:friendship`, con PostgreSQL real configurado, pasa 21/21 sin omisiones. Incluye rollback, actor ajeno, nuevas invitaciones, consistencia JS/SQL de IDs, contrato público de IDs, permisos de chat y handlers reales de cancelación y unfriend. No equivale a ejecutar Android en dispositivo ni a certificar producción.

Revisión independiente incremental: sin bloqueantes en los recorridos ordinarios revisados; repitió 20 casos SQL con PGlite y la prueba multiconexión con PostgreSQL real. Mantiene los límites anteriores y no autoriza ni acredita un despliegue. Auditoría npm de producción: cero vulnerabilidades al verificar este candidato.

## Retención de estado de amistad al borrar una cuenta

La auditoría posterior encontró que las tablas nuevas de solicitudes históricas y barreras no estaban incluidas en las rutas existentes de borrado de cuenta. Se incorpora su limpieza tanto al borrado administrativo como al propio usuario, dentro de la misma transacción, después de eliminar las fuentes legacy y antes de eliminar la cuenta cuyos alias permiten identificar las marcas. Se conservan las marcas de otras personas. No se ejecutó ningún borrado real.

La revisión reprodujo una inversión de locks entre el bloqueo administrativo de tablas y unfriend. Las operaciones ordinarias ahora adquieren ROW EXCLUSIVE sobre barriers, requests y friends en ese orden, antes de su advisory lock y escrituras; son compatibles entre sí. La regresión multiconexión espera un bloqueo real y verifica con NOWAIT que el administrador no queda bloqueado por una operación que ya escribió friends: falló antes y pasa después. La prueba no acredita ausencia global de deadlocks en otros módulos.

Pruebas focalizadas: limpieza y rollback reales con PGlite, orden de llamada del handler administrativo mediante fixture y comprobación estática del orden en autoservicio; grupo SQL completo con PostgreSQL real: 22/22. La coordinación integral de baja/recreación y migraciones simultáneas sigue pendiente. Las pruebas de limpieza no sustituyen un ensayo completo de borrado/restauración en una copia aislada del sistema.

Comprobación sintáctica aprobada. Suite API tras el ajuste de locks: 1.102 casos, 1.085 aprobados, 17 omitidos y cero fallos. El commit anterior `59b1ab15` ya tiene backend y web aprobados en GitHub; el backend ejecutó las 21 pruebas de amistad, incluida PostgreSQL multiconexión, sin omisiones. Android seguía en curso al registrar esta evidencia.

## Android: no confirmar una eliminación de amistad parcial

La ruta legacy de `FriendRepository.unfriendUser` consideraba suficiente que una de sus dos peticiones DELETE fuera exitosa. Una respuesta vacía 204 de una dirección podía ocultar un 403/409/500 de la dirección que todavía contiene la amistad. Ahora ambas respuestas deben ser 2xx; la ruta Ubuntu queda intacta.

Se compiló y ejecutó la prueba JUnit del predicado con Kotlin 2.2.10 y JUnit 4.13.2 oficiales en un directorio temporal, sin SDK Android. La variante con el OR anterior falla; la condición corregida pasa, incluidos fallos en ambas direcciones y redirecciones. Revisión independiente sin bloqueantes. Esta evidencia valida la lógica JVM aislada, no el repositorio HTTP completo ni la UI. Las dos peticiones siguen sin ser atómicas: ante un fallo parcial la interfaz debe permitir refrescar/reintentar, y ese recorrido requiere una prueba posterior de integración/dispositivo.

## Web: cancelar solicitudes enviadas

Enviadas mostraba invitaciones pendientes sin una acción para cancelarlas. Se incorpora Cancelar solicitud solo a esas filas, usando el componente y API actuales. Comparte la exclusión de mutaciones por cuenta y la protección frente a resultados tardíos al cambiar pestaña/cuenta; recarga la pestaña vigente después del éxito. Los errores conservan la solicitud para reintentar.

Ubuntu usa DELETE por ID mediante el transporte autenticado existente. La ruta legacy limita la escritura por ID, emisor y estado pending y exige una fila devuelta: si la solicitud cambió, muestra error en lugar de confirmar una cancelación inexistente. Las solicitudes accepted/rejected no ofrecen esa acción. Las pruebas enfocadas cubren éxito, filtros legacy, respuesta vacía, bloqueo entre pestañas, duplicados, errores, recarga y ruta API/ID codificado. La prueba de la opción faltante falló antes de implementar el botón. Revisión independiente del delta sin bloqueantes; no se realizó comprobación visual ni conexión a datos reales.

Validación final web: lint aprobado; 184 suites y 1.076 pruebas aprobadas, sin fallos; build de producción aprobado con `VITE_FALLBACK_API_URL=https://ci.novvin.invalid`, incluido chequeo de páginas públicas y shell privado. Son verificaciones locales, no un despliegue. Las pruebas enfocadas adicionales cubren explícitamente ausencia de botón para accepted/rejected y éxito legacy con recarga.

## Candidato Android: respuestas tardías de acciones de amistad

Las lecturas de amistad ya verificaban cuenta y perfil, pero los resultados de enviar/cancelar/eliminar amistad revertían el estado optimista sin comprobar si la persona había navegado o cambiado de cuenta. Se añade un ámbito específico de acciones, separado del de lecturas: cambia al navegar o cambiar de cuenta, pero no al refrescar la misma ruta. No exige que el alias de la ruta coincida con el ID público del perfil. También se reinicia el estado de amistad al cargar la misma ruta con otra cuenta.

El helper suspend comprueba vigencia antes de iniciar y después de la respuesta. Las siete pruebas JVM aisladas cubren resultado diferido, otra cuenta, cierre de sesión, acción encolada obsoleta, refresco, navegación de ida/vuelta y resultado vigente. La variante sin validación reproduce tres fallos; el helper corregido pasa las siete pruebas. Esto no prueba toda la pantalla ni la integración de red. La exclusión entre dos acciones simultáneas de amistad sobre la misma vista sigue fuera de esta corrección.

La revisión de integración añadió dos protecciones: al navegar se retira el Success anterior antes de suspender una lectura de caché, evitando asociarlo al ticket nuevo; al salir sin identidad se invalida explícitamente el ámbito para que volver a la misma cuenta/ruta no reactive tickets antiguos. El refresco de la misma vista no provoca esa invalidación.

Revisión independiente final aprobada para este delta, con recompilación y ejecución de las siete pruebas JVM actuales. `git diff --check` limpio. No se afirma compilación completa Android, ejecución de pantalla ni solución de mutaciones simultáneas de la misma vista.

## Ajustes web: aislamiento de borradores y respuestas por cuenta

Tres regresiones de componente reprodujeron que cambiar de cuenta mantenía campos de contraseña y borradores, y que una carga o guardado tardíos de la cuenta anterior podían sustituir los datos visibles de la nueva. El formulario ahora tiene identidad propia por backend e ID de cuenta y se retira al cerrar sesión. Su desmontaje limpia todos sus estados, incluidos campos sensibles y flags de guardado. La respuesta fallback antigua no modifica los metadatos de una sesión posterior, y el retry legacy no continúa después del desmontaje.

Se verificaron cambio de cuenta, lectura/guardado diferidos, cierre y regreso a la misma cuenta, cambio de backend con el mismo ID y guardado de una sesión anterior. Las seis regresiones nuevas pasan; suite web completa: 185 suites, 1.082 pruebas aprobadas. Lint y build de producción aprobados con URL CI aislada. Revisión independiente inicial sin bloqueantes. No se afirma cancelar escrituras ya enviadas ni resolver orden de respuestas dentro de la misma cuenta. No se usaron contraseñas reales ni se cambió configuración de producción.

## Android: fondos de chat y procesamiento tardío entre cuentas

La clave anterior de `chat_settings` y el nombre de imagen dependían únicamente de la conversación. La limpieza normal de cambio de cuenta sí ejecuta `clearAllTables`; por tanto, esa clave compartida por sí sola no acredita filtración en un cambio normal. El riesgo acotado está en una optimización de imagen suspendida que continúa escribiendo después de la limpieza, sin comprobar el propietario. Además, sanear `:` como `_` permitía colisiones de nombres.

El almacenamiento ahora utiliza una clave inequívoca por cuenta/conversación y SHA-256 de esa clave para el archivo. Chat, grupo y comunidad capturan el propietario antes de lanzar la operación y lo validan antes/después de preparar la imagen. Las lecturas cambian con la sesión, emiten el fondo por defecto y verifican el propietario también al entregar cada valor. No se adopta ninguna fila antigua sin propietario ni se borran sus archivos: esas personalizaciones necesitan seleccionarse de nuevo, en lugar de asignarlas por suposición.

Seis pruebas JVM del store con DAO y preparación de imagen inyectados pasan. La prueba de imagen diferida pausa la preparación, simula la limpieza y entrada de B, guarda el fondo de B y completa A; no permite repoblar los registros. La variante sin comprobación posterior reproduce un fallo. Se cubren claves/nombres, lecturas de otra cuenta, cierre de sesión, trabajos encolados y entrega antes de actualizarse el flujo de sesión. Revisión y ejecución independientes: seis aprobadas, sin bloqueantes del alcance.

Límites: no se ejecutaron Room, decodificación de bytes, Hilt/Gradle completo ni dispositivo para este candidato. La validación por propietario no es una generación de sesión: A→B→A puede admitir una tarea de A al regresar. Dos preparaciones simultáneas de la misma cuenta/conversación y retención de archivos huérfanos siguen pendientes. No se declara cierre integral de multimedia ni se despliega el cambio.

## Bandeja web: ocultación diferida y cambio de identidad

Una prueba de componente reprodujo que ocultar un chat en A, cambiar a B y recibir un fallo tardío sustituía la lista de B y mostraba el error de A. La operación ahora conserva una generación de vista que se invalida al cambiar cuenta, backend o pestaña y al desmontar; una respuesta obsoleta no restaura su snapshot, no libera el indicador de otra vista y no emite el evento legacy. El fallo del contexto vigente sigue restaurando su caché e informando del error.

La revisión encontró también que `hideFallbackChat` no pasaba la identidad esperada a la API autenticada. Se captura su generación antes de esperar el token y se reutilizan las comprobaciones existentes antes del envío, de la renovación/reintento y de entregar la respuesta. Tres regresiones con la API real y transporte simulado fallaron antes del arreglo: cambio durante la espera del token, 401 tardío y respuesta tras cerrar/reabrir la misma cuenta. También se verifica el DELETE actual y su evento de éxito. La prueba de interfaz cubre cambio de cuenta, regreso a A, cambio de pestaña y error vigente.

Este cambio no revierte una petición que el servidor ya aceptó, no certifica los reintentos del cliente legacy y no cubre otras acciones de la bandeja ni el orden de refrescos dentro de la misma vista. No hubo acceso a conversaciones reales, comprobación visual ni despliegue.

Validación de este delta: 17 pruebas enfocadas aprobadas; suite completa web de 185 suites y 1.090 pruebas aprobada. Lint y build de producción con URL CI aislada aprobados. Las pruebas de transporte usan respuestas sintéticas; no representan un ensayo de servidor o navegador real.

Revisión independiente final del delta: sin bloqueantes detectados; 17 pruebas enfocadas repetidas y `git diff --check` limpio.

## Android: ocultar chats sin escribir sobre una sesión posterior

Se aisló el método original `MessageRepository.deleteChat` sin cambiar su cuerpo, con transporte y DAO simulados. Al completar el hide después de sustituir la caché de cuenta, la prueba detectó una llamada de borrado local y eliminación de la lista nueva. Esto reproduce el orden del método, no una ejecución real de Room ni de dispositivo.

Los dos ViewModels ahora capturan un `AuthSessionLease` antes de encolar trabajo. La operación valida generación y cancelación antes/después del token y de las llamadas remotas. Una cancelación no activa el fallback de clear. La rama legacy conserva el PATCH de cutoff por usuario, pero separa su fase remota de los efectos locales para no recapturar otra identidad dentro de `clearChat`. Se mantiene el fallback Ubuntu existente ante un fallo ordinario, sin ampliar las acciones remotas.

El commit Room comparte exclusión con la activación de cuentas y comprueba la sesión dentro de la transacción, también después del DAO. Solo después publica memoria y retira el override de lectura bajo el commit de sesión. El rollback optimista/error del listado también exige el lease capturado. No se publican flujos ni se hace HTTP dentro del gate de almacenamiento.

Doce pruebas nuevas del coordinador, rollback optimista y gate real con almacenamiento inyectado, más 22 pruebas existentes del guard de sesión, pasan en Kotlin/JVM aislado. La cancelación vigente retira el optimismo sin mensaje de error y se propaga; un lease antiguo no retira el optimismo de una sesión nueva. Incluyen token diferido, éxito/fallo tras cambio, regreso A→B→A, cancelación devuelta o absorbida por transporte, fallback con el mismo token y espera del gate. El mutante sin validación de lease falla tres de las ocho pruebas. No se ejecutaron aquí SQLite/Room real, Hilt, el ViewModel completo ni interfaz; esas capas y el CI completo del commit deben verificarse por separado. El cambio no deshace un hide ya aceptado por el servidor ni corrige `clearChat` como operación independiente, navegación inmediata de la pantalla o duplicados simultáneos de la misma sesión.

Revisión independiente final: 34 pruebas JVM recompiladas y aprobadas, sin bloqueantes detectados del delta acotado y `git diff --check` limpio. La compilación completa de la aplicación queda para el CI del commit.

## Preparación de emulador: reintento acotado de datos móviles

El run instrumentado [42947311470](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/42947311470), sobre `f3e18aca`, ejecutó y aprobó un caso de `SecondarySavedAccountLoginFlowTest`. `SavedAccountLogoutLoginFlowTest` y `MessagesMultiAccountSwitchingTest` no llegaron a ejecutarse: agotaron la preparación offline con `airplane_mode_on=1`, `wifi_on=0` y `mobile_data=1`. La restauración KVM terminó en los tres jobs, según la revisión de sus logs. Estos dos fallos son de preparación, no resultados funcionales negativos de la aplicación. El arreglo posterior de deleteChat no está incluido en ese SHA.

El preparador anterior emitía `svc data disable` una sola vez y luego únicamente observaba. Ahora vuelve a emitir ese comando real cada dos segundos mientras el valor de datos siga sin estar en cero, dentro del mismo presupuesto total de 30 segundos. Conserva los tres valores exactos, el timeout por comando, los errores fail-closed y las restricciones de CI/rama/serial. No modifica directamente los settings para forzar un resultado ni rebaja el aislamiento. La salida acotada de los tres comandos fijos se registra incluso con exit cero para investigar servicios no preparados o rechazos lógicos.

El fixture de orden temprana aceptada pero no aplicada falló antes del cambio y pasa al reintentar. Las 17 pruebas específicas y las 139 pruebas de herramientas pasan. Incluyen ausencia de reintento si datos ya están apagados, valores desconocidos/permanentes, fallo de un reintento, comandos lentos consumiendo el mismo plazo y un exit cero que no acredita estado offline. Revisión independiente: 17 aprobadas, sin bloqueantes del delta.

No se ejecutó adb ni un emulador para verificar este cambio, no se modificó KVM ni se redisparó el workflow. Que la orden inicial llegase antes de estar listo el servicio es una hipótesis compatible con los logs, no una causa AOSP demostrada. El próximo ensayo debe confirmar si el reintento resuelve el bloqueo o aporta un diagnóstico más preciso; no se declara recuperado el ensayo todavía.

## Android: vaciado de chat ligado a su sesión

El método original de `clearChat`, extraído sin modificar su cuerpo con transporte/DAO simulados, reprodujo un envío de clear usando el token de B después de cambiar de A a B durante la espera del token. La comprobación de snapshot posterior rechazaba el resultado, pero llegaba demasiado tarde para impedir esa llamada remota. Además, el DAO suspendible precedía a las actualizaciones de memoria sin una segunda comprobación.

El vaciado captura ahora un lease antes del salto de dispatcher, desde la confirmación de pantalla, y usa el mismo coordinador de sesión que la ocultación. La operación CLEAR llama solo a clear, una vez: no llama a hide ni utiliza su fallback. Mantiene el cutoff por usuario, la fila de conversación y los mensajes persistidos. La fase legacy remota se comparte sin recapturar sesión. Room se actualiza bajo gate de activación y transacción con comprobaciones internas; memoria y feedback de pantalla requieren el lease vigente y, para el feedback, el mismo chat.

Se aprobaron 37 pruebas Kotlin/JVM aisladas (15 del coordinador/rollback y 22 del guard), incluidas tres nuevas de selección CLEAR, fallo sin fallback y respuesta tras A→B→A. Las pruebas de selección remota usan transporte y callback de commit inyectados: no ejecutan preservación de filas/metadatos en Room real ni el ViewModel. La integración fue revisada por separado. Tras reiniciarse el entorno se reconstruyó el diff y se repitió la verificación antes de publicarlo.

Persisten fuera del alcance las carreras entre refrescos/mensajes nuevos del mismo chat y cuenta, y los efectos remotos ya aceptados antes de cambiar sesión. No se ejecutó emulador para este cambio ni se desplegó. La compilación completa Android corresponde al CI del nuevo commit.

Revalidación independiente tras restaurar el entorno: mismos blobs de producción que el diff revisado, 37/37 JVM aprobadas y `git diff --check` limpio. Sin bloqueantes nuevos del alcance.

## Web: preferencias de chat y respuestas de otra sesión/vista

Tres pruebas con la API autenticada real y transporte simulado reprodujeron que fijar, silenciar o archivar un chat podía atravesar un cambio de cuenta durante la espera del token, reintentar un 401 tras otro login o emitir una notificación al regresar una respuesta antigua. El helper ahora captura la identidad completa de sesión y la pasa a las guardas existentes, también para el evento posterior.

La bandeja y el detalle invalidan las respuestas de preferencias al cambiar cuenta, backend, pestaña/conversación o desmontarse. Se comprueban ACK, errores y continuaciones legacy antes de otro fallback. El detalle libera el bloqueo para el contexto nuevo, pero un finally antiguo no libera un guardado nuevo. Se reprodujeron en rojo y luego verde éxito/error de A sobre bandeja B y pin de un chat sobre el siguiente. Dos regresiones adicionales mantienen B guardando mientras termina A con éxito o fallo, hasta que responde B.

Límites: no se revierten PATCH ya aceptados, no se certifica el token interno del SDK legacy ni todas las mutaciones/refrescos simultáneos de la misma vista. La identidad de la API Ubuntu cubre reautenticación del mismo ID; el contexto React requiere observar la transición de cuenta/backend/vista, por lo que no se afirma aislamiento completo del feedback si un logout/login idéntico queda agrupado sin transición visible. No hubo llamadas a conversaciones reales, emulador ni despliegue para esta tanda.

Validación final: 185 suites / 1.098 pruebas Jest aprobadas; 118 pruebas Node, lint (cero errores, siete avisos existentes) y build/SEO aprobados. Revisión independiente del delta sin bloqueantes; repitió las cuatro suites enfocadas antes de los dos casos adicionales (54 pruebas) y después el detalle completo con esos casos (30 pruebas). No se afirma validación visual.

## Web: mensajes programados aislados por conversación y sesión

La revisión retomó el candidato local sin publicar y verificó que el remoto seguía en `7ddc8586`. El detalle ahora reinicia borrador, fecha, listado, bloqueo y clave de idempotencia al cambiar conversación, cuenta, backend o generación de identidad. Las respuestas antiguas de creación, lectura y cancelación no modifican el contexto nuevo. Las tres llamadas Ubuntu pasan la identidad y la cuenta esperadas a las guardas del transporte autenticado.

Seis regresiones nuevas cubren borradores entre conversaciones, éxito/fallo de A mientras B programa, listado antiguo, error antiguo de cancelación y renovación de identidad con el mismo ID. Los logs conservados del candidato reproducían fallos antes de la corrección. La revalidación del árbol final aprobó 185 suites y 1.104 pruebas Jest, 118 pruebas Node, lint con cero errores y siete avisos, y build/SEO con 19 páginas públicas y cero bytes JavaScript público. El build usó `https://ci.novvin.invalid`: es un candidato aislado, no una entrega de producción.

No se revierten operaciones ya aceptadas por el servidor. No se certifican ordenación de refrescos simultáneos dentro de una misma sesión/conversación, token interno del SDK legacy, interfaz visual, emulador ni despliegue. La prueba usa transporte simulado para las operaciones programadas; comprueba las guardas enviadas, no un envío real al servidor.

El Quality Gate Android del commit anterior terminó correctamente en [43039675663](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/43039675663). El segundo ensayo instrumentado sigue pendiente: no hay sesión de GitHub CLI en este entorno y el conector disponible no expone despacho de workflows. No se cambió KVM ni se ejecutó otro ensayo instrumentado.

## Web: reportes privados separados por identidad

La página de reportes ahora mantiene un árbol de estado distinto por generación de sesión. Un cambio de cuenta, invalidación o regreso al mismo ID retira los reportes anteriores; las respuestas y errores tardíos no restauran datos ni liberan la carga de la sesión nueva. `loadFallbackProfileMe` acepta una identidad esperada opcional y esta página la transmite a las guardas existentes del transporte. Se mantiene compatible el resto de consumidores del helper.

Seis pruebas de página cubren datos ya cargados al cambiar cuenta, respuesta antigua después de la nueva, invalidación antes de actualizar Auth, regreso al mismo ID, error antiguo durante carga nueva y reintento de un error vigente. La revisión independiente del parche no encontró bloqueantes. Son pruebas locales con transporte simulado, no una validación del servidor ni una auditoría de todas las pantallas privadas.

## Web: apodos privados y respuestas de otro contexto

Se reprodujeron cinco regresiones antes del cambio: éxito antiguo cerraba el editor del siguiente chat, error antiguo aparecía allí, PUT/DELETE carecían de guardas de identidad y renovar sesión conservaba el bloqueo anterior. El guardado captura identidad y contexto de chat/cuenta/backend; solo su contexto vigente actualiza título, participante, editor, error, evento de preferencias y bloqueo. El editor y borrador se reinician al cambiar contexto. Las llamadas Ubuntu para guardar o retirar un apodo pasan identidad y cuenta esperadas.

Las seis regresiones finales añaden cambio de cuenta mientras una segunda petición queda pendiente. La revisión independiente aprobó 74 pruebas en cinco suites de interacción, identidad, carreras de sesión, cuentas migradas y reintentos, sin bloqueantes del delta. No repitió la fase roja. No hubo llamadas a cuentas reales, navegador ni emulador. No se revierten escrituras aceptadas por el servidor y la ruta Supabase heredada no está ejercitada por estas regresiones. Cerrar y reabrir el editor durante un guardado dentro del mismo chat/sesión no queda certificado por este alcance.

## Verificación integrada de reportes y apodos

Sobre ambos parches juntos pasaron 186 suites / 1.116 pruebas Jest, 118 pruebas Node, lint con cero errores y siete avisos existentes, y build/SEO (19 páginas públicas, JavaScript público cero). El build usó `https://ci.novvin.invalid`; no es una entrega de producción. Un intento Node coincidió con la regeneración de `dist` y registró fallos de las páginas públicas; la repetición después de terminar el build aprobó las 118 pruebas. Se conservan las advertencias React de mocks/act y de tamaño de chunks. No se desplegó ni se ejecutó el ensayo instrumentado pendiente. La revisión integral continúa abierta.

## Web: reacciones privadas Ubuntu ligadas a sesión y conversación

Se reprodujeron cinco fallos antes de las guardas correspondientes: la respuesta de una reacción del chat A insertaba su mensaje completo en el chat B; el error también aparecía en B; el transporte permitía cambiar cuenta durante la espera del token y repetir un 401 tras otro login; y la interfaz de A podía iniciar una reacción cuando la identidad del transporte ya era B.

El helper captura identidad y cuenta para las comprobaciones del transporte. El detalle rechaza de entrada una identidad fallback distinta del usuario visible y descarta respuestas/errores de otro chat, cuenta, backend, generación de sesión o instancia desmontada. Se conservan ruta y payload. No se modificaron reacciones de grupos/comunidades, Supabase heredado ni borrado de mensajes.

Ocho pruebas añadidas cubren los cinco fallos y los caminos de éxito, renovación de identidad y cambio de cuenta. La revisión independiente leyó los registros rojos y repitió 61 pruebas en dos suites, sin bloqueantes. La batería final pasó 186 suites / 1.124 pruebas Jest, 118 Node, lint con cero errores y siete avisos, y build/SEO (19 páginas, JavaScript público cero) con el origen aislado `https://ci.novvin.invalid`. No hubo navegador real, emulador, cuentas reales ni despliegue. La protección de desmontaje resulta de la guardia, pero no hay un caso explícito nuevo para ese escenario ni para un error tras renovar identidad. No se revierte una reacción que el servidor ya haya aceptado.

## Web: borrado por sesión en chats, grupos y comunidades

Se reprodujo que el borrado podía continuar con otra identidad tras consultar el pendiente local o al esperar/reintentar autenticación. Las respuestas tardías también insertaban mensajes eliminados o contenido de otra cuenta en la vista nueva, o mostraban su error allí. El helper ahora captura identidad antes de la consulta local, la verifica al terminar y la transmite junto con la cuenta al transporte autenticado. Las vistas rechazan discrepancias entre usuario visible e identidad fallback y condicionan resultados y errores al contexto vigente.

El borrado por lotes del chat privado se detiene al cambiar cuenta, conversación o generación de identidad. Se reinicia la selección/bloqueo para el contexto nuevo y un finally antiguo no libera el lote nuevo. Las regresiones cubren cambio de conversación, nuevo login del mismo ID y cambio de backend.

La ampliación de pruebas con una fila pendiente real del outbox demostró otro fallo: el helper rechazaba al final, pero la cancelación local ya había eliminado el mensaje bajo un login renovado del mismo ID. `cancelPendingWebMessage` comprueba ahora la identidad antes y después de sus esperas. `operation()` ejecuta una guardia opcional después de esperar la base de datos y antes de abrir la transacción; se propaga a los borrados de mensaje/archivo y la escritura de caché. Se comprueba también antes del evento y retorno. No hay rollback de una transacción ya iniciada ni de una petición ya aceptada por el servidor.

Se añadieron 51 pruebas: 21 de helper/transporte, 12 del outbox con adaptador IndexedDB mínimo y 18 de interfaz/lotes. Los registros rojos reprodujeron 12 fallos del transporte, 15 de interfaz, el bloqueo de lote y tres cancelaciones locales tras renovar sesión. Se verifica además la identidad justo antes de la transacción de borrado y el éxito vigente con evento único. La revisión independiente aprobó 125 pruebas en seis suites, sin bloqueantes del delta.

Validación final: 188 suites / 1.175 pruebas Jest, 118 Node, lint con cero errores y siete avisos, y build/SEO aprobados con `https://ci.novvin.invalid` (19 páginas públicas, JavaScript público cero). Son verificaciones locales y transporte simulado; no se ejercitaron IndexedDB/Web Locks de un navegador real, emulador ni producción. Las rutas heredadas tienen guardas de feedback, pero esta tanda no certifica su transporte ni todo el outbox. No se desplegó ni se borró ningún repositorio o rama.

## Web: identidad de sesión en el transporte RPC compartido

`backendRpc` protegía el ID de cuenta, pero no distinguía dos logins del mismo ID. Cuatro regresiones reprodujeron aceptación tras logout/login durante la espera del token, respuesta 200 antigua, reintento de un 401 antiguo y cambio de generación del backend. Ahora captura la identidad después de leer la sesión y la transmite al transporte autenticado junto con el ID local existente. Se conserva la compatibilidad con perfiles migrados y el contrato `{ data: null, error }`, sin caer a otra autenticación tras un rechazo Ubuntu.

Se añadieron seis pruebas con el transporte real y fetch simulado; las dos de éxito cubren identidad normal y perfil canónico distinto del ID local. La revisión independiente aprobó 49 pruebas en seis suites de RPC, identidad, sesión, persistencia y reintentos. La batería global pasó 189 suites / 1.181 pruebas Jest, 118 Node, lint con cero errores y siete avisos, y build/SEO con el origen CI aislado (19 páginas públicas y JavaScript público cero).

La renovación ensayada es logout/login; el refresco normal de token dentro de la misma sesión no cambia la generación. El cambio de backend se simuló invalidando la identidad, sin ejecutar discovery real. No se certifican todos los callbacks de interfaz que usan RPC, ni llamadas iniciadas por una vista ya obsoleta, ni la sesión interna de Supabase heredado. No hubo navegador real, emulador ni despliegue.
