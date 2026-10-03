# Entrada a salas sin peticiones repetidas — 2026-10-03

## Implementación

Android registraba al usuario por HTTP antes de abrir señalización y repetía ese registro después de conectar el socket. La señalización también ejecuta la entrada autenticada contra la API antes de confirmar `room-joined`. Se eliminaron los dos registros redundantes del cliente y el registro al volver a adjuntar una pantalla a una conexión ya activa.

El servidor sigue validando contraseña, cuenta y acceso a comunidades, y persiste el identificador real del socket. Android mantiene `Conectando` hasta recibir la admisión; un rechazo sigue mostrando error. La API de entrada continúa disponible para otros clientes.

Se recibe el estado de escenario que emite el servidor (`voice-stage-state`). Un visitante empieza como oyente, se conservan el silencio elegido por el usuario y los roles válidos; roles desconocidos no dan permiso para publicar. Eventos de participantes de conexiones sustituidas se descartan.

## Pruebas y mediciones

Compilación de APK y APK de pruebas: aprobada, 4 min 17 s. El conjunto filtrado de llamadas, voz, LIVE, TURN y señalización produjo 188 pruebas / 52 suites, sin fallos ni errores. Incluye 6 pruebas nuevas del estado de admisión y roles.

En los dos emuladores, con las mismas cuentas y sala privada de auditoría:

| Medición | Antes | Después |
| --- | ---: | ---: |
| Entrada confirmada del visitante con contraseña correcta | 6.999 ms | 1.839 ms |
| ICE conectado del visitante, desde iniciar entrada | 9.945 ms | 5.268 ms |
| Entrada confirmada del propietario | — | 1.904 ms |

Son muestras puntuales, no percentiles ni una garantía para todas las redes. La prueba correcta del visitante se hizo después de rechazar una contraseña incorrecta, por lo que tenía parte de la red preparada. La primera conexión de ese emulador tardó 4.682 ms hasta el socket: el arranque frío sigue pendiente.

La contraseña incorrecta fue rechazada. La correcta dejó a ambos usuarios conectados y con RTP de audio creciente. Se comprobaron otra vez ambas cámaras, con recepción aproximada de 26–29 FPS, y la salida de ambos sin cierre de Novvin. Los emuladores no prueban escucha humana ni redes móviles independientes.

La investigación del servidor sobre la sala propia, con notificaciones desactivadas y transacción revertida, midió 306 ms en el manejador, de los que 233 ms fueron la contraseña. Se verificó que las filas de participantes quedaron intactas tras `ROLLBACK`. Una comprobación adicional de la pila HTTP local midió 281,4 ms para entrar y 40,1 ms para repetir con el mismo socket, seguida de salida explícita. Esto no reproduce ni descarta las demoras intermitentes bajo concurrencia.

## Límites y continuación

- Todavía no se cumple un máximo de 1 segundo para todo el recorrido, ni 60 FPS.
- Arranque frío de la APK de depuración: 4.255 y 5.212 ms en estas muestras; no cumple el objetivo de 2,5–3 s. Falta medición de release.
- La política de escenarios tiene pruebas unitarias; la promoción y revocación entre dos usuarios requieren prueba nativa adicional.
- LIVE y los demás módulos continúan en auditoría. No se declara toda la aplicación lista para producción.

Los datos de prueba, capturas y logs están en `build/physical-qa-2026-10-01/`, ignorado por Git. Se identificó un error del proceso de `uiautomator` por capturas solapadas; no correspondía al proceso de Novvin. Se repitieron las capturas sin solapamiento.

## Despliegue y reversión

Se instaló la APK con `install -r` en los emuladores, conservando datos. No se modificó API, esquema ni versión (`117`, `9.0.20`). Para revertir, revertir el commit de admisión y recompilar Android.

## Referencias

- [Socket.IO Java: eventos del cliente](https://socketio.github.io/socket.io-client-java/usage.html).
- [WebRTC: conexión de peers e ICE](https://webrtc.org/getting-started/peer-connections).
