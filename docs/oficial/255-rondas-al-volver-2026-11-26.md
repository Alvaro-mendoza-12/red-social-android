# Rondas al volver a Novvin Android

Rondas vuelve a calcular el tiempo disponible en cuanto se muestra la pestaña y antes de esperar a la red. La lectura inicial, la actualización manual y el sondeo comparten una operación. Al ocultar Rondas se cancela esa operación y se libera el indicador de carga. Una respuesta anterior conserva su cancelación aunque llegue después de abrir la pestaña de nuevo.

Dos pruebas reprodujeron el contador que quedaba en 600 segundos pese a haber vencido y la actualización manual obsoleta que bloqueaba la siguiente consulta. El código corregido aprobó 55 pruebas JVM de Rondas, plazos de lectura e identidad de sesión. También se repitieron las 42 pruebas del motor y del controlador de Juegos.

## Comprobaciones del paquete

La APK de referencia 9.2.1 (187) pasó firma y alineación de 16 KiB en ARM64. Se comprobaron también los segmentos ELF de las 14 bibliotecas de 64 bits de la universal. Se instaló esa APK en un emulador limpio de Android 16. Arrancó con Wi-Fi y datos desactivados y regresó a primer plano desde Inicio sin un cierre registrado en el búfer de fallos. La sesión era anónima y este ensayo de arranque no cubre el flujo autenticado de Rondas.

La biblioteca más grande de la APK ARM64 de referencia es `libxeno_native.so`, con 21.597.896 bytes. El AAR local de MediaPipe coincide con la entrada y la dependencia de segmentación se usa para recortar el fondo de stickers. Las otras dos bibliotecas grandes son las variantes de WebRTC, con 23.863.944 bytes en conjunto. El inventario identificó además los recursos `gift_anim_group_2_sheet`, `gift_anim_group_4_sheet`, `gift_anim_group_5_sheet` y `gift_celestial_dragon` entre los archivos de mayor tamaño. Retirar cualquiera de estas piezas requiere verificar sus usuarios y reproducción.

Gitleaks escaneó 43 MB de la instantánea versionada de Android y Gradle. La única alerta coincidió exactamente con el SHA-256 calculado a partir de las constantes ficticias de una prueba del almacén de borradores. Se excluyeron las configuraciones locales y otras rutas del repositorio.

## Candidato 9.2.1 (188)

Gradle generó la APK universal y el AAB firmados desde `a156710c`. Se comprobaron el certificado de subida, `targetSdk 36`, los recursos de Juegos dentro de ambos archivos y el código compilado. Pasaron la validación del AAB con Bundletool, el control de R8 sobre 17 componentes de la aplicación y la alineación de la APK y de sus 14 bibliotecas de 64 bits. Gradle ejecutó la tarea habitual de mapas de Crashlytics del ensamblado de lanzamiento. El candidato sigue pendiente de revisión en Google Play.

El emulador aceptó la actualización de 187 a 188 y la APK inició sin conexión. Tras los tiempos de espera de los primeros ensayos, se comprobó el regreso a Novvin desde Ajustes de Android. La actividad recuperó el primer plano en el mismo proceso y el búfer de fallos quedó vacío.

El primer intento con la suite completa se interrumpió por el cierre del proceso de pruebas de Gradle. El segundo intento renovó el proceso cada 20 clases y aprobó las 3.603 pruebas de la variante `release` en 601 clases, sin fallos, errores ni omisiones.

Quedan pendientes el recorrido autenticado de Rondas, Juegos, chat y Videos con cambios de red, el audio y micrófono entre dos teléfonos, y la medición del editor con fotos grandes. La revisión móvil integral continúa abierta.
