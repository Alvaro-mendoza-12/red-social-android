# 140 — Acciones de notificación por visita de llamada

14 de noviembre de 2026. Continúa [139](139-visitas-notificacion-y-cierre-live-2026-11-14.md). La revisión móvil integral y la APK final siguen abiertas. La [evidencia estructurada](../quality/evidence/140-call-notification-visits.json) identifica fuentes y resultados del bloque.

## Reproducción

Sobre el servicio original de 139 fallaron cuatro aserciones del generador real: Colgar y Silenciar reutilizaban el PendingIntent de otra llamada; el retorno retenido cambiaba al id nuevo; volver a entrar al mismo id público reutilizaba Colgar. El XML registra cuatro fallos de aserción, sin errores ni omisiones. Las ocho pruebas de la guardia preparada pasaron en esa misma selección, antes de conectarla al repositorio.

La reproducción aisló únicamente el estado consultado por el generador original. Su arnés definitivo y las huellas de fuentes, log y XML quedan identificados por separado. Un primer arnés falló al construir el repositorio sin constructor vacío; esos cuatro errores de preparación no cuentan como reproducción. Una ejecución anterior se canceló por memoria, sin resultados. El arnés final de regresión usa el contrato de sesión y no asignación de objetos sin constructor.

La [referencia oficial de PendingIntent](https://developer.android.com/reference/android/app/PendingIntent) explica por qué los extras no distinguen por sí solos las operaciones y por qué actualizar un PendingIntent inmutable puede cambiar sus extras. Por eso se incorpora una URI de datos por llamada, visita y acción, además de la validación al ejecutar.

## Corrección acotada

El repositorio captura una visita de cuenta antes de suspender el arranque y crea un identificador local nuevo para cada entrada a llamada. La notificación obtiene una instantánea vigente del controlador. Colgar vuelve a comprobar su propietario al ejecutarse después del cambio de dispatcher; Silenciar expresa el estado deseado y conserva su significado al retener una acción antigua. Ningún Intent contiene credenciales.

START, UPDATE y STOP transportan la identidad capturada. Una orden antigua no reemplaza ni retira el aviso de la visita nueva, incluso si comparten id público. La parada conserva la identidad saliente antes de limpiar el repositorio. Un cierre suspendido o fallido no retira el foreground de otra visita. La [referencia del servicio Android](https://developer.android.com/reference/android/app/Service) fundamenta el uso del identificador de inicio al detenerlo.

La emisión diferida de mute-state conserva llamada, socket y visita; se descarta si cualquiera ha cambiado. El monitor del repositorio serializa la comprobación de la acción con la instalación y el cierre locales. Se mantienen los permisos y tipos de foreground existentes.

## Verificación

La selección aprueba **37 pruebas en cuatro clases**, sin fallos, errores ni omisiones: quince casos del servicio real con controlador de laboratorio y Robolectric API 34, seis casos de identidad del PendingIntent, ocho de la guardia real de cuenta y ocho regresiones adyacentes de tonos. Kotlin y el grafo Hilt compilaron. Los cuatro contratos reproducidos conservan sus aserciones; el arnés posterior usa la instantánea del nuevo controlador.

Después aprobó `:app:qualityGateDebug`, con un trabajador y JDK 21: **2.879 pruebas JVM en 534 clases**, cero fallos, errores u omisiones. Lint terminó sin errores y con **342 advertencias**; ninguna apunta a las ocho rutas modificadas. El gate aprobado no cierra esas advertencias ni prueba todos los recorridos. Los XML y logs de ambas tandas se conservan separados; la selección no se suma a la batería como casos adicionales. Se comprobaron las ocho huellas de fuentes después del resultado terminal, sin cambios respecto a lo probado.

No se ejecutan onCreate de Hilt, el repositorio de llamadas completo con HTTP y WebRTC reales ni recorridos físicos. Compilar el grafo de inyección no sustituye esos ensayos. No se genera ni entrega una APK nueva en este bloque.

## Límites y coordinación

El asistente de escritorio leyó completo CallRepository en su huella original `c823b9dda5f4f3fa6ca36306c456066903605d21` y revisó el diff del parche. La nueva huella se conserva como lectura parcial mientras no se relea completa. Una prueba aprobada no eleva por sí sola la cobertura.

Continúan abiertos la propiedad global de cuenta/medios de llamadas, la credencial de operaciones HTTP pendientes, los callbacks retenidos de pantalla y señalización, el historial tras RECONNECTING y la carrera de presentación de invitaciones frente al tono. Son observaciones de lectura separadas de los defectos reproducidos aquí. Rechazar una acción de otra cuenta no demuestra el cierre completo de su motor de medios.

Los dos asistentes se coordinan mediante `COORDINACION_IA.md`, sin ocupar la pantalla del propietario. Se reservan archivos e índices y se turnan Gradle para evitar saturar memoria. El bloque LIVE 141 sigue separado. Se preservan los documentos residuales de main y las ediciones ajenas de feature; no se modifica correciones ni se incorporan experimentos 105. La publicación de las correcciones terminadas debe conservar árboles iguales en main y feature/live-gifts. La aplicación completa todavía no se declara lista para producción.
