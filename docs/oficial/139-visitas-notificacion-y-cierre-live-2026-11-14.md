# 139 — Notificaciones y cierres de una visita LIVE

14 de noviembre de 2026. Continúa [138](138-tonos-android-y-web-desplegada-2026-11-14.md). La auditoría móvil y la APK final siguen abiertas. La [evidencia estructurada](../quality/evidence/139-live-notification-visits.json) identifica fuentes y ensayos de este bloque.

## Problema reproducido

Sobre el código de 138, dos notificaciones de directos diferentes obtenían el mismo PendingIntent de cierre y la acción no incluía el id del directo. Dos pruebas del generador real del servicio fallaron por esas aserciones, sin errores de preparación. El XML y el log anteriores al parche quedan conservados con sus huellas. Es reproducción de laboratorio; no prueba que un teléfono productivo haya sufrido el fallo.

La [documentación de Android](https://developer.android.com/reference/android/app/PendingIntent) distingue la identidad del Intent de sus extras. Por ello la corrección usa también una URI distinta por visita y acción; añadir extras solamente no distinguiría operaciones equivalentes. Los PendingIntent siguen siendo explícitos e inmutables. El [código de ShadowService 4.12.2](https://github.com/robolectric/robolectric/blob/robolectric-4.12.2/shadows/framework/src/main/java/org/robolectric/shadows/ShadowService.java) permite observar las llamadas de foreground del ensayo; no sustituye al sistema Android físico.

## Corrección y alcance

Cada ActiveLiveSession incorpora un id local de instancia, distinto del id público del directo. Las copias de estado conservan esa instancia. El repositorio la envía al promover el servicio; el servicio rechaza inicio, cierre o parada de otra instancia, incluso si el directo público coincide. El contrato de servicio es pequeño, de Kotlin puro, y Hilt lo vincula al LiveRepository singleton.

El cierre del aviso captura su instancia antes de lanzar la tarea, la pasa al repositorio y comprueba nuevamente la propiedad antes de retirar foreground tras una suspensión. Una petición antigua no retira el aviso nuevo. Un fallo que conserva la sesión activa conserva su notificación. Las órdenes heredadas sin propietario no cierran una sesión actual; un servicio sin foreground propio termina en vez de quedar abierto por una orden tardía.

El repositorio comprueba la instancia antes de entrar al coordinador y otra vez dentro de los bloqueos de cierre/arranque. Los resultados del coordinador pertenecen a su propietario: un cierre anterior o un no-op obsoleto no consume el cierre de otra visita. El lanzamiento interno asíncrono captura la instancia antes de encolarse; la burbuja LIVE envía la instancia que mostraba su callback. La espera de foreground para proyección contrasta también esa instancia.

Las paradas del servicio se envían con su propietario. Se evita crear un servicio para una parada que ya no tiene foreground correspondiente. Este bloque conserva las restricciones de permisos y tipos del servicio; no afirma haber probado todos sus fallos de promoción en un dispositivo.

## Verificación

La selección aprueba **23 pruebas en tres clases**: doce del servicio, seis del coordinador y cinco de política de arranque. Cubre notificaciones distintas, nueva visita al mismo directo, acción retenida, cierre suspendido, inicio/parada viejos, cierre válido y fallido, órdenes heredadas e instancia inactiva. El coordinador prueba solicitudes concurrentes por propietario y que un resultado viejo no se reutiliza para el nuevo.

Después se ejecutó la batería JVM completa: **2850 pruebas en 531 clases**, con cero fallos, errores u omisiones. Kotlin y el grafo Hilt compilaron. Las huellas se registran por fuente e informe; las lecturas parciales no se elevan a completas por estos resultados.

La primera selección posterior al parche se detuvo al leer una clase generada durante la transformación ASM; no llegó a ejecutar pruebas. Se comprobó que el archivo volvía a ser legible y se reintentó sin borrar salidas, con un trabajador y stacktrace. Ese reintento y la batería posterior son los aprobados; el fallo previo queda separado.

Los ensayos del servicio usan su implementación real con un controlador de laboratorio y Robolectric API 34. No ejecutan la clausura de red/medios del repositorio real ni onCreate de Hilt; su grafo se comprobó mediante compilación. No son ensayos físicos de audio, cámara, proyección, Wi-Fi/datos, avisos de segundo plano o LIVE autenticado. No se generó una APK nueva.

## Revisión cruzada y pendientes críticos

El asistente de escritorio leyó el servicio, el contrato, el coordinador y el diff, y contrastó la comprobación repetida de instancia. Señaló además las llamadas sin propietario explícito en LiveSessionViewModel y la salida del anfitrión tras withFrameNanos. La lectura propia encontró también la salida y onDispose del visor. Se conservan como observaciones de lectura pendientes de reproducción; **no se declaran corregidas por este bloque**. La captura anterior al lanzamiento interno no identifica por sí sola la pantalla que creó un callback retenido.

La propiedad completa de cuenta/medios sigue abierta: atajos de arranque por id/rol, A → B → A, lectura de usuario/token actuales al finalizar, callbacks de socket/WebRTC y disconnectAndClear global. El id de instancia de este bloque no sustituye una concesión de cuenta ni credenciales capturadas. Estos recorridos y los cierres suspendidos de pantalla necesitan su propia reproducción y reparación antes de afirmar preparación integral.

La coordinación se realiza por COORDINACION_IA.md. El otro asistente mantiene reservado el bloque 140 de llamadas; no se incorpora su guardia ni sus pruebas en preparación. AGENTS y los archivos de coordinación ajenos permanecen fuera de esta entrega. Se conservan los once cambios versionados ajenos, correciones y la separación del plan 105. Las correcciones terminadas se publican en main y feature/live-gifts con árboles iguales; la prueba física y la APK final siguen pendientes.
