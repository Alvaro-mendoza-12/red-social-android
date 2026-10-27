# 78 — Actualización del host, capacidad y acceso público web

Revisión: **27/10/2026, America/Bogota**. Continúa la [auditoría 76](76-auditoria-servidor-2026-10-26.md). La carga se ejecutó la noche del 26/10 en Bogotá; las verificaciones web se repitieron el 27/10. No sustituye la aceptación Android pendiente en la [revisión 75](75-cierre-movil-167-2026-10-25.md).

## Host actualizado y reiniciado

Se ejecutó el mantenimiento revisado con autorización sudo y se completaron `fwupd` y `linux-firmware`, que requerían nuevas dependencias. Ambas unidades terminaron con código cero. APT quedó sin paquetes actualizables. El propietario autorizó el reinicio inmediato, conociendo que cortaría una sala de voz activa.

Después se comprobaron 14 contenedores persistentes en ejecución, saludables todos los que tienen healthcheck; ninguna unidad systemd fallida y ausencia de `/var/run/reboot-required`. Se confirmó el cambio de identificador de arranque. Docker quedó en **29.9.2**, Tailscale en **1.104.2** y Node del host en **22.23.4**. El kernel observado es `6.8.0-147-generic`. El 27/10 se volvieron a comprobar los 14 contenedores, cero actualizaciones pendientes y el reinicio completado. No se eliminaron volúmenes, conversaciones ni archivos de usuarios.

Diagnóstico privado: `/var/log/novvin-host-maintenance/20261027T010641Z/`, incluidos `result` y `remaining-result` con salida cero. Se conservaron respaldos e imágenes de recuperación. Las credenciales de autorización no se guardan en el repositorio.

## Simulación de carga y límite observado

Se levantó una copia aislada de la imagen API activa `4f97240f0229`, con restauración real de **256 tablas**, estadísticas `ANALYZE`, PostgreSQL separado y red Docker interna. Sin puertos publicados ni volúmenes de producción. Se mantuvieron los límites observados: API 3 CPU/2 GiB, base 2 CPU/1 GiB y pool de 10 conexiones.

La mezcla cubrió perfil, bandejas de chats/grupos/comunidades, feed, lectura invitada y hasta 50 mensajes de conversaciones propias. Se usaron 32 cuentas restauradas, 25 con chat, y sesiones independientes en la copia. Cada fase duró unos 12 segundos. La concurrencia representa solicitudes del generador; **no equivale a usuarios activos**.

| Concurrencia | Solicitudes | Solicitudes/s | p95 | Resultado |
| --- | ---: | ---: | ---: | --- |
| 1 | 79 | 6,6 | 562 ms | 79 HTTP 200 |
| 8 | 422 | 34,5 | 462 ms | 422 HTTP 200 |
| 24 | 260 | 19,1 | 4.760 ms | 260 HTTP 200; latencia elevada |
| 64 | 347 | 25,5 | 7.000 ms | 324 HTTP 200; 23 fallos de transporte o plazo |

La prueba se detuvo en 64 por errores/latencia; no continuó a 128, 256 ni 512. La API de prueba siguió ejecutándose, sin OOM. La sonda de producción pasó de 20 a 19 ms y no activó la protección. Se retiraron exclusivamente contenedores y red temporales identificados como propiedad de la auditoría. `success` en el ejecutor confirma finalización y limpieza, **no aprobación de capacidad**.

`pg_stat_statements`, habilitado solo en la copia, identificó una consulta de perfiles importados con nueve alias y filtrado `to_jsonb`: **790 llamadas, 60.495,2 ms acumulados y 76,6 ms de media**, principal coste SQL de la muestra. La siguiente acumuló 3.254,1 ms. Su optimización y comparación antes/después siguen pendientes; no se aplicó una reescritura SQL en producción en esta entrega.

Sondas públicas acotadas: `/ready` respondió 48/48 en concurrencias 1 y 8; en 24 respondió 44/48 y hubo cuatro fallos de transporte. Las 96 lecturas invitadas y cinco sondas de recuperación respondieron HTTP 200. Estas muestras cortas no son una prueba prolongada, ni cubren cargas de archivos, transcodificación, llamadas o LIVE. **No se aprueba crecimiento masivo con estos resultados.**

## Conexión web sin permiso de red local

La web pública resolvía directamente un hostname que dentro de Tailscale puede apuntar a `100.91.84.2`. El navegador puede tratarlo como acceso de una web pública a red local. Pedir permiso a cada persona no resuelve el producto.

Se conserva el origen lógico firmado y se adapta el transporte de la web oficial a su propio origen HTTPS. `middleware.ts` reenvía exclusivamente rutas API, almacenamiento y señalización previstas al servidor existente, sin destino arbitrario. Conserva autenticación, cuerpos, métodos, rangos de vídeo y parámetros de archivos firmados. Las respuestas API no se almacenan en caché compartida ni se indexan; el almacenamiento conserva su caché de navegador.

Las actualizaciones WebSocket no pasaban por una reescritura HTTP externa en la prueba. `api/novvin-socket.js` conserva los cuatro canales: realtime, salas, llamadas y LIVE. Reenvía tramas y códigos de revocación, comprueba origen, limita colas y no introduce tokens en URL. El máximo configurado es 300 segundos; los clientes mantienen su reconexión existente. Una sesión audiovisual prolongada y su reconexión siguen requiriendo prueba específica. Android conserva su transporte.

Dependencias fijadas: `@vercel/functions` 3.6.1 y `ws` 8.22.0. `npm audit --omit=dev` informó cero avisos conocidos de producción; la instalación completa mantiene cuatro avisos de herramientas de desarrollo, no resueltos aquí.

## Verificación y publicación

- Regresión web previa: **790 casos en 144 suites**, aprobados. Páginas públicas y relé: **66/66**. TypeScript del middleware y compilación Linux de Vercel aprobados.
- Despliegue promovido: `dpl_6Ncp4DfiivBkuVyn2VhNzV1MNVw7`, URL `https://red-social-android-c1iexh0ne-alvaro-s-projects1.vercel.app`, alias `https://red-social-android.vercel.app`.
- Producción: salud HTTP 200, acceso privado sin token HTTP 401, login incompleto HTTP 400, sesión invitada HTTP 201, feed/vídeos invitados HTTP 200, miniatura WebP y rango de vídeo HTTP 206 de 65.536 bytes.
- Sesión diagnóstica revocable: perfil y tres bandejas HTTP 200, realtime y signaling autenticados. Se eliminó mediante el ayudante existente; sin mensajes, publicaciones ni ingreso a salas. El 27/10 las lecturas tardaron 566–1.950 ms: confirman funcionamiento, no una latencia objetivo cumplida.
- **69 controles SEO HTTP** aprobados; detalle en la [revisión 79](79-indexacion-publica-2026-10-27.md).

Reversión web: promover el despliegue anterior de transporte público `https://red-social-android-bnhalhc6u-alvaro-s-projects1.vercel.app`, sin restaurar bases ni perder mensajes. El anterior a la corrección de red era `https://red-social-android-h75x74qjf-alvaro-s-projects1.vercel.app`; volver a él recuperaría el riesgo de permiso de red local.

Evidencias privadas en `artifacts/server-capacity-20261026/` y `/home/alvaro/novvin-candidates/capacity-20261026/`. No se publican volcados, objetos de usuarios, sesiones ni logs privados.

Referencias: [red local en Chrome](https://developer.chrome.com/blog/local-network-access), [rewrites de Vercel](https://vercel.com/docs/routing/rewrites), [WebSockets en Vercel](https://vercel.com/docs/functions/websockets).
