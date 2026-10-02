# Preparación de red y llamada con la tablet bloqueada

Fecha: 2026-10-02. Android permanece en 117 / 9.0.20.

## Cambios y comprobaciones

La señalización de llamadas, LIVE y voz comparte un cliente de red con lectura de 60 segundos para long polling. Este límite permite mantener abierta una petición de recepción; no impone una espera de 60 segundos antes de conectar. TURN deriva un cliente con su límite de petición de 10 segundos y comparte el pool de conexiones. Referencia: [Socket.IO Java, configuración de OkHttp](https://socketio.github.io/socket.io-client-java/faq.html).

Al abrir una conversación o recibir una invitación se prepara DNS/TLS mediante una petición HEAD opcional a `/ready`, limitada a tres segundos y una vez cada dos minutos. No abre el micrófono, la cámara ni una sala. El envío de invitaciones comienza en paralelo a la preparación nativa del audio. Una cancelación rápida invalida el trabajo de arranque.

Pasaron **1.073 pruebas unitarias en 239 suites**, sin fallos, errores u omisiones. Una comprobación de la primera APK detectó una clase Hilt ausente en un resultado de transformación recuperado de caché. Se regeneró la transformación sin reutilizar ese resultado y se comprobó la definición `SocialApp_GeneratedInjector` en ambas APK antes de instalarlas. La prueba aislada de configuración de audio utiliza la aplicación de pruebas, como las demás pruebas unitarias de Media3. El arranque físico posterior confirmó que la app inicia.

Las APK debug y audit se instalaron conservando sesiones y datos. La aplicación original de Play en la tablet permanece separada de Novvin QA. Los hashes y resultados están en [la evidencia sanitizada](../evidencias-movil-preparacion-red-2026-10-02/resultados.json).

## Resultado físico y límite de latencia

El Motorola utilizó LTE y la tablet Wi-Fi. Con la tablet bloqueada, apareció la pantalla entrante de Novvin y se pudo contestar. Hubo tráfico de audio recibido en ambos extremos, una oferta inicial y cero eventos ICE de desconexión en la muestra. El llamante terminó la llamada y conservó la aplicación abierta. No hubo excepción Java fatal registrada.

**La demora de arranque sigue pendiente.** En esta muestra, la tablet tardó 4.246 ms entre la inicialización nativa y la conexión de medios. No se afirma que la preparación HEAD haya reducido ese tiempo: la muestra anterior fue de 4.079 ms y las condiciones difieren. La marca previa a pulsar «contestar» también incluye la ejecución de ADB; el tiempo del llamante incluye esperar la respuesta. Los relojes de dispositivos distintos no se restan entre sí.

El contador RTP de pérdidas del receptor indicó -1. WebRTC puede reportar contadores con signo; se conserva el valor y no se interpreta como una pérdida negativa ni como una certificación de calidad audible. El transporte conectado no sustituye una evaluación humana del sonido, una prueba prolongada o la validación de FCM del paquete de Play.

El siguiente trabajo es adelantar la conexión autenticada de señalización mientras suena la invitación y reutilizarla al contestar, manteniendo los medios desactivados hasta aceptar.

## Documentos y servidor

En el Motorola se autorizó una carpeta que solo contiene archivos QA. Se verificaron su exploración y la subcarpeta dentro de Novvin, búsqueda de un archivo, filtro PDF y categoría Word sin coincidencias. La selección habitual no abre el explorador del sistema; Android interviene para autorizar inicialmente una carpeta o un documento.

Los nueve servicios del servidor estaban activos y saludables. Cinco peticiones HEAD a `/ready` desde el PC devolvieron 200: 721 ms en la primera y 34–56 ms en las siguientes. Estas cifras corresponden al PC y no representan la negociación WebRTC ni la red móvil. No se desplegó un cambio de backend en este bloque.

## Reversión y trabajo abierto

Revertir el commit de este bloque y recompilar Android restaura la preparación de red anterior. No hay migración de datos. Sigue abierta la auditoría completa por módulos, el arranque inferior a un segundo, la prueba prolongada de medios y las pruebas de redes que bloqueen WebSocket. No se certifica toda la aplicación para producción.
