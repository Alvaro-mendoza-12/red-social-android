# 95 — Auditoría móvil: notificaciones, actualización y reconexión

Fecha: 2026-10-30, Bogotá. Continúa las auditorías móviles 85–90 y la revisión web 94. El propietario mantiene la disponibilidad de emulador, sin teléfonos físicos. Se distinguen revisión de código, pruebas controladas, recorrido de un paquete firmado y distribución en Play.

## Corrección: contador de notificaciones fuera de orden o de sesión

Las lecturas de notificaciones comprobaban el identificador de la cuenta antes de actualizar el contador, pero no ordenaban respuestas concurrentes ni distinguían dos sesiones sucesivas de la misma cuenta. Una consulta lenta iniciada antes podía reemplazar un contador más reciente. Además, «marcar todas como leídas» y «eliminar todas» ponían el contador global a cero al terminar la petición, sin comprobar si entretanto había cambiado la sesión.

Se introduce `NotificationUnreadCounter`, con el guardián de sesión existente y un orden de solicitudes. La publicación conserva la respuesta más reciente completada; iniciar otra consulta que luego falla no descarta un resultado utilizable. Limpiar los datos invalida las solicitudes anteriores, incluso si después vuelve a entrar la misma cuenta. Los valores negativos se limitan a cero. Las lecturas de lista y contador usan el mismo coordinador; una respuesta descartada tampoco vuelve a programar el temporizador de expiración de momentos.

Al finalizar las dos operaciones masivas, se consulta el contador autorizado solamente si sigue vigente la sesión que las inició. Se conserva la confirmación del servidor y se evita trasladar un cero a otra cuenta. No cambia el esquema Room, las credenciales, las dependencias ni los permisos.

El hallazgo procede de revisar las asignaciones y sus puntos de suspensión; no se provocó una pérdida de avisos de usuarios reales. Siete regresiones controladas comprueban respuestas fuera de orden, consulta posterior fallida, cambio de cuenta, cierre y regreso a la misma cuenta, invalidación, ausencia de cuenta y normalización de valores.

## Recorrido del paquete firmado anterior

Se instaló la entrega firmada 9.1.30 / 170 en un AVD propio AOSP API 35. Arrancó, permitió iniciar sesión con una cuenta sintética y mostró la explicación de notificaciones con «Ahora no». Se abrieron Mensajes, Videos, Social, Juegos y Perfil sin diálogo de cierre ni error de carga visible en las capturas.

Sin Wi-Fi ni datos, `dumpsys connectivity` confirmó que no había red predeterminada. Se escribió un borrador sin enviarlo, se forzó el cierre y se volvió a abrir la app: el borrador reapareció en la bandeja y en el editor del chat. Se retiró el texto de prueba y se restauró la conexión. El buffer posterior de crashes quedó vacío. Un fallo del servicio Bluetooth del sistema durante el primer arranque del AVD se conservó aparte y no se atribuye a Novvin.

`am start -W` registró 2.569 ms en el arranque y 2.157 ms en la reapertura sin red. Son tiempos de lanzamiento de Activity en esa máquina; no miden latencia de API, finalización del acceso ni percentiles de rendimiento.

## Pruebas sobre el código corregido

- Compilación de QA, APK de pruebas y análisis estático completos: aprobados. La reconstrucción sin resultados anteriores tardó 36 min 37 s.
- **1.963 pruebas unitarias en 415 suites**, sin fallos, errores ni omisiones; incluye las siete nuevas del contador.
- **65 pruebas nativas en 21 clases**, sin omisiones, en AOSP API 35; 154 s y buffer de crashes vacío.
- **Lint: cero errores y 320 advertencias existentes**. No se desactivaron reglas ni se añadió una línea base.

Las pruebas nativas seleccionadas cubren entrada/registro hasta antes del envío, invitado/salida, migraciones Room, colas por cuenta, resolución de enlaces, navegación, paginación Social, perfil, Unicode y borradores, portapapeles, fotos/stickers y sus gestos, PDF, audio, caché/primer fotograma de video y estados de los controles de llamada/búsqueda de sala. No representan la ejecución de toda la colección histórica de pruebas nativas ni una llamada real entre dos equipos.

## Otras comprobaciones de código y empaquetado

Se revisaron refresco de sesión, monitores de conectividad, reintentos seguros y cancelación en las rutas compartidas de lecturas y colas; configuración HTTPS obligatoria y componentes del manifiesto; permisos solicitados por función y política de respaldo limitada a preferencias de uso. Se conservaron las fuentes y claves.

El visor de documentos desactiva JavaScript y acceso a archivos. El visor de enlaces de voz bloquea archivos, contenido local y contenido mixto. El módulo de encuestas sirve cuatro recursos internos, intercepta las demás solicitudes y bloquea navegación, conservando la selección explícita de adjuntos. Esta revisión no equivale a una certificación de seguridad de toda la aplicación.

La APK universal 170 concentra la mayor parte del tamaño en bibliotecas de cuatro arquitecturas: aproximadamente 49,35 MiB para x86_64, 43,41 para arm64, 42,78 para x86 y 28,16 para ARMv7. No son las cachés temporales retiradas del disco en la revisión 93. No se eliminaron motores nativos que utilizan edición o comunicaciones.

## Paquetes 9.1.31 / 171 y comprobaciones finales

APK y AAB **9.1.31 / 171 firmados**, generados con el código `b2de5e8838c4` y configuración de producción, sin `debuggable`. La compilación release completa terminó correctamente en 22 min 40 s. Se comprobó el certificado de subida usado en las entregas anteriores, la retención R8 de 17 componentes y las bibliotecas críticas, catorce bibliotecas ELF de 64 bits con alineación de al menos 16 KiB, `zipalign -P 16`, validación del AAB y `PAGE_ALIGNMENT_16K` en el bundle. Son verificaciones de empaquetado; no una ejecución en hardware físico con páginas de 16 KiB. El objetivo es API 36 y el mínimo API 24; esta tanda no ejecuta la app en API 24.

Las copias entregadas se verificaron otra vez por SHA-256:

| Archivo | Bytes | SHA-256 |
| --- | ---: | --- |
| APK 171 | 221.166.641 | `30e0de10c754fc56dca2816176d54bb282bd66146209ee214fb9a727cb61c628` |
| AAB 171 | 131.947.780 | `11209e528d39fe7b8c97dc093ae76d23dd6cf30d16a82d0a33e3b6bdce5b7c88` |

En AOSP 35 se instaló el APK firmado sobre la 170 sin borrar datos: conservó la sesión sintética y volvió a abrir las cinco pantallas principales. A continuación, con la red del emulador realmente desconectada, se envió desde la interfaz un mensaje exclusivo de esta auditoría. Apareció en espera, sobrevivió a un cierre forzado y reapertura y llegó al restaurar la red. El servidor registró cero copias antes de la reconexión y una después; otra reapertura mantuvo exactamente una. Se eliminó solamente ese mensaje propio y se marcó como leída la conversación del otro participante sintético. No es una prueba de carga ni una medida precisa de latencia de entrega.

No se registró un crash de Novvin en ese recorrido. El buffer también contenía reinicios del servicio `com.android.bluetooth` del AVD, conservados como incidencia del sistema emulado y sin atribuirlos a la aplicación.

En una instalación limpia Google APIs **API 36**, con 3 GiB y cuatro CPU, se comprobaron bienvenida, formulario de acceso, primer paso de registro sin enviar datos, invitado, perfil de invitado, salida y reapertura del APK firmado. **El primer intento mostró «System UI isn't responding»** con pantalla física de 1080×2400 a 420 dpi. Se conservó esa captura, se eligió «Wait» y se pasó a 720×1600 a 280 dpi, manteniendo el ancho lógico de teléfono. El recorrido posterior y la reapertura terminaron sin crash ni ANR registrado de Novvin. No se atribuye la causa inicial a la app ni se presenta el primer intento como limpio; estas dos acciones y el tiempo de arranque del sistema no permiten aislar una causa. Tampoco se repitieron las 65 pruebas nativas completas en API 36.

Se realizó después otra reapertura con el emulador reiniciado y la resolución original restaurada, **1080×2400 a 420 dpi**: apareció la bienvenida y no reapareció el aviso de falta de respuesta ni se registró un crash de Novvin. Esta repetición reduce la incertidumbre sobre un fallo persistente, pero conserva la incidencia del primer arranque y no permite atribuirle una causa.

Las correcciones funcionales están subidas en `b2de5e88` de `feature/live-gifts` y `4fa11412` de `main`. Las fuentes de ambas ramas son idénticas; la entrega 170 anterior se conserva. No se subió un mapping de una versión aún no publicada a Crashlytics ni se modificó el número mínimo exigido por el servidor.

## Alcance y referencias

No se publica en Play ni se despliega una nueva API, base de datos o integración de retiros. El certificado local de subida no demuestra la firma administrada por Play; la asociación HTTPS directa y el código de versión disponible en la consola requieren su propia comprobación antes de distribuir. Siguen pendientes las comunicaciones y notificaciones push entre dispositivos físicos y redes distintas. No se certifica capacidad masiva ni ausencia de todo error.

Se contrastó el alcance con las [guías de calidad de Android](https://developer.android.com/docs/quality-guidelines/core-app-quality) y las [prácticas de corrutinas de Android](https://developer.android.com/kotlin/coroutines/coroutines-best-practices). Los resultados anteriores corresponden exclusivamente a las comprobaciones descritas.
