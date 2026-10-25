# Continuación de mensajería, imágenes y teclado — 24 de octubre de 2026

Versión preparada en `main` y `feature/live-gifts`: **9.1.25, código 165**. Las modificaciones anteriores del proyecto se conservan. El manifiesto de los archivos reflejados y sus copias previas está en `artifacts/mobile-hardening-20261024/`.

Los registros y artefactos se centralizan en `D:\red social - copia\artifacts\mobile-hardening-20261024`, aunque el código también esté reflejado en el checkout de `feature/live-gifts`.

## Causas comprobables en el código y correcciones

| Recorrido | Hallazgo | Corrección |
| --- | --- | --- |
| Scroll Android | El anclaje podía competir con la mutación de desplazamiento nativa al cambiar el teclado o llegar un mensaje. | La observación del scroll se realiza con `snapshotFlow`; los ajustes automáticos comprueban que no haya gesto ni inercia activos, también después de esperar un fotograma. |
| Grupos y comunidades | Las listas tenían claves pero no tipos de contenido. | Se especifica el tipo de fila para reutilizar composiciones compatibles. |
| Bandeja local | Se hacía una consulta por cada identidad al refrescar contactos. | Lectura por lotes de hasta 800 identificadores y conservación de los datos conocidos ante instantáneas parciales. La regresión verifica 1.205 contactos con dos consultas. |
| Autores de Social | Una petición de perfiles por lote volvía a hacer consultas locales individuales; la transformación de perfiles podía continuar en el contexto del llamador. | Consulta local por lote y ejecución de la carga/conversión en IO. |
| Emojis Android | El panel del chat solo ofrecía stickers. | Pestaña de emojis con búsqueda en chats privados, grupos y comunidades; inserta texto en el borrador. Los mensajes breves de emojis se muestran con tamaño mayor, sin cambiar el texto enviado. |
| Envío inmediato Android | El botón consultaba el borrador observado, que puede preceder al último commit del IME. | Botón e IME publican sincrónicamente el texto real del editor antes de enviar. Los iconos leen ese mismo estado; los indicadores por mensaje conservan la confirmación y reintentos. |
| Portapapeles Android | El proveedor puede anunciar el MIME en la descripción y omitirlo al resolver la URI. | El receptor nativo utiliza ambos datos. Existe además una acción explícita de pegado de imágenes en adjuntos, que reutiliza el editor multimedia. |
| Imagen web repetida | Dos componentes con la misma foto podían iniciar transferencias simultáneas y ocupar varios puestos en la cola acotada. | Lectura compartida por imagen y generación de cuenta. Desmontar un consumidor no cancela a los demás; cancelar todos sí termina la transferencia. |
| Parpadeo web | El cleanup de un efecto retiraba `img.src` al llegar metadatos nuevos, incluso cuando React mantenía la misma URL válida. | La limpieza imperativa de píxeles se reserva para invalidación de identidad. Los cambios normales quedan bajo el control de React. |
| Vista previa web | Se volvía a aplicar carga diferida después de terminar de obtener el Blob de una foto visible. El tamaño del original podía rechazar una miniatura pequeña. | Las imágenes visibles listas se decodifican sin otra compuerta lazy. El lector acotado valida los bytes de la miniatura real. |
| Fechas Android | La normalización añadía `Z` a fechas que ya tenían zona negativa, por ejemplo `-05:00`; el timestamp terminaba en el fallback de fecha inválida. La hora visible utilizaba un lector distinto. | El orden y la hora visible comparten un lector; se respetan offsets ISO, offsets cortos de PostgreSQL y UTC para fechas antiguas sin zona. Se reutiliza el formateador inmutable. Siete pruebas cubren caché, serialización, zonas equivalentes y la hora visible en Bogotá, UTC y Kolkata. |

## Evidencia terminada

- **40 pruebas unitarias Android aprobadas**, en ocho suites: identidades, continuidad de caché, borradores Unicode, retención de bandeja entre pantallas y selección inicial del historial. XML conservados en `artifacts/mobile-hardening-20261024/unit-results/`.
- **9 pruebas Android adicionales aprobadas** en la variante audit: siete de fechas y dos de migración real de la base local. Las migraciones mantienen mensajes, texto Unicode y multimedia pendiente; se comprueban reapertura e índices del historial. Total de esta continuación: **49 pruebas unitarias en 11 suites**. Registros: `artifacts/mobile-hardening-20261024/timestamp-migration-validation.log` y `tablet-final-build.log`.
- **782 pruebas web aprobadas, 143 suites**, en la ejecución completa. Incluyen las 31 pruebas dirigidas de medios, sesión y reloj. `NoWing-web/output/playwright/web-full-tests-20261024.log` y `web-tests-20261024.log`.
- Compilación web completa terminada: TypeScript, Vite y generación de páginas públicas. `NoWing-web/output/playwright/web-build-20261024.log`.
- Playwright utilizó componentes reales con imágenes y sesión ficticias, en un origen local separado. Tres apariciones de la misma foto generaron una sola lectura. Salir y regresar mantuvo una lectura; la comprobación de regreso tardó **32 ms** en una ejecución local. Este número incluye automatización y **no es una medición de producción ni del servidor**.
- Revisión visual en escritorio, 360 px y 800 px: sin desbordamiento horizontal; fotos completas tras los metadatos tardíos. Capturas en `NoWing-web/output/playwright/media-hardening-*.png`.
- Un segundo recorrido con seis fotos distintas hizo seis lecturas y las conservó tras salir y regresar, sin nuevas transferencias. `NoWing-web/output/playwright/media-hardening-distinct-check.txt`.
- En la zona `America/Bogota`, `2026-10-24T00:23:40Z` y `2026-10-23T19:23:40-05:00` mostraron `19:23`. El reloj optimista usa la muestra del backend y tiempo monotónico; sus pruebas cubren un reloj de PC atrasado y su corrección posterior.
- La ejecución anterior del emulador aprobó ocho casos de emojis: escritura, composición IME, secuencias combinadas, pegado, recuperación/reapertura de borradores, selector y texto visible. XML preservado en `artifacts/mobile-hardening-20261024/emoji-before-scroll.xml`.

## Validación física terminada

La tablet conectada es una Samsung SM-X520, Android 16. Se utiliza `conectate.com.qa` para conservar su instalación existente de Play y sus datos. El proyecto permite seleccionar `novvinInstrumentationBuildType=audit` para ejecutar las regresiones en ese paquete separado.

La variante QA no dispone de un cliente Firebase propio: se omite su generador de recursos. Las variantes habituales mantienen su configuración. Esta ejecución no certifica notificaciones push.

La batería física cubre escritura y borradores con emojis, portapapeles de texto e imágenes, inserción de imágenes mediante el contrato IME nativo, anclaje del historial, actualización durante un scroll, continuidad de fotos, editor multimedia, audio WebM y portada de vídeo. Un diagnóstico adicional recoge `FrameMetrics` durante gestos nativos sobre 400 burbujas reales de texto; sus cifras pertenecen a QA debug y no certifican el rendimiento de release ni incluyen red.

Comando:

```powershell
.\gradlew.bat :app:connectedAuditAndroidTest -PnovvinInstrumentationBuildType=audit "-Pandroid.testInstrumentationRunnerArguments.class=conectate.com.presentation.messages.ChatEmojiDraftDeviceTest,conectate.com.presentation.messages.ChatClipboardPasteDeviceTest,conectate.com.presentation.messages.ConversationScrollAnchorDeviceTest,conectate.com.presentation.messages.ConversationUpdateIsolationDeviceTest,conectate.com.presentation.messages.ChatComposerIsolationTest,conectate.com.presentation.components.ProfileAvatarContinuityDeviceTest,conectate.com.presentation.components.ProfileAnimatedAvatarDeviceTest,conectate.com.presentation.messages.ChatScrollFrameMetricsDeviceTest,conectate.com.presentation.messages.MessageMediaComposerLayoutTest,conectate.com.util.MessageAudioPlayerTest,conectate.com.util.ChatVideoPosterTest" --no-daemon
```

**Resultado final: 30/30 pruebas, cero fallos**, en 52,702 s sobre el APK final. Tras compilar e instalar APK e instrumentación, la ejecución directa de `adb shell am instrument -w -e class ... conectate.com.qa.test/androidx.test.runner.AndroidJUnitRunner` terminó con `OK (30 tests)`. Registro definitivo: `artifacts/mobile-hardening-20261024/tablet-final-instrumentation.txt`; compilación: `tablet-final-build.log`. El búfer de cierres existente no contenía entradas del paquete QA al terminar; `tablet-qa-crash-buffer.txt`.

La primera batería con la pantalla desbloqueada completó 30/30 pruebas en 52,45 s. Su XML está en `device-results/` y el registro en `tablet-functional-30-tests.log`. Es una ejecución previa: no se suma a los 30 casos de la batería final como si fueran pruebas distintas.

El diagnóstico de gestos se corrigió para usar `ActivityScenario`, eventos táctiles nativos y Choreographer real, sin el reloj virtual de animaciones de Compose Test. **Resultado final: 387 fotogramas, mediana 9,86 ms, p95 16,36 ms, máximo 29,62 ms; 16 fotogramas fuera del plazo (4,1 %)**, cero callbacks de métricas perdidos. La lista pasó de la fila 367 a la 269. `tablet-scroll-final.json`. La ejecución anterior con Choreographer también quedó conservada en `tablet-native-scroll-validation.txt` y `tablet-scroll-frames.json`; no se atribuyen las pequeñas diferencias entre ejecuciones a una optimización concreta. Se excluye de las conclusiones la medición con reloj virtual (`tablet-scroll-test-clock.json`). Son cifras de QA debug, texto generado y sin red, no del uso completo de una cuenta real ni de release.

El primer intento se interrumpió con la pantalla bloqueada; no se presenta como validación del producto. Se conserva en `tablet-locked-screen-attempt.log`. El usuario desbloqueó la tablet antes de las ejecuciones válidas. El ajuste temporal de pantalla encendida por USB se restauró a su valor original, 0. La instalación de Play y sus datos se conservaron; el APK final permanece instalado como **Novvin QA**.

## Artefactos

- `Novvin-9.1.25-165-qa.apk`: paquete separado `conectate.com.qa`, versión `9.1.25-qa`, código 165. SHA-256 `6ED6A223E5DFFD4724A3B0CF9139659D0A6791822CC903A133A35BE5CED99C50`.
- `qa-apk-metadata.json`: identificación generada por AGP del APK ensayado.
- `validation-summary.json`: conteos, métricas y hash del artefacto final.
- **`Novvin-9.1.25-165-release.aab`**: paquete release `conectate.com`, generado con R8, validaciones críticas y firma de release. `bundleRelease` terminó con `BUILD SUCCESSFUL` en 14 min 17 s. Tamaño 125,6 MiB. SHA-256 `7852391F6B55288596B3B62EE04DA5691C17867E536CFEF98859DEAB8124729C`.
- `release-signature-verification.log`: `jarsigner -verify` terminó con `jar verified`; también se comprobó la presencia de la firma y el manifiesto base en el bundle. Los avisos de cadena autofirmada y ausencia de sello de tiempo de Java no equivalen a comprobar o rechazar la llave de subida de Play Console; se utilizó la configuración de firma existente.
- `release-165-build.log`, `release-bundle-metadata.json` y `release-165-mapping.zip`: registro del build, identificación de AGP y copia del mapa de ofuscación. La tarea de subida del mapa a Crashlytics terminó correctamente.

El bundle está generado para la subida; no se publicó una nueva versión en Play Store ni se hizo un despliegue Vercel en esta continuación.

## Límites

No se ha obtenido la excepción del cierre que mostró la captura del teléfono afectado. Una prueba de componente que pasa no demuestra por sí sola el envío completo a otra cuenta ni todos los teclados/modelos. La prueba anterior de pegado de imagen pasó en el Moto G04; su batería completa no terminó por desconexión USB.

Las pruebas de aislamiento y continuidad no equivalen a certificar 60 FPS. Falta medir una compilación optimizada con conversaciones reales, comparar red normal/lenta y probar el envío entre cuentas. Las imágenes nuevas dependen de su transferencia y autorización; la caché evita volver a descargarlas durante los recorridos ya comprobados.

La comprobación web se realizó en un navegador de escritorio con tamaños de escritorio, teléfono y tablet. La revisión automática rechazó el comando para abrir Chrome en la tablet con `blocked by policy`; no se presenta ese recorrido como una prueba web física. Se utilizó la revisión responsive del navegador como alternativa.

No se borraron datos de Novvin ni se desplegaron cambios de servidor en esta continuación.

## Referencias consultadas

- [Android: buenas prácticas de rendimiento de Compose](https://developer.android.com/develop/ui/compose/performance/bestpractices).
- [Android: listas, claves y tipos de contenido](https://developer.android.com/develop/ui/compose/lists).
- [Android: copiar y pegar contenido enriquecido en Compose](https://developer.android.com/develop/ui/compose/touch-input/copy-and-paste).
- [Android: FrameMetrics y plazo disponible por fotograma](https://developer.android.com/reference/android/view/FrameMetrics).
