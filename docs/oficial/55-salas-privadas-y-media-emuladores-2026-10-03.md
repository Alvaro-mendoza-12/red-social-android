# Salas privadas y media entre dos cuentas — 2026-10-03

## Cambio aplicado

La búsqueda por código rechazaba salas privadas incluso con la contraseña correcta: comparaba el texto escrito con `room.password`, un campo que el servidor oculta deliberadamente. Ahora la búsqueda resuelve la sala y el acceso pide la contraseña una sola vez en la pantalla de entrada. El servidor conserva la validación de contraseña y de pertenencia a comunidades.

La búsqueda tiene estado independiente de la lista de salas. Cancelar, iniciar otra búsqueda o cambiar de cuenta descarta respuestas anteriores. El diálogo muestra búsqueda en curso, error con reintento y cancelación; evita peticiones repetidas.

## Comprobaciones realizadas

- APK y APK de instrumentación recompiladas e instaladas sobre dos emuladores Android 35, conservando datos y sesión.
- 175 pruebas unitarias de 46 suites: sin fallos ni errores. Este conjunto incluye 6 pruebas nuevas de búsqueda de salas.
- 3 pruebas de interfaz del diálogo: aprobadas, 7,607 segundos.
- Prueba nativa con dos cuentas de auditoría en una sala privada propia: una contraseña incorrecta fue rechazada por el servidor; la correcta permitió entrar.
- Audio RTP recibido y enviado por ambos usuarios, con contadores crecientes. No se certifica escucha humana: los emuladores se ejecutan sin salida de audio.
- Ambas cámaras visibles. Una muestra recibió 29–30 FPS; otra fluctuó entre 23 y 29 FPS. No se certifican 60 FPS ni rendimiento de dispositivos físicos.
- Silenciar y reactivar micrófono cambia el control correspondiente. Mensaje enviado desde una cuenta aparece en el chat de la otra.
- Compartir pantalla, tras el permiso real de Android, muestra la pantalla del segundo emulador en el primero. Detener la transmisión recupera el estado de cámara apagada.
- Salir del segundo usuario reduce el contador del primero de 2 a 1. Ambos regresan a la lista al colgar, sin cierre observado de la aplicación.

La versión sigue en `versionCode=117`, `versionName=9.0.20`.

## Rendimiento pendiente y siguiente investigación

En la entrada con contraseña correcta se midieron, dentro del mismo reloj de Android:

| Hito desde iniciar entrada | Tiempo |
| --- | ---: |
| Socket autenticado | 2.770 ms |
| Admisión confirmada por señalización | 6.999 ms |
| ICE conectado con el otro participante | 9.945 ms |

La API registró una petición `POST /v1/data/voice/rooms/:roomId/join` de **3.995 ms**. Se investigan esa operación y las entradas HTTP repetidas del cliente antes y después de conectar el socket. Tener contenedores sanos no demuestra baja latencia. Los relojes de servidor y emulador difieren: no se restan marcas de máquinas distintas.

Sigue pendiente reducir estos tiempos, verificar LIVE, y completar los otros puntos de la auditoría solicitada. Esta prueba no es una certificación completa para producción ni de redes móviles frente a Wi-Fi.

## Evidencia y reversión

Capturas, jerarquías y logs privados están en `build/physical-qa-2026-10-01/`, ignorado por Git. Incluyen cámara en ambas cuentas, contraseña rechazada/aceptada, chat recibido, pantalla compartida y salida. Las credenciales de auditoría no se publican.

Para revertir, revertir el commit de búsqueda de salas privadas y reinstalar la APK anterior conservando datos. Este cambio no modifica el esquema ni requiere despliegue de API.

## Referencias oficiales consultadas

- [Socket.IO Java: eventos y configuración del cliente](https://socketio.github.io/socket.io-client-java/usage.html).
- [WebRTC: señalización, ICE y conexión de media](https://webrtc.org/getting-started/peer-connections). Una conexión de señalización no demuestra por sí sola audio o video recibido.
