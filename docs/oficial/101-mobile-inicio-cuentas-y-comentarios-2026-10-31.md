# Revisión móvil en curso: entrada, cuentas y comentarios

Este es un avance de correcciones comprobadas, no el cierre de la revisión integral ni una declaración de que toda la aplicación esté lista. Se continúa primero en Android; la web y el PPTX de aproximadamente 10,5 MB siguen pendientes para su fase.

## Problemas corregidos

- Los comentarios abiertos desde un perfil mostraban acciones de reacción, edición y menciones con callbacks vacíos. Ahora usan el mismo modelo y operaciones autenticadas del detalle de publicación. Los callbacks visibles de `CommentItem` son obligatorios en compilación.
- El formulario conservaba poco contexto ante un fallo: eliminaba el borrador antes de confirmar el envío y trataba una lectura fallida como comentarios vacíos. Conserva texto y edición hasta confirmación, muestra error con reintento y limita la espera de lectura.
- La construcción de hilos ocultaba respuestas cuyo padre ya no estaba disponible y podía recursar indefinidamente con datos cíclicos. La organización iterativa conserva cada comentario una vez y limita la sangría visual.
- Una captura de archivo entrante podía terminar después de recibir una notificación o enlace y reemplazar su destino. La nueva entrada invalida también esa captura pendiente.
- Los efectos de suspensión seguían suscritos a la cuenta anterior cuando se cambiaba de cuenta sin cerrar sesión. La suscripción y la decisión de salida ahora pertenecen a la cuenta y al destinatario correspondiente.
- Recuperación, migración, contraseña y seguridad podían recibir respuestas antiguas después de un cambio de cuenta o dejar el formulario cargando si fallaba la hidratación local. Se reutiliza el control de generación de sesión, se gestionan excepciones y cancelación, se bloquea el envío duplicado y se cancelan solicitudes al abandonar el formulario. Se conserva la contraseña exacta, incluidos sus espacios.
- Ajustes abierto desde el navegador raíz carecía de los destinos Idioma, Ayuda y Términos. Se registraron con las pantallas existentes y se comprobó navegación de ida y vuelta en el grafo real.

## Comprobación ejecutada

`testDebugUnitTest` compiló la aplicación y ejecutó **33 pruebas seleccionadas, 8 clases, sin fallos, errores ni omisiones**: comentarios vacíos/error/espera/cancelación/reintento; hilos huérfanos, cíclicos y de 10.000 niveles; hidratación de acceso; cancelación de formularios y respuesta atrasada; límites de cuenta/suspensión; guardia de mutaciones de sesión; entrada de archivos y navegación real de Ajustes.

La ejecución anterior detectó una aserción de identidad de excepción incompatible con la recuperación de trazas de las corrutinas; se cambió para comprobar tipo, mensaje y conservación de la causa original. No se ocultó ningún error del servidor.

No se generó una APK nueva, no se usó emulador en esta tanda y no se ejecutaron cambios de contraseña, correos ni pagos sobre usuarios reales. Las mejoras de presentación de comentarios todavía requieren comprobación visual. La compilación y estas pruebas no sustituyen la revisión de los demás módulos.

## Continuación obligatoria

Quedan por cerrar, entre otros, el borrador al responder, la disponibilidad de comentarios posteriores al número 50 y las respuestas atrasadas del resumen del hilo. Después continúa la lectura de los módulos móviles pendientes y sus contratos de servidor, manteniendo una matriz de cobertura por archivo y recorrido. No se pasa a web hasta completar la fase móvil.

## Segundo bloque comprobado

Se completaron esos tres pendientes de comentarios: responder conserva el texto; una revisión del borrador impide que un ACK viejo borre texto nuevo, incluso cuando se reescribe lo mismo; y las lecturas de resumen se cancelan/invalidadan al cambiar cuenta, publicación o contenido. Las menciones y el cierre de comentarios del perfil invalidan también sus lecturas anteriores.

El móvil estaba recortando a 50 un resultado que el servidor Ubuntu ya había enviado completo. Ahora conserva el resultado acotado existente del servidor y presenta ventanas de 50 mediante «Ver más comentarios», sin añadir peticiones. Una notificación puede revelar directamente un comentario posterior a esa primera ventana. No se amplió el máximo de 50.000 filas que ya aplica ese endpoint; paginar también la consulta del servidor sigue siendo una mejora pendiente para publicaciones masivas.

El envío de stickers de comentario conserva la respuesta hasta confirmación y evita envíos simultáneos. El detalle guarda el texto ante recreación y separa borradores y diálogos por cuenta y publicación. Los errores visibles de estas acciones usan mensajes habituales.

Se ejecutó después **toda la suite Android: 2.092 pruebas, 439 clases, 0 fallos, 0 errores, 0 omisiones**. Incluye las pruebas nuevas de ventanas, destino de notificación fuera de la primera página y conservación del borrador ante ACK atrasado. La invalidación de resumen/menciones está verificada por lectura de flujo y compilación; no se afirma una prueba instrumental nueva de esos controles. Continúan pendientes la revisión de otros módulos, la comprobación visual y una APK nueva.

La lectura siguiente identificó concurrencia pendiente de corregir en captura/persistencia de cuentas guardadas. Ese hallazgo y los demás módulos revisables mantienen abierta la fase móvil.

## Tercer bloque: coherencia de cuentas y caché

La captura de una cuenta guardada esperaba el token y después leía por separado la identidad y el token de renovación. Un cambio de cuenta entre esas lecturas podía mezclar credenciales. Ahora la captura y las respuestas de acceso/perfil comprueban la cuenta propietaria; las escrituras de sesión se confirman bajo la misma guardia que el cambio de cuenta. La actualización de perfil obtiene también un token ligado a su destinatario.

Las modificaciones de cuentas guardadas se serializan y solo se publican tras confirmar su persistencia segura. Un fallo de almacenamiento ya no muestra una eliminación o una actualización que se perderá al reiniciar. La sincronización continúa después de un fallo puntual.

Una prueba con Room reprodujo otro defecto: al terminar tarde la limpieza de la cuenta A, se borraba la caché inactiva que ya pertenecía a B. La prueba falló antes de corregirlo. La limpieza de salida ahora rechaza una propiedad ajena antes de modificar la base; se comprobó tanto caché sin envíos como mensajes pendientes sin marcador de propietario.

Validación de este bloque: **261 pruebas seleccionadas, 59 clases, 0 fallos, errores u omisiones**, compilación Android y `lintDebug` completados con **0 errores y 319 advertencias**. Se utilizó `-Pandroid.lint.useK2Uast=false`, como en las revisiones anteriores. Una ejecución anterior con K2 se atascó analizando tipos; se detuvieron únicamente sus procesos de Gradle/Kotlin identificados y se repitió con K1. Las advertencias no se presentan como corregidas.

Este bloque no incluye una APK nueva ni ensayos de dispositivo. Siguen abiertas la revisión de los demás módulos y las confirmaciones de reportes, donde se detectó un aviso de éxito anterior a la respuesta del servidor. La web continúa pendiente de su fase posterior.

## Cuarto bloque: reportes con confirmación real

Los reportes de usuarios en chats mostraban «Reporte enviado» inmediatamente, aunque los modelos ignoraban el resultado del repositorio. Los de publicaciones, comentarios y videos cerraban el formulario antes de saber si se había enviado. Se sustituyó ese contrato por una operación suspendida que devuelve el resultado real del envío. Todos los puntos de entrada compilados usan ahora ese contrato.

El formulario común conserva motivo, detalle y referencia de evidencia; bloquea el doble toque, muestra el envío pendiente y conserva los campos para reintentar tras un fallo. La confirmación depende del ACK; una cancelación o una respuesta de otra cuenta no produce un aviso de éxito. Tiene desplazamiento y espacio para el teclado; se evita cortar el título con letras grandes. Cancelar la selección de otra imagen conserva la evidencia anterior.

Las solicitudes de reporte obtienen credenciales ligadas al propietario y las renuevan antes de enviar después de preparar la evidencia. Se reconoció también el código real `VIDEO_ALREADY_REPORTED` del servidor; el caché de videos reportados se reinicia al cambiar de cuenta y no admite lecturas atrasadas de otra sesión.

Se corrigió otra pérdida posible: después de despachar el POST, una respuesta perdida podía provocar que el móvil borrase la evidencia de un reporte ya guardado. Ahora conserva esa evidencia si la escritura pudo haberse realizado. Esto exige contemplar la conciliación de archivos sin referencia cuando el resultado quedó incierto; no se afirma limpieza completa ni ausencia de duplicados tras perder el ACK de un POST sin idempotencia.

Comprobación: **480 pruebas seleccionadas, 102 clases, 0 fallos, errores u omisiones**, incluyendo siete regresiones del envío/confirmación, y compilación correcta de las pruebas Android de interfaz. Estas últimas se compilaron; **no se ejecutaron en dispositivo**. No se generó una APK nueva y la revisión integral continúa abierta, incluida la identidad de insignias, los cambios de cuenta y los módulos restantes.

## Quinto bloque: contenido sensible e idioma

`Post` y `Video` declaraban la marca de contenido sensible fuera del constructor de sus clases de datos. Por ello, `copy` podía perder la marca al reaccionar o compartir, y un cambio solo de clasificación podía no llegar a la pantalla mediante `StateFlow`. Se incorporó al constructor, manteniendo el nombre JSON y el valor predeterminado para cachés anteriores.

También se corrigió la sincronización de idioma. En Android 13 o posterior, la selección procede del sistema y la preferencia antigua se migra una sola vez, sin revertir un cambio posterior en Ajustes de Android. En versiones anteriores, volver a «Idioma del sistema» restablece también el idioma de los formatos. Se siguió la [documentación oficial de idiomas por aplicación](https://developer.android.com/guide/topics/resources/app-languages). Esto no traduce las cadenas que todavía están escritas directamente en español.

Se añadieron primero nueve regresiones: ocho fallaron con el código anterior por estos dos defectos; la lectura del JSON antiguo ya funcionaba. Después de corregirlos, pasaron **272 pruebas seleccionadas, 64 clases, sin fallos, errores ni omisiones**, incluyendo esas nueve, comentarios, reacciones, hidratación de autores y videos. La revisión de insignias y de los demás recorridos sigue abierta; todavía no se generó una APK nueva.

## Sexto bloque: identidad de administradores

Varias pantallas deducían una insignia ADMIN de nombres visibles o correos escritos en el cliente. Un nombre duplicado podía aparentar esa identidad; esto no concedía permisos de administración en el servidor. Se eliminó esa inferencia en chats, perfiles, listas, mensajes, videos y comentarios. Los controles consumen el rol o una insignia de catálogo autorizada.

El contrato de autores incorpora rol y verificación en videos/comentarios. El servidor consulta la moderación junto con el listado de perfiles, aplica la misma regla que la autorización y descarta los campos internos antes de responder. La información de rol procede de columnas de administración y de la identidad canónica, sin aceptar esos campos en una edición de perfil. También se conserva en las respuestas del perfil propio y sus actualizaciones. No se cambió el esquema de base de datos.

Comprobación local: **suite Android completa: 2.120 pruebas, 444 clases, 0 fallos, errores u omisiones**. Incluye cinco pruebas del contrato nativo de identidad y las regresiones anteriores. En el servidor: **965 casos/grupos, 951 pasados, 0 fallos y 14 omitidos** por requisitos de PostgreSQL real o fixtures Linux. Se comprobó que los metadatos de un video y los nombres visibles no sustituyen el rol de su autor.

**Despliegue pendiente de este bloque:** Tailscale exigió renovar SSH al contrastar el archivo activo con el candidato. No se ha reemplazado la imagen `storage100` ni se ha publicado una APK nueva. Es necesario comparar los archivos activos, conservar sus diferencias previas, validar el candidato en el servidor y entonces activarlo con posibilidad de reversión. Estos cambios locales y las pruebas no equivalen a esa activación ni al cierre de la auditoría móvil.

## Séptimo bloque: mensajes guardados

Actualizar guardados borraba la lista antes de confirmar la lectura. Una desconexión podía presentarse también como colección vacía. Ahora conserva el resultado confirmado durante una actualización y su fallo, distingue errores de lectura y de acciones y permite reintentar. La paginación bloquea solicitudes repetidas y elimina claves duplicadas antes de dibujarlas.

El modelo observa la cuenta, cancela su trabajo anterior y separa lista, búsqueda y filtros por propietario. La pantalla oculta de inmediato una instantánea ajena, incluso antes de que se ejecute el observador. Quitar un elemento conserva la fila hasta confirmar el cambio y rechaza una acción pendiente de otra cuenta. Una lectura anterior al ACK no puede resucitar el guardado; una lectura posterior sí puede mostrarlo si se guardó de nuevo desde otro dispositivo. El cambio de filtros durante esa operación conserva su consulta activa.

Los filtros se desplazan dentro de la lista para dejar accesible la búsqueda y aprovechar el espacio con teclado. Estas modificaciones de presentación siguen pendientes de comprobación visual. Se comprobaron **25 pruebas seleccionadas, 2 clases, sin fallos, errores u omisiones**: once regresiones de guardados y catorce de acceso a conversaciones. No se repitió todavía la suite completa tras este bloque.

SSH ya se renovó. La comparación del código activo identificó además ajustes y eliminación de salas de voz presentes en el repositorio pero ausentes de la imagen desplegada, incluido el endpoint usado para guardar su configuración. Se continúa con ese contrato de servidor y con la activación de insignias; no se declara cerrado el móvil ni se pasa todavía a web.

## Octavo bloque: contrato activado en el servidor

Se compararon los archivos de la imagen activa con el padre de la corrección de insignias. La diferencia de salas de voz era real: faltaban la ruta de actualización, el módulo de ajustes y el manejo de bajas de salas importadas. El candidato conserva la base `storage100` y añade únicamente los archivos de identidad y ese soporte de voz que ya estaba versionado en ambas ramas.

La nueva imagen es `novvin-api:audit101-mobile-20261101`, SHA-256 `dad88b6e0aa9621c5f27400689c627428435bfcc5dbfe18fe11a42a591c7f348`. Su código de estos archivos corresponde al commit `e0e7f78d`; se verificaron sus hashes dentro de la imagen y las dependencias resueltas antes de activarla. Las pruebas de identidad y voz se ejecutaron en un contenedor aislado sin red.

También se contrastaron contra PostgreSQL **13 perfiles: 4 administradores y 9 cuentas ordinarias**, comprobando que el rol público coincide con la autorización y que no se exponen campos internos. Una sala temporal se probó dentro de una transacción: rechazo de un no propietario, contraseña cifrada como hash, conservación de identidad/canal, cambio de nombre, conservación de contraseña y baja que no reaparece en el directorio. La transacción se revirtió y se comprobó que no quedó el registro temporal.

La activación pasó la comprobación local y pública de disponibilidad; la ruta de ajustes exige autenticación y responde 401 sin credenciales. No se cambió el esquema ni se enviaron mensajes a contactos. Se conservan la imagen anterior y el punto de reversión `/home/alvaro/novvin-candidates/audit101-mobile/backup`. El primer intento de SSH quedó sin ejecutar el proceso remoto; se verificó esa ausencia y se sustituyó únicamente esa conexión antes de validar y desplegar.

Esto cierra el despliegue pendiente de insignias y de ese contrato de voz. Todavía no se generó una APK nueva y siguen abiertas las comprobaciones visuales y el resto de módulos móviles; no es el cierre de producción de toda la aplicación.

## Noveno bloque: destinatarios y bloqueos

Los chats con uno mismo, los grupos o una conversación sin un interlocutor válido no deben mostrar acciones de reportar o bloquear a otro usuario. La lista y el chat resuelven ahora un único participante distinto del propietario; un registro incompleto o ajeno no produce un destinatario vacío ni uno elegido arbitrariamente. El reporte de la lista se reinicia al cambiar de cuenta.

El bloqueo desde la lista ignoraba su resultado. Ahora reutiliza el ejecutor de acciones existente, evita envíos repetidos, espera el ACK y comunica tanto éxito como fallo. Bloquear y desbloquear desde el chat también quedan ligados al propietario y al interlocutor que inició la operación. El repositorio captura primero al actor y obtiene credenciales para esa cuenta; las lecturas de relación y su caché rechazan respuestas ajenas.

Además, el sondeo de bloqueos del servidor convertía un fallo de lectura en una relación vacía. Eso podía dibujar un desbloqueo que nunca ocurrió. Solo publica estados confirmados; conserva el anterior durante un fallo y deja de emitir tras cambiar de cuenta. Se comprobaron **52 pruebas seleccionadas, 6 clases, sin fallos, errores u omisiones**, incluidas cinco regresiones nuevas de destinatario y sondeo. La primera compilación detectó un import incorrecto del modelo de relación; se corrigió antes de esta ejecución satisfactoria.

La lectura posterior de Ajustes encontró otra inferencia de administración por correo y un consumo incorrecto de un resultado booleano: se comprobaba que la consulta terminase, en vez de su valor. Son pendientes de la siguiente corrección, junto con los límites de sesión del cambio de cuenta. La fase móvil sigue abierta y no se ha pasado a la web.
