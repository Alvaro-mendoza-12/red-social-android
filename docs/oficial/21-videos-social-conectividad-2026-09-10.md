# Videos, Social y conectividad pública — 2026-09-10

## Alcance

Esta intervención atiende los síntomas reportados en la APK: Social quedaba en un círculo de carga o mostraba `INTERNAL_ERROR`, Videos no podía continuar la reproducción, los perfiles mostraban sólo la inicial y el carrusel de recomendaciones no conservaba las fotos. También se revisó la ruta de Mensajes porque comparte endpoint, almacenamiento y resolución de identidades.

No se modificaron PostgreSQL, el volumen de almacenamiento, realtime, signaling ni el servicio de túnel durante el despliegue del API. La actualización del servidor fue acotada a `data-routes.js` y se creó respaldo antes de reemplazarlo.

## Causa raíz confirmada

El servidor no estaba caído. El túnel público vigente respondía correctamente, pero la APK conservaba una URL efímera anterior. El cliente sólo descubría el endpoint firmado después de que la primera petición al endpoint viejo fallaba, lo que producía esperas, pantallas vacías y errores de reproducción. Una URL de Quick Tunnel tampoco debe considerarse válida sólo porque el proceso `cloudflared` siga vivo: el hostname puede haber rotado aunque el proceso exista.

La conectividad real del servidor se comprobó con `/health`, `/ready`, feeds, búsqueda de perfiles, imágenes optimizadas y rangos HTTP de video. Los rangos devolvieron `206 Partial Content`, `Content-Range` coherente y `video/mp4`; por tanto, el problema principal era la selección y renovación del endpoint, no el ancho de banda del archivo.

## Cambios del cliente Android

- `RuntimeBackendEndpoint.kt`: valida `/ready` y las capacidades del backend antes de usar un endpoint; usa una caché de validación de 30 segundos, invalida el endpoint tras un fallo y conserva la firma criptográfica del descubrimiento. Los timeouts de descubrimiento quedaron acotados a 4 s de conexión, 4 s de lectura y 6 s de llamada.
- `LocalFallbackApiClient.kt`: vuelve a basar automáticamente cada URL de API en el endpoint vigente, manteniendo path, query y fragmento. Si una petición falla, actualiza el endpoint y reintenta una sola vez.
- `VideoRepository.kt` y `data-routes.js`: el feed de “Siguiendo” usa una consulta filtrada del backend (`followingOnly=true`) y conserva el snapshot local si la red falla. Se evita mezclar una segunda consulta heredada que podía sobrescribir el resultado.
- `SocialScreen.kt`, `UserRepository.kt` y `ProfileIdentityImageStore.kt`: el carrusel recomendado y los perfiles calientan/leen un caché durable y limitado de avatares estáticos; así una carga lenta no vuelve a mostrar sólo la inicial.
- `FullScreenImageDialog.kt` y `FullScreenImageScreen.kt`: las fotos de perfil normales pueden usar el respaldo durable; los medios efímeros, de vista única o genéricos no se guardan de forma permanente.

La paginación vertical de Videos conserva el comportamiento de feed y no se habilitó un gesto inverso que pudiera romper la navegación normal. La renovación de sesión se realiza al entrar de nuevo a Videos y el feed inicial conserva contenido ya montado mientras actualiza.

## Cambios del servidor

`/v1/data/videos` ahora interpreta `followingOnly`/`following_only`, carga las relaciones de seguimiento, amplía alias de perfiles y filtra por propietario propio o seguido antes de enriquecer el resultado. La visibilidad pública/propia se conserva.

El archivo publicado coincide con el artefacto local:

```text
SHA-256 d46d337a69e8cd147d709ea0da584c694e90427d732562268e8604f94bd9a264
```

Respaldo remoto:

```text
/home/alvaro/conectate-local-fallback/api/src/data-routes.js.backup-20260910T102220Z
```

Sólo se recreó `conectate-local-fallback-api-main` mediante `docker compose up -d --no-deps --force-recreate api`. Después quedó `healthy` y `/ready` respondió `ok=true`. El túnel `conectate-cloudflare-tunnel.service` quedó `active` con el endpoint vigente:

```text
https://collaboration-forecasts-stadium-occasions.trycloudflare.com
```

El script local del túnel también quedó corregido para no conservar un hostname únicamente por detectar vivo el proceso propietario: exige comprobaciones HTTP exitosas antes de reutilizarlo y usa HTTP/2 sobre IPv4 para su Quick Tunnel.

El respaldo del script instalado en el servidor es `/home/alvaro/bin/conectate-cloudflare-tunnel.sh.backup-20260910T0455Z`. La consulta real de `Videos?followingOnly=true` respondió HTTP 200 con contenido después del despliegue.

## Verificación pública posterior al despliegue

El monitor oficial terminó con `status=ok` y `consecutive_failures=0` a las `2026-09-10T10:23:45Z`.

- Social: HTTP 200, 37 elementos, 754 ms.
- Videos: HTTP 200, 12 elementos, 732 ms.
- Búsqueda de perfiles: HTTP 200, 20 perfiles, 1.054 ms.
- Imágenes: WebP HTTP 200, entre 420 y 707 ms.
- Video: dos rangos HTTP `206`, entre 715 y 952 ms, con `video/mp4`.
- Mensajes: chats, grupos, comunidades y perfiles HTTP 200; historial de 188 mensajes en 545 ms e imágenes entre 440 y 452 ms.

La suite API completa quedó en 111 pruebas: 110 aprobadas, 0 fallos y 1 omitida prevista.

## Verificación Android

- `:app:testDebugUnitTest`: 607 pruebas, 0 fallos/errores y 0 omitidas.
- `:app:assembleDebug`: exitoso.
- `:app:lintDebug`: exitoso, 0 errores y 194 advertencias.

APK generada:

```text
D:\red social - live-gifts\app\build\outputs\apk\debug\app-debug.apk
bytes=273927848
SHA-256=0DA12C647F10D222C3C3A6096F12BAFDAD3E8ACD84A319BFA51B93CD0F6F4C09
```

## Pendiente de entrega

La APK fue compilada pero no se instaló automáticamente en un teléfono. Falta una prueba manual con la APK nueva: abrir Social desde frío, cambiar entre Descubrir/Seguidos, abrir Videos y reproducir varios elementos, deslizar al siguiente, comprobar imágenes de perfil y repetir con una rotación del Quick Tunnel. La lectura pública del servidor ya está validada; esta prueba confirma el comportamiento visual y de reproducción del cliente real.
