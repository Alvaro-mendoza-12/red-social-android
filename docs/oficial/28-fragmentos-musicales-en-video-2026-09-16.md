# Fragmentos musicales fuera de los primeros 15 segundos

Fecha: 2026-09-16. Rama: `feature/live-gifts`. Trabajo sin teléfono por indicación del usuario.

## Causa reproducida en código

`MomentMusicPickerDialog` usaba `min(duraciónCanción, duraciónVideo, 60000)` tanto para la longitud máxima del fragmento como para el final de la línea de tiempo de la canción. Para un video de 15 segundos, no se podía escoger 2:00–2:15 de una canción de cuatro minutos. El servidor y el exportador ya aceptaban inicios posteriores; la UI los hacía inaccesibles. Esto es distinto de escuchar un preview corto, corregido anteriormente.

## Implementación

- `MomentMusicTrimPolicy.kt`: separa posición en la canción completa, longitud del fragmento, mínimo aplicable y fades. Mantiene los límites actuales de Momentos, sin convertirlos en un límite al inicio de la canción.
- Control «Inicio en la canción»: mueve el fragmento entero manteniendo duración. Un video de 15 segundos puede usar 0:00–0:15, 2:00–2:15 o los últimos 15 segundos. El otro control permite acortarlo.
- Al volver al editor se conserva el inicio elegido. Si cambia la duración disponible se ajusta el largo sin regresar al segundo cero.
- Videos parten con un fragmento que cubre su duración admitida; las fotos conservan su valor inicial de seis segundos.
- Los controles de escucha de la selección usan el audio completo y el modo de recorte. Progreso relativo al fragmento; fades acotados al recorte nuevo. La columna es desplazable para evitar controles inaccesibles en pantallas pequeñas.
- `CreateMomentViewModel`: la mezcla del video resuelve la URL actual de Ubuntu igual que la vista previa. Antes podía previsualizarse bien y fallar la descarga final usando un hostname de túnel antiguo. Los URI locales siguen teniendo prioridad; no cambia la identidad enviada al servidor para validar derechos.

## Evidencia

- Ocho regresiones Android nuevas para desplazamiento, restauración, máximos, mínimos, final de canción y fades: **todas pasan**, XML del 16/09 12:14. Suite Android completa: **655 pruebas, cero fallos, cero errores y cero omitidas**. Las dos pruebas de portadas también pasan. No equivale a una prueba táctil o del codec en el teléfono.
- API completa: 160 pruebas; 159 pasan, una omitida, cero fallos. Dos nuevas regresiones comprueban 2:00–2:15 y 4:15–4:30, además del rechazo de inicios fuera de la canción. `tmp/api-music-offset-tests-20260916.log`.
- Herramientas de calidad: seis pruebas de `test_media3_dependency_guard.py` y dos de `test_edge_upload_streaming_contract.py` pasan. Comprobación de imports de fuentes API sin dependencias faltantes. Son controles de configuración/código, no una prueba de carga o reproducción física.
- `scripts/verify-moment-music-offset.js`: contenedor desechable de la imagen activa, sin red y con almacenamiento en tmpfs. Audio sintético de 4:30 con tonos diferentes antes/después de 2:00 y después de 4:15. La rutina real `momentMusicTranscodeArgs` produjo clips desde 0:00, 2:00, 4:00 y 4:15 con las frecuencias esperadas. Se comprobó contenido de audio decodificado, no sólo duración/argumentos. El último contenedor AAC midió 15014 ms por su empaquetado; tolerancia de comprobación menor a 100 ms. Original intacto por SHA-256. `tmp/music-offset-renderer-20260916.log`.
- La primera ejecución de ese fixture falló por inicialización del directorio de storage al importar el módulo; se corrigió el aislamiento de la prueba antes de repetirla satisfactoriamente. No se modificaron rutas ni datos del servidor para hacerla pasar.
- No hace falta desplegar una API nueva para esta corrección de posición: la API activa del 14/09 ya respeta esos offsets. Salud e identidad de imagen verificadas el 16/09.
- Lecturas públicas del 16/09: Social 926 ms/37 publicaciones; Videos 770 ms/12 elementos; perfiles 595 ms/20; dos rangos MP4 con 206 en 918 y 677 ms. Momentos respondió con una lista vacía, salas con dos elementos, juegos con 30 y regalos con 24. No había LIVE activo para verificar recepción de regalos. `tmp/services-music-offset-20260916.log`. Son lecturas desde el servidor hacia su endpoint público, no una prueba de latencia móvil ni entrega audiovisual.

## Qué falta verificar

APK debug generada a las 12:09 del 16/09: SHA-256 `0fb93e7e0e8f2f2c4044eeed4b5afb1cd4cc72c5256ac7c62657373dd8f31584`. Firma verificada con `apksigner`: certificado debug `36d82212584fbfe06adcb38f99590e45bca492c52e8432aab42eb9f0fbf290f9`. No es AAB firmado para Play.

La primera ejecución de la suite terminó inesperadamente dentro del proceso de tests tras empaquetar correctamente (`tmp/music-trim-build-20260916.log`). Se aisló `CommunityMusicArtworkTest` con `UnitTestApplication`, igual que las otras pruebas de repositorios, para no inicializar los servicios reales de la aplicación. No se eliminó ni omitió ninguna prueba. La suite repetida pasó en `tmp/music-trim-validation-20260916.log`; el análisis Lint sigue pendiente al escribir esta sección. La coincidencia temporal con la prueba de portadas no demuestra por sí sola la causa del cierre del proceso.

Actualización a las 17:29: la continuación [29](29-catalogo-musical-concurrencia-2026-09-16.md) incluye una APK más reciente y completó compilación, 670 pruebas aprobadas y Lint con cero errores/196 advertencias. Esa ejecución sí cerró satisfactoriamente la validación estática, además de los cambios de concurrencia del catálogo. La ejecución anterior de Lint se había interrumpido sin informe nuevo.

Sin teléfono no se ha probado la interacción táctil, la sincronización audiovisual ni la exportación Media3 real de este cambio; el test ffmpeg cubre el renderer del servidor, no sustituye esa comprobación Android. Tampoco se instalaron APK ni se publicaron Momentos reales.

La continuación del catálogo musical y sus respuestas antiguas de paginación se documenta en [29](29-catalogo-musical-concurrencia-2026-09-16.md). Las búsquedas de PlayMatch presentan la carrera documentada en 25, todavía pendiente. La puerta de privacidad multimedia, las pruebas LIVE/llamadas entre dos extremos y el hostname estable siguen siendo requisitos pendientes para producción.

La puerta de privacidad se volvió a ejecutar el 16/09 con archivos sintéticos y Express real: ocho rutas públicas/legadas de los cuatro buckets privados entregaron el archivo sin autenticación. `tmp/messaging-media-privacy-20260916.log`. Sigue fallando y no se ha omitido ni debilitado para declarar la app lista. Su corrección exige coordinar permisos por objeto y URLs de acceso entre servidor y clientes; no se cerraron rutas reales durante estas pruebas musicales. Sólo se eliminaron los archivos desechables creados por el verificador.
