# Auditoría y mejoras de Android — 20 de septiembre de 2026

Trabajo en `D:\red social - live-gifts`, rama `feature/live-gifts`. Esta es la copia con Mensajes, Videos, Social, Juegos y Perfil indicada por el usuario. Se conservaron los cambios que ya existían en ella. Este informe registra cambios y evidencia; no certifica que toda la aplicación esté libre de errores.

## Errores comunicados por el usuario

### El video enviado muestra portada pero no reproduce

El resolvedor de mensajes puede entregar un archivo local del caché privado (`file:/…/.media`) o una subida pendiente. La portada podía abrir ese archivo, pero el origen configurado dentro de la caché de Media3 sólo admitía HTTP. Ahora utiliza `DefaultDataSource` con el cliente HTTP compartido, que también abre archivos locales y proveedores `content:`. El visor registra sus eventos antes de preparar, respeta el ciclo de vida y permite reconstruir el reproductor al reintentar, invalidando sólo la entrada multimedia fallida.

La regresión unitaria abre archivos reales y comprueba sus bytes y una lectura parcial con la caché Media3 activada. Además, la prueba instrumental en Android 15 reprodujo un MP4 real guardado con extensión `.media`, alcanzó READY y renderizó el primer fotograma usando la fábrica de caché de producción. No verifica el video concreto del usuario ni todos los códecs Android.

### La portada incorporada a una canción es rechazada

La portada extraída se guardaba correctamente como JPEG local. La subida intentaba deducir nombre/formato únicamente mediante `ContentResolver`, que puede no dar MIME ni nombre para `file:` o imágenes de álbum. Ahora se comprueban los bytes reales y se genera una copia JPEG de hasta 1024 píxeles antes de iniciar la transferencia del audio. Nombre, extensión y MIME concuerdan. Se rechaza una imagen ilegible antes de subir la canción, y se limpian archivos temporales y transferencias incompletas conocidas.

Detalles: [mensajes](audit-messages-2026-09-19.md), [música de comunidad](audit-community-music-2026-09-19.md).

## Opciones integradas

| Sección | Comportamiento disponible |
| --- | --- |
| Mensajes | Selector propio de documentos y visor interno compartido por chats, grupos y comunidades. Espacio privado con pendientes, cronograma con fechas, notas de contexto y borradores recuperables, separado por cuenta y canal. Datos locales cifrados; no se capturan mensajes de una sola vista o eliminados. |
| Videos | Punto privado con nota y regreso al segundo guardado; repetición de un tramo de hasta diez segundos; bloques de reproducción de 5, 15 o 30 minutos. |
| Social | Guardar el video original de un momento; desactivar el avance automático; buscar textos cargados por tema/autor y tiempo estimado de lectura; historial y retirada de canciones propias; búsqueda e invitaciones por chat para salas de voz. |
| Juegos | Filtro de jugadores disponibles ahora, limpiar filtros y búsquedas/páginas coordinadas para evitar mezclar resultados de consultas anteriores. |
| Perfil y ajustes | Categorías plegables, búsqueda con equivalencias de palabras y acentos, y acceso a las opciones existentes sin recorrer todos sus controles desplegados. |

Estas son funciones concretas de esta implementación; no se afirma exclusividad mundial. Los borradores, notas y puntos de video son locales, no sincronizados entre teléfonos. La descarga de momentos conserva el archivo original y no incorpora textos superpuestos ni música agregada por separado; el diálogo lo explica.

El cronograma ordena pendientes privados por fecha: no programa envíos ni genera alarmas. El selector de documentos requiere autorizar carpetas una vez mediante Android. El visor permite leer PDF, editar texto y crear copias TXT/PDF; DOCX/XLSX/PPTX se leen como contenido sin diseño ni recálculo. No equivale a un editor universal de cualquier formato. Detalles y límites en [documentos](audit-documents-2026-09-20.md).

## Fiabilidad y mantenimiento

- Progreso de video aislado del resto de la pantalla y búsqueda de posición al terminar el arrastre. Se evita trabajo de recomposición repetido, sin afirmar mejoras de FPS no medidas.
- Subidas, preparación de metadatos, exportaciones y preparación de directos protegidas frente a acciones duplicadas o resultados obsoletos. Lectura de metadatos fuera del hilo de interfaz.
- Búsqueda unificada con espera breve al escribir, resultados independientes de personas/contenido, plazo acotado y protección frente a respuestas tardías.
- Juegos usa consultas tipadas independientes; cancelar una sesión sólo presenta éxito cuando lo confirma el servidor. La paginación elimina duplicados y detiene cursores repetidos.
- Descarga de momentos por bloques, con límite de 80 MB, cancelación, selección del destino del usuario y eliminación de archivos parciales.
- Realtime limita tamaño de mensajes, suscripciones y conexiones lentas; comprueba caducidad del token y revalida identidad y permisos durante conexiones largas.
- API comprueba pertenencia antes de eventos de escritura y añade lectura de mensajes visibles. La publicación de eventos tiene plazo de red y destinatarios únicos.
- El chatbot interpreta seguimientos breves dentro del historial recibido y evita perder caracteres UTF-8 fragmentados entre bloques HTTP. Sigue siendo el motor de conocimiento existente; no se sustituyó por un modelo generativo.
- La lógica nueva de búsqueda, descubrimiento, lectura, descarga y herramientas de reproducción está separada en componentes pequeños con pruebas dirigidas.
- En salas de voz, el botón de invitación anunciaba éxito con una implementación vacía. Ahora busca usuarios y entrega la invitación al chat mediante el repositorio de mensajes, con protección frente a pulsaciones repetidas y mensajes explícitos ante resultados inciertos. Buscar salas cancela consultas antiguas y deja de mostrar resultados al borrar el texto.

## Evidencia de validación

Las compilaciones y pruebas finales se registran en `build/audit-2026-09-19/`.

- API: comprobación sintáctica aprobada; 193 pruebas aprobadas y una omitida, sin fallos, incluyendo historial y eliminación de canciones.
- Gateway realtime: comprobación sintáctica y suite de autenticación/autorización HTTP-WebSocket aprobadas, incluyendo caducidad y revocación durante una conexión.
- IA: comprobación sintáctica y seis suites aprobadas, incluida continuidad contextual.
- Auditorías estáticas de seguridad SQL/runtime: sin hallazgos en los controles automatizados ejecutados.
- Android: compilación Kotlin y empaquetado de la aplicación y pruebas instrumentadas aprobados. 789 pruebas unitarias aprobadas, sin fallos ni omisiones. `lintDebug` aprobado: cero errores y 206 advertencias (192 sugerencias KTX, 12 recursos sin uso y dos duplicidades de formato de icono); se conserva el informe completo.
- Instrumentación: **7 de 7 pruebas aprobadas en Android 15 / API 35**, emulador aislado `NovvinAudit`. Cubren reproducción real de video desde caché, normalización nativa de portada PNG sin extensión a JPEG, cifrado y separación de cuentas con Android Keystore, creación/renderizado de PDF conservando el original, interfaz de edición/envío de copia y dos casos de configuración plegable/búsqueda. Resultado: `OK (7 tests)` en 13,732 segundos.

ADB no detectó teléfonos físicos conectados. Se instaló y verificó la misma APK entregada en el emulador, sin cuentas de usuarios ni mensajes enviados a personas. Las pruebas de envío de documentos comprueban el callback y el archivo que recibe; no representan una transferencia completa entre usuarios contra producción.

## APK de prueba entregada

- Archivo: `build/audit-2026-09-19/Novvin-3.3.6-debug.apk` (269.563.079 bytes).
- Paquete: `conectate.com`; versión real del artefacto `3.3.6-debug`, código 68; mínimo Android API 24, objetivo API 36.
- Firma de depuración Android, verificada mediante `apksigner` con esquema v2.
- SHA-256: `E5A58AC5AD4E02CA7AF830A55DA8736DD1CA0407DC691F972F030F0192DF0333`.
- Evidencia reproducible: `android-unit-summary.json`, `android-documents-verified.log`, `android-lint-final.log`, `lint-results-debug.html`, `android-instrumentation.log`, `android-instrumentation-summary.json`, `apk-signature-verification.log` y `run-android-smoke.ps1` en esa misma carpeta. El script de instrumentación comprueba el nombre del emulador aislado antes de instalar.

## Condiciones pendientes para publicar

1. Probar en Android el envío y apertura del video original que falló, reproducción sin conexión de medios retenidos, subida de una canción con portada incorporada y audio recortado. Las pruebas automatizadas no sustituyen esas comprobaciones.
2. Integrar y desplegar el servidor de esta rama, especialmente `/v1/data/messages/chats/:chatId/read-visible`, `/v1/data/music/my` y la eliminación de canciones, antes de distribuir el cliente. No se ha desplegado ni modificado un servidor de producción en este trabajo. El cliente no vuelve al marcado global al faltar la ruta, para evitar marcar mensajes no vistos.
3. Probar llamadas y directos con anfitrión/espectador en dos equipos y redes distintas, incluyendo permisos, reconexión y cambios entre primer y segundo plano. Medir fluidez y consumo del feed en equipos modestos.
4. Generar la entrega de publicación con la llave original. En esta copia no existe `keystore.properties`, las cuatro variables `NOVVIN_UPLOAD_*` de firma están ausentes y sólo se ha encontrado la llave de depuración. No se ha sustituido ni fabricado una firma de producción.

La APK de depuración servirá para validar estos cambios. Una compilación correcta no equivale a una publicación verificada ni demuestra capacidad para tráfico masivo; eso requiere las pruebas anteriores y mediciones de carga del despliegue real.

## Actualización de esta ejecución

- El selector de documentos ya no depende de que exista una carpeta autorizada: `OpenDocument` permite elegir un PDF, Word, Excel, PowerPoint, texto u otro archivo, conserva el permiso de lectura y lo incorpora al catálogo interno. La vista previa de Office se muestra en tarjetas con ajuste de línea y selección; PDF se renderiza página a página y el texto conserva edición y exportación.
- El historial de canciones propias tiene botones visibles con contraste en ambos temas. El botón de eliminación conserva la confirmación y actualiza la lista; el servidor valida propietario, estado y referencias antes de retirar una canción.
- En Momentos, una pista local ya mezclada por Android no se vuelve a transcodificar en el API. Se conserva su metadata y se evita el segundo acceso al archivo temporal que provocaba el fallo de publicación; las pistas externas y los Momentos de imagen siguen pasando por el render AAC-LC del servidor.
- Contador de mensajes, grupos y comunidades: la lectura se registra por cuenta, conversación y mensaje visible; cambiar de cuenta o abrir una pantalla no marca mensajes que no se vieron. La marca remota se reintenta cuando vuelve la conectividad y el contador local se mantiene exacto.
- Evidencia actual: **801 pruebas unitarias Android, 0 fallos, 0 omitidas**; `assembleDebug` y `assembleDebugAndroidTest` correctos. `lintDebug` también termina sin errores (210 advertencias existentes). La suite API completa termina con **200 pruebas, 199 aprobadas, 0 fallos y 1 omitida**. APK: `build/audit-2026-09-20-runtime/Novvin-3.3.6-runtime-debug.apk`, 269.612.215 bytes, SHA-256 `88DCDB6FCFE2E919A47747B2F64E900EF7D56E9F332B7298A0B5CA5527F9FF68`, firmada con la llave de depuración estable del repositorio (`36D82212584FBFE06ADCB38F99590E45BCA492C52E8432AAB42EB9F0FBF290F9`). Los logs completos son `android-validation-final.log`, `android-lint-final.log`, `android-assemble-final.log` y `backend-all-tests-final.log` en esa carpeta.
- Se creó y verificó el respaldo remoto `/home/alvaro/conectate-backups/20260921T034558Z` (PostgreSQL, storage y datos de IA). Las imágenes nuevas del API y los servicios quedaron saludables en la mini-PC y `/ready` local/público responde 200. Las rutas de historial (`/v1/data/music/my` y DELETE de música) y publicación de Momentos responden 401 sin token, confirmando que están montadas y protegidas. La verificación de contenido desde dentro del contenedor no pudo completar las imágenes porque el túnel efímero no permite hairpin hacia sí mismo; por eso el marcador de release conserva el identificador anterior aunque los contenedores ya contienen el código actualizado. Hace falta repetir ese smoke desde una red externa o configurar el dominio HTTPS estable antes de llamarlo producción.
