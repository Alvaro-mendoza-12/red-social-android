# Bloque 3: carga y apertura de imágenes

Fecha de verificación: 2026-07-21.

## Causas encontradas

- Android mostraba las vistas previas con claves de caché estables, pero varios visores de pantalla completa y ramas reales del chat volvían a usar la URL cruda. Las URLs firmadas cambiaban y provocaban otra descarga.
- El visor comenzaba con una URL nula aunque no hubiera nada que resolver, lo que producía el cuadro negro inicial. La descarga para guardar usaba además otro `ImageLoader`.
- El resolvedor de medios firmaba de nuevo el mismo objeto en cada apertura.
- El backend servía siempre el original, incluidos archivos de decenas de MB, y no generaba variantes acotadas. La respuesta 304 se emitía antes de añadir todas las cabeceras de caché.
- Web cargaba demasiados originales de forma anticipada; `thumbnail_url` podía interpretarse como otro medio y duplicar la imagen. El temporizador de Momentos empezaba antes de que el medio estuviera listo.
- El chat web descargaba medios de túneles ngrok como un `blob:` nuevo en cada montaje y revocaba esa URL al salir, por lo que al volver repetía el trabajo y la espera.
- Nginx almacenaba temporalmente el cuerpo completo de cargas compatibles antes de pasarlo a la API.

## Cambios

- Android: `MediaImageRequestFactory` centraliza claves de memoria/disco, vistas previas y placeholder del original; los posts, mensajes y visores reutilizan esas solicitudes. `UbuntuStorageUrl` normaliza URLs públicas/firmadas sin perder las claves históricas y conserva los parámetros de variante. `MessageMediaResolver` reutiliza firmas durante 50 minutos y agrupa solicitudes concurrentes. El chat grupal vuelve a entregar su lista real al visor.
- Backend: `storage-routes.js` genera bajo demanda variantes WebP limitadas con Sharp, agrupa trabajos concurrentes, limpia derivados al sobrescribir/eliminar, transmite POST/PUT a archivo temporal y aplica ETag, Last-Modified, Cache-Control, 304 y Range/206 de forma coherente. Las rutas inmutables reciben caché anual; las mutables revalidan.
- Web: feed con preview optimizada, carga diferida, decodificación asíncrona y prioridad solo para los primeros elementos; deduplicación de thumbnail/original; Momentos espera `onLoad`/`onCanPlay` antes de avanzar. El chat reescribe hosts antiguos, usa la variante WebP, agrupa descargas concurrentes de ngrok y conserva un caché LRU de previews durante la sesión.
- Nginx: la ruta de objetos desactiva `proxy_request_buffering` y mantiene el streaming y las cabeceras de la API.

Archivos principales: `app/src/main/java/conectate/com/core/media/MediaImageRequestFactory.kt`, `UbuntuStorageUrl.kt`, `MessageMediaResolver.kt`, `FullScreenImageDialog.kt`, `FullScreenImageScreen.kt`, `MessageBubble.kt`, `PostCard.kt`, `GroupChatScreen.kt`, `Conectate-web/src/core/runtimeBackend.ts`, `FeedPage.tsx`, `mediaUtils.ts`, `ChatDetailPage.tsx`, `MomentsViewerPage.tsx`, `infrastructure/local-fallback/api/src/storage-routes.js` y `infrastructure/local-fallback/nginx/default.conf`.

## Verificación

- Backend: `npm run check` y `npm run test:storage`; incluye HTTP real para WebP, ETag/304 con cabeceras y Range estricto 206.
- Web: 10 suites, 63 pruebas, 0 fallos; `npm run build` correcto.
- Android: 31 suites, 127 pruebas, 0 fallos/errores/omitidas; `assembleDebug` correcto.
- Android Release: `assembleRelease` correcto excluyendo Android Lint Vital por el fallo interno ya conocido del analizador y excluyendo la subida del mapping de Crashlytics. Se generó `app-release-unsigned.apk` localmente.
- `git diff --check` correcto para el alcance del bloque.

No se desplegó, instaló ni modificó información real. No había ADB ni una API local activa para una prueba manual en dispositivo, por lo que aún se recomienda comprobar visualmente en dos dispositivos la primera carga, la reapertura desde caché y el modo avión antes de publicar.

## Ampliación: álbumes, ranking y administración

- Los mensajes con 2, 3, 4 o más imágenes ahora se presentan como un solo mosaico en chats directos, grupos y comunidades. Se muestran como máximo cuatro celdas y la cuarta indica `+N`; al tocarla se abre la galería completa. El servidor actual no conserva un identificador de lote, por lo que el cliente agrupa medios consecutivos del mismo remitente dentro de una ventana corta.
- La pantalla completa mantiene visible la vista previa ya almacenada mientras resuelve o decodifica el original. Las firmas de distintos objetos se resuelven en paralelo, y las imágenes seleccionadas por el usuario se reducen y comprimen antes de subirlas.
- El ranking dejó de depender de tarjetas de búsqueda sin contadores: la API calcula seguidores, seguidos y publicaciones con alias canónicos y sin duplicar relaciones.
- El panel permite eliminar cuentas de prueba y cuentas normales previamente suspendidas. Para cuentas normales exige ID, correo, confirmación irreversible, permisos de administrador y purga completa. Tras confirmar la eliminación, el servidor publica `account_deleted` en los identificadores del usuario: si la app está conectada cierra la sesión y muestra `Tu cuenta ha sido eliminada` inmediatamente; si perdió el WebSocket, la siguiente solicitud autenticada recibe `ACCOUNT_DELETED` y aplica el mismo cierre.
- La ruta `/ready` publica capacidades para detectar un backend antiguo. El 404 observado en `/v1/data/admin/users` corresponde a una instancia pública que aún no contiene las rutas locales actuales; no se desplegó este bloque.

Verificación adicional: 14 pruebas Android focalizadas sin fallos; `compileDebugKotlin` y `compileReleaseKotlin` correctos; `npm run check`, `npm run test:storage`, `npm run test:data-routes`, `npm run test:admin` y la compilación web correctos. La eliminación se probó únicamente con fixtures ficticios.
