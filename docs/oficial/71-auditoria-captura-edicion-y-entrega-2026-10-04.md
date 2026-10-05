# Auditoría de captura, edición y entrega de fotos y vídeos

**Fecha:** 4 de octubre de 2026. **Tipo:** revisión de código y diseño de verificación integrada.

Este informe no acredita un envío integrado que todavía no se haya ejecutado. Complementa [medios, arranque y avisos](70-medios-arranque-y-avisos-validacion-2026-10-04.md) y su evidencia de servidor. Las pruebas anteriores de cámara y de HTTP cubren etapas diferentes.

**Actualización vigente:** el apartado final «Cierre posterior del recorrido integrado de cámara» registra seis entregas ejecutadas y correctas en tablet. La revisión y los pendientes anteriores se conservan como historial; su alcance no certifica toda la aplicación para producción.

## Fuente examinada

Se revisó el árbol de trabajo compartido sobre `14b08e55adcc0ff1ebef3464a6913f1e072cc7f0`; contiene cambios de producto sin confirmar. Identidad de los dos archivos al revisar:

| Archivo | SHA-256 |
| --- | --- |
| `MessageMediaComposer.kt` | `7c3d832acd1b5c99e1728bb360efe7d4b2f24e082ff7b9833e338d097f7469d1` |
| `ChatCameraCapture.kt` | `c4e703f78121a164bc7a9f7a86594eb6d576abd46814239ae9108befe959a473` |

Los hallazgos originales corresponden a esas fuentes. La actualización de código revisada después se identifica en la sección final; su ejecución de regresión sigue pendiente.

## Hallazgos por prioridad

### Alta: una edición fallida puede enviar el original

En la fuente original de `MessageMediaComposer.kt`, la preparación de imagen usaba `runCatching { processComposerImage(...) }.getOrElse { item.uri }`. Además, `processComposerImage` devolvía la URI original si no conseguía decodificarla. El envío continuaba sin comunicar que se perdió la edición. **Estado actualizado: corrección implementada y revisada; regresión pendiente de ejecución.**

Un recorte o una cobertura dibujada puede ocultar información privada. Enviar silenciosamente el original incumple la intención del usuario. La escritura JPEG tampoco comprueba el booleano de `Bitmap.compress`.

**Corrección requerida:** detener el envío cuando no se pueda preparar la imagen; mantener selección, comentario y editor; mostrar un error con reintento; propagar la cancelación de la coroutine; eliminar un archivo de salida parcial. La salida debe ser válida y no vacía antes de iniciar la subida.

**Regresión requerida:** editar una imagen y simular una fuente ilegible o un error de salida. El callback de envío debe permanecer en cero; el editor debe conservar el comentario y permitir reintentar. Un caso válido debe demostrar la edición en la imagen que recibe la cuenta de prueba.

### Media: el botón HD no tiene una acción

La herramienta de vídeo original `RoundToolButton(Icons.Default.Hd, "HD") {}` tenía un callback vacío. Pulsarla no cambiaba calidad ni exportación. **Estado actualizado: acción retirada del código revisado; compilación y prueba pendientes.**

**Corrección requerida:** implementar y comprobar la calidad seleccionada, o retirar la acción hasta que exista. La captura HD de CameraX no demuestra que ese botón funcione.

### Brecha de validación: aceptar el envío no demuestra recepción

En grupo y comunidad, el repositorio puede devolver `Result.success(Unit)` después de dejar un medio pendiente para reintentar. Una prueba que solo compruebe ese resultado puede pasar aunque no exista un mensaje recibido.

**Criterio:** comprobar identidad del mensaje, objeto remoto válido y lectura desde la cuenta receptora; medir por separado la cola local y la entrega confirmada.

## Recorrido auténtico

| Etapa | Implementación |
| --- | --- |
| Captura | `ChatCameraCapture`, foto finalizada o vídeo finalizado; URI propia de FileProvider |
| Editor | `MessageMediaComposer`; imagen procesada o vídeo exportado antes de `onSend` |
| Chat privado | `ChatScreen` → `ChatViewModel.sendMediaBatch` → `MessageRepository.sendMessage` |
| Grupo | `GroupChatScreen` → `GroupChatViewModel.sendMediaMessage` → `GroupRepository.sendGroupMediaMessage` |
| Comunidad | `CommunityChatScreen` → `CommunityChatViewModel.sendMediaMessage` → `CommunityRepository.sendCommunityMediaMessage` |
| Entrega | Evento del destinatario y consulta autenticada del mismo mensaje; decodificación del objeto recibido |

No debe sustituirse el callback de las pantallas por HTTP para afirmar que se probó este recorrido completo. Un test del compositor conectado directamente al repositorio acredita esa integración parcial y excluye el cableado de pantalla y ViewModel.

## Matriz de ejecución en emulador

Se requieren seis casos: foto y vídeo en cada uno de los tres ámbitos. Deben utilizar dos cuentas QA propias, contextos privados y un identificador de ejecución único.

1. Comprobar antes de cualquier escritura que la sesión activa corresponde a una de las cuentas QA suministradas. Usarla como remitente y la otra como destinatario.
2. Crear únicamente los grupos y comunidades privados de la ejecución. Un chat privado QA ya existente puede reutilizarse; conservar sus mensajes previos.
3. Abrir la suscripción autenticada del destinatario y esperar su confirmación antes de enviar.
4. Abrir las pantallas reales en la actividad existente, capturar, editar y añadir un comentario único. En chat privado, la ruta recibe el ID del **usuario destinatario**, no el ID de conversación.
5. Confirmar que no se envía antes de pulsar Enviar. Comprobar la edición de foto o el recorte de vídeo, no solamente la existencia del archivo.
6. Correlacionar remitente, contexto, tipo, comentario e ID entre mensaje local, evento del destinatario y lectura autenticada del servidor. Descargar y decodificar el objeto remoto recibido.
7. Limpiar solo mensajes, objetos y espacios creados por la prueba. Comprobar que la sesión activa conserva su usuario.

### Límite de sesión

Los repositorios reales utilizan el `AuthService` compartido y no aceptan una sesión alternativa por llamada. Si el emulador tiene una cuenta humana, esta prueba no puede enviar mediante ellos con un token QA conservando esa sesión. No se debe cambiar la sesión, modificar preferencias privadas o autenticar por reflexión para ocultar ese límite.

Con una sesión distinta, una prueba HTTP con token QA sigue siendo útil, pero se registra como transporte. En un único paquete, un receptor verificado mediante WebSocket y REST tampoco equivale a una segunda interfaz autenticada probada en otro dispositivo.

## Limpieza y registro

Registrar en un archivo privado los IDs propios y objetos en cuanto se creen. Eliminar medios y miniaturas antes de borrar las salas, porque sus permisos pueden depender de la membresía. Limitar cada eliminación al bucket permitido y al contexto conocido de la ejecución.

`MessageRepository.deleteMessage` puede encolar la eliminación, y `deleteChat` oculta la conversación: ninguna de esas operaciones demuestra por sí sola una eliminación remota inmediata. No eliminar un chat reutilizado, cuentas, tablas completas ni cachés completas. Ante una limpieza incompleta, conservar el registro privado y reportar el fallo.

Cada resultado debe incluir: captura válida, edición aplicada, envío después de confirmar, objeto subido, mensaje persistido, evento recibido, lectura del destinatario, archivo decodificado, comentario conservado, limpieza completa, sesión preservada y tiempos por etapa. No incluir tokens o contraseñas en el informe público.

## Corrección implementada: revisión posterior

La IA responsable de mensajes implementó los cambios. La revisión independiente de código examinó:

| Archivo corregido o nuevo | SHA-256 |
| --- | --- |
| `MessageMediaComposer.kt` | `300bd3f10d439aaf44634ab2e54e1608917fcc738bb24e5444eae378fdba1b26` |
| `MessageMediaPreparationTest.kt` | `67320cadc2c12dd3e66d421266bb0fcded5e3aa35a7b86cc51b8a01170dab121` |

La preparación ya no retorna silenciosamente el original al fallar. Toma una instantánea de selección, ediciones y comentario, detiene el lote completo ante un error y mantiene el editor con un mensaje visible. Propaga la cancelación; registra los archivos preparados para eliminar un lote interrumpido; comprueba la compresión y longitud JPEG y recicla los bitmaps propios. La exportación de vídeo comprueba que el archivo de salida exista y no esté vacío. Un GIF editado se convierte en foto fija con un aviso en la interfaz. El botón HD sin acción fue retirado.

Se añadieron tres pruebas del compositor:

1. Imagen ilegible con giro: ausencia de envío, comentario conservado y posibilidad de reintentar.
2. Imagen válida girada: JPEG decodificado con dimensiones intercambiadas y comentario enviado después de confirmar.
3. Segundo archivo inválido: ningún envío del lote y eliminación de la salida preparada del primero.

**Estado:** código revisado; compilación y ejecución de estos tres casos todavía pendientes al actualizar este documento. Estas pruebas usan archivos propios y un callback local; no invocan subida o recepción de otra cuenta y no sustituyen los seis casos integrados.

### Seguimiento: cambios durante la preparación

La revisión posterior detectó que el tipo de salida todavía lee `viewOnceEnabled` después de empezar a preparar el medio, mientras la herramienta «Ver una vez» y algunas acciones de edición permanecen activas. Cambiar esa opción durante una exportación puede alterar el tipo después de confirmar. Cambiar una edición puede mostrar un resultado distinto del que tomó la instantánea.

Se comunicó la necesidad de capturar también la opción «Ver una vez» y bloquear cambios de edición, selección y navegación durante la preparación o el envío. Una revisión posterior comprobó `viewOnceSnapshot`, las protecciones de callbacks y la desactivación de herramientas y pager en el código. Se añadieron dos pruebas: controles con `isSending=true` y conservación del tipo `view_once_image` al preparar la foto. El primer caso comprueba un estado externo estático; no reproduce una pulsación durante una exportación suspendida.

La fuente de esta revisión posterior de `MessageMediaComposer.kt` es `803ec539c5edc6e668f117ecfe34f573be365a5f7ee7c185d06e3ac7d5d99570`; la de los cinco casos de `MessageMediaPreparationTest.kt` es `97ae20a1b8b1e258956c2cb14a5065719d68a6bb754d47426cb456cd7913946b`. La otra IA comunicó que los tres casos anteriores y cuatro pruebas de enlaces pasaron. Este informe no dispone todavía de la ejecución de los dos casos nuevos.

La revisión señaló dos posibles discrepancias entre las aserciones nuevas y la interfaz: el campo de comentario conserva semántica editable aunque su callback ignore cambios, y el icono con descripción «Enviar» se sustituye por un indicador durante el envío. Se necesita desactivación y semántica accesible estable, o una aserción que compruebe el componente que realmente existe. Son observaciones de código; no se registran como fallos ejecutados.

## Alta: la vista previa y el JPEG no muestran las mismas ediciones

La revisión independiente de la fuente `803ec539…` confirma las siguientes diferencias. Todavía no se ha ejecutado una prueba por píxeles de estos casos.

| Edición | Vista previa | Exportación | Consecuencia |
| --- | --- | --- | --- |
| Giro | `AsyncImage` muestra la URI original con `ContentScale.Fit`; no usa `rotateQuarterTurns` | `processComposerImage` rota el bitmap | El usuario no ve el giro que recibirá el destinatario |
| Dibujo | Coordenadas y Canvas de todo el viewport, incluidas las bandas negras | Normalización contra `previewSize` completo | El trazo cambia de posición y los dibujos fuera de la foto pueden aparecer dentro del JPEG |
| Texto o emoji | Texto centrado en el viewport, en negrita y con fondo oscuro | `drawText` con origen en 8 % del ancho y línea base en 18 % del alto, sin esa caja | El texto enviado tapa otra zona y cambia su presentación |
| Recorte combinado | El diálogo recorta la URI sin aplicar el giro del editor; después conserva trazos y otros datos previos | Rota la URI recortada y vuelve a normalizar los trazos | La combinación de recorte y ediciones carece de una geometría compartida |

El recorte aislado sí genera una URI recortada que utiliza la vista previa; no se afirma que todo recorte falle. El problema adicional es la composición con giro y anotaciones previas. El grosor del dibujo también difiere: 6 píxeles en pantalla frente a `max(6, ancho * 0.008)` en el bitmap, sin una conversión común de escala.

### Ejemplo verificable del desplazamiento

Una fotografía de 320 × 180 en un viewport de 480 × 960 se representa con `Fit` en un rectángulo de 480 × 270, comenzando en `y=345`. Un trazo situado en `y=399` está al 20 % de la altura de la foto y debería ocupar aproximadamente `y=36` en el JPEG. La fórmula actual lo coloca en `399 / 960 * 180 = 74,81`. El caso permite detectar el error con una fotografía uniforme y una línea de color contrastante.

### Regresiones mínimas requeridas

1. **Giro:** PNG asimétrico con cuatro cuadrantes de colores. Después de Girar, comprobar aspecto y orden de muestras interiores tanto en la captura del área de foto como en el JPEG decodificado. Comprobar únicamente dimensiones no detecta el error de vista previa.
2. **Trazos:** fotografía oscura con bandas negras en pantalla. Dibujar una línea roja al 20 % de la altura del rectángulo visible de la foto. Verificar rojo en la misma posición normalizada en vista previa y JPEG, y fondo en la posición que producía la fórmula incorrecta. Intentar dibujar fuera de ese rectángulo no debe añadir un trazo a la imagen exportada.
3. **Texto:** comparar la posición normalizada y el estilo del texto en el rectángulo de imagen de ambas salidas. Buscar solamente que exista algún píxel blanco no acredita igualdad de posición.
4. **Combinación:** dibujar y después recortar de forma asimétrica; girar y después recortar. Verificar los marcadores de contenido y anotaciones que permanecen en la imagen enviada.

Medir el rectángulo de imagen realmente representado; las barras, controles y teclado no pertenecen a ese espacio. Utilizar muestras interiores y tolerancia de compresión JPEG y antialias, evitando bordes y píxeles del chrome de la interfaz. Un identificador de prueba del viewport facilitaría capturas y gestos repetibles.

**Corrección propuesta:** compartir la geometría y el render de foto y anotaciones entre vista previa y exportación; almacenar los trazos en coordenadas normalizadas del contenido y recortar lo que quede fuera de él. Aplicar la orientación y el recorte en el mismo orden en ambos recorridos. Rotar solamente una capa rectangular sin recalcular su encuadre no resuelve por sí solo el dibujo ni el texto.

**Estado:** hallazgos comunicados a la IA responsable; implementación y pruebas de igualdad visual pendientes. Esta revisión no modificó producto ni ejecutó Gradle, ADB o SSH.

## Render compartido implementado para conectar al editor

Después de la revisión se autorizó implementar únicamente dos archivos nuevos: `MessagePhotoRendering.kt` y `MessagePhotoRenderingTest.kt`. La integración con `MessageMediaComposer.kt`, el recorte y la ejecución de Gradle/ADB corresponden a la otra IA.

El componente usa el mismo render de Android Canvas para la vista previa y el JPEG. Dibuja foto, texto, emoji y trazos en coordenadas de foto fuente y gira el conjunto después. Mantiene tamaños de texto y grosor proporcionales; no utiliza mínimos físicos diferentes en cada resolución. La vista previa decodifica hasta 1.024 píxeles y la exportación hasta 2.560, fuera del hilo principal.

El rectángulo táctil tiene la proporción de la foto girada y queda ajustado dentro del espacio disponible. Las bandas negras no pertenecen al área de dibujo. El gesto se transforma inversamente a coordenadas normalizadas de la fuente; el render limita las anotaciones al contenido. La exportación crea `message_media_<UUID>.jpg` directamente en `cacheDir`, valida escritura y longitud, libera bitmaps propios y elimina una salida parcial si falla o se cancela.

El estado de preparación se notifica por URI, estado de edición y gesto actuales. La vista previa comunica que está lista después de dibujar esa representación, muestra error y reintento si no puede prepararla y no sustituye una edición fallida por el original. Para Android anterior a API 28, se contempla la orientación EXIF en el bitmap decodificado; el formato y la lectura de esos metadatos se basan en [AndroidX ExifInterface](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface.html).

### Contratos entregados

- `MessagePhotoRenderState`: giro, texto, emoji, trazos normalizados a fuente y color del pincel.
- `exportMessagePhoto(context, sourceUri, state)`: función suspendida que devuelve la URI del JPEG preparado.
- `MessagePhotoPreview(...)`: vista previa con callbacks de trazo y preparación; etiqueta de prueba `message_photo_preview` en el rectángulo real de la foto y descripción «Foto editada».

Se prepararon siete pruebas locales: cuatro cuadrantes de giro en captura y JPEG; trazo que gira con la foto; bandas negras sin dibujo y posición del gesto; transformación de gesto girado a fuente; posición y tamaño de texto con distinta resolución; conversión inversa para todos los giros y rechazo de puntos externos; error de fuente ilegible sin envío del original.

**Estado:** archivos implementados y entregados para conectar y compilar; estas siete pruebas todavía no se han ejecutado al actualizar esta sección. No acreditan subida, recepción ni recorte combinado. La igualdad visual y la integración permanecen abiertas hasta obtener sus resultados.

## Revisión posterior de la conexión al compositor

Se revisó sin modificar `MessageMediaComposer.kt` con SHA-256 `ec632de69268d1d0178fc206a169396a6f785b32a2aada1a1876bb22bec3af28`. La conexión utiliza el render compartido y compara URI y estado de edición tanto para habilitar Enviar como al ejecutar su callback. Una representación de otra página del pager actualiza su propia clave y no habilita la foto activa. El recorte se abre sobre una exportación de la edición visible y reinicia giro, texto y trazos al aceptar, evitando aplicarlos otra vez sobre los píxeles ya preparados.

### Pendiente: misma URI con contenido cambiado

El render compartido decodifica la URI para la vista previa y vuelve a leerla para exportar. El estado de preparación identifica la URI y las ediciones, pero no la identidad de sus bytes. Si una fuente de galería se modifica conservando esa URI y las mismas dimensiones, la vista previa puede conservar el bitmap anterior mientras el envío prepara el nuevo contenido. Este límite afecta también al helper, no solo a la conexión del compositor.

**Caso mínimo propuesto:** preparar un PNG rojo, esperar la vista previa y sobrescribir ese mismo archivo con un PNG azul. El envío debe conservar la foto que se representó o rechazar el cambio y exigir una nueva vista previa. Comprobar una fuente eliminada no cubre esta modificación válida. El caso se deriva del código y todavía no se ha ejecutado en esta revisión.

**Corrección propuesta:** una instantánea privada e inmutable de la fuente, compartida entre vista previa, exportación y recorte; alternativamente detectar un cambio del contenido y volver a representarlo antes de habilitar el envío. La fecha de modificación por sí sola no acredita identidad de contenido.

### Pendiente: propiedad y limpieza de los recortes

Los JPEG preparados para abrir el recorte se registran y eliminan. Una URI nueva de `ShareableMediaFileStore.crops`, devuelta como `content://`, no se incorpora a la misma lista de limpieza de archivos `file://` en la conexión revisada. Al quitar la selección o cerrar el editor puede quedar ese recorte temporal. Se comunicó registrar y limpiar únicamente las URIs y rutas propias creadas en ese recorrido, conservando cualquier JPEG que ya se entregó al repositorio.

Siguen pendientes las regresiones de giro seguido de envío inmediato, edición seguida de recorte y envío con comparación por píxeles, cancelación del recorte y recorte ORIGINAL que devuelve la misma URI preparada. La revisión no ejecutó herramientas de compilación, dispositivo o servidor y no acredita esos resultados.

## Actualización: copias privadas y primera ejecución por píxeles

Se implementó `MessagePhotoSourceStore` para conservar una copia de los bytes elegidos. El compositor conectado usa la misma URI de esa copia para vista previa, recorte y exportación; el identificador de preparación incluye tanto la URI de la copia como el estado de render. El GIF sin editar mantiene sus bytes y obtiene el MIME de su cabecera; se copia a un archivo de salida independiente antes de cerrar el almacén. El compositor también registra los recortes propios `content://` y limita su limpieza a su autoridad, carpeta y nombre conocidos. Esta conexión se revisó en la fuente `MessageMediaComposer.kt` con SHA-256 `719fbda06a4f8989605982d781c4b5457144ff497bfe798e8d451654814fbaa8`.

El almacén copia por streaming, limita cada fuente a 50 MiB y recoge hasta 32 bytes de cabecera aunque el proveedor entregue fragmentos pequeños. El nombre y la extensión se generan en caché propia; no se confía en el nombre de origen. Las peticiones se serializan con un Mutex y la publicación verifica que `forget` o `close` no hayan invalidado la copia mientras se leía. Un fallo o una cancelación elimina el archivo parcial. Solo se eliminan archivos creados por esa instancia; no se elimina la fuente original. Si se pierde una copia publicada, no se vuelve a leer silenciosamente una versión nueva del original.

Se prepararon **nueve pruebas** de este almacén: conservación ante cambio o eliminación del original, peticiones concurrentes, cabecera GIF fragmentada con extensión original engañosa, limpieza propia y selección nueva explícita, copia publicada perdida, límite y fallo de stream, cierre durante copia, retirada durante copia y cancelación durante copia. **Su ejecución seguía pendiente al emitir esta actualización.**

| Fuente del almacén preparada en el repositorio | SHA-256 |
| --- | --- |
| `MessagePhotoSourceStore.kt` | `75cf0d8f29f6fc4c28fe0727ef94ad2be5f6c6576c0dddc3cc9f31c206236b4c` |
| `MessagePhotoSourceStoreTest.kt` | `d00eabd2437976ec789d5d3674499c260946a32d355dc42854e9573c022918cd` |

La revisión detectó una diferencia entre estos archivos y el mirror utilizado por la compilación en curso: el mirror conservaba `6342f75c…` y `b9dbfff9…`. Los ajustes posteriores eran la comprobación de cancelación antes de devolver una copia cacheada y las aserciones de limpieza mediante una URI propia del snapshot. Se comunicó la diferencia al responsable de la compilación; no se modificó el mirror durante su ejecución ni se atribuyen esos ajustes a resultados del mirror anterior.

### Resultado verificado del render

El responsable ejecutó `MessagePhotoRenderingTest` en el nuevo emulador con Google APIs, API 35. La lectura independiente del log nativo confirma **7 pruebas correctas en 16,516 segundos**. El error de compilación previo por `size.width/size.height` dentro de `TouchInjectionScope` se corrigió usando `width/height` directamente antes de esta ejecución.

| Evidencia | Identidad |
| --- | --- |
| Fuente `MessagePhotoRendering.kt` | `e4d27b7dbe8fffc1523e39318fcba30e0b4bd2bdb2967e6aea6f21fa23534466` |
| Fuente `MessagePhotoRenderingTest.kt` | `31698096284bbf32fbddf31dfe96110d83c9ebb2f52d88294d67c84e981ba30c` |
| Log privado de ejecución | `d4d46a6be1a2acc9c65b012c2339ca20a17c66c00d9c0a04eaa5ae4a64a1172d` |

Este resultado acredita los casos locales del render en el emulador. No acredita todavía el recorte combinado del compositor, la entrega entre cuentas, las nueve pruebas del almacén o un dispositivo físico. La identidad de los APK y el resto de los recorridos se registrarán en la evidencia consolidada de la compilación correspondiente.

### Seguimiento de recuperación de caché

Si la copia privada se pierde o corrompe después de mostrar la foto, el envío debe fallar. Se comunicó además que el reintento explícito necesita invalidar la copia y su identificador de preparación, volver a crear y representar la fuente disponible y conservar el comentario y las ediciones. Releer únicamente la misma URI averiada desde el botón de la vista previa no recupera esa selección. La nueva imagen debe mostrarse antes de habilitar otro envío; no debe enviarse automáticamente una versión nueva del original.

**Actualización de código:** la revisión posterior de `MessageMediaComposer.kt`, SHA-256 `99010185bd3fac7c3767c51468587ab4f1a155927a064226aa8427d0123a5b85`, confirma la invalidación del ítem fotográfico cuando falla su preparación. Se eliminan sus entradas de fuente y preparación, se conserva comentario y ediciones y se muestra el reintento del padre. La copia nueva se solicita mediante una acción explícita y Enviar permanece bloqueado hasta que se represente. Se añadió una regresión de copia perdida y recuperación y otra de cambio del original tras la vista previa. Estas modificaciones todavía requerían una nueva compilación y ejecución cuando se revisaron; no se atribuyen al resultado anterior de siete pruebas del render.

## Vídeo de cámara: normalización del audio en servidor

La otra IA comunicó un rechazo HTTP 422 del vídeo real de cámara con H.264 y audio AMR-NB. Implementó una corrección acotada en `storage-routes.js`, commit `1e1515f`, para conservar el vídeo y convertir las pistas de audio no AAC a AAC-LC. Esta revisión independiente fue solo de lectura; no ejecutó FFmpeg ni desplegó el cambio.

| Archivo revisado | SHA-256 |
| --- | --- |
| `storage-routes.js` | `d36dc2dc27f4d51fa690c2432947198e437e159b404139e64d0f78a901a28f6d` |
| `storage-mp4-normalization.test.js` | `71b4854dbbf3100c57076f4b3c05dda82cc338572d6a10665aa72e162950c0d1` |

Se verifica el listado completo de pistas de audio antes de construir las opciones. El orden de `-map 0:a?` y la selección de cada `-c:a:N` mantienen los ordinales de audio; `-c copy` conserva vídeo y AAC existentes y las opciones posteriores seleccionan el codificador de las otras pistas. Esta interpretación se apoya en la documentación oficial de [FFmpeg](https://www.ffmpeg.org/ffmpeg.html) y de [especificadores de pistas](https://ffmpeg.org/ffprobe.html). El archivo original se reemplaza mediante `rename` después de comprobar la salida; no se elimina antes. Los fallos conservan la fuente y limpian el temporal.

**No se encontró un defecto bloqueante en esta revisión del código.** La IA responsable comunicó 30 pruebas unitarias nuevas y 30 de regresión correctas. Las primeras utilizan procesos simulados y archivos artificiales; no demuestran una conversión ni reproducción real.

La validación final `probeVideoDelivery` comprueba vídeo, duración y bitrate, pero no el número, códec o perfil de pistas de audio. Para adjuntos de chat no aplica los límites específicos del feed; sigue comprobando tamaño. El flag `+faststart` solicita reorganizar el índice, pero estas pruebas no inspeccionan por sí mismas la posición de los átomos del archivo generado. Por ello queda pendiente la comprobación real en Linux bajo control del responsable del servidor.

### Matriz real comunicada para el cierre

- H.264 + AMR-NB de la captura: H.264 conservado y audio AAC-LC, con número de pistas y duración comprobados.
- H.264 + AAC: comprobar que los datos de paquetes de vídeo y AAC permanecen iguales, sin exigir igualdad del hash del contenedor que cambia al remuxear.
- Vídeo silencioso y audio mixto: comprobar ausencia de pistas cuando corresponde y conversión por ordinal de todas las pistas no AAC.
- Decodificar la salida real y comprobar el índice `moov` antes de `mdat`, según la finalidad de [faststart](https://ffmpeg.org/ffmpeg-formats.html).
- Rechazo, exceso de tamaño o timeout: conservar el hash de la fuente y no dejar temporales de normalización.

**Estado:** código revisado y entregado al responsable para comprobación real. No se acredita despliegue ni se da por resuelto el envío de vídeos de cámara mediante las pruebas simuladas. El recorrido captura → repositorio real → entrega y descarga del destinatario debe repetirse después de validar la corrección.

## Estado de cierre

Los hallazgos y el seguimiento se comunicaron a la IA responsable de mensajes y de la compilación compartida. La revisión confirma los cambios de código descritos, sin certificar todavía su comportamiento en un APK. El cierre exige resultados posteriores vinculados a las fuentes corregidas, al APK realmente probado y al recorrido de recepción correspondiente.


## Actualización de ejecución consolidada

Los pendientes de los checkpoints anteriores se actualizaron mediante pruebas del coordinador: las nueve pruebas del almacén, las siete de render y las siete de preparación/recorte forman parte de las suites de **31/31 en Motorola y 31/31 en tablet**, junto con cámara y enlaces. APK y logs se identifican en `docs/oficial/evidencias/cierre-11-2026-10-04.json`; se preserva el timeout inicial de recorte que luego pasó por separado y en suite completa.

También pasaron siete escenarios de FFmpeg real en Linux, conservación de paquetes de H264/AAC, normalización AMR a AAC LC, pistas mixtas, video silencioso, decodificación, faststart y conservación/limpieza ante rechazo. El servidor vigente `live-gifts-cierre11-a1299ad` fue distribuido con respaldo verificado, salud de API/gateway y 187/187 archivos seleccionados coincidentes. El archivo AMR que falló obtuvo HTTP200 y descarga por receptor200 después del despliegue. Detalle y procedencia en el documento70 y evidencia consolidada.

Estas comprobaciones no sustituyen los seis envíos integrados nativos por chat/grupo/comunidad; la repetición utiliza DNS de producción y limpieza de thumbnails del fixture. La certificación global permanece pendiente de ese recorrido, arranque optimizado, variación del primer cuadro LIVE y cobertura acústica/red.

## Cierre posterior del recorrido integrado de cámara

La repetición final en Samsung SM-X520 Android16 pasó: **seis casos, 68,151 s**. Se capturó y confirmó una foto y un video por chat directo, grupo y comunidad; se envió por el compositor/repositorio real, se recibió mediante HTTP y WebSocket con otra cuenta propia, se descargó y se decodificó. Se comprobaron dimensiones de foto y fotograma/audio/duración de video. No se afirma que la prueba recorra toda la navegación de cada ChatScreen.

La duración enviada coincidió con el intervalo confirmado del editor dentro de 200 ms. Directo: captura 4.272 ms, selección 2.938 ms, entrega 2.971 ms. Grupo: 4.206 / 2.892 / 2.904 ms. Comunidad: 4.272 / 2.938 / 2.971 ms. El clip debía ser al menos 250 ms más corto que la fuente capturada.

Se conservan los intentos anteriores y sus correcciones en el fixture: limpieza de thumbnails, uso del DNS/transporte de producción, sustitución de una suposición fija de duración por selección/fuente reales y sincronización de los padres de grupo/comunidad en Room antes de enviar. El último problema produjo SQLiteConstraintException al saltarse el flujo normal de carga; no motivó una modificación del repositorio de producción.

APK principal: `93f2d8ae66cdac96573850fd751d6815c37b0b4b7a5129d9acb612f7bcc51205`; test: `dca3cd32974f86f6239e82e2fc5dbb8ce6820e47c199613ef65ce651f5b29e7f`. Los hashes de logs, seis resultados y trazabilidad figuran en [evidencia consolidada](evidencias/cierre-11-2026-10-04.json). Se retiraron los objetos propios y espacios de grupos/comunidades por API, y las capturas/exportaciones privadas del caso. El DELETE de chat directo oculta/limpia para su propietario; no acredita borrado físico de todas las filas.

Por tanto, el pendiente de seis entregas del checkpoint anterior queda acreditado con este alcance. La certificación global mantiene los límites de [informe 70, sección 8](70-medios-arranque-y-avisos-validacion-2026-10-04.md): Motorola optimizado, variación de latencias, aviso físico, acústica/red y alcance general de producción.
