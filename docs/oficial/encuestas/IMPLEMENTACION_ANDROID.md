# Encuestas de Novvin: implementación Android

**Rama:** `feature/live-gifts`

**Fecha:** 2026-10-03

**Estado:** módulo implementado y verificado con PostgreSQL local y un emulador Android. API y antivirus desplegados con Encuestas desactivadas; falta comprobar activación y recorrido contra producción. El usuario eligió el emulador para esta revisión.

## Recorrido disponible

1. En **Social → Más opciones** se elige **Crear encuesta** o **Mis encuestas**.
2. El propietario crea o importa preguntas, configura secciones, imágenes, audiencia, consentimiento, propósito y conservación declarada. Puede guardar la planilla, duplicarla, recuperar un borrador local y consultar sus revisiones.
3. **Publicar** fija una revisión independiente, permite programar la apertura o el cierre, y opcionalmente crea la tarjeta en Social. El servidor utiliza una clave de publicación para evitar duplicados tras un reintento.
4. La tarjeta, el enlace profundo `conectate://survey/{id}` y el QR abren la encuesta en Android. Los enlaces cuya clave fue regenerada o cuya audiencia no está autorizada son rechazados por la API. El QR requiere la aplicación instalada; no hay sitio web público para responder.
5. El participante autenticado lee propósito, contacto, conservación, carácter identificado y consentimiento antes de empezar. PostgreSQL admite una respuesta por cuenta y campaña. Esa misma respuesta se puede editar durante 30 minutos desde el primer envío, siempre que la campaña siga abierta.
6. El propietario consulta respuestas privadas y resúmenes con frecuencias, descriptivos, gráficos, cruces y filtros; puede cambiar el diccionario de variables sin alterar respuestas, y descargar Excel, Word, PDF, CSV, gráficos y QR.

Las escalas ordinales conservan frecuencias, mediana y cuartiles. Media y desviación estándar requieren marcar en el editor el supuesto de tratar los puntos como intervalares. Las relaciones con variables ordinales usan Spearman con rangos medios para empates; las numéricas intervalares usan Pearson descriptivo. Los informes identifican el método y el supuesto.

## Código principal

| Área | Archivo o carpeta | Responsabilidad |
|---|---|---|
| Entrada móvil | `app/src/main/java/conectate/com/presentation/social/SocialScreen.kt` | Opciones de Encuestas desde Social. |
| Navegación | `app/src/main/java/conectate/com/presentation/navigation/` | Ruta, enlace profundo y continuación tras iniciar sesión. |
| Editor móvil | `app/src/main/java/conectate/com/presentation/surveys/SurveysScreen.kt` y `app/src/main/assets/surveys/` | Pantallas locales incluidas en el APK; el puente nativo gestiona autenticación, archivos, copiar y compartir. |
| Publicación | `app/src/main/java/conectate/com/presentation/components/PostCard.kt`, `Post.kt` y `LocalFallbackApiClient.kt` | Tarjeta de encuesta, identificación y contador en Social, incluidos los datos recibidos por el mapper del feed. |
| API | `infrastructure/local-fallback/api/src/surveys/` | Esquemas, reglas, rutas, análisis, QR y exportaciones. |
| Almacenamiento | `ensureSurveyTables` en `routes.js` | Crea tablas al iniciar la API y añade el vínculo `survey_id` a publicaciones. |

El recurso HTML del editor se lee desde `assets` del APK con un origen local interceptado por Android WebView. El WebView no carga código ni recursos de un dominio externo. Las llamadas a la API pasan por el cliente HTTP nativo y el token de la sesión; los permisos se vuelven a comprobar en el servidor.

## Verificaciones realizadas

- `:app:assembleDebug` y `:app:assembleDebugAndroidTest` con JDK 21: APK y paquete de pruebas generados correctamente en esta rama.
- `node --check` de los módulos de Encuestas: correcto.
- 48 comprobaciones focalizadas de dominio, exportación e integración HTTP/PostgreSQL: correctas. Incluyen ramificación, límites contradictorios, entradas inválidas, versiones, publicaciones y respuestas concurrentes, consentimiento, privacidad, seguidores y bloqueos, cambios de audiencia desde Social, regeneración de QR, apertura/cierre programados, eliminación de respuestas y acceso a adjuntos. También verifican escalas ordinales y generación de Excel, Word, PDF, CSV, QR y gráficos seleccionados.
- La integración usa cuentas ficticias y esquemas temporales en una base exclusiva de PostgreSQL local, sin credenciales ni datos productivos. Reutiliza authenticateClientSession con JWT firmados, emisor/audiencia, vencimiento y revocación en el registro de sesiones; comprueba dos ejecuciones consecutivas de la creación de tablas. Las pruebas no arrancan todo server.js ni sustituyen la verificación tras el despliegue.
- Siete pruebas instrumentadas de Encuestas pasaron en el emulador (17,333 s después de corregir las esperas entre diálogos): cinco de `SurveyWorkbenchTest` cubren editor, participación, recuperación, configuración/conservación y moderación; una comprueba el contrato del selector de documentos; otra ejecuta el cliente HTTP nativo real por HTTPS contra la API y PostgreSQL de QA, incluyendo JWT, publicación, privacidad, envío, edición y exportaciones. Los casos de interfaz usan transporte sintético; el caso HTTPS usa dos cuentas ficticias y no demuestra la entrega de la API productiva.
- La comprobación en Android detectó y permitió corregir una devolución al WebView fuera del hilo principal. La revisión visual añadió espacio para la barra de navegación y compactó las acciones del editor.

La combinación Android `782fd6c10ff6` pasó 1.230 pruebas unitarias, sin fallos, errores ni omisiones. Incluye el ajuste de carga del audio, la reutilización acotada de su lectura inicial y nueve pruebas del control de cambio de sesión incorporado por la otra IA. La API pasó 427 pruebas en Windows, repetidas después de actualizar las dependencias en `2011e32`, y las dos omitidas allí se ejecutaron correctamente en Linux. Las 48 pruebas focalizadas también pasaron sobre PostgreSQL 16.15. Los resultados focalizados y generales se superponen.

R8 y la construcción conjunta de APK/pruebas pasaron sobre `782fd6c10ff6`, conservando la clase y los seis métodos de SurveysBridge. El commit se verificó dentro del DEX del APK y sus hashes están registrados en `VALIDACION_ARTEFACTOS_ANDROID.json`. La regresión conjunta anterior de 27 casos fue correcta; sin embargo, la suite de cinco casos de medios sobre el APK posterior falló en dos inicios de audio de perfil. Se mantiene esa comprobación pendiente hasta resolver la causa y repetir las pruebas. `bundleRelease` se detuvo en la comprobación de firma; el usuario conserva la firma manual desde Android Studio y todavía no se ha verificado un AAB final firmado para Google Play.

Los logs locales incluyen `output/survey-production-api-regression-final.log`, `output/survey-production-mobile-test-final.log` y `output/survey-production-final-combination-build.log`. Los registros privados del audio permanecen en `build/physical-qa-2026-10-01/` y no se publican íntegramente.

Las capturas finales revisadas están en `output/surveys-mobile/survey-validation/`: editor, administración, consentimiento y confirmación. Las acciones principales del editor quedan por encima de la barra de navegación de Android.

## Alcance de la especificación que continúa pendiente

La especificación describe el producto final deseado. El código actual todavía no cubre todos sus criterios:

- No incluye contrastes inferenciales con valores p, intervalos de confianza, comparaciones entre grupos o medidas repetidas. La tabla cruzada muestra chi cuadrado descriptivo y advierte sobre frecuencias esperadas. Los datos y el diccionario se exportan para análisis especializado.
- No incluye notificaciones configurables de participación/cierre ni resúmenes narrativos por IA.
- El editor permite agregar imágenes, pero no incorpora recorte manual. La biblioteca busca títulos y ofrece filtros de estado; faltan las alternativas completas de ordenación descritas en la especificación.
- Las exportaciones Word/PDF incorporan portada, secciones, preguntas y opciones con sus imágenes privadas. Las tablas y gráficos están incluidos; todavía hace falta validar variantes extensas y maquetación en todos los lectores Office móviles.
- No se han medido todos los objetivos de rendimiento ni realizado una auditoría completa con TalkBack, tamaños de letra extremos, archivos grandes y redes lentas.

## Antes de habilitarla para usuarios

- Desplegar la API y aplicar sus tablas en PostgreSQL junto con la versión Android; el APK por sí solo no puede guardar encuestas.
- Completar el ciclo crear → publicar → abrir desde Social/QR → consentir → responder → editar → cerrar → exportar contra el servidor, con dos cuentas y audiencias pública/seguidores/privada. Para esta revisión el usuario eligió el emulador; la comprobación en teléfono físico no está acreditada.
- Comprobar que el antivirus ClamAV y sus firmas estén disponibles en el contenedor para adjuntos PDF.
- Verificar en el servidor el trabajador de conservación: el plazo de 1 a 3.650 días queda fijado al publicar y se aplica por respuesta desde el envío. Al vencer se excluyen respuestas y adjuntos de las lecturas inmediatamente; el trabajador borra su contenido y los adjuntos en lotes cada minuto. Conserva un marcador mínimo por cuenta/campaña para impedir segundos envíos. Las copias descargadas y los respaldos tienen políticas separadas. No asigna plazos retroactivos a campañas antiguas sin configuración.
- Repetir en hardware productivo las mediciones locales: CSV con 20.000 respuestas y cuatro variables, 223 ms; Excel, 5.401 ms y pico de RSS 1.553 MiB. Hay un trabajo de exportación y uno de análisis por proceso, hasta 20.000 respuestas filtradas, 16 MiB de respuestas, 250.000 celdas de datos y 24 MiB de archivo. Una selección mayor debe dividirse con los filtros; no se promete exportación arbitraria de 20.000 × 100 preguntas.
- Confirmar el flujo administrativo de reportes QR: el participante reporta sin enviar respuestas al moderador. Los roles de lectura y moderación usan appAdminAccess y sus capacidades. La restricción afecta Social/QR y nuevas respuestas; el responsable conserva sus resultados y no puede levantarla. La nueva publicación en Social conserva campaña/respuestas sin reiniciar plazos.

Esta lista indica verificaciones y trabajo pendiente; no sustituye los criterios de aceptación de la [especificación oficial](ESPECIFICACION_FUNCIONAL_Y_TECNICA.md).

## Controles incorporados después de la revisión cruzada

Las imágenes del instrumento deben pertenecer al creador y tener campaign_id nulo; un adjunto de participación no puede reutilizarse en otra encuesta. Guardar/publicar bloquea las imágenes hasta confirmar la transacción; borrar comprueba también revisiones históricas. Publicar distintas planillas de una misma cuenta se serializa para respetar límite y claves de idempotencia. Las imágenes se convierten de una en una por proceso. El antivirus de PDF usa el servicio privado persistente por INSTREAM, sin omitir escaneo ni ejecutar clamscan como alternativa.

El estado de habilitación se controla con NOVVIN_SURVEYS_ENABLED, desactivado por defecto en Compose. Debe activarse después de instalar y validar API, tablas y antivirus. La navegación pendiente hacia una encuesta se guarda en SavedStateHandle y el puente JavaScript tiene @Keep para la compilación R8 de producción.

Consulta [INFORME_PREPRODUCCION.md](INFORME_PREPRODUCCION.md) para evidencia, alcance y pasos pendientes del despliegue.

## Servicio habilitado y comprobado

El 3 de octubre de 2026 se desplegaron la API `2011e32` y el antivirus privado y se habilitó Encuestas. El servicio autenticado pasó 36 comprobaciones funcionales con datos ficticios propios, incluidos permisos, revisiones, cierre y cuatro exportaciones. Todos los recursos creados para esa comprobación fueron retirados. [Evidencia del servidor](VALIDACION_PRODUCCION_FUNCIONAL.json).

El mensaje de apertura distingue ahora un servicio de Encuestas no disponible de una restricción de acceso a una encuesta concreta. La corrección `c2a02b0` tiene ocho pruebas unitarias nuevas. La regresión del nuevo APK conjunto se mantiene pendiente hasta registrar su resultado.
