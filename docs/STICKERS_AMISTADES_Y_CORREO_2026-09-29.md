# Stickers, solicitudes de amistad y recuperación por correo

Checkout autorizado: `D:\red social - live-gifts`, rama `feature/live-gifts`.
No trabajar estas correcciones en el checkout principal `D:\red social - copia`.

## Correcciones

- Favoritos, recientes y catálogo de stickers separados por cuenta. Las operaciones y la sincronización comprueban el propietario para descartar respuestas tardías de otra sesión.
- Restauración de favoritos del servidor incluso cuando el sticker ya existe en el catálogo local. Las decisiones locales de añadir/quitar tienen prioridad durante la sincronización.
- El perfil consulta el estado de amistad también cuando usa datos guardados: un primer mensaje que crea una solicitud cambia el botón a «Cancelar solicitud».
- Solicitudes con carga inicial explícita y caché en memoria de la cuenta actual; perfiles hidratados en paralelo. Un error al obtener los perfiles no se interpreta como lista vacía.
- No mostrar el estado vacío antes de terminar la carga de confirmaciones recientes. Una carga anterior cancelada no puede finalizar el indicador de la carga nueva.
- Los avisos de notificaciones provocan actualización de solicitudes en Social. Verlas marca su novedad como leída, conservando las solicitudes pendientes.
- Aceptar o rechazar una solicitud la retira de la caché de pendientes después de confirmarse en el servidor.
- Se elimina la duplicación de una cuenta entre pendientes y confirmadas recientes. La prueba descubrió un cierre de Compose por claves repetidas y motivó esta corrección.
- El backend entrega `username_origin` en perfiles y tarjetas de búsqueda: la app muestra el identificador elegido, incluyendo en las solicitudes, conservando la privacidad del correo.
- El trigger que crea la solicitud por el primer mensaje también identifica la notificación con ese mensaje. La API publica la notificación ya guardada al canal del destinatario, sin insertar otra ni retrasar el envío del chat. Las fechas se convierten a ISO antes de publicarse.
- Se corrigió la ruta pública `/realtime`: Tailscale la enviaba al servicio web en el puerto 4187, que respondía `INVALID_SESSION` incluso con una sesión recién iniciada. Ahora se enruta al edge del backend en 3080. El script de reparación conserva las otras rutas; el verificador de producción comprueba la identidad de `/realtime/health` para detectar este error.
- El contador de solicitudes de Social se refresca al aumentar las notificaciones, además de hacerlo al volver a la pantalla.

## Evidencia realizada

- Dos cuentas sintéticas en el emulador: cada una tiene un favorito distinto; ningún identificador coincide entre ambas listas.
- La segunda cuenta envió su favorito a la primera y quedó en estado `sent`; al volver a la primera, su propio favorito se conservó y también pudo enviarlo.
- Desde la app: abrir un perfil sin amistad, enviar el primer mensaje y volver al perfil guardado cambió «Añadir amigo» por «Cancelar solicitud». La API confirmó una relación `pending` para ese par.
- Primer mensaje hacia otra cuenta sintética generó una solicitud automática. La pantalla de Mensajes mostró un punto rojo en Social y el encabezado Social mostró el contador de solicitudes.
- Después de reparar la ruta, con la receptora abierta y sin reiniciar, el primer mensaje de una cuenta nueva generó una notificación visible y el punto rojo en Social (`build/realtime-route-fixed.png`). Abrir Solicitudes mostró las cuentas pendientes, sin cierre; al volver, el punto de Social desapareció (`build/realtime-requests-fixed.png`, `build/realtime-seen-fixed.png`).
- Prueba integrada por WebSocket contra el servidor público: login, canal propio, primer mensaje, solicitud y notificación recibida en 60 ms en esa medición. La suscripción al canal de otra cuenta fue rechazada y `/realtime/internal/publish` continuó inaccesible (404).
- Pruebas de rutas de datos: `data-routes tests passed`.
- Pruebas de acceso a medios, política de stickers y notificaciones de solicitudes: 30 aprobadas, 0 fallidas. Se conserva el rechazo a stickers privados de otras cuentas.
- Después del despliegue del API, contenedor `healthy`; perfil sintético devuelve el username solicitado y origen `chosen`.
- Medidas HTTP de esta sesión: login correcto aproximadamente 390–766 ms, consulta de amistades 47–188 ms. Son observaciones puntuales, no una garantía de latencia.
- `/v1/auth/password-reset/request` respondió HTTP 200 con `delivery: disabled`: no está activo el correo automático.

## Pantalla de solicitudes

- APK con deduplicación instalada: Solicitudes abrió con pendientes y confirmaciones recientes, sin el cierre por claves repetidas de Compose.
- Una solicitud sintética se rechazó desde «Eliminar». Al volver y reabrir la lista no reapareció como pendiente desde la caché. La solicitud de la cuenta personal Alvaro se conservó.
- La lectura de solicitudes elimina el aviso de novedad en Social, sin aceptar ni rechazar las cuentas pendientes.
- APK final instalada en `NovvinFriends35` (Android 15), interfaz de la app en español. El login de la receptora sintética permitió entrar a Mensajes y después a Social.
- Con Social abierto, el primer mensaje de otra cuenta hizo pasar el contador de solicitudes de 4 a 5, aumentó las notificaciones y mostró el punto rojo (`build/counter-before-new-request.png`, `build/counter-after-new-request.png`). Al abrir Solicitudes y volver, el contador de solicitudes y el punto de Social desaparecieron, manteniendo las peticiones pendientes (`build/counter-viewed-requests.png`, `build/counter-cleared-after-view.png`).

## Límites y continuación

- ADB no detectó el Motorola durante esta continuación. Las pruebas visuales se hicieron en el emulador, sin modificar la sesión personal del teléfono.
- El entorno Android 16 sufrió cierres del ejecutable Windows `qemu` con excepción `0xc0000005`. La revisión final pudo completarse con el entorno Android 15; estos resultados no certifican todos los modelos ni todas las versiones de Android.
- La caché de solicitudes es de memoria y no elimina el tiempo de red en la primera carga o después de cerrar el proceso.
- Las preferencias globales antiguas de stickers se conservaron en disco; no se asignan automáticamente a una cuenta porque no contienen información suficiente para identificar a su propietario. Los favoritos propios del servidor se restauran.
- La APK es de depuración; no se dispone de la clave de firma original para producir y validar una actualización release.
- La auditoría completa de LIVE, PK, salas de voz y llamadas sigue siendo trabajo aparte: las pruebas HTTP y de stickers no certifican audio o video entre dos dispositivos.
- La recepción real de códigos por correo requiere configurar el remitente y ejecutar la recuperación hasta el cambio de contraseña y nuevo login. Opciones y costos: `RECUPERACION-POR-CORREO-2026-09-29.md`.
- Las cuentas sintéticas y la amistad entre las dos cuentas de medios se conservan para continuar las pruebas pendientes de LIVE, salas y llamadas. No se manipuló la sesión personal del Motorola.

## Entrega

- APK debug 9.0.0, código 97: `build/Novvin-9.0.0-live-gifts-debug.apk`.
- Compilación del ajuste final: `BUILD SUCCESSFUL in 7m 20s`, registro `build/friends-realtime-counter-build.log`.
- SHA-256: `6C0875BBCCE3ACBCD78CAC6FFD03828487AC7AD5FD301A22737997B248B9EA81`.
- Cambios finales del servidor desplegados: `5ee5e67`, `daf03d8`, `6140449`; ruta Tailscale reparada y scripts sincronizados. Ajuste del contador y comprobación pública de tiempo real: `cabdef3`.
