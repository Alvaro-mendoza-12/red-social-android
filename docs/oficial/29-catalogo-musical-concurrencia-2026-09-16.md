# Catálogo musical: concurrencia y recuperación

Fecha: 2026-09-16. Rama: `feature/live-gifts`. Continuación de 28, sin dispositivo Android conectado.

## Fallos encontrados

`loadMoreApprovedMusic` lanzaba una tarea sin conservarla ni asociar sus resultados a la consulta inicial. Al cambiar búsqueda, categoría o sección, la página anterior podía añadir canciones o errores a la lista nueva. Cancelar sólo la primera página no bastaba; el cliente HTTP también convertía cancelaciones en un `Result.failure`, permitiendo iniciar el fallback comunitario después de abandonar la búsqueda.

Una actualización de la misma consulta vaciaba siempre la lista y mostraba el cargador completo. Los fallos de paginación con canciones visibles no tenían mensaje propio ni un reintento distinguible. No había un plazo total específico del selector, incluyendo autenticación y descubrimiento del endpoint.

## Cambios por módulo

- `MomentMusicCatalogLoader.kt`: controlador independiente del ViewModel, probado con coroutines y reloj virtual. Invalida inmediatamente la generación, cancela búsqueda y página anterior, comprueba que la respuesta todavía pertenece a la generación vigente y preserva cancelaciones externas. Las respuestas obsoletas no modifican lista, cursor ni errores.
- La actualización de la misma consulta conserva los resultados visibles durante la lectura y ante un fallo. Una respuesta válida vacía sí los elimina. Cambiar consulta, categoría o sección no conserva resultados de otra búsqueda. No se añadió caché global ni persistencia compartida entre cuentas.
- Paginación de una solicitud a la vez, deduplicación por ID, cursor conservado ante fallos y detección de cursores repetidos/cíclicos para evitar pedir indefinidamente la misma página.
- Un plazo total de diez segundos limita cada lectura del catálogo; la espera de escritura de 350 ms ocurre antes de la red. El reintento de la misma consulta no añade ese retraso.
- `MomentMusicPickerDialog`: progreso discreto mientras se actualiza una lista existente, mensaje y botón para reintentar la actualización, y error/reintento específicos de «Cargar más». No se bloquea toda la lista para actualizarla.
- `CommunityMusicApiClient` y fallback Commons: propagan cancelaciones en vez de convertirlas en fallos ordinarios. No cambia la política de derechos ni se añaden fuentes al selector.
- `ReadDeadline`: `withTimeoutOrNull` distingue su propio plazo de una cancelación por tiempo impuesta por el llamante. Un resultado legítimo `null` continúa siendo un éxito.
- `CreateMomentViewModel` delega las lecturas al controlador. El estado tipado se extrae del ViewModel y sus consumidores se actualizan; las operaciones de publicación no se mezclan con esta lógica.

## Validación

Ejecución: `tmp/music-catalog-validation-20260916.log`, con `assembleDebug`, suite `testDebugUnitTest` y `lintDebug`, un trabajador y Kotlin dentro del proceso de Gradle. **BUILD SUCCESSFUL**, salida cero, 18 min 26 s; finalizó a las 17:29 del 16/09. Informe Lint nuevo, no reutilizado del 14/09: **cero errores y 196 advertencias**. Sigue pendiente reducir esas advertencias; en el código musical señalado son sugerencias `UseKtx` para `Uri.parse`, incluida la resolución de URL agregada en 28. No se añadieron supresiones ni se desactivaron verificaciones. `git diff --check` pasó en los archivos versionados tocados; sólo avisó sobre la normalización CRLF del repositorio.

`assembleDebug` completó y la APK fue verificada con `apksigner`: SHA-256 del archivo `b37bd99e40b82018195a70d967efe50e015ee0eb6a51c222ea32ffcfd984cf6e`, certificado debug `36d82212584fbfe06adcb38f99590e45bca492c52e8432aab42eb9f0fbf290f9`. Ruta: `app/build/outputs/apk/debug/app-debug.apk`. No es una versión release de Play y no se instaló en un teléfono.

Comprobación de servidor a las 17:18 (Bogotá): API `running healthy`, imagen SHA-256 `33508c74f6d10b89630285ccc7d9558274bfb11324aded2dc1fcfc3753d2a51c`, iniciada el 14/09, contador de reinicios cero. Los hashes de `music-routes.js`, `community-music-policy.js` y `moment-music-renderer.js` coinciden con el despliegue musical ya validado. No se desplegaron cambios ni se reinició el contenedor. Esto prueba salud del proceso, no la experiencia de reproducción en una red móvil.

Resultado de las 17:19, XML nuevos de 148 suites: **670 pruebas, cero fallos, cero errores, cero omitidas**. Las doce nuevas pruebas del controlador pasan: escritura rápida, primera página tardía, página tardía con éxito/error, cambio durante debounce, cambios de categoría/sección, actualización conservando canciones, respuesta vacía válida, deduplicación/ciclo de cursor, timeout de primera página/paginación, cancelación cooperativa y excepción de lectura. También pasan las cinco de `ReadDeadline` (incluyendo plazo externo y resultado nulo), las tres de mensaje de error, las ocho de recorte musical, las dos de portada y las veinte de recuperación de mensajes programados. No se retiró ningún test para obtener este resultado.

## Límites y reversión

No se publicaron canciones, no se modificó el servidor ni se instaló la APK en el teléfono. Este cambio no certifica la app para producción ni reemplaza la prueba táctil/Media3. Continúan abiertos la privacidad de buckets de mensajería, las pruebas entre dos extremos de LIVE/llamadas, el hostname estable y las carreras de PlayMatch descritas en 25.

Para revertir, retirar sólo el controlador y reconectar las dos funciones del ViewModel y su estado anterior, revertir sus consumidores UI y la propagación de cancelación del cliente musical; conservar los cambios de recorte de 28, portadas y duración completa de 27. No restaurar archivos completos de Git sobre las otras modificaciones no confirmadas del proyecto.
