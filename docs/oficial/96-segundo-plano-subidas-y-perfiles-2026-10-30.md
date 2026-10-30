# 96 — Segundo plano, subidas, video y perfiles

Fecha: 2026-10-30, Bogotá. Continúa la auditoría móvil 95 y desarrolla los cinco puntos solicitados por el propietario. Se usan emuladores y cuentas sintéticas existentes; se distingue la prueba del paquete anterior, el código corregido y el despliegue del servidor.

## 1. Segundo plano y registro push

El callback de renovación FCM y la entrada a la aplicación registraban el token en una corrutina sin reintento durable. Una desconexión podía dejar ese registro sin completar. Ahora `PushRegistrationWorker` usa WorkManager con conexión requerida y espera exponencial, hasta seis intentos por solicitud. Cada ejecución consulta la cuenta y el token vigentes; el trabajo no almacena credenciales ni tokens. `APPEND_OR_REPLACE` conserva otra pasada si llega una renovación mientras termina una anterior. La vuelta al primer plano vuelve a programarlo.

El repositorio serializa los registros y comprueba el guardián de sesión antes de enviar y antes de guardar la confirmación. Limpiar los datos invalida el trabajo anterior. Las regresiones comprueban ausencia de cuenta, cambio de cuenta durante la consulta del token, consulta del token más reciente y propagación de cancelación.

Con el APK firmado **171**, una cuenta sintética envió desde la interfaz un mensaje sin red. Se habilitó Doze en el AVD AOSP —el primer intento indicó que estaba deshabilitado— y se confirmó el estado `IDLE`. `am kill` no había terminado el proceso; se verificó su identidad y se terminó mediante SIGKILL, sin usar force-stop. Tras salir de Doze y restaurar la actividad del sistema, **sin abrir la app**, el servidor pasó de cero a una copia del mensaje; observaciones posteriores mantuvieron una. Se retiró solamente ese mensaje propio y se marcó como leída la conversación sintética.

Esta prueba comprueba la cola existente bajo muerte del proceso y reposo; no demuestra entrega FCM, puesto que ese emulador no tiene Google Play Services. El nuevo trabajo respeta las restricciones de Android y no promete saltarse Doze ni un force-stop del usuario. Alcance contrastado con [Firebase](https://firebase.google.com/docs/cloud-messaging/android/receive-messages), [trabajo persistente](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [trabajo único](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work) y [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby).

## 2. Cancelación y reanudación de archivos

Las peticiones grandes usaban una llamada HTTP bloqueante desvinculada de la cancelación de la corrutina. Se introduce `awaitStorageResponse`: cancelar la tarea cancela la llamada, una respuesta tardía se cierra y el receptor conserva la propiedad de una respuesta entregada normalmente. Se mantiene desactivado el reintento HTTP implícito de escrituras.

Las claves de las sesiones reanudables ahora incluyen SHA-256 del contenido completo, calculado fuera del hilo principal y con comprobaciones de cancelación. Dos archivos con igual nombre, tamaño y tipo, pero bytes distintos, ya no comparten la sesión de una subida anterior. Se exige el tamaño anunciado para detectar fuentes truncadas. La ubicación de una sesión recién creada se valida antes de enviar más peticiones autenticadas: mismo origen y ruta esperada, sin credenciales en URL, consulta ni fragmento. También se refuerza esa validación para las sesiones guardadas.

Las regresiones controladas cubren cancelación de red, cierre de respuesta tardía, ausencia de reintento oculto, propiedad de respuesta, destinos de sesión y huella del contenido. La lectura local para calcular la huella tiene un coste de E/S; no se presenta como una reducción de latencia de red.

La prueba contra producción descubrió además una incompatibilidad real: `OPTIONS` responde 204 por el tratamiento genérico de CORS y no anuncia TUS, mientras que la creación autenticada responde 201 y `Tus-Resumable: 1.0.0`. La app interpretaba ese anuncio ausente como falta de soporte y usaba una subida completa. Se confirmó creando y eliminando una sesión vacía propia, sin transferir archivo. El primer ensayo de interrupción había usado efectivamente la ruta de streaming; un intento de limpiar un objeto que aún no existía ocultaba la aserción del test, por lo que también se corrigió esa limpieza.

Ahora la respuesta de creación de la sesión real determina el soporte, sin una petición `OPTIONS` previa. La sesión guardada se consulta antes de crear otra. Solamente 404/405/501 de la creación habilitan compatibilidad con el servidor anterior; autenticación, límites, errores temporales o pérdida de conexión conservan el error y no provocan otra subida completa. Las cinco regresiones HTTP añadidas comprueban estos contratos y una ubicación de creación ajena al origen. La creación con longitud/versionado, la consulta del offset por HEAD y el envío posterior por PATCH se contrastaron con el [protocolo TUS](https://tus.io/protocols/resumable-upload.html).

La repetición contra producción pasó con el servicio real de la app y un archivo de texto propio de **8.388.608 bytes**. Se canceló en el segundo fragmento: HEAD confirmó **6.291.456 bytes** conservados; el primer progreso del reintento fue 0,765625 y la descarga final coincidió por SHA-256 con los bytes locales. La prueba terminó en 22,255 s, incluida preparación, cancelación, reintento, descarga y limpieza; ese tiempo no representa una subida normal. Se eliminaron la sesión TUS y el objeto propio, y el test finalizó sin omisiones. No demuestra todos los cortes posibles de un proveedor móvil.

## 3. Preparación de páginas de video

La ruta Ubuntu del feed actualizaba y leía estadísticas locales por cada video antes de publicar la página. Ahora `mergeServerPage` realiza una lectura del lote y una escritura transaccional: normaliza identificadores, retira alias, conserva el máximo de vistas ya conocido y acepta del servidor los contadores de reacciones. La prueba Room comprueba una sola lectura de `video_stats` para una página de veinte videos, alias con distinto uso de mayúsculas y página vacía. No cambia el esquema de base de datos.

La medición de primer fotograma se ejecuta por separado con lectura autenticada de una publicación existente y la fuente de medios/caché de la app. Es una muestra de emulador; no representa percentiles de usuarios ni una comparación antes/después del cambio de estadísticas.

La primera muestra válida registró 899 ms para el JSON del feed y 2.807/1.401 ms para el primer fotograma en dos aperturas sucesivas. Otra repetición registró 4.043 ms y 2.420/3.039 ms. Se conserva esa variación; no se presenta una apertura repetida como garantía de mayor velocidad. Se usa el reproductor por defecto con la fuente/caché de la app, no todo el flujo de interfaz ni su perfil de buffering personalizado. El lote Room reduce una sobrecarga demostrada de preparación local; estas muestras no aíslan red, autenticación, decodificación y carga del equipo, ni atribuyen toda la espera al switch o al cable.

## 4. Optimización de perfiles aplicada al servidor

El acceso SSH se renovó con la intervención del propietario. Se comparó el archivo activo con el candidato aislado de la auditoría 85: además de la consulta, aquel candidato contenía otros cambios. Se extrajo únicamente `importedRowsByFieldValues` de la fuente ya versionada en `19a5d8522356`. El resto de `data-routes.js` conserva los bytes de producción.

Antes del despliegue, PostgreSQL 16.15 aislado, sin puerto publicado, ejecutó **dos pruebas y diez casos de equivalencia**. Tres perfiles existentes se compararon en transacciones de solo lectura, con límite de cinco segundos por sentencia. Doce muestras alternadas de EXPLAIN, seis por variante, dieron medias de ejecución de **26,007 ms anteriores y 3,154 ms optimizados**, con los mismos resultados. Son tiempos de ejecución SQL; no son el tiempo completo entre el móvil y el servidor ni una prueba de capacidad masiva.

Se verificó la integridad del respaldo existente, se conservó la imagen anterior y se creó un checkpoint privado de fuente/configuración/marcador. El primer intento de construir la imagen derivada falló antes de modificar producción por el formato de referencia a la imagen base; el siguiente usó la etiqueta local verificada y terminó correctamente. La imagen nueva deriva de la imagen activa y copia únicamente el archivo con esa función sustituida. Se verificaron sintaxis y dependencias; se recreó solo el contenedor API, sin cambios de tablas, datos, dependencias o contenedor PostgreSQL.

Identidad del despliegue:

| Dato | Valor |
| --- | --- |
| Release | `novvin-profile96-20261030` |
| Procedencia de la función | `19a5d8522356` |
| Imagen base | `sha256:f2cb913ec18af7c8b7103dc3cc3aed449b7be03c4fc0e47e76eca7c76ebca8c0` |
| Imagen activa | `sha256:38585b299db04dff2b5f49810913ab113bed283f729024c1e41c58a7a8d5d8a7` |
| SHA-256 de data-routes.js activo | `2ebc01d944311f2673879d7d37ea58f1211d5394334edd910f46a12121a5f6f6` |
| Source digest publicado | `f3e2c2ccb746039262d8ae84741127222e5fd401569bd3fb1ba5c9a095a2e3f4` |

El commit del marcador identifica la procedencia de la función; **no afirma que toda la imagen sea una compilación completa de ese commit**. Los demás archivos proceden de `moment-alert169`. El checkpoint está en `/home/alvaro/novvin-candidates/mobile96-profile/rollback`, con acceso privado; sus archivos protegidos no se copian al repositorio. El procedimiento tiene reversión de fuente/configuración/marcador e imagen si falla la disponibilidad posterior.

Pasaron **veinte comprobaciones posteriores** entre origen directo y proxy web: disponibilidad con identidad nueva, perfil, tres bandejas, historial sintético, Social, Videos, salas y dos lecturas de video por rango con respuesta 206 y exactamente 1.024 bytes. Estas lecturas acotadas no saturan producción. La muestra del proxy web aún puede ser considerablemente más lenta que la consulta SQL; la mejora no elimina el coste de transporte y autenticación.

## 5. Revisión de advertencias Android

Se clasificaron las 320 advertencias anteriores. Se corrigen tres usos del alto global de configuración en paneles de preguntas/compartir/promoción: se toma el tamaño real de la ventana para que los paneles respondan al redimensionado. Se trasladan a recursos los textos de linterna QR y el símbolo de apertura de cuatro widgets.

Se conservan las recomendaciones de estilo `UseKtx`, atributos modernos con alternativa para widgets antiguos, iconos adaptativos junto a recursos anteriores, recursos usados mediante manifiestos dinámicos y el guardado síncrono intencional de permisos. No se desactivan reglas ni se añade línea base para ocultar advertencias.

La primera ejecución de lint frenó la entrega por tres traducciones faltantes de los textos nuevos del QR. Se completaron los idiomas soportados y se repitió la comprobación; el registro del primer fallo se conserva separado del resultado final.

## Validación y entrega

En el código de esta tanda pasan **1.979 pruebas unitarias en 417 suites**, sin fallos, errores ni omisiones, y **67 pruebas nativas en 22 clases** en la repetición completa, sin omisiones. El primer recorrido nativo tuvo un timeout al esperar el aviso de una foto inexistente. El caso aislado, la clase completa de siete pruebas y el segundo recorrido de 67 pasaron sin modificar ese código. Se conserva el fallo inicial y no se le asigna una causa no demostrada. El buffer de crashes del segundo recorrido quedó vacío.

Otras tres pruebas nativas dirigidas aprueban cola push sin conexión, primer fotograma real y recuperación de la subida propia: **70 casos nativos únicos seleccionados en 25 clases**, ejecutados en tandas separadas y sin omisiones finales. La prueba de cola comprueba que dos solicitudes sin red quedan encoladas/bloqueadas, sin registrar un token FCM ficticio. El cambio posterior de selección TUS se cubre con las cinco regresiones HTTP y la repetición nativa del archivo real; no se afirma haber repetido todo el recorrido de interfaz después de cada edición del cliente de subidas.

La primera medición adicional de video se detuvo antes de leer el feed por una configuración incorrecta de acceso al grafo de dependencias del test. Se corrigió con una entrada compartida para las variantes debug/audit/auditRelease, excluida de release y accesible desde el componente de aplicación correspondiente, según el [patrón de Hilt](https://dagger.dev/hilt/entry-points.html); no se interpreta como un fallo del servidor. Se conservan los registros de los intentos y la medición solo se acepta cuando finaliza sin omisiones.

La entrega es **9.1.32 / 172**, con 1.979 unitarias aprobadas de nuevo después de la segunda revisión y lint final con **cero errores y 312 advertencias**, frente a las 320 iniciales. Los recursos de idioma quedaron reconstruidos. La entrega anterior 171 se conserva y no se publica en Play.

## Paquetes firmados y cierre

APK y AAB generados con el código `c36765ecc9d6` (equivalente funcional de main: `973d51555676`), configuración de producción, mínimo API 24 y objetivo API 36, sin debuggable. Se verifica la misma firma de subida de las entregas anteriores; esto no sustituye la huella de firma administrada por Play. R8 conserva los 17 componentes del manifiesto y el nuevo PushRegistrationWorker; MediaAuditEntryPoint queda excluido del release. Se comprueban catorce ELF de 64 bits con alineación de al menos 16 KiB, zipalign -P 16, validación del AAB y PAGE_ALIGNMENT_16K. No se ejecuta hardware físico de páginas de 16 KiB.

| Archivo | Bytes | SHA-256 |
| --- | ---: | --- |
| APK 172 | 221169433 | `4d1f1975bc36f2845c824f0dc42b0c3734b723fc5d8dba13f7a81e86544a1568` |
| AAB 172 | 131956613 | `19e51090a50adfe1468c4dba7bdf136df83eae4229f4d788f0987f6c123cdbff` |

Se instala el APK normal firmado sobre la versión 171 sin borrar datos, con sesión sintética confirmada antes y después de actualizar. Se recorren las cinco secciones principales y se vuelve a abrir la aplicación; la comprobación termina sin crash ni ANR de Novvin registrado en ese recorrido. El resultado no extiende las pruebas anteriores de API 36 a este paquete ni confirma comunicaciones entre dos teléfonos. No se sube el mapping de una versión todavía no publicada a Crashlytics.

El seguimiento final del servidor confirma la identidad del despliegue, 12 servicios del conjunto principal saludables y ausencia de los patrones graves buscados en los últimos treinta minutos de registros. Es una comprobación acotada, no un análisis exhaustivo de cada petición.

Siguen fuera de la evidencia disponible las llamadas/LIVE y notificaciones FCM entre teléfonos físicos/redes distintas, la asociación HTTPS con la huella administrada por Play y el comportamiento en todos los fabricantes. Las compras pospuestas por el propietario y la integración de retiros no forman parte de estos cambios.
