# Corrección de portada al publicar música comunitaria

Directorio: `D:\red social - live-gifts`, rama `feature/live-gifts`. Investigación iniciada el 19 de septiembre y revisión final el 20 de septiembre de 2026.

## Causa reproducible por código

`CommunityMusicUploadDialog` recibe una portada creada por `readCommunityMusicMetadata`: esa función ya decodifica la imagen embebida, reduce sus dimensiones y produce un JPEG válido en caché. La interfaz entrega `Uri.fromFile(file)` al repositorio.

`MomentMusicCatalogRepository` consultaba `ContentResolver.getType` y `OpenableColumns.DISPLAY_NAME` para decidir si la portada era JPG, PNG o WebP. Un URI `file://` no tiene por qué responder a consultas de proveedor; el tipo y el nombre quedaban vacíos. El código rechazaba el JPEG correcto con «La portada debe ser JPG, PNG o WebP». Además, esa comprobación ocurría después de subir el audio.

## Comportamiento corregido

- La portada se abre por su URI local `file://` o `content://` y se valida mediante sus bytes. No depende de MIME, extensión ni nombre suministrados por el proveedor.
- La entrada tiene un límite de 10 MB y se comprueban las dimensiones antes de decodificar. Se crea un JPEG de hasta 1024 píxeles por lado, con extensión `.jpg` y tipo `image/jpeg` coherentes.
- `withPreparedCommunityCoverUpload` debe terminar la preparación antes de entrar a la etapa que transfiere audio y portada. Una portada inválida no inicia esa transferencia.
- La copia preparada tiene un propietario y se elimina mediante `finally`, tanto al terminar como al fallar o cancelar. Se conserva el archivo original que pertenece al selector.
- Si falló la publicación antes de crear la pista en catálogo, se intenta borrar únicamente los objetos cuya transferencia había sido confirmada. El rollback puede ejecutarse aunque el trabajo esté cancelado; tiene un plazo total de cinco segundos. Un fallo de limpieza no sustituye el error original. Después de crear la pista no se borran sus medios, aunque la aprobación posterior falle.
- El nombre de archivos `file:` se resuelve desde su ruta de archivo; los URI de proveedores conservan la consulta de nombre y el fallback de último segmento. Esto también permite reconocer la extensión del audio recortado en caché y funciona en las pruebas JVM de Windows.

No se modificó ni desactivó la validación de archivos del servidor. No fue necesario cambiar el selector ni el diálogo para corregir el tipo de portada.

## Pruebas y alcance

### Historial y eliminación solicitados

El selector de música de Momentos incorpora «Mis canciones publicadas», con historial paginado de las subidas de la cuenta, estado de revisión, motivo y fecha. La eliminación requiere confirmación dentro de la aplicación y sólo retira una fila tras respuesta válida del servidor. Cambiar de cuenta cancela y descarta las respuestas anteriores.

El servidor incorpora `GET /v1/data/music/my` y `DELETE /v1/data/music/:trackId`. La consulta está limitada al propietario, excluye retiradas y pagina por fecha e ID conservando los microsegundos de PostgreSQL. La eliminación bloquea la fila del propietario, retira la publicación y futuros usos, registra la acción y es idempotente. Conserva los medios ya usados por momentos publicados. Un usuario no puede retirar canciones de otra cuenta. Estas rutas requieren desplegar el backend de la rama; no se han desplegado en producción.

Las seis pruebas de `music-library-routes.test.js` aprobaron: cursores inválidos y fechas imposibles, paginación precisa y límite, aislamiento del propietario, eliminación transaccional, reintentos, rollback y autenticación. Suite API actualizada: 193 aprobadas, una omitida, cero fallos.

Se ampliaron `CommunityMusicArtworkTest` con casos de JPEG `file://`, albumart `content://` sin MIME ni extensión, archivo falso `.jpg`, bloqueo de la etapa de subida cuando falla el preflight y limpieza de la copia cuando se cancela la transferencia. La prueba usa el decodificador gráfico de Robolectric; el callback representa la frontera de subida, sin enviar archivos a un servidor real.

`CommunityMusicUploadCleanupTest` comprueba rollback desde un trabajo cancelado, orden inverso, continuación tras un error y plazo máximo ante una operación bloqueada. Estas pruebas aprobaron dentro de las 789 pruebas unitarias de Android. Además, `CommunityMusicCoverTest` aprobó en Android 15: abrió una imagen PNG real sin extensión ni MIME de proveedor, creó un JPEG acotado y conservó el original. La APK fue instalada en un emulador, no en un teléfono físico.

Los medios cuyo servidor haya recibido pero cuya respuesta se pierda antes de confirmar la clave no pueden reconciliarse desde esta transacción local. Continúa siendo necesaria la auditoría periódica de objetos sin referencia del backend. Tampoco se verificó aquí una subida real con red intermitente ni el comportamiento de todos los proveedores Android.
