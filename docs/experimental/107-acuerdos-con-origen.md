# Acuerdos con origen. Primer recorrido experimental

Trabajo del 4 de noviembre de 2026, exclusivamente en `codex/experiencias-con-proposito`. Continúa la segunda prioridad del [plan 105](105-experiencias-con-proposito.md). La entrega estable conserva su estado en la [revisión 104](../oficial/104-camara-y-reacciones-android.md). La revisión integral sigue abierta.

**Estado vigente:** la recuperación local de la fuente `46ee09b5b711` tiene las comprobaciones del último apartado. La nueva validación PostgreSQL/HTTPS espera a que vuelva el servidor, apagado por un corte eléctrico. Los resultados anteriores de este documento conservan sus fuentes y no sustituyen esa comprobación pendiente.

## Alcance del primer recorrido

Dos personas de un chat privado pueden proponer un acuerdo desde un mensaje visible, consultar lo propuesto, confirmar, pedir cambios, retirar su confirmación y revisar el texto. La persona que propuso puede cancelar. Proponer conserva la autoría y deja las dos confirmaciones pendientes. Modificar el texto inicia otra revisión y reinicia ambas confirmaciones. La pantalla muestra quién respondió y permite consultar un fragmento de hasta 300 caracteres del mensaje de origen cuando siga disponible.

El servidor determina los dos participantes a partir de la conversación y sus identidades canónicas. Exige acceso vigente, ausencia de bloqueos y una solicitud de mensajes ya aceptada. Los chats de grupo, comunidades, invitaciones adicionales, IA, notificaciones push nuevas y el cliente web quedan para ampliaciones posteriores. Esta selección permite comprobar el consentimiento y la concurrencia antes de extenderlos a más participantes.

La API se activa explícitamente con `NOVVIN_EXPERIMENTAL_AGREEMENTS=true`. El valor predeterminado mantiene el experimento desactivado. El móvil consulta esa capacidad. Una cuenta sin material local oculta las entradas cuando el servidor no la ofrece. Si ya hay un borrador o un envío sin confirmar, permite recuperarlo localmente y exige verificar la capacidad antes de escribir en el servidor. La implementación de esta rama no implica activar el experimento en el servidor de producción.

## Diseño antes de implementar

La pantalla se organiza alrededor del texto acordado y las respuestas de las dos personas. Se conserva la tipografía de la cuenta, con título y lectura alineados a la izquierda y ancho máximo para pantallas grandes. Índigo `#535BC1` identifica acciones, menta `#B7E5DF` las confirmaciones, melocotón `#F8D1BB` las solicitudes de cambio y tinta `#263149` los textos sobre esos fondos. Fondo y superficies proceden de MaterialTheme. Las respuestas llevan texto además de color.

```text
Volver              Acuerdos             Actualizar

Guitarra el sábado
La propuesta aparece aquí con espacio para leerla.

[ Ana ]  Por confirmar
[ Tú  ]  Confirmaste esta revisión

[ Ver mensaje de origen ]
[ Confirmar ]       [ Pedir cambios ]

Revisión 2          Propuesto por Ana
[ Editar propuesta ]             [ Cancelar acuerdo ]
```

Crear o editar abre un editor breve. La lista no mantiene formularios abiertos. Los botones se ajustan al espacio y al tamaño de letra. Errores y envíos pendientes conservan el texto y ofrecen una acción concreta. La inspección del plan elimina indicadores decorativos y porcentajes de aprobación que podrían hacer parecer obligatorio el consentimiento. Se muestran las respuestas reales de ambas personas.

Se toman como referencias de organización los [ejemplos oficiales Compose, Reply](https://github.com/android/compose-samples/tree/main/Reply) y la [guía Android de tamaños adaptables](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes). La serialización de escrituras utiliza transacciones y bloqueos según la [documentación de PostgreSQL](https://www.postgresql.org/docs/current/explicit-locking.html). El módulo se escribe sobre los contratos existentes del proyecto.

## Contrato y aceptación

- Cada escritura incluye identificador de operación y revisión esperada. El servidor liga la clave al actor y al contenido. Repetir una operación confirmada devuelve el estado vigente sin aplicar dos veces su efecto. Reutilizar la clave con otro contenido se rechaza.
- Dos escrituras sobre la misma revisión no se pisan. Una gana y la otra recibe un conflicto para actualizar. Una respuesta pendiente no se atribuye a otra persona y una aceptación anterior no confirma un texto editado.
- Se comprueban nuevamente cuenta, conversación, participantes y permisos al leer y escribir. Cambiar de cuenta o de chat invalida las respuestas móviles anteriores.
- El origen se consulta bajo los permisos del lector. El acuerdo no guarda una copia automática del mensaje original. Los mensajes borrados o de una sola vista no sirven como origen de propuestas nuevas.
- El resultado aparece confirmado después del acuse del servidor. Una pérdida de respuesta mantiene el identificador de operación para reintentar. El borrador permanece ante fallo.
- Los eventos de tiempo real contienen referencias. El receptor vuelve a leer el estado bajo autorización. La pantalla también permite actualizar y relee al volver a primer plano.

Las pruebas se registran con sus fuentes, distinguiendo dominio, HTTP, PostgreSQL, interfaz y ejecución efectiva. El cifrado local del espacio privado no se presenta como prueba del cifrado de extremo a extremo de este nuevo contrato compartido.

## Implementación y primeras comprobaciones

El primer prototipo está en `4c84b2fcaa9c`. La API usa tres módulos separados para dominio, persistencia y HTTP. El chat Android tiene entradas desde su menú y desde un mensaje de texto visible. Se ocultan cuando el servidor no ofrece la capacidad. El origen se abre como fragmento autorizado consultado de nuevo. La edición conserva su revisión inicial y compara el texto actual con el borrador antes de aplicar cambios sobre una versión posterior.

La tanda de lógica aprobó **45 pruebas JVM en cinco clases**, catorce de Acuerdos y 31 de Rutas. El primer paquete instrumentado detectó un uso de `last()` que esta versión de la colección de nodos de Compose no proporciona. `3cf8682e` corrigió únicamente ese selector y la separación del servidor de pruebas respecto de su ensayo automático. La aplicación principal no cambió para resolver ese error del ensayo.

Los paquetes correspondientes aprobaron **40 pruebas de interfaz y navegación en 54,759 segundos** y una prueba conectada de **9,627 segundos**. Esta última recorrió la pantalla, el modelo Android, el cliente HTTPS y PostgreSQL para proponer, confirmar desde dos identidades, editar, volver a confirmar el estado pendiente, consultar el origen y cancelar. Utiliza cuentas ficticias y adaptadores de acceso del entorno de prueba. No es una prueba del inicio de sesión real ni de todo el chat de producción.

Se inspeccionaron las capturas clara y oscura. Se corrigieron después el contraste de los iconos de sistema de la ventana clara y la invalidación de una lectura de origen que terminase tras cerrar el módulo. Se añadió una regresión de esa lectura tardía. La ampliación de letra se comprobó también cambiando el ajuste real del sistema del emulador a 1,6 en una pantalla de 280 puntos, porque una densidad de composición exterior no representa necesariamente la de una ventana de diálogo.

El servidor pasó **13 pruebas sin fallos ni omisiones**, incluidas las operaciones HTTP y su transacción con PostgreSQL 16.15. El ensayo confirmó un solo registro al repetir una creación, la reutilización de la misma operación tras perder el acuse, el rechazo de otro contenido con esa clave, una confirmación anterior que no acepta la nueva revisión, un conflicto entre dos escrituras simultáneas y la ocultación de un origen eliminado. La base de pruebas carece de la columna opcional `username`; el adaptador conserva la compatibilidad con el esquema base.

## Entorno de prueba y límites actuales

Se crearon dos contenedores de QA, una red interna y una base `novvin_agreements_test` con datos ficticios. La API de prueba usa HTTPS y un certificado específico. Su acceso desde Android pasa por un túnel local y la prueba confía únicamente en ese certificado. No se deshabilita la verificación de nombre ni se modifica la seguridad de red de la aplicación. La imagen de producción se reutiliza como runtime para las dependencias de QA. Sus servicios activos no se reemplazan.

Los eventos de la prueba se capturan mediante un publicador ficticio. La pantalla está conectada al canal de usuario existente y tiene ensayos de filtrado por cuenta y conversación. La entrega de esos eventos entre dispositivos reales y la entrada completa desde un chat autenticado siguen pendientes. El contrato de la prueba no sustituye esas comprobaciones.

El primer prototipo conservaba el borrador y la operación de envío incierto en memoria. La continuación al final de este documento incorpora su recuperación cifrada y la limpieza al eliminar una cuenta, con comprobaciones propias. Antes de promover el experimento siguen pendientes la política de archivo y retención al alcanzar el límite de 50 y la evaluación con personas, además de los recorridos conectados que se indican en cada bloque. Los grupos, las comunidades, la adaptación web y los demás módulos del plan siguen pendientes. La activación en producción requiere la instrucción expresa del propietario.

## Comprobaciones del ajuste final

La fuente Android final de este bloque es `74fd855db3ec`. Ambos paquetes de depuración incorporan ese identificador y conservan la numeración **9.1.39-debug / 179**. El AAB firmado estable descrito en 104 corresponde a su código anterior y conserva su propia entrega. No se ha generado ni publicado una actualización de Google Play de este experimento.

| Comprobación | Resultado sobre el ajuste final |
| --- | --- |
| Acuerdos y regresiones de Rutas | 46 pruebas JVM en cinco clases, cero fallos, errores u omisiones |
| Interfaz y navegación Android 15 | 40 pruebas aprobadas en 54,136 segundos |
| Pantalla, modelo, cliente HTTPS y PostgreSQL de QA | Un recorrido aprobado en 9,137 segundos |
| Pantalla de 735 × 1920 píxeles a densidad 420 y fuente del sistema 1,6 | Una comprobación aprobada en 3,597 segundos |
| Regresiones del servidor, incluido el dominio de Acuerdos | 33 pruebas aprobadas, sin omisiones |
| PostgreSQL y HTTP, junto con el dominio de Acuerdos | 13 pruebas aprobadas, sin omisiones |
| Lint K1 | Completado en 9 minutos y 45 segundos: cero errores, cero errores fatales y 321 avisos |

Las dos selecciones del servidor comparten las doce pruebas del dominio. No se suman como si fueran casos diferentes. Sus logs y las capturas iniciales y finales se conservan por separado. La comprobación de fuente grande verificó el valor efectivo de `Configuration.fontScale` de Android. Se examinaron las capturas finales, incluido el contraste corregido de la barra clara y las acciones accesibles al desplazar con letra grande. El emulador recuperó su configuración anterior antes de apagarse. Lint mantuvo sus reglas y no señaló avisos en los archivos nuevos de Acuerdos; los 321 avisos del conjunto no se presentan como corregidos.

Las huellas SHA-256 de los paquetes ensayados son las siguientes.

```text
app-debug.apk              6290FEC8D047529F9FD95D4C1C8FEF4CE83C29812F4AC261CD59D7836943B8AC
app-debug-androidTest.apk   1AF8F4F364BDFF88891A89601CBF25322E84A1BA4EA213EABEC85F31648273E8
```

Los archivos de los tres módulos del servidor y sus dos ensayos tuvieron las mismas huellas en Windows y en la copia de QA en Ubuntu. Al terminar se detuvieron exclusivamente los dos contenedores de QA, tras comprobar sus nombres y etiquetas, y se cerró el túnel propio. Se conservaron sus archivos y evidencias. La API activa mantuvo `novvin-api:likes104-20261103_84ae4912` y respondió correctamente a `/health`. Este control de continuidad no activa el contrato nuevo en producción.

## Continuación. Recuperación local antes de ampliar el módulo

Se conservará un registro cifrado por cuenta y chat, excluido de las copias de Android, con el borrador y la operación exacta pendiente. La operación debe quedar confirmada en disco antes de iniciar su petición. Recuperarla no enviará ni aceptará nada automáticamente. El acuse de red retirará el registro pendiente únicamente después de confirmar su actualización local. Una revisión local impedirá que otra pantalla sobrescriba el borrador o elimine una operación posterior.

El editor indicará cuándo está guardando y cuándo el borrador quedó guardado. Una escritura fallida conservará el contenido visible y ofrecerá reintentar. Si otra pantalla cambió el registro, se exigirá una acción expresa antes de recuperar ese estado. Se comprobarán reapertura del registro, aislamiento de cuentas, fallos de almacenamiento, pérdida de acuse y recuperación con el almacén de claves de Android. Las siguientes comprobaciones se registrarán separadas de las del primer prototipo.

### Implementación de la recuperación y preparación sin Ubuntu

`b26604ba` incorpora el registro cifrado por cuenta y conversación. La fuente Android posterior de este bloque es `46ee09b5b711`. El registro guarda el texto elegido, su revisión y la operación exacta. Una revisión local y la guardia de autenticación impiden que otra pantalla o una sesión anterior sobrescriban ese material. Los registros vacíos conservan su revisión para evitar que una respuesta tardía resucite un borrador descartado. Un archivo ilegible conserva su contenido y comunica el fallo.

El guardado al escribir agrupa cambios durante 150 milisegundos y diferencia «Guardando» de «Borrador guardado». Esa confirmación corresponde al acuse de almacenamiento. Las últimas pulsaciones todavía sin confirmar pueden perderse si el sistema termina el proceso durante ese intervalo. Volver desde el editor guarda antes de cerrarlo. Descartar es una acción separada con confirmación. Una caída de Ubuntu permite seguir trabajando en un borrador recuperado; volver a conectar no envía nada automáticamente.

La operación se confirma en disco antes del POST. Si falta el acuse de red o falla la retirada local del pendiente, el reintento conserva su clave y contenido. Un 403 puede ocurrir después del commit del servidor, al comprobar nuevamente el acceso: se conserva la operación para conciliarla. Proponer desde otro mensaje mientras hay una operación pendiente abre su recuperación. Un origen no admitido produce una explicación visible.

La eliminación autenticada de una cuenta captura su propietario y programa la limpieza con WorkManager. La tarea no necesita red y elimina únicamente su material local de Acuerdos, conservando una marca que impide su restauración tardía. Utiliza el [trabajo persistente de Android](https://developer.android.com/develop/background-work/background-tasks/persistent). El commit local se completa sin sustituir su dispatcher al protegerlo frente a la cancelación, siguiendo el contrato de [`withContext`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html). El registro no acredita cifrado de extremo a extremo del contenido compartido.

La API incorpora la limpieza de los acuerdos y sus recibos en la transacción de borrado definitivo existente. Reconoce los alias canónicos y funciona también cuando el esquema experimental nunca se instaló. Las escrituras bloquean las filas de sus dos usuarios con `FOR KEY SHARE`, para que un borrado concurrente no deje aparecer un acuerdo después de eliminar al participante.

El propietario informó un corte eléctrico y el apagado del servidor durante esta continuación. Las nuevas comprobaciones con PostgreSQL, el recorrido HTTPS y la disponibilidad actual de producción quedan pendientes de su regreso. Las pruebas anteriores de 107 corresponden a su fuente y momento. No se reinició ni desplegó la API de producción en este bloque.

Las dos compilaciones intermedias aprobaron 69 y 73 pruebas JVM seleccionadas, respectivamente. Se conservan sus logs y XML por separado; sus cifras no sustituyen la comprobación final de `46ee09b5b711`. El ensayo preparado para emulador separa interfaz, almacén de claves, limpieza sin red y dos ejecuciones con cierre real del proceso entre ellas. El transporte de esas dos fases se simula para poder verificar la persistencia con Ubuntu apagado.

### Comprobación local de `46ee09b5b711`

Ambos APK de depuración incorporan esa identidad. La ejecución conjunta de pruebas y compilación terminó correctamente en cinco minutos y tres segundos. Conservan **9.1.39-debug / 179**. El AAB estable 179 mantiene su código anterior. Este bloque no genera una entrega firmada de producción.

| Comprobación | Resultado |
| --- | --- |
| Lógica de Acuerdos, registro, Rutas y avisos de cuenta eliminada | 75 pruebas JVM en siete clases, cero fallos, errores u omisiones |
| Interfaz y navegación | 44 pruebas aprobadas, 53,843 segundos |
| Almacén de claves, aislamiento y limpieza mediante WorkManager con Wi-Fi y datos desactivados | Dos pruebas aprobadas, 0,719 segundos |
| Preparación del envío pendiente antes del cierre de proceso | Una prueba aprobada, 0,366 segundos |
| Recuperación en otro proceso y reintento explícito con la misma operación | Una prueba aprobada, 0,572 segundos |
| Repetición de dos recorridos con 735 × 1920 píxeles, densidad 420 y escala de texto 1,6 | Dos comprobaciones aprobadas, 4,312 segundos |
| Regresiones Node del módulo, limpieza, marcadores y política de mensajes | 36 pruebas aprobadas, sin omisiones |
| Suite local de administración, incluido el borrado experimental | Aprobada; el publicador se sustituye en el ensayo para evitar eventos de identidades ficticias fuera de él |
| Lint K1 | Completado en diez minutos y 43 segundos: cero errores, cero errores fatales y 326 avisos |
| Nueva ejecución PostgreSQL y HTTPS | Pendiente: servidor apagado |

El cierre explícito se realizó entre las dos fases de instrumentación: el proceso cambió de PID `2500` a `2561`. Se recuperó el registro cifrado y no se envió nada hasta solicitar el reintento. La prueba usa un transporte simulado, por lo que no se presenta como repetición del recorrido HTTPS/PostgreSQL. Las dos comprobaciones de letra grande repiten recorridos de la selección de interfaz y no se suman como casos distintos. Se abrieron y examinaron ambas capturas del editor. Los ajustes del emulador se restauraron antes de apagarlo.

Lint conserva sus reglas. Cinco avisos nuevos son sugerencias `ApplySharedPref` y `UseKtx` en el registro y la limpieza local. Estas operaciones necesitan comprobar el resultado de [`SharedPreferences.Editor.commit()`](https://developer.android.com/reference/android/content/SharedPreferences.Editor#commit()) antes de confirmar el guardado o iniciar el POST. Se ejecutan en E/S. Sustituirlas por el guardado asíncrono recomendado de forma general eliminaría ese acuse. Los 321 avisos anteriores continúan sin resolverse y no se presentan como corregidos.

```text
app-debug.apk              2830D63C6FC090F41CDF6F336F9B8DB981479F354D4B59C89461A0879A854BD2
app-debug-androidTest.apk   94B14A802C9FDEB5768A50BEF10BF1F44ED150D2422D5459537EFC372708122D
```

### Preparación para reanudar con Ubuntu encendido

El archivo local `agreements108-qa-46ee09b5b711.tar` contiene siete fuentes y ensayos de Git, su manifiesto, las sumas de comprobación, instrucciones y un ejecutor de QA. Su SHA-256 es `F10B7B5954A9E938EAC851D2086401B723D51706A19C474C3DFEB0D94B3B2CB6`. Se verificaron las huellas y la sintaxis del ejecutor con Git Bash. No incluye archivos de entorno, claves SSH, claves TLS privadas ni credenciales de cuentas reales.

Para reanudar, se copia a un directorio nuevo bajo `/home/alvaro/novvin-candidates/agreements108/46ee09b5b711`, se extrae allí y se ejecuta `bash run-isolated-qa.sh`. El ejecutor verifica las fuentes y crea su propia red interna y base temporal `novvin_agreements_test`, sin puertos públicos ni montajes de producción. Reutiliza únicamente las dependencias de la imagen histórica. Comprueba nombres y etiquetas y detiene su base al terminar; conserva contenedor, red y logs para inspección. Una instalación anterior con esos nombres exige revisarla antes de repetir.

El ensayo pendiente incorpora rollback de la limpieza, cascada de recibos y la carrera entre una propuesta y el borrado de un participante. No se ha ejecutado mientras Ubuntu permanece apagado. El nuevo recorrido Android por HTTPS requiere además preparar el servidor de QA y un certificado de prueba vigente. Siguen pendientes la entrada completa desde un chat autenticado, la entrega real de eventos entre clientes, archivo y retención, la ampliación a grupos y web y la evaluación de producto. La revisión móvil integral continúa abierta.
