# Mensajes: respuestas a multimedia y rechazos del servidor

Fecha: 2026-10-02. Android conserva versionCode 117 y versión base 9.0.20.

## Cambios

- **Rechazos HTTP:** el adaptador de Ubuntu conservaba únicamente el texto del error; el repositorio de chats convertía cualquier fallo en HTTP 500. Por eso, un 403 `CHAT_ACCESS_DENIED` o un 409 `FRIEND_REQUEST_PENDING` podía permanecer como envío pendiente. Los tres adaptadores de envío conservan ahora el estado HTTP y el código del servidor. Chats, grupos y comunidades distinguen rechazos definitivos de respuestas temporales: 408, 425, 429 y 5xx pueden volver a intentarse; otros 4xx pasan a fallo. Las cancelaciones de corrutina se propagan.
- **Descubrimiento del servidor:** un rechazo definitivo de chat ya no dispara una búsqueda de otro endpoint antes de devolver el error. Se mantienen los permisos, bloqueos y restricciones del servidor.
- **Citas:** el compositor y las respuestas entregadas comparten un texto legible para sticker, GIF, video, audio, foto, archivo, encuesta, contacto y ubicación. Un sticker sin descripción ya no deja una cita vacía. Un adjunto eliminado se identifica sin mostrar su contenido antiguo.
- **Android anteriores a 12:** `SyncWorker` implementa `getForegroundInfo()` y crea el aviso de sincronización requerido por trabajo expedito. El aviso tiene baja importancia, no incluye contenido ni destinatarios y no vuelve a alertar en cada actualización. La exigencia está documentada en [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
- **Medición:** los envíos de chat registran tiempos monotónicos para creación, guardado local, token disponible, inicio y retorno de la solicitud y confirmación local. Estos registros nuevos no contienen texto, tokens, nombres ni URLs.
- **Escritura duplicada:** se retira el PATCH de compatibilidad del último mensaje para Ubuntu. Su ruta de envío ya actualiza esa información antes de confirmar; repetirla añadía una solicitud y frenaba el vaciado secuencial de pendientes. La actualización local de la conversación sigue realizándose.

## Evidencia y alcance

Los resultados finales de compilación, pruebas, hash de APK, comprobaciones nativas y tiempos están en [resultados](../evidencias-emulador-mensajes-sync-2026-10-02/resultados.json).

La compilación completa terminó correctamente. Pasaron **1.108 pruebas unitarias en 245 suites**, sin fallos, errores ni omisiones; **siete pruebas de interfaz en 21,859 s** y **tres pruebas instrumentadas de transporte en 5,135 s**. Estas últimas llaman desde Android a las rutas reales de chat, grupo y comunidad con un token deliberadamente inválido: las tres conservan HTTP 401 / `INVALID_TOKEN` como rechazo definitivo. No crean mensajes ni cambian la sesión guardada.

Se emplea únicamente el emulador Android API 35 y cuentas ficticias de esta auditoría. El Motorola y la tablet no se operan en este bloque. La prueba de avisos anteriores a Android 12 utiliza Robolectric en SDK 25 y 30; no equivale a una prueba física de esos sistemas.

Tras las pruebas instrumentadas, Windows registró una violación de acceso `0xc0000005` en `qemu-system-x86_64-headless.exe`. También se observaron ANR de System UI y de Novvin durante arranque: una traza esperó al hilo de render y otra quedó en la inicialización de Hilt. El entorno terminó funcionando con GPU del host NVIDIA RTX 3050, cuatro núcleos, 2 GB de RAM y Vulkan desactivado. Se compiló el paquete instalado con ART en modo `speed`, únicamente en el emulador, conservando los datos. El arranque frío posterior siguió tardando **5.425 ms** (`am start -W`; espera total 5.479 ms). Estos fallos y la compilación AOT se registran como limitaciones; no prueban que el arranque de producción esté resuelto. Las capturas nativas se aceptan únicamente con una jerarquía nueva de UI; los intentos con `null root node` o diálogo ANR no cuentan como navegación correcta.

Se respondió a un sticker desde el gesto de respuesta del chat ficticio. El compositor mostró «Sticker» y la cita entregada conservó ese texto, el autor y la referencia al mensaje. Room confirmó una sola fila, estado `sent`, sincronizada y sin envío pendiente, con el tipo `sticker` retenido después de la confirmación del servidor. La captura con teclado abierto mostró el mensaje por encima del compositor. Después se enviaron otros dos stickers; el panel permaneció abierto y el último mensaje quedó visible por encima de él.

Las tres muestras nativas registraron guardado local en **68 / 107 / 117 ms**, retorno de la solicitud en **347 / 775 / 620 ms** desde su inicio y confirmación completa en **2.721 / 2.422 / 2.400 ms** desde la entrada al repositorio. El intervalo hasta disponer del token incluye actualización de la conversación, comprobaciones de red y sesión; estos registros todavía no separan esas causas. Sigue pendiente reducir y volver a medir el recorrido Android: estas muestras no cumplen una confirmación total inferior a un segundo.

Pasaron **45 pruebas seleccionadas del backend**: inserción sin duplicados, permisos de multimedia, confirmaciones de lectura, solicitudes automáticas de amistad, restricciones de primer contacto, marcadores y programación de mensajes. No se modificó ni desplegó código del servidor.

Por HTTPS público desde Windows, tres stickers de las cuentas ficticias recibieron HTTP 201 en **126,2 / 89,7 / 93,5 ms**. Un envío vacío produjo 400 y un chat inexistente o sin acceso produjo 403. Estas mediciones incluyen transporte público desde esta PC; no representan latencia en datos móviles ni el recorrido completo del compositor Android. Una primera ejecución de la sonda no terminó; los valores publicados pertenecen a su repetición completa, no prueban disponibilidad permanente.

El endpoint público `/ready` devolvió HTTP 200 y `ok=true`. SSH por LAN agotó el tiempo de conexión y por Tailscale pidió una comprobación adicional. Por tanto, no se certifica el estado actual de los nueve contenedores mediante una inspección administrativa en este bloque.

## Diseño y auditoría pendiente

El reloj compacto, progreso y reintento sobre la imagen se validaron en el [bloque anterior](48-emulador-stickers-y-cola-2026-10-02.md). Esta corrección añade consistencia a las citas y distingue estados reales de envío, siguiendo los principios de visibilidad del estado, consistencia, reconocimiento y recuperación de errores de las [heurísticas de Nielsen](https://www.nngroup.com/articles/ten-usability-heuristics/). Es una evaluación parcial, no una certificación de las diez heurísticas en toda la app.

Continúa el orden acordado: opciones restantes de chats y adjuntos; grupos y encuestas; comunidades; voz y llamadas; videos y LIVE; Social, juegos y perfil. Después corresponde completar la paridad web y el despliegue solicitado. Siguen abiertas las pruebas integrales de todos esos módulos, la cadencia real de LIVE, el arranque del invitado y las comprobaciones de producción. No se prometen 60 FPS ni entrega universal en menos de un segundo.

## Reversión

Revertir el commit de este bloque y recompilar. No hay migraciones de datos ni cambios de configuración del servidor. La APK se instala sobre los datos existentes en el emulador.
