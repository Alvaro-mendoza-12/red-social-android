# Acuerdos con origen. Primer recorrido experimental

Trabajo del 4 de noviembre de 2026, exclusivamente en `codex/experiencias-con-proposito`. Continúa la segunda prioridad del [plan 105](105-experiencias-con-proposito.md). La entrega estable conserva su estado en la [revisión 104](../oficial/104-camara-y-reacciones-android.md). La revisión integral sigue abierta.

## Alcance del primer recorrido

Dos personas de un chat privado pueden proponer un acuerdo desde un mensaje visible, consultar lo propuesto, confirmar, pedir cambios, retirar su confirmación y revisar el texto. La persona que propuso puede cancelar. Proponer conserva la autoría y deja las dos confirmaciones pendientes. Modificar el texto inicia otra revisión y reinicia ambas confirmaciones. La pantalla muestra quién respondió y permite volver al mensaje de origen cuando siga disponible.

El servidor determina los dos participantes a partir de la conversación y sus identidades canónicas. Exige acceso vigente, ausencia de bloqueos y una solicitud de mensajes ya aceptada. Los chats de grupo, comunidades, invitaciones adicionales, IA, notificaciones push nuevas y el cliente web quedan para ampliaciones posteriores. Esta selección permite comprobar el consentimiento y la concurrencia antes de extenderlos a más participantes.

La API se activa explícitamente con `NOVVIN_EXPERIMENTAL_AGREEMENTS=true`. El valor predeterminado mantiene el experimento desactivado. El móvil consulta esa capacidad y oculta las entradas cuando el servidor no la ofrece. La implementación de esta rama no implica activar el experimento en el servidor de producción.

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

El borrador y la operación de envío incierto permanecen en el modelo mientras siga vivo el proceso. Antes de promover este experimento falta conservarlos de forma segura tras muerte del proceso, integrar la retirada de sus datos en la eliminación de cuentas, definir archivo y retención al alcanzar el límite de 50 y validar su utilidad con personas. Los grupos, las comunidades, la adaptación web y los demás módulos del plan siguen pendientes. La activación en producción requiere la instrucción expresa del propietario.

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
