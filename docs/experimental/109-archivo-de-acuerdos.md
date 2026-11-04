# Archivo del chat y páginas acotadas

Continuación del 4 de noviembre de 2026, sólo en `codex/experiencias-con-proposito`. Parte de la [recuperación local comprobada en 107](107-acuerdos-con-origen.md). La revisión móvil integral y el plan 105 siguen abiertos. El último intento de conexión a Ubuntu no respondió; sus comprobaciones se mantienen separadas.

## Recorrido y contrato antes de implementar

El límite de 50 se aplicará a los acuerdos activos del chat. Archivar libera un lugar y conserva el texto, su origen y las respuestas. El archivo pertenece a la conversación: cualquiera de sus dos participantes puede mover un acuerdo, con una confirmación que indica su efecto para ambos. Restaurarlo respeta el mismo límite. Ninguna de estas acciones acepta, cancela ni cambia el texto de una propuesta. Se conserva la posibilidad de retirar una confirmación y cancelar una propuesta propia archivada.

El archivo no tendrá eliminación automática. Su conservación termina mediante el flujo existente de eliminación de una cuenta participante. Esta decisión del prototipo mantiene disponibles los acuerdos elegidos para consulta y evita borrar datos por el simple hecho de archivarlos. La capacidad operativa del almacenamiento y una futura política de eliminación individual requieren su evaluación antes de promoverlo.

Las lecturas tendrán páginas acotadas y avance en las dos direcciones. La posición combina fecha de creación e identificador, conserva los microsegundos del servidor y está ligada al chat y al filtro. No se acumularán todas las páginas en memoria. La comparación sigue el [orden de filas de PostgreSQL](https://www.postgresql.org/docs/current/functions-comparisons.html#ROW-WISE-COMPARISON), con un [orden único para los límites](https://www.postgresql.org/docs/current/queries-limit.html).

Crear, archivar y restaurar compartirán la transacción y el bloqueo de conversación existentes. Los reintentos conservarán clave y contenido y devolverán el estado vigente sin volver a aplicar una acción antigua. Se revisará también una respuesta 404 de una API retirada: no demuestra por sí sola que una operación anterior no se haya guardado.

El borrador tendrá una entrada propia en la lista para poder consultar acuerdos sin descartarlo. Abrirlo permitirá continuar. Una respuesta sobre otro acuerdo conservará ese borrador. El editor consultará el acuerdo que está revisando aunque se haya movido a otra página o al archivo. Se descartarán respuestas antiguas al cambiar de filtro, página, conversación o cuenta.

## Diseño y revisión del recorrido

Se conservan las fuentes y el tema elegidos por la cuenta. El título del acuerdo usa `titleLarge`, las respuestas `titleSmall` y los metadatos `bodySmall`. Índigo `#535BC1` marca la acción, menta `#B7E5DF` la confirmación, melocotón `#F8D1BB` lo pendiente, azul claro `#D2DFFC` el contexto y tinta `#263149` el texto sobre acentos. Las superficies generales proceden de MaterialTheme.

```text
Volver              Acuerdos             Actualizar

[ Activos ] [ Archivo ]

Tu borrador                     [Continuar]
El texto elegido para retomar…

Lo que decidimos / Archivo del chat
Texto de la propuesta
Ana confirmó / Tú por confirmar
[ Ver mensaje de origen ]
[ Confirmar ] [ Pedir cambios ]
[ Archivar en el chat ]

[ Más recientes ] [ Más antiguos ]
```

El contenido se alinea a la izquierda, con ancho máximo y acciones que se ajustan al tamaño de letra. El archivo conserva las tarjetas de propuestas y sus respuestas; no añade formularios. El texto del límite indicará cómo liberar un lugar. El movimiento mostrará su confirmación después del acuse de servidor y del registro local. Una lectura fallida conservará únicamente la página que corresponda al filtro seleccionado.

La revisión del plan elimina contadores inventados de páginas o totales. Los controles describen hacia dónde se avanza y aparecen sólo cuando hay una posición válida. La capacidad se negocia con el servidor para conservar el uso básico frente a una API anterior. Las comprobaciones de lógica, HTTP/PostgreSQL, emulador y despliegue se registrarán por separado.

## Implementación y comprobaciones locales

`878daae6` incorpora el archivo, el índice por filtro y posición y el recorrido Android. Las páginas usan 25 elementos en Android y admiten hasta 50 para conservar el contrato anterior. El editor consulta su acuerdo por separado cuando quedó fuera de esa página. Se conserva una página por filtro y se descartan ambas al cambiar de cuenta o perder el acceso. La lectura no almacena en el acuerdo una copia adicional del mensaje original.

La creación y la restauración comparten el bloqueo de chat con el resto de escrituras. Si dos restauraciones compiten por el último lugar, una recibe un conflicto; la transacción fallida no consume su clave. Repetir una acción ya registrada devuelve el estado actual. Archivar o restaurar no reinicia las respuestas ni permite reabrir una propuesta cancelada. Se puede retirar una confirmación sobre un acuerdo archivado.

Un borrador puede guardarse y volver a la lista sin descartarlo. Las respuestas sobre otro acuerdo conservan ese material. La operación pendiente de archivo o restauración usa el mismo diario cifrado y la misma conciliación explícita que las propuestas. Un 404 sin código de dominio puede ser una ruta retirada después del commit: mantiene la clave y exige volver a comprobar la capacidad. El prototipo no se presenta como cifrado de extremo a extremo de los acuerdos compartidos.

La fuente Android posterior `89d72ef9f5e6` mantiene bloqueada una edición cuyo acuerdo ya se confirmó ausente aunque falle la siguiente lectura. La pérdida de todos los elementos situados después de un cursor vuelve a la primera página y lo comunica. La primera selección de 88 pruebas detectó una aserción del fake que exigía mantener un foco después de guardar y cerrar el editor. Se corrigió ese fake y se añadieron dos regresiones del estado del editor y del reinicio de página. Sus resultados se conservan separados de la nueva selección.

La selección final de lógica de esta fuente aprobó **90 pruebas JVM en siete clases**, sin fallos, errores ni omisiones. Incluye 38 del modelo de Acuerdos, seis de su contrato, siete del diario, 31 de Rutas y ocho de avisos de cuenta eliminada. Los resultados nativos se registran por separado a continuación.

La compilación conjunta de los dos APK terminó en seis minutos y 27 segundos. La primera selección nativa heredó una pantalla de 735 × 1920 píxeles y texto a escala 1,6. Cuatro selectores exigían que un elemento estuviera compuesto o visible antes de desplazar hasta él. `c1c34352` corrige únicamente los ensayos: busca los elementos de la lista diferida antes de accionarlos y desplaza hasta el segundo participante. La repetición fija una pantalla normal y separa la comprobación estrecha con texto grande. La aplicación principal sigue siendo la fuente `89d72ef9f5e6`; sus archivos y su APK no cambiaron al recompilar los ensayos.

El servidor del prototipo aprobó **22 pruebas** sin omisiones: catorce de dominio/acceso, tres de posiciones, tres de limpieza y dos recorridos HTTP/PostgreSQL. La prueba de páginas siembra 50 acuerdos con la misma fecha de creación a precisión de microsegundos y verifica orden, ida y vuelta, límite, liberación de espacio, claves repetidas, consulta del acuerdo fuera de página, aislamiento entre chats y restauraciones simultáneas. Otra selección de políticas multimedia y marcadores aprobó 42 pruebas; comparte las tres de limpieza y no se suma como 42 casos adicionales diferentes.

También se ejecutaron las 16 pruebas del paquete anterior congelado `46ee09b5b711`, que incluyen rollback del borrado, cascada de recibos y competencia con la eliminación de un participante. Su resultado queda ligado a esa fuente anterior. Ambas tandas usaron PostgreSQL **16.9 en Windows**, en una base nueva `novvin_agreements_test` que sólo escucha en `127.0.0.1:18109`. No se modificaron los servicios ni las bases existentes. Esto resuelve la comprobación local de esas consultas; no prueba disponibilidad de Ubuntu ni sustituye su repetición con PostgreSQL 16.15.

Se contrastaron también los identificadores de cuenta: el propietario capturado por Android y `userIdentity` en Ubuntu eligen el mismo identificador canónico, incluido el alias conservado de las importaciones. Esa lectura de funciones concretas no acredita la revisión completa de `AuthService` ni de `data-routes.js`, que mantienen cobertura parcial.

## Ensayos Android observados

La aplicación principal corresponde a `89d72ef9f5e6`. La instrumentación final corresponde a `c1c343523fa4`, que sólo modifica selectores de prueba. Conservan **9.1.39-debug / 179**. Se comprobó que no hay diferencias de código principal entre ambas fuentes y que la recompilación de la instrumentación conserva el APK principal. Esta numeración de depuración no constituye una nueva entrega firmada ni actualiza el AAB estable.

| Comprobación | Resultado |
| --- | --- |
| Lógica de Acuerdos, diario, Rutas y avisos de cuenta | 90 pruebas JVM en siete clases; cero fallos, errores u omisiones |
| Interfaz y navegación en Android 15 | 49 pruebas aprobadas en 60,319 segundos |
| Pantalla, modelo, cliente HTTPS y PostgreSQL local | Un recorrido aprobado en 10,803 segundos |
| Keystore, aislamiento y limpieza WorkManager sin Wi-Fi ni datos | Dos pruebas aprobadas en 0,758 segundos |
| Preparación antes del cierre real del proceso | Una prueba aprobada en 0,389 segundos |
| Recuperación en otro proceso y reintento explícito | Una prueba aprobada en 0,536 segundos |
| Tres recorridos repetidos con 735 × 1920 píxeles, densidad 420 y fuente del sistema 1,6 | Tres comprobaciones aprobadas en 6,264 segundos |
| Lint K1 | Completado en siete minutos y 23 segundos; cero errores, cero errores fatales y 326 avisos |

El recorrido HTTPS propuso, confirmó desde dos identidades, editó y comprobó el reinicio de respuestas, consultó el origen, archivó, restauró y canceló. Usa HTTPS real con un certificado de QA específico, la API y SQL reales del módulo y adaptadores de identidad/permisos ficticios. La prueba no cambia la confianza TLS de producción ni omite la verificación de nombre. No equivale al inicio de sesión ni al acceso desde el chat real.

La prueba de recuperación cambió del PID `2773` al `2832` mediante cierre explícito. El almacén cifrado y la sustitución del proceso son reales; la pérdida de acuse se simula en ese ensayo. No se envió nada hasta pedir el reintento. Los tres recorridos con letra grande repiten casos de interfaz y no se cuentan como casos únicos adicionales.

Lint mantuvo las mismas reglas y el número de avisos del bloque anterior. Los avisos de acuse síncrono del registro cifrado conservan la justificación de 107. Los 326 avisos no se presentan como corregidos. El registro local `agreements109-evidence.json` reúne identidades, logs, huellas de APK, capturas y XML de Lint; las compilaciones y selecciones fallidas se conservan en directorios separados.

Se examinaron cinco capturas de la lista con borrador, archivo oscuro, acciones y editor. Las acciones se ajustan y se alcanzan al desplazar con texto grande. Se conservaron las configuraciones originales del emulador, se fijó una pantalla normal para la selección principal y se restauraron tamaño, densidad y fuente antes de apagarlo. Se detuvieron únicamente HTTPS y PostgreSQL propios, después de verificar PID, fecha de creación, ejecutable y alcance. Se conservaron datos, claves de QA y logs. No se operó el teléfono físico ni los servicios PostgreSQL instalados.

```text
app-debug.apk              9293E5FDD1A9A3BE4FD104AED0777A5E1F8469C465C6E79469B081E4056AB1D9
app-debug-androidTest.apk   8CF0B5F1EE5474DF7A49699E73BE74D4F286B55C40B2F98F0CD7DEA060F6AB0C
```

## Preparación para Ubuntu y límites

El paquete local `agreements109-qa-89d72ef9f5e6.tar` contiene nueve fuentes y ensayos de Git y cuatro archivos de manifiesto/ejecución. Su SHA-256 es `A15B703DDF198B7F23D1155F0DD6458DE08DF2E19D1C5062B2977991AD10C0E5`. Se compararon las nueve fuentes con Git y se comprobó la sintaxis Bash. No incluye archivos de entorno, claves SSH, claves TLS privadas ni credenciales de usuarios reales.

Para repetir cuando Ubuntu vuelva, extraerlo en un directorio nuevo bajo `/home/alvaro/novvin-candidates/agreements109/89d72ef9f5e6` y ejecutar `bash run-isolated-qa.sh`. El ejecutor verifica las huellas, comprueba que sus nombres estén libres y crea su propia red interna y base temporal con etiquetas de QA. Usa las dependencias de la imagen histórica sin reemplazarla, publicar puertos ni montar datos de producción. Detiene su base al terminar y conserva contenedor, red y logs. Esta preparación no es un despliegue efectivo.

La opción experimental de servidor permanece desactivada por defecto. Ante una reversión se mantiene apagada y se conserva el esquema y los datos para revisar la compatibilidad; no se borran acuerdos ni columnas automáticamente. El AAB estable 179 no incorpora estos cambios.

Siguen pendientes el acceso completo desde un chat realmente autenticado, la entrega de eventos entre clientes, las pruebas físicas, grupos/comunidades y la contraparte web. Antes de promover se evaluarán la capacidad de retención y eliminación individual y el uso con personas. Rutas conserva sus pendientes propios y los demás módulos del plan 105 siguen abiertos. La revisión integral móvil no queda cerrada por esta tanda.
