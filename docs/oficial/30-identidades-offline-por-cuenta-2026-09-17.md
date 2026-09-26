# Identidades sin conexión: retención y cuentas

Fecha: 2026-09-17. Rama: `feature/live-gifts`. Continúa el objetivo general; no lo reduce a este módulo ni certifica producción. Trabajo sin dispositivo físico, según la indicación vigente del usuario.

## Causas verificadas

El almacén de identidad en `noBackupFilesDir/profile-identity-images-v1` era compartido y expulsaba imágenes por LRU: máximo 96 imágenes/24 MiB. Ver más perfiles podía desplazar fotos de la bandeja. `InboxIdentityCache` sólo precargaba las primeras 24 URLs, incluso si ya estaban disponibles. Los componentes consultaban el sistema de archivos directamente durante composición. El guardado eliminaba primero la versión existente antes del rename: un fallo de publicación podía perder la última copia completa.

## Implementación por módulo

- `ProfileIdentityFiles.kt`: subdirectorios con hash de cuenta y de referencia de imagen. Una cuenta vacía no se convierte en caché anónima. Las miniaturas de cuentas no participan en el LRU genérico. Cada archivo admite hasta 1 MiB y la escritura conserva una reserva de 64 MiB libres; ante falta de espacio falla la copia nueva, sin expulsar fotos ya retenidas. Publicación desde temporal sincronizado en el mismo directorio, sin borrar primero la copia anterior. Se intenta un `File.renameTo` local; si no puede publicar la sustitución se conserva el destino anterior. La prueba de fallo de publicación es inyectada y no sustituye una prueba de interrupción física del almacenamiento del teléfono.
- `ProfileIdentityImageStore`: imágenes limitadas a 512 px en su lado mayor, sin ampliar las pequeñas. Lectura, reducción, codificación y publicación fuera del hilo de UI. No se modifica ni recicla el bitmap propiedad de Coil o del llamante. Se conserva el poster de un avatar de video una vez renderizado; no implica conservar toda su animación.
- `ProfileIdentityWriteFence`: la limpieza invalida la generación capturada antes de la descarga/renderizado. Las escrituras y limpiezas usan el mismo bloqueo; una respuesta anterior no puede volver a crear los archivos eliminados. Otra cuenta no pierde sus solicitudes ni sus archivos.
- Migración al leer: sólo la referencia exacta solicitada puede copiarse del almacén genérico anterior al de esa cuenta. No se copian directorios enteros ni archivos de otra cuenta. La limpieza marca la cuenta para no volver a importar inmediatamente esas copias legadas. Los archivos anteriores se preservan para evitar una pérdida de disponibilidad durante la actualización.
- `ProfileIdentityWarmup`: máximo tres trabajadores por lote, con semáforo global de tres cargas. La bandeja ya no corta permanentemente en 24 identidades; recorre las URLs recibidas, deduplicadas. Cada lectura de red tiene un límite de seis segundos. Sin conectividad validada, la precarga sólo usa memoria/disco de Coil y no inicia descarga. Un refresco posterior puede volver a intentar las identidades faltantes. Se excluyen URLs de video del precalentamiento para no descargar un video completo al buscar una miniatura.
- `InboxIdentityCache` recibe explícitamente la cuenta y la generación capturadas al comenzar la lectura de mensajes, antes de Room y de la red. `UserRepository` captura `ProfileIdentityRequest` (cuenta y generación) **antes** de consultar perfiles, obtener tokens o suspenderse por Room. Las siete salidas que precalientan identidades reciben esa captura; una respuesta de la cuenta anterior no se redirige a la nueva cuenta. La generación anterior también se conserva hasta el precalentamiento, para respetar una limpieza durante la consulta del perfil o de la bandeja.
- `LocalProfileIdentityAccount` se proporciona al `AppNavGraph` desde la identidad local del repositorio de autenticación, no desde el sujeto de un token migrado. Durante cambio de cuenta o sesión no autenticada se desactiva la persistencia de esa composición. `CircularProfileImage` y el visor ampliado usan esa cuenta y lecturas asíncronas; el estado de respaldo se renueva al cambiar de cuenta.
- `ProfileOfflineStorageSection`: opción separada en los ajustes de mensajes para consultar cantidad/espacio y eliminar las miniaturas de la cuenta confirmada. No borra fotos de perfil remotas, galería, mensajes ni otras cuentas. Una confirmación antigua no puede limpiar la cuenta nueva. El aviso aclara que al volver a ver perfiles las imágenes pueden guardarse otra vez; no promete borrado seguro de todas las cachés de Coil/Media3.
- `OfflineRoutePolicy`: la pantalla `fullscreen_image` ya no queda tapada por el bloqueo global de red al ampliar el avatar del chat. Se separa la política de navegación del archivo de actividad y se comparan destinos completos, no prefijos como `home_cualquier_cosa`. Las pantallas LIVE y otras que requieren red siguen bloqueadas. El visor puede mostrar su copia local; esto no hace disponibles imágenes que nunca se recibieron.

`file_paths.xml` sólo expone rutas concretas de caché; no tiene `root-path` ni rutas que incluyan este almacén en `noBackupFilesDir`. No se cambió FileProvider.

## Validación

Validación final de esta etapa: `tmp/profile-identities-resumed-validation-20260917.log`, con `assembleDebug`, `testDebugUnitTest` y `lintDebug`; un trabajador, JDK 21 y Kotlin dentro de Gradle. **BUILD SUCCESSFUL en 18 min 15 s**, terminado el 17 de septiembre a las 14:09:55 de Bogotá. Incluye la captura temprana de cuenta/generación, la política del visor offline y la corrección de las advertencias nuevas introducidas durante el desarrollo.

Se añadieron pruebas de más de 96 imágenes por cuenta frente al LRU genérico, reinicio del almacén, separación y normalización de cuentas, reserva de espacio, publicación fallida, tamaño máximo, limpieza confirmada, migración legada, nombres sin identificadores/URLs en claro, dimensiones y exclusión de video. También se añadieron pruebas de generaciones, un lote de 140 identidades con tres cargas simultáneas y cancelación de trabajos pendientes. Cinco pruebas con gráficos nativos de Robolectric codifican/decodifican WebP reales y ejercitan guardado, cambio de origen, migración y limpieza con escritura anterior.

Los XML de `app/build/test-results/testDebugUnitTest`, renovados a las 14:00:50, suman **697 pruebas en 154 suites, cero fallos, cero errores y cero omitidas**. Las 27 nuevas son 10 de `ProfileIdentityFilesTest`, cinco de `ProfileIdentityPersistenceTest`, dos de `ProfileIdentityWriteFenceTest`, dos de `ProfileIdentityWarmupTest`, una adicional de `InboxIdentityCacheTest`, cuatro de `ProfileIdentityRequestTest` y tres de `OfflineRoutePolicyTest`. También pasan las ocho pruebas de fragmentos musicales y las doce de concurrencia del catálogo de la tanda previa. Las advertencias nuevas de nulabilidad de `File.parentFile` en tests se corrigieron antes de esta ejecución.

Lint, informe de las 14:09:54: **cero errores, 196 advertencias**. Se eliminó la advertencia nueva sobre escalado de Bitmap usando la extensión KTX equivalente. No se añaden ni se ocultan errores en un baseline de Lint.

APK de prueba: `app/build/outputs/apk/debug/app-debug.apk`, generado a las 13:59:33; 269.202.615 bytes. SHA-256: `34d893ad7843542c1295ba47e3ff00e5965b460d727905e248a9fe70aed88605`. `apksigner verify --print-certs` pasó; certificado debug SHA-256 `36d82212584fbfe06adcb38f99590e45bca492c52e8432aab42eb9f0fbf290f9`. No se generó AAB release ni se instaló esta versión. Este hash corresponde a esta etapa: futuras compilaciones pueden reemplazar el mismo archivo.

Historia de ejecuciones: `tmp/profile-identities-validation-20260917.log` terminó con 690 pruebas en un estado intermedio; su APK de las 00:32:47 (`0c451979ef067c44e66d0638ecaf2cd3765301bfb217969649abdf61175c160e`) no contiene las últimas correcciones. `tmp/profile-identities-final-validation-20260917.log` quedó interrumpido a las 00:47:13 y no cuenta como validación satisfactoria. No se confunden esos resultados con la ejecución final de las 14:09.

## Comprobación de conectividad complementaria

El 17 de septiembre, aproximadamente a las 00:29–00:30 de Bogotá, la consulta SSH de sólo lectura mostró `conectate-local-fallback-api-main` en `running/healthy`, cero reinicios del contenedor y la misma imagen `sha256:33508c74f6d10b89630285ccc7d9558274bfb11324aded2dc1fcfc3753d2a51c` de la corrección musical. El servicio efectivo `conectate-cloudflared.service` y `conectate-health.timer` estaban activos. El nombre genérico `cloudflared.service` no es la unidad usada: consultarlo aisladamente no demuestra desconexión.

`/ready` devolvió HTTP 200 desde localhost en 2,556 ms y desde la laptop, por el Quick Tunnel leído en ese momento del servidor, en 1,079 s. Cinco peticiones públicas adicionales, cada una con conexión nueva, devolvieron 200 y duraron 1,064497 / 0,491542 / 0,499919 / 0,539431 / 0,502515 s (mediana 0,502515 s). DNS tomó 18–27 ms y la negociación TLS acumulada desde el inicio terminó en 295–313 ms. Son muestras del endpoint de disponibilidad, no mediciones de feed autenticado, descarga de videos, redes móviles ni capacidad bajo carga. No permiten atribuir toda la demora de la app a una sola causa ni certificar todos los servicios.

No se reinició, desplegó ni modificó ningún servicio. El origen público es temporal y puede rotar; se mantiene pendiente el hostname estable exigido para producción.

## Límites y pendientes

- No se instaló ni probó esta versión en el moto g04. Falta medir la interacción real en modo avión, reinicio de la app/teléfono, cambio de cuenta y recuperación Wi-Fi/LTE. Las pruebas del filesystem y codec de Robolectric no sustituyen esa experiencia.
- Las fotos que nunca llegaron al teléfono siguen necesitando una conexión inicial. Para avatares de video se conserva sólo un fotograma ya obtenido; no se promete animación offline completa.
- Se conservan miniaturas, no el archivo original a resolución completa. La descarga/exportación de originales es otro recorrido y no queda certificada por estas pruebas.
- Los componentes fuera de `AppNavGraph` que no reciben el contexto de cuenta (por ejemplo ciertas superficies separadas de llamada) conservan el fallback anónimo anterior; falta completar su auditoría. Las cachés generales de Coil/Media3 siguen siendo distintas de este almacén privado.
- Sigue pendiente distinguir respuestas parciales de una eliminación explícita de foto en los merges de identidad de la bandeja; mantener una foto ante un dato ausente no demuestra que se respete todavía toda revocación remota.
- No se tocó el servidor. Continúan abiertas la puerta de privacidad de multimedia de mensajes, la validación LIVE/llamadas entre dos dispositivos, el hostname estable y los demás requisitos del objetivo completo.

## Reversión

Los archivos por cuenta están en subdirectorios nuevos del almacén existente. Conservarlos al revertir; el código anterior sólo consulta los archivos genéricos y no sabría leer las nuevas copias. Retirar únicamente la integración de contexto de cuenta, el guardado nuevo y su sección de ajustes, preservando los cambios musicales y los archivos de usuario. No restaurar completos los archivos grandes desde Git sobre otras modificaciones sin confirmar del proyecto.
