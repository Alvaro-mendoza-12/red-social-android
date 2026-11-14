# Continuación 141: propiedad de cuenta, motor y pantallas LIVE en Android

Fecha: 14 de noviembre de 2026. Corrección móvil en curso, anterior en numeración a las urgencias 142–143. Identifica una selección terminada de correcciones y sus límites; no acredita por sí solo publicación remota, APK final ni auditoría móvil completa.

## Fallos comprobados

El repositorio podía reutilizar el motor de una visita anterior, incluso tras A → B → A; callbacks y proveedores de credenciales conservados podían consultar la nueva cuenta. La primera reproducción usa el repositorio real con límites HTTP/nativos controlados: 16 casos, 13 fallos de aserción y tres controles aprobados. La preparación anterior al parche solo incorporaba inyección de factoría y dispatchers para hacer observables esas carreras; sus huellas y el original están conservados. No se cuenta una compilación fallida por recursos como fallo funcional.

Tras corregir ese núcleo, el ViewModel real todavía podía cerrar desde una pantalla anterior o adoptar la cuenta que aparecía antes de ejecutar un inicio encolado. La reproducción de interfaz tenía cinco casos: cuatro aserciones fallidas y un control vigente aprobado. El primer recibo de pantalla superó 30 de 32 casos; quedaron reproducidos dos fallos de micrófono y exposición de estado desde una ruta antigua.

La segunda tanda pasó 44 de 45 casos: esos dos fallos aprobaron, pero un observador iniciado después de invalidar la cuenta, antes de ejecutar actualizaciones encoladas, recibía el estado anterior del caché. La tercera pasó 45 de 46: corrigió ese replay y reprodujo otra omisión con colección inmediata, al adoptar el directo minimizado sin modificar `activeSession`. Las fuentes no cambiaron durante esas ejecuciones; se conservan sus resultados negativos, XML, logs y fuentes.

## Comportamiento del cambio

Cada vida del motor tiene un `LiveMediaVisit` con identidad propia y la visita de cuenta que lo autorizó. Sus credenciales pueden renovarse dentro de esa visita; otra cuenta o el retorno posterior a la misma cuenta no le presta su token. La salida conserva la última credencial propia. Los callbacks de señalización y las colecciones nativas comprueban propietario antes de publicar estado o emitir acciones. La cámara previa puede promoverse conservando motor y lente; no se usa `hardReset` como prueba de destrucción definitiva.

Cada pantalla obtiene su `LiveScreenEntry` antes de cualquier salto de dispatcher. El inicio pendiente, los controles síncronos, las lecturas y la salida conservan ese recibo. Una salida repetida comparte el resultado del cierre y no termina antes solo porque `activeSession` haya quedado vacío. La disposición de una ruta pendiente cancela su inicio; minimizar una ruta establecida conserva el directo para reabrirlo. La reapertura autorizada revoca el recibo anterior y notifica después de instalar el nuevo propietario.

Los estados expuestos por el ViewModel comprueban cuenta/ruta al leer `.value`, el replay y cada entrega a un colector. El valor se toma bajo el compromiso del propietario; no basta con esperar una recomposición o confiar en el valor almacenado antes del cambio de cuenta. Las herramientas conservadas por la pantalla también se invalidan al abandonar su ruta.

Los cierres de las pantallas actuales y antiguas conservan el identificador de su entrada de navegación. La comprobación no se limita al nombre de la ruta: abrir de nuevo el mismo directo produce una entrada distinta. Los callbacks de salida atrasados y su fallback deben conservar la pantalla abierta después. La navegación real pasa expresamente el identificador de `NavBackStackEntry` a las pantallas actuales.

La adopción de la cámara previa se serializa con el inicio y el cierre. La espera modelada de retirada de video se conserva en el propietario antes de vaciar los flujos; un directo sin video no añade esa espera. Son comprobaciones de comportamiento con recursos nativos controlados, no una certificación física de renderizado.

## Validación de esta selección

La cuarta tanda incorporó veinte fuentes congeladas y cinco clases. Aprobó **54 pruebas**, sin fallos, errores ni omisiones: 22 de pantalla/ViewModel, 16 del repositorio, cinco de visita del motor, seis de herramientas y cinco de navegación. Los controles de preview durante inicio, espera de retirada y salida por entrada se añadieron a partir de lectura de código; no se atribuye una ejecución anterior al parche cuando solo se verificaron después.

Después, `:app:qualityGateDebug --offline --max-workers=1 --no-daemon` aprobó **2927 pruebas JVM en 538 clases**, sin fallos, errores u omisiones. Lint terminó con **0 errores y 343 advertencias**. Sus XML y log son de esa ejecución; las veinte fuentes coinciden con la tanda focalizada. Las advertencias no se silencian para presentar el informe en verde.

La selección usa Robolectric API 34/JDK 21 y controla HTTP, autenticación y recursos nativos en sus límites. No sustituye hardware ni una llamada audible. La navegación usa `NavHostController` real y entradas distintas para la misma ruta. La adición de MockK 1.14.12 pertenece a pruebas JVM; no añade ese paquete a la APK de producción.

El APK 143 de laboratorio precede a estas últimas correcciones y **no las contiene**. No se entrega una APK final en este bloque. Pruebas focalizadas, batería JVM, Lint, emulador, hardware, publicación remota y despliegue efectivo se mantienen separados. La [evidencia de 141](../quality/evidence/141-cuentas-motor-pantallas.json) registra las huellas y las tandas negativas anteriores. Los recibos de Git se conservan por separado de los resultados de pruebas.

El primer intento de `qualityGateDebug` no completó la compilación: el log conserva el código Windows `1073807364`, sin resultado de pruebas de esa tanda. El proceso ya no existía al reanudar y Windows registraba un reinicio posterior a su última escritura. La causa exacta de terminación no está demostrada. Se conserva el intento original y se repitió en otra carpeta con las mismas veinte fuentes; no se cuenta el intento interrumpido como fallo de aserción ni como aprobación.

El informe de Lint incluye `UseTomlInstead` para la versión literal de MockK 1.14.12 en `testImplementation`. Esa versión pertenece al entorno de pruebas; su consolidación en el catálogo de versiones queda pendiente. No se añadió una supresión ni se presentó el total de advertencias como cero.

## Recorridos todavía abiertos

No está resuelta aquí la barrera física global e inmediata entre Auth, llamadas, LIVE y salas de voz. El observador de cambio de cuenta pide el cierre de LIVE, pero no demuestra por sí solo que el último frame o audio nativo haya drenado antes de completar cualquier transición global de cuenta.

Las acciones asíncronas restantes necesitan su propia propagación de recibo, credencial y compromiso después de cada espera: comentarios, invitaciones, batallas, SFU, música, seguimiento y otros productores. Los contratos públicos conservados del repositorio no deben presentarse como completamente migrados. La música puede terminar una decodificación tardía después de `clear`; la revisión de todos esos consumidores sigue abierta. `InteractiveMediaGate` aún no representa todas las llamadas ni todos los previews.

Se requieren ensayos físicos con dos cuentas y dispositivos, permisos concedidos/denegados, cambios de red, cámara/pantalla/voz, entrada/salida y audio audible. Los veinte controles de seguridad, RLS, los historiales importados vacíos o truncados, medios públicos, rendimiento restante y el resto de requisitos conservan su estado abierto. No se declara preparación integral para producción ni marketing masivo.
