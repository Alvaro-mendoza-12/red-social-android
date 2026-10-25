# Continuación de fluidez y avatares — 23 de octubre de 2026

Versión configurada: **9.1.22 / versionCode 162**, tanto en `main` como en `feature/live-gifts`.

## Cambios de esta continuación

- El historial observa los mensajes visibles mediante `snapshotFlow`, fuera de las lecturas de estado de la pantalla completa. Se conserva la comprobación de índice y clave durante actualizaciones del listado y se incluyen las imágenes agrupadas para los recibos de lectura.
- La fila del audio activo observa su posición y duración. Las filas de texto e imágenes no observan el reloj de reproducción; los audios inactivos dejan de observarlo. Esto se aplica a chat directo, grupo y comunidad.
- Se prepara una ventana pequeña de filas cercanas al desplazamiento y se aísla la capa que dibuja el fondo. La escritura evita persistir otra vez el mismo borrador al cambiar solo la selección o composición del teclado.
- La precarga de archivos deduplica solicitudes pendientes por cuenta, sesión y origen; tiene dos descargas simultáneas y una cola acotada. Revalida permisos, preferencias y sesión al conseguir un turno.
- Social carga la identidad propia desde el almacenamiento local sin esperar a la petición del feed. Los autores incompletos se completan mediante una consulta local por lote antes de publicar el listado.
- Home recupera la URL del avatar propio de una instantánea compacta vinculada a la cuenta, sin construir `UserRepository` en el hilo principal. Las instalaciones anteriores pueden recuperar su perfil guardado.
- El avatar visible conserva sus píxeles en memoria antes de la escritura a disco. La persistencia deduplicada pertenece al almacén, de modo que navegar no la cancela. Las imágenes ya decodificadas evitan una rasterización adicional en Main; las capturas de animación y vídeo se limitan a 256 px.
- GIF, WebP y APNG conservan la petición del original animado. Los vídeos de avatar mantienen su reproductor y una imagen guardada durante la preparación.
- Se conservan fotos ante respuestas parciales, pero las mutaciones de perfil confirmadas permiten actualizar o eliminar expresamente la imagen del autor. Se mantiene la protección por cuenta y generación para las escrituras tardías.

## Verificación

Compilación final `BUILD SUCCESSFUL`: APK QA `9.1.22-qa`, código 162 y APK de instrumentación. **61 pruebas unitarias seleccionadas: 0 fallos, 0 errores, 0 omitidas**, en 12 suites. Registro: `artifacts/fluidez-162-final-20261023.log`.

**21 pruebas instrumentadas: 0 fallos**, en un emulador Android API 35, duración 48,687 s. Incluyen teclado/IME y borrador con emojis (6), pegado (2), posición inicial del chat (1), aislamiento de scroll/audio (2), aislamiento del editor (2), continuidad de avatares (2), GIF animado (1), WebM y controles de reproducción (4) y portada de vídeo opaco (1). Registro íntegro: `artifacts/fluidez-162-instrumentation-20261023.txt`. El búfer de cierres del proceso no registró fallos durante esa ejecución.

Se añadieron regresiones sobre conservación tras cancelar una pantalla, captura acotada, recuperación del avatar propio entre versiones, eliminación, separación de cuentas y aislamiento de las actualizaciones de scroll/audio. Las pruebas instrumentadas de aislamiento son verificaciones funcionales de recomposición; no equivalen a una medida de fotogramas en un teléfono real.

Durante esta continuación ADB dejó de detectar el Moto g04. La última ejecución física pendiente debe cubrir arranque, entrada inmediata a un chat, desplazamiento de historial, teclado, reproducción y regreso a Social con caché caliente. No hay todavía una nueva medición física que demuestre ausencia de tirones.

Los intentos interrumpidos, la compilación anterior 161 y las pruebas de una versión anterior no se presentan como validación del APK 162. No se han publicado estos cambios ni se ha certificado todo el conjunto de solicitudes anteriores.

## Referencias técnicas consultadas

- [Android: rendimiento de Jetpack Compose](https://developer.android.com/develop/ui/compose/performance): limitar trabajo costoso durante composición y verificar el rendimiento con una compilación optimizada.
- [Android: listas en Compose](https://developer.android.com/develop/ui/compose/lists): claves y tipos de contenido para listas; medición fiable en release con R8.
- [Coil: imágenes previas desde memoria](https://coil-kt.github.io/coil/recipes/): reutilizar una imagen ya cargada como imagen previa síncrona. El código local conserva píxeles por cuenta, además de la caché de Coil existente.

## Evidencia y límites de la entrega

Las copias previas de los archivos reflejados están en `artifacts/continuacion-20261023/`. Las pantallas de ambas ramas mantienen sus diferencias de producto; se aplicaron los mismos cambios de observación, audio y caché. No se sustituyó el checkout ni se descartaron cambios ajenos.
