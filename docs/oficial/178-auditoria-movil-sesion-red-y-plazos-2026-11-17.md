# 178 · Auditoría móvil en curso: sesión, conexión y plazos

El propietario amplió la revisión el 17 de noviembre a toda la APK, desde los defectos de mayor impacto hasta los de interfaz. Esta continuación publica las correcciones comprobadas de sesiones de biblioteca y conectividad. **La auditoría integral permanece abierta.** Su alcance no equivale a revisar todos los archivos Android ni a validar el servidor o un teléfono por compilar una selección.

## Hallazgos corregidos, por prioridad

| Prioridad | Fallo y alcance | Comportamiento corregido |
| --- | --- | --- |
| Alta | Once operaciones privadas de biblioteca podían obtener una credencial de otra cuenta al renovar el token. El identificador de usuario tampoco distinguía una salida y nuevo acceso a la misma cuenta. Incluye borrar historial, guardar/quitar pendientes y modificar colecciones. | La visita de sesión iniciadora acompaña la petición desde el ViewModel hasta el repositorio. Se comprueba antes y después de renovar credenciales y al recibir la respuesta. Las firmas públicas anteriores se conservan. |
| Alta | Las hojas de biblioteca podían conservar borradores o confirmaciones de la visita anterior. | Los botones validan al propietario que se mostraba al crearse el callback. Las confirmaciones y borradores usan esa visita como clave. Los ViewModels observan también las generaciones de sesión y vacían el contenido privado aunque el identificador vuelva a ser el mismo. La interfaz física sigue pendiente. |
| Media | El plazo de primera página comenzaba después de renovar el token: una renovación suspendida podía mantener la carga indefinidamente en Historial, Ver después y Colecciones. | El límite de diez segundos incluye renovación, suscripción y lectura de la página. La pantalla recibe un error reintentable y libera el indicador. |
| Media | La conexión y el transporte se calculaban con getters síncronos dentro de callbacks Android. Un `onLost` podía seguir publicando conexión si el getter devolvía la red perdida; `onAvailable` y el cambio de transporte podían usar una instantánea anterior. | Cada observador sigue su red y usa las capacidades notificadas. Perder la red seguida publica desconexión. Android 24/25 conserva una consulta diferida fuera del callback, que cede ante capacidades ya notificadas. Se mantiene el comportamiento de red celular y VPN medida. |
| Media | Una cancelación que escapaba del repositorio podía dejar ocupados los controles de borrado o guardado. | La limpieza de los controles ocurre en `finally`, vinculada a la visita correspondiente. Crear una colección y añadirle el video usan una sola corrutina de mutación. |

La política de sesión común se extrajo de `NotificationAccountCall` a `AccountScopedCall`, manteniendo el contrato anterior y sus siete controles. El cambio no sustituye las autorizaciones del servidor ni intenta deshacer una escritura que ya llegó correctamente a la cuenta iniciadora; evita enviar con credenciales nuevas y publicar respuestas en una visita distinta.

El comportamiento del observador se contrastó con el [contrato de callbacks de Android](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback) y con la [guía de estado de red](https://developer.android.com/develop/connectivity/network-ops/reading-network-state). Las consultas síncronas iniciales se mantienen fuera de esos callbacks. La compatibilidad diferida de API 24 tiene un control separado de los casos de API 34.

## Evidencia

- La referencia `feature/live-gifts cabeb1b6` reprodujo **22 fallos de autorización en once operaciones**, con los dos cambios A → B y A → B → A; los once controles de cuenta vigente aprobaron. Las peticiones pasan por el repositorio real y un servidor simulado, sin dinero, credenciales ni contenido reales.
- El ensayo inicial del observador encontró cuatro fallos del laboratorio Android por stubs, y su segunda ejecución encontró cuatro por una dependencia ausente. Se conservaron ambos resultados. La repetición con el runtime completo permitió ejecutar las aserciones. El conjunto final de referencia registró seis fallos en ocho controles: tres corresponden a getters desactualizados y tres son secuencias defensivas de callbacks que no se presentan como un fallo físico reproducido.
- Una candidata intermedia con las guardias de sesión reprodujo **tres fallos de plazo**, uno por cada primera página cuya renovación quedaba suspendida. Los otros 66 controles de esa ejecución aprobaron.
- La selección final compiló los repositorios, helpers, ViewModels y las tres hojas Compose modificados y aprobó **87 pruebas JVM en cuatro clases**, con huellas de entrada estables: 44 de límites de cuenta del repositorio, ocho de conexión, 28 de carga/estado/visita de biblioteca y siete del contrato de notificaciones. Tres controles de interrupción de mutaciones se añadieron después del parche; no se cuentan como una reproducción previa.

Registros y huellas: `D:/Novvin-Entregas/evidencias/2026-11-17-mobile-audit178/`. `baseline`, `baseline-v2` y `baseline-v3` conservan los ensayos anteriores; `baseline-network-final` contiene el conjunto final de referencia de red; `before-deadline-fix`, `candidate` y `final` separan los cambios y resultados posteriores.

No se ejecutó una batería Gradle completa, no se instaló una APK de este bloque y no se desplegó una API. Los consumidores físicos, la conmutación Wi-Fi/datos/VPN real, el arranque frío y la persistencia entre procesos siguen pendientes. El empaquetado experimental del otro asistente se conserva separado de las ramas estables.

## Orden vigente de la auditoría integral

Este es el orden de continuación, no una certificación ni una lista de módulos cerrados. La evidencia de archivo y su huella continúa en `review-coverage.json`; una lectura histórica no sustituye el recorrido actual.

| Prioridad | Área | Estado de esta ampliación y siguiente comprobación |
| --- | --- | --- |
| Alta | Cuenta y datos privados | Corregida la frontera de biblioteca; quedan consumidores, acciones retenidas, proceso y resto de repositorios. |
| Alta | Monedero, compras y retiros | Lectura parcial iniciada; verificar propiedad de sesión, exclusión de solicitudes y reconciliación de confirmaciones perdidas. |
| Alta | Mensajes, grupos y comunidades | Mantener evidencia anterior; revisar recepción/reconexión, permisos, entrega y borrado por visita. |
| Alta | LIVE, llamadas y salas | Mantener exclusión de medios anterior; continuar RPC, cierres y recorridos de hardware pendientes. |
| Alta | Carga, publicación y eliminación de contenido | Biblioteca corregida; seguir con archivos, permisos, respuestas confirmadas y limpieza local. |
| Alta | Recuperación, segundo factor y cuentas guardadas | Mantener evidencia anterior; validar almacenamiento, SMTP, reinicio y navegación física pendientes. |
| Media | Transporte, DNS y descubrimiento de servidor | Observador corregido; revisar plazos, cancelación, expiración y fallos parciales de los consumidores restantes. |
| Media | Informes, moderación y administración | Lectura parcial de Mis reportes; contrastar errores parciales, renovación y respuestas antiguas. |
| Media | Feeds de Social, Videos y Momentos | Conservar las reservas de Social; continuar paginación, cachés, permisos y recuperación multimedia. |
| Media | Notificaciones y sincronización en segundo plano | Se conserva el contrato de petición; quedan entrega física, suspensión del proceso y recuperación. |
| Media | Perfil, privacidad, contactos y búsqueda | Seguir por opciones y navegación, sin trasladar rediseños experimentales a las ramas estables. |
| Media | Juegos, sorteos y recompensas | Contrastar admisión, rondas, saldos y reintentos con el servidor. |
| Baja | Ajustes, accesibilidad, textos y estados vacíos | Revisar después de los recorridos de mayor impacto; los estados vacíos no deben ocultar errores. |
| Validación | Arranque, batería completa y paquete Android | Pendientes de esta ampliación; no sustituirlos por las pruebas focalizadas ni por el AAB 182 histórico. |
