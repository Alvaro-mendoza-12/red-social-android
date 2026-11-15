# Compilación QA, IDE y latencia de medios

Continuación 144, iniciada el 14 de noviembre de 2026, hora de Colombia. Identifica el empaquetado QA y las mediciones verificadas; la revisión móvil integral, los ensayos restantes y la APK final permanecen abiertos.

## Fallo de empaquetado reproducido

El primer `assembleAuditRelease` terminó la compilación, R8 y `packageAuditRelease`, pero falló en `uploadCrashlyticsMappingFileAuditRelease`: el directorio generado por Google Services no existe para QA, cuyo generador se deshabilita porque no hay un cliente Firebase registrado. La tanda completa acabó con código 1 a las 19:15:40, después de 36 min 8 s; no aprobó y no se instaló su APK. Las 2032 entradas congeladas permanecieron iguales.

La corrección añade `CrashlyticsExtension.mappingFileUploadEnabled = false` únicamente a `audit` y `auditRelease`. Conserva el optimizador, la firma QA, los paquetes y la configuración de las variantes normales. Se sigue la [documentación oficial de Firebase](https://firebase.google.com/docs/crashlytics/android/get-deobfuscated-reports). No se inventó un cliente Firebase QA ni se añadió una clave.

La primera comprobación auxiliar posterior se detuvo por una aserción incorrecta del arnés: esperaba `true` literal para release/debug, pero el plugin 2.9.9 usa `null` como ausencia de sobreescritura por variante. QA ya devolvía `false`. Se conserva esa ejecución y se corrige únicamente el arnés en una carpeta nueva. No es una segunda reproducción del defecto del producto ni un empaquetado aprobado.

## Sincronización del IDE

Los intentos de 18:18 y 18:40 agotaron diez minutos esperando el bloqueo mientras otra compilación ocupaba el proyecto. La importación iniciada a 19:44:29 terminó correctamente a 19:45:28, en **58,731 segundos**, con **Temurin JDK 21**. Se utilizó el IDE ya abierto; no se inició una nueva instancia. El registro y el recibo diferencian estos resultados de la reparación e importación históricas de 142–143.

Después, a 19:45:42, el IDE inició `assembleDebug`. Se respetó ese turno y el bloqueo serializó las escrituras en el mismo `app/build`. Su resultado posterior se conserva por separado.

## Conectividad y latencia observadas

El servidor enlaza a **1000 Mbps**. Una muestra de tres segundos a 19:00:50 registró CPU **28,24 %**, memoria disponible **11446/14915 MiB**, tráfico recibido **0,2324 Mbps** y enviado **0,2366 Mbps**. Es actividad observada, no capacidad máxima ni prueba de concurrencia.

Se repitieron lecturas autorizadas como invitado de dos avatares públicos, sin guardar imágenes ni imprimir nombres, URLs de objetos o credenciales. La fotografía original de 30375 bytes produjo una miniatura WebP de 1998 bytes: primer byte local **27,96 ms**, público **2648,08 ms**. El otro avatar era video: `width` y `animated` conservan su original, mientras `poster=1` produjo una imagen de **4706 bytes**, con **55,24 ms** locales y **5996,76 ms** públicos. Esas peticiones distintas no son una comparación de versiones ni prueban un defecto por devolver el video solicitado.

Un diagnóstico posterior con conexiones nuevas separó DNS, TCP, TLS y respuesta. El mismo avatar de 1998 bytes dio **735,59 ms** por IPv4 público y **1215,58 ms** en selección automática; localmente dio **23,43 ms**. `/ready` devolvió HTTP 200 en **1258,99 ms** hasta el primer byte, con `Server-Timing: app;dur=6.1`. En esas muestras buena parte de la espera corresponde al recorrido exterior a la API; no se atribuye a un proveedor concreto sin aislar sus saltos. Los intentos forzados IPv6 no resolvieron AAAA y se conservan con curl 6; no se presentan como éxito ni caída general del sitio, que sí respondió por IPv4.

Estas muestras no establecen una mejora porcentual por el cable ni una experiencia equivalente a TikTok. Para los videos se requieren tiempos de primer fotograma, esperas durante reproducción, gestos y condiciones de red del teléfono. La descarga del servidor por sí sola no mide su subida hacia espectadores ni la entrega por R2.

## Interrupciones y recursos del entorno

La repetición posterior con el arnés corregido agotó diez minutos esperando el build del IDE; no llegó a empaquetar ni a comprobar el parche. La tanda siguiente esperó fuera de Gradle hasta liberar el bloqueo, aprobó el control de variantes y llegó a R8, pero no dejó resultado terminal. Al reanudar el objetivo ya no existían su handle, orquestador, wrapper ni daemon; se conservan log y huellas sin atribuir una causa no demostrada. No se reinició una compilación solo por agotar un plazo de observación.

El fallo del IDE incluyó `OutOfMemoryError` al escribir una caché Gradle y errores nativos de recursos insuficientes, además de la cancelación de Javac. No se identifica como un error de sintaxis de Novvin. La memoria comprometida y la paginación de Windows mostraron presión durante el fallo. La sincronización aprobada no convierte ese build en aprobado ni demuestra que cualquier proyecto móvil esté validado. No se cambiaron el archivo de paginación ni la configuración del router para estos ensayos.

## Empaquetado corregido

La repetición conservada en `retry-after-resume` terminó con **código 0** a `2026-11-15T01:42:38.808781+00:00`. Aprueba la comprobación de variantes y genera la aplicación QA optimizada y su instrumentación sin exclusiones de tareas por línea de órdenes. QA devuelve `mappingFileUploadEnabled=false`; release/debug mantienen `null`, su ausencia de sobreescritura. Los generadores Google Services normales siguen habilitados y los QA permanecen deshabilitados. Las **2032 entradas de compilación** no cambiaron.

Estas APK son de laboratorio, con firma QA y paquete separado; no son una entrega final ni una validación completa de release. Su procedencia es el commit base de 141 más el parche Gradle de esta selección, identificado por huella, porque el empaquetado precede a su commit documental. No se afirma que contengan commits futuros.

Las firmas se verificaron y coinciden con los dos APK QA archivados previamente. El manifiesto de instrumentación apunta únicamente a `conectate.com.qa` y contiene el runner previsto. Las cuatro clases seleccionadas están presentes en el DEX; esto prueba su inclusión, no su ejecución.

- `conectate.com.qa`: versión `9.1.40-qa-optimized`, código `180`, 221617476 bytes, SHA-256 `81b774dcbc71a9206148f678d5306b63bed1d01a2d6d33ae37e19a110854a49c`.
- `conectate.com.qa.test`: versión `(sin nombre)`, código `(sin código declarado)`, 2404683 bytes, SHA-256 `1e5f1430f59cdca608c8c3c45c6b02f67671d608664737a81ee518eccfd4cb9a`.

## Ensayo físico pendiente

El Moto g04 conectado anteriormente dejó de aparecer por ADB después de la reanudación; el propietario confirmó que lo volvió a conectar, pero ADB y los nodos de dispositivo examinados en Windows todavía no lo detectan. Se solicitó comprobar el modo USB y la autorización del teléfono. No se instalaron estas APK ni se ejecutaron sus mediciones físicas en esta selección. Los APK QA instalados con anterioridad se archivaron para preservar su identidad; no se atribuyen a las correcciones nuevas. La instalación preparada verifica paquete, firma coincidente y conservación del principal antes de ejecutar lecturas públicas y los controles existentes de foto/GIF. La ausencia del teléfono no se presenta como una prueba aprobada.

## Publicación y recorridos abiertos

La continuación 141 quedó publicada en main y feature/live-gifts con árboles iguales; el despliegue web del commit main 6488ac6ade776e7e1245bf7b0d4d57744c5cbd46 quedó READY y con alias novvinapp.com. Sus recibos se distinguen de las pruebas y de una APK instalada. Esta selección se publica con su propio commit y la igualdad de árboles se verifica por separado; el documento por sí solo no acredita despliegue de API ni entrega final.

La lectura completa de los archivos listados en la evidencia se refiere a sus huellas; no significa que todos sus recorridos estén ensayados. Se identificaron para reproducción posterior el avatar nuevo sin imagen guardada ante un fallo remoto y la preparación de música que termina después de limpiar una visita; no se afirman correcciones ni reproducciones de ejecución de esos hallazgos. La lectura de GuestModeApi, VoiceRoomRepository y data-routes sigue siendo parcial. El límite global de mensajes importados, el resto de LIVE/voz/cuentas, RLS, los demás controles de seguridad y la APK final identificada siguen abiertos. No se declara preparación integral para producción o marketing masivo.
