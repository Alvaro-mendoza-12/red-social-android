# Música completa, límite de publicación y aviso de Windows

Cambios del 14 de septiembre; evidencia consolidada el 16 de septiembre de 2026.

## Diagnóstico y correcciones

Se inspeccionaron tres pistas existentes con `ffprobe`, en transacción de sólo lectura: originales de 170664, 270696 y 270696 ms. Sus previews separados duran 30000 ms. El problema reproducido en el catálogo era la selección del preview para escuchar; no se demostró que esos originales estuvieran cortados a 15 segundos. No se borraron ni recortaron canciones existentes.

- El catálogo Android usa el original para escuchar música comunitaria. La selección para Momentos sigue teniendo su propia duración y controles.
- `CommunityMusicPolicy.kt` y `community-music-policy.js` delimitan publicaciones nuevas entre 5 segundos y 4:30. Si una pista excede el máximo, el selector abre el recorte y no permite saltárselo con «pista completa».
- El servidor comprueba duración declarada y duración real con `ffprobe`; una duración falsa no evita el máximo. El audio original no se sustituye por el preview. Las canciones históricas ya publicadas no se eliminan por exceder ligeramente el límite nuevo.
- `CommunityMusicMetadata.kt` recupera metadatos y portada del archivo original, incluso cuando se seleccionó un recorte. Reencoda la portada a JPEG acotado a 1024 px y 10 MiB; valida dimensiones antes de decodificar. El diálogo muestra la portada y pasa su URI al upload de miniaturas existente.
- El límite de Comunidad no se aplica a audios completos enviados por chat.

## Verificación

- APK debug compilada el 14/09: SHA-256 `5e76a99a8e1177b8d461bb163c2fe339d214636ccca6e6691e3287a42955dbb8`. No instalada en esta sesión; no es AAB de producción.
- Suite Android: 647 pruebas, cero fallos/errores/omitidas. Lint: cero errores, 195 advertencias. Log `tmp/music-full-tests-retry-20260914.log`, final satisfactorio a las 11:13.
- La primera ejecución del proceso de tests terminó inesperadamente. Las dos pruebas nativas de portadas pasaron aisladas; después pasó la suite completa sin excluirlas. No se atribuye ese fallo inicial a una causa probada. Logs inicial e independiente conservados.
- API: 158 pruebas, 157 pasan, una omitida, cero fallos. `tmp/api-music-final-tests-20260914.log`.
- Imagen candidata: 36 pruebas pasan. `verify-community-music-processing.js` ejecutó ffmpeg/ffprobe reales en contenedor aislado, sin red ni datos reales: 240 y 270 segundos aceptados completos; 300 segundos rechazados incluso declarando 240; archivo inválido rechazado. Los hashes de originales no cambiaron. Sólo se eliminaron fixtures temporales generados por la prueba.
- Catálogo HTTP público: 200 en 673 ms. Las tres pistas originales entregan sus últimos 65536 bytes mediante 206, separadas del preview. No se registraron reproducciones ni publicaciones. `tmp/music-http-20260914.log`.

## Servidor y reversión

Se recreó sólo API, sin reiniciar el servidor ni otros servicios:

- Imagen `conectate-api-music-length:20260914`, ID `sha256:33508c74f6d10b89630285ccc7d9558274bfb11324aded2dc1fcfc3753d2a51c`; salud verificada nuevamente el 16/09.
- `music-routes.js`: `255e421e29097f1631e07e18e2495f1efda64f4ba2093924e3767a3cce958a9e`.
- `community-music-policy.js`: `227395e3149d3c91f199638d280a09aa18907dadb58995ea67090ef61397c001`.
- Fuentes del host reconciliadas. Los arreglos previos de permisos multimedia, música de Momentos y PlayMatch se conservaron, con hashes verificados.
- Release `/home/alvaro/conectate-releases/api-music-length-20260914`; respaldo `host-before.JshfyF/music-routes.js`.
- Imagen anterior `conectate-api-before-music-length:20260914` = `sha256:f5c8bef3092179972716621f4ccbe881af9d95696dabb8d921b12eddfa2340ed`.
- Para revertir: comprobar primero imagen activa y ausencia de otro despliegue; adquirir bloqueo de Compose; reetiquetar la imagen anterior como `conectate-local-fallback-api:latest`; recrear sólo `api` con `--no-deps --no-build`; restaurar `music-routes.js` del respaldo; comprobar `/ready`. Conservar copia de fuentes sustituidas. No es necesario borrar el módulo nuevo no utilizado.

Lecturas HTTP públicas posteriores: Social 1268 ms, Videos 883 ms, perfiles 698 ms; rangos MP4 206 en 728/448 ms; historial de chat 1015 ms. Momentos, salas, retos, juegos y catálogos LIVE/regalos también respondieron. Son muestras del servidor a su URL pública, no mediciones LTE ni certificación audiovisual. `tmp/read-paths-after-music-20260914.log`.

## Aviso repetitivo de Windows Script Host

La tarea exacta `\Conectate Cloudflare Tunnel Watchdog` intentaba ejecutar un VBS inexistente en `D:\red social - copia\scripts`. Se exportó su XML y se desactivó/detuvo esa tarea, sin borrar archivos del proyecto ni tareas ajenas. Quedó `Disabled`, verificado otra vez el 16/09. El proceso WScript asociado terminó.

Respaldo: `tmp/cloudflare-watchdog-task-before-20260914.xml`. No reactivar ese XML sin corregir la ruta y revisar el script: el servidor ya tiene su servicio de túnel y temporizador de salud. No se ejecutó el watchdog viejo ni se alteró el registro.

## Pendientes

La prueba física de publicación, portada embebida y exportación AAC a exactamente 4:30 sigue pendiente: el teléfono no quedó accesible por Wi-Fi y el usuario pidió continuar sin dispositivo. Una prueba de límites no prueba el exportador del fabricante. No se publicaron canciones para probar.

El selector de fragmentos para video tenía un problema distinto: limitaba también la posición dentro de la canción. Se aborda en el documento 28. La privacidad multimedia y los requisitos de producción del documento 25 continúan abiertos; estos resultados no certifican toda la app.
