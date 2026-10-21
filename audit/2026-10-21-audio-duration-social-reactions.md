# Duración de audios y respuesta de reacciones — 21 de octubre de 2026

## Alcance

Android 9.1.19, código 159. Conservar los cambios en `main` y `feature/live-gifts`. No borrar datos del teléfono. La edición local de facturación en la segunda carpeta se conserva.

## Hallazgos y cambios

- La burbuja compartida forzaba `0:00` al estar inactiva, ignorando la duración del contenido. Ahora muestra el total en reposo y transcurrido/total durante la reproducción, también en grupos y comunidades.
- Los audios de navegadores pueden omitir la duración del contenedor. La lectura local usa MediaMetadataRetriever y, si falta el dato, las marcas de tiempo de los paquetes de audio con MediaExtractor. Se ejecuta en IO, admite dos lectores simultáneos y limita el recorrido a 100 000 paquetes/1,5 segundos. No descarga un archivo desde la fila para averiguar la duración. Reutiliza las copias ya disponibles; si falta información muestra `—:—`, sin inventar un audio de cero segundos.
- Los reproductores conservan hasta 256 duraciones por pantalla para que al terminar no desaparezca el total. Se mantiene la protección contra NaN en barras de progreso y se aplica también al cálculo de grupos/comunidades.
- Social actualizaba el like anticipadamente, pero una lectura anterior podía sobrescribirlo. También descartaba los toques recibidos mientras esperaba la primera escritura. Ahora se usa el escritor de reacciones compartido: actualización sincrónica de la interfaz, una escritura por publicación a la vez y conservación de la última elección. Las lecturas capturan una revisión antes de salir a red; una respuesta antigua no deshace la selección reciente. Los cambios de cuenta cancelan e invalidan el trabajo anterior. Un fallo de escritura revierte solo la reacción y ofrece reintentar, preservando cambios de contenido simultáneos.
- La API cargaba los comentarios tanto al validar acceso como al preparar la respuesta, y esperaba la notificación al autor. La validación ahora omite los detalles de comentarios conservando el control de visibilidad. Android solicita una confirmación mínima después de escribir la reacción en PostgreSQL; los clientes anteriores conservan su respuesta completa. La notificación sigue siendo de mejor esfuerzo y comienza después de responder.

## Verificación y entrega

Resultados, revisiones y firmas finales en `artifacts/release-9.1.19/`. Las pruebas no publican reacciones en cuentas de usuarios.

- Backend: `node --check` y `tests/data-routes.test.js`, incluidas regresiones sobre autorización previa, confirmación posterior a la escritura, respuestas mínimas y entrega de notificaciones sin bloquear la respuesta.
- API publicada: imagen `novvin-api:reactions919-20261021`, raíz de contenedor de solo lectura. Se compararon las fuentes del host con las de la imagen activa antes de construir; se guardaron fuentes y configuración para restaurar la imagen anterior.
- Conectividad pública: cuatro rutas TLS/IP/puerto responden 200; seis comprobaciones públicas de salud y rechazo de accesos no autorizados pasan. Esto comprueba disponibilidad y controles concretos, no una auditoría de seguridad completa.
- Muestra sintética WebM/Opus de 3,2 segundos, generada con FFmpeg `-live 1`, sin duración declarada según FFprobe. No contiene audio de usuarios. Se incluye como fixture de instrumentación.
- La instalación y la verificación física dependen de recuperar la conexión USB del Motorola; no se contabilizan como realizadas solo por compilar o ejecutar el emulador.

## Límites

La respuesta visual al toque no equivale a una escritura ya confirmada. Guardar y propagar entre teléfonos requiere conexión y respuesta del servidor. Ante un fallo se informa y permite reintentar. Las pruebas con red simulada lenta no certifican latencia nula en cualquier red o dispositivo.

Un audio sin metadatos ni copia local puede mostrar duración desconocida hasta disponer del archivo. Las cachés siguen siendo acotadas; no se precargan indiscriminadamente todos los audios.

## Fuentes primarias

- [Android: MediaPlayer](https://developer.android.com/media/platform/mediaplayer/basics): preparar y liberar el reproductor según su ciclo de vida.
- [Android: MediaMetadataRetriever](https://developer.android.com/reference/android/media/MediaMetadataRetriever): extracción y liberación de metadatos.
- [Android: arquitectura con datos locales](https://developer.android.com/topic/architecture/data-layer/offline-first): mostrar los datos locales y sincronizar con la red sin bloquear la interfaz.
