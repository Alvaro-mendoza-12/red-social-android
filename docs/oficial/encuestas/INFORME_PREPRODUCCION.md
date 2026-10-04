# Informe técnico de preproducción: Encuestas Android

**Fecha:** 3 de octubre de 2026, America/Bogota.

**Rama:** `feature/live-gifts`.

**Estado:** API y antivirus desplegados, Encuestas habilitadas y ciclo funcional comprobado en el servidor. Regresión Android conjunta en curso.

**Alcance:** Android, API de Encuestas, PostgreSQL, exportaciones, adjuntos y coordinación con la auditoría móvil existente.

Este informe reúne evidencia reproducible. No declara que toda la aplicación esté libre de errores ni acredita una certificación externa. La especificación completa del producto sigue en `ESPECIFICACION_FUNCIONAL_Y_TECNICA.md`; su alcance pendiente se enumera en `IMPLEMENTACION_ANDROID.md`.

## Evidencia y límites

| Comprobación | Resultado documentado | Alcance |
|---|---|---|
| Unitarias Android finales | 1.230 correctas, cero fallos, errores u omisiones | 269 suites, última ejecución sobre `782fd6c10ff6`; incluye la reutilización acotada de la lectura inicial del audio y nueve regresiones del control de sesión. |
| API focalizada | 48 comprobaciones correctas | HTTP real y PostgreSQL local, JWT firmado y revocable, cuentas ficticias; incluye nueve casos del protocolo antivirus. |
| Regresión completa API | 427 correctas, 2 omitidas en Windows, 0 fallos; backend `2011e32` | Linux ejecutó aparte los dos fixtures omitidos, ambos correctos. Su suite de tres incluye una comprobación estática ya contada en Windows; no sumar esa comprobación dos veces. |
| PostgreSQL de producción equivalente | 48 pruebas correctas, cero omisiones, PostgreSQL 16.15 | Contenedor QA desechable con datos ficticios, tablas inicializadas dos veces y esquemas de pruebas retirados; no se alteró la base productiva. |
| Dependencias productivas de la API | Cuatro avisos corregidos; auditoría final sin avisos conocidos | Lockfile `2011e32`, actualizaciones compatibles de busboy, grpc-js, brace-expansion y multer; 48 pruebas focalizadas y 427 generales repetidas correctamente. La cobertura depende del registro de avisos consultado. |
| Imágenes candidatas Ubuntu | API y antivirus construidos correctamente con fuentes verificadas | Candidato `2011e32`, 180 archivos de API y cuatro del antivirus; no se reemplazaron servicios productivos. IDs, etiquetas y hashes en `VALIDACION_IMAGENES.json`. |
| Arranque completo de server.js | Salud 200, registro 201, JWT real y reinicio idempotente | PostgreSQL 16.15 en tmpfs/red interna, secretos ficticios, sin push/SMTP ni datos productivos. Encuestas desactivada devuelve 503 y sin sesión devuelve 401; los tres recursos QA fueron retirados. |
| APK Android | APK debug y androidTest generados correctamente; commit embebido `782fd6c10ff6` | Commit comprobado dentro del DEX con apkanalyzer, hashes en `VALIDACION_ARTEFACTOS_ANDROID.json`. El archivo BuildConfig generado se modificó después; se verificó el APK, no ese archivo posterior. |
| Encuestas en emulador | Siete pruebas correctas en 17,333 s tras corregir las esperas entre diálogos | Cinco de interfaz, contrato de documentos y transporte HTTPS real; los casos de interfaz usan datos sintéticos. La regresión conjunta de 27 se repite al cerrar la revisión del audio. |
| Regresión móvil sin medios | 22 pruebas correctas en 52,483 s, cero omisiones | APK diagnóstico `da17647`, incluye los siete casos de Encuestas y HTTPS real a PostgreSQL local. La primera ejecución rechazó la sesión QA vencida; se reinició el fixture manteniendo JWT y sesiones de una hora y se repitió la suite completa. No acredita los cinco casos de medios pendientes. |
| Regresión móvil combinada | 27 pruebas correctas en 67,722 s, cero omisiones | APK con `BUILD_COMMIT=e1443afdcb39`, código Android `09da048` y API de QA `2011e32`. Se repite para la optimización posterior del audio `782fd6c`; no trasladar automáticamente este resultado a otro APK. |
| Audio tras el cambio `782fd6c` | Prueba focal de ciclo de vida correcta; dos fallos entre cinco casos de medios | Dos inicios de audio de perfil no alcanzaron reproducción dentro de los 10 s de la prueba. Permanecieron en BUFFERING, con intención de reproducir y sin pérdida de foco. No se aumentó el timeout. Diagnóstico de apertura/lectura incorporado en `da17647`; causa aún pendiente. |
| Transporte HTTPS Android | Correcto en la prueba nativa final | Certificado exclusivo de pruebas en androidTest, dos cuentas ficticias, API real y PostgreSQL local; nunca relaja TLS del aplicativo. |
| Reducción R8 | Compilación final correcta sobre Android `782fd6c10ff6` | Compilación conjunta debug, tests, unitarias y R8 en 15 min 30 s. Se conservaron clase y seis métodos de SurveysBridge. Se ejecutó la reducción sin empaquetar ni firmar un artefacto de producción. |
| AAB para Google Play | Generación detenida por falta de configuración de la clave de subida | `verifyReleaseSigningConfiguration` rechazó correctamente la entrega sin firma. El usuario confirmó en el chat coordinado que firma manualmente desde Android Studio; queda pendiente verificar ese AAB firmado. |
| Exportaciones PDF | Instrumento, informe y QR renderizados y revisados | Datos e imágenes ficticios; no se afirma compatibilidad visual con todos los lectores móviles. |
| Excel a volumen alto | 20.000 respuestas × 4 variables, 5.401 ms, pico RSS 1.553 MiB | Equipo Windows local; no demuestra esos tiempos en Ubuntu ni en un teléfono. |
| CSV a volumen alto | Misma selección, 223 ms, archivo 3,26 MB | Misma limitación de hardware y selección. |
| Antivirus productivo anterior | PDF limpio aceptado; EICAR detectado con UID 10001 | clamscan: 24,8/14,8 s y ~963 MiB por arranque. Confirma firmas y detección; se sustituye por un daemon privado. |
| Antivirus persistente real | PDF limpio 12/2 ms; EICAR y PDF cifrado rechazados en 7 ms | Contenedor QA privado, sin red pública, UID 10001, firmas reales de solo lectura. Arranque/PING 18,6 s, pico 963 MiB; contenedor e imagen temporales eliminados. No representa latencia de todo PDF ni del envío desde un teléfono. |
| API productiva de Encuestas | API `2011e32` y antivirus desplegados; salud 200 | Login y perfil autenticado correctos. `/v1/surveys/me` devuelve 503/SURVEY_DISABLED con sesión y 401 sin sesión; Encuestas permanece desactivada hasta validar su activación y flujo QA. |
| Preparación de entrega API | Respaldo, archivos, imágenes y materiales de reversión verificados | Preparación sin reinicio, seguida de entrega exclusiva de API y antivirus. Respaldo fresco `20261003T232528Z`, cuatro hashes correctos, sin podar respaldos previos ni restaurar producción. La activación sigue pendiente. |
| Teléfono físico | No ejecutado | El usuario eligió explícitamente el emulador para esta revisión. |

Los contadores de las pruebas focalizadas y generales se superponen. No deben sumarse para inflar el número de verificaciones independientes.

## Defectos corregidos en la revisión cruzada

1. Un adjunto sujeto a conservación podía reutilizarse como imagen en otra planilla. Ahora las imágenes del instrumento deben ser propias y estar separadas de adjuntos de participación.
2. Borrar una imagen podía competir con guardar una planilla, y no se comprobaba el historial. Ahora hay bloqueos transaccionales y se comprueban también revisiones anteriores.
3. Las conversiones de imágenes podían consumir memoria simultáneamente. Ahora hay un límite global por proceso y errores recuperables de saturación.
4. Publicar distintas planillas simultáneamente podía superar 200 campañas o producir un error de clave duplicada. Ahora se serializa por propietario y se devuelve idempotencia o conflicto controlado.
5. El texto de conservación no ejecutaba ningún borrado. Ahora cada respuesta tiene vencimiento, queda excluida al vencer y se purga su contenido y adjuntos en lotes. Las ediciones no extienden ese plazo.
6. El creador podía descargar un adjunto aún no enviado. Ahora solo accede a adjuntos de respuestas enviadas y vigentes.
7. El cierre por fecha podía abrir implícitamente al cambiar su programación. Ahora exige reabrir expresamente cuando ya venció.
8. Faltaba moderación para encuestas compartidas únicamente por QR. Ahora hay reporte, revisión del instrumento y restricción con capacidades administrativas existentes; los moderadores no acceden al conjunto de respuestas.
9. Retirar/archivar una publicación impedía volver a compartir la misma campaña. Ahora existe nueva publicación en Social conservando respuestas y plazos.
10. Las exportaciones omitían imágenes y deformaban algunos gráficos. Ahora incorporan imágenes y preservan proporciones; histogramas y bastones usan ejes numéricos adecuados.
11. El destino de encuesta podía perderse durante una interrupción del inicio de sesión. Ahora se conserva en el estado guardado de navegación.
12. El puente nativo queda protegido contra eliminación o renombrado de sus métodos por R8.
13. La autenticación descarta resultados de renovación/startup anteriores a logout/cambio de cuenta y limpia importaciones tardías del SDK. La última mutación se serializa con el cambio de identidad.
14. El antivirus de música existente también limita escaneos simultáneos y fuerza la terminación al vencer su timeout, para evitar competir sin límite con exportaciones.
15. El estado accesible de operación en curso podía anunciar disponibilidad antes de que terminara un diálogo. Ahora se mantiene consistente durante acciones, navegación y cargas de archivos; la prueba de moderación espera a que finalice la acción antes de continuar.

## Operación y límites efectivos

- Una respuesta por cuenta/campaña; no una por ser humano. Su edición vence a los 30 minutos desde el envío, o al cierre/restricción anterior. Reabrir no reinicia plazos.
- Conservación explícita de 1 a 3.650 días desde cada envío, fijada en el cuestionario publicado. El servidor no extiende fechas al editar respuestas o planillas.
- Al vencer, preguntas/respuestas y adjuntos dejan de estar disponibles para descarga. El trabajador se ejecuta cada minuto, usa bloqueos entre procesos y lotes de hasta 2.000 respuestas por ejecución. El contenido permanece físicamente hasta que termine la limpieza si hay retrasos.
- Se conserva el marcador mínimo de participación por cuenta/campaña hasta borrar la campaña o la cuenta, para impedir un segundo envío. Esto se informa antes del consentimiento.
- Un respaldo o una descarga anterior puede conservar información ya eliminada del servicio. El propietario debe gestionar sus archivos y el operador la vigencia de los respaldos. Tras restaurar un respaldo, ejecutar la limpieza antes de habilitar Encuestas.
- Hasta 200 planillas y 200 campañas por propietario, 100 preguntas, 30 secciones y 40 opciones por pregunta. Imágenes y PDF hasta 4 MiB; hasta tres adjuntos por pregunta y 100 MiB de archivos por cuenta.
- Consultas hasta 20.000 respuestas seleccionadas y 16 MiB de contenido. Descargas hasta 250.000 celdas de datos, 24 MiB de archivo e imágenes del instrumento hasta 100 referencias/16 MiB de origen. Informes con más de 10.000 categorías requieren filtros o CSV.
- Análisis y exportaciones tienen cupos separados por proceso. La saturación devuelve 503 con un mensaje para reintentar; los límites excedidos devuelven 413. No hay una cola ilimitada de cuerpos grandes.
- QR y enlaces abren Novvin Android instalado. La clave del QR se puede rotar; la autorización sigue dependiendo de cuenta, audiencia, bloqueos, publicación y moderación.
- `NOVVIN_SURVEYS_ENABLED=false` es el valor predeterminado de Compose. Las nuevas tablas se crean de forma aditiva; habilitar la función requiere configurar `true` después de verificar la entrega.

## Servidor y reversión

La otra IA hizo preflight de solo lectura del servidor: 16 GiB de RAM, aproximadamente 12,9 GiB disponibles y 424 GiB libres en NVMe. El volumen de Docker está sobre NVMe. La API tiene límites de 4 GiB, 3 CPU y 384 procesos; el nuevo antivirus privado tiene 2 GiB, 1 CPU y un hilo de escaneo. El daemon no publica el puerto en el host, monta firmas en solo lectura y usa actualización existente; no inicia un segundo freshclam.

La otra IA creó el respaldo `20261003T221229Z`; su manifest terminó a las 22:14:34 UTC. Verificó cuatro de cuatro hashes, lista del dump de PostgreSQL y archivos de almacenamiento/IA. Usó `RETENTION_DAYS=0`, conservando respaldos anteriores. No hizo una restauración sobre producción. Los hashes y tamaños están en `VALIDACION_RESPALDO.json`. Volver a comprobar que ese respaldo sea suficientemente reciente al desplegar, y guardar imagen, fuentes, Compose y marcador de versión anteriores.

Imagen de API observada para reversión: `sha256:83b2366a16e7aecf442bf2f5b31c9e04c18f33ab5ada34680f8029363f608a17`. Comprobar que siga siendo la imagen activa al realizar la entrega; esta observación no autoriza borrar imágenes ni reemplazar contenedores.

La reversión sustituye la imagen de API y desactiva Encuestas, conservando la base y las escrituras posteriores. No restaurar toda la base como procedimiento habitual de rollback: eso perdería actividad nueva. Las tablas aditivas pueden permanecer. Si hay daño de datos, la recuperación desde respaldo exige un procedimiento independiente.

## Pasos concretos para aceptar la entrega

1. Congelar la combinación revisada, guardar su commit y hashes de APK/AAB, ejecutar las suites y comprobar la firma del artefacto de producción.
2. Probar el servicio antivirus privado real: PING, PDF limpio, EICAR, rechazo de límites y comportamiento de PDF cifrado/no analizable. Comprobar memoria, latencia, recarga de firmas y fallo cerrado.
3. Crear/verificar respaldo fresco y conservar materiales de reversión. Revisar la migración sobre una copia de PostgreSQL de versión equivalente a producción.
4. Construir el antivirus y la API. Hacer una entrega controlada de esos servicios; el script general que también recrea Redis/señalización requiere otra revisión y no se usa automáticamente para este módulo.
5. Verificar salud, versión y tablas; activar la función de forma controlada. Repetir con cuentas QA el ciclo Android: planilla → Social/QR → consentimiento → respuesta → edición → cierre → resultados → exportación, incluyendo cuentas sin permisos y cambio de cuenta.
6. Confirmar trabajador de conservación, reportes/moderación, reintentos y fallos del antivirus en el servidor. Comprobar que el tráfico de funciones ya existentes siga saludable.
7. Registrar el resultado y decidir la habilitación para usuarios. Google Play y la revisión en teléfono físico tienen evidencia separada; este trabajo no publica la app automáticamente.

## Reproducción local

API focalizada, con PostgreSQL desechable y **sin usar DATABASE_URL**:

```powershell
$env:SURVEY_TEST_DATABASE_URL='postgresql://survey_validation@127.0.0.1:55439/novvin_surveys_test'
node --test tests/survey-domain.test.js tests/survey-exports.test.js tests/survey-resources.test.js tests/survey-clamd-client.test.js tests/survey-routes.integration.test.js
```

`scripts/benchmark-survey-exports.js` genera solo datos sintéticos en memoria. `scripts/serve-survey-validation.js` requiere la base local exclusiva, certificado de androidTest y una clave TLS de prueba en output; escucha solamente en 127.0.0.1:55440, crea un esquema temporal y lo elimina al recibir `stop` por stdin. Su endpoint QA no forma parte de server.js ni de producción. Para instrumentación, usar ADB reverse y el argumento `survey_api_url=https://127.0.0.1:55440`. TLS de prueba se configura únicamente dentro del test; el APK mantiene certificados del sistema y bloqueo de HTTP sin cifrar.

Los logs de la revisión actual incluyen `output/survey-production-api-tests-final.log`, `output/survey-production-api-regression-final.log`, `output/survey-production-mobile-test-final.log`, `output/survey-production-audio-debug-build.log`, `output/survey-production-r8-validation.log` y `output/survey-export-benchmark.json`. Los logs privados de la regresión combinada están en `build/physical-qa-2026-10-01/`; no se publican íntegramente. Las capturas y PDF ficticios están bajo `output/surveys-mobile/` y `output/pdf/`. output no se debe agregar íntegramente al repositorio: puede contener registros locales y materiales de otras verificaciones.

## Habilitación y comprobación funcional del servidor

La API `2011e32` quedó habilitada mediante el controlador de activación `7164c1a`, con respaldo comprobado y cambio limitado al servicio API. El antivirus privado conserva su imagen validada. Se ejecutaron 36 comprobaciones HTTP sobre el servicio desplegado: arranque con cuenta autenticada, consentimiento, respuesta identificada, edición de la misma respuesta, rechazo de revisión antigua, resultados privados, CSV/Excel/Word/PDF, rotación del QR, cierre y reapertura sin renovar el plazo de edición.

Se utilizó una cuenta QA existente del equipo y un participante ficticio creado exclusivamente para esta ejecución. La encuesta no generó una publicación en Social. Se retiraron la campaña, la planilla y el participante creado; su sesión dejó de ser válida. No hubo errores de limpieza. La evidencia no contiene credenciales ni identificadores de usuarios: [VALIDACION_PRODUCCION_FUNCIONAL.json](VALIDACION_PRODUCCION_FUNCIONAL.json).

Esta comprobación acredita el flujo HTTP del servidor. La instalación Android con los cambios conjuntos `6adc49b` y su revisión visual en el emulador se documentarán con resultados propios; no se trasladan automáticamente los resultados de un APK anterior.
