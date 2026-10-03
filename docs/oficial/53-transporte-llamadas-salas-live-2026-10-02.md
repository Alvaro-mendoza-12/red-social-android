# Conexión de llamadas, salas y LIVE — 2 de octubre de 2026

## Problema y cambios

Salas y LIVE intentaban únicamente WebSocket, con un límite de diez segundos por intento. Una red que acepta TCP/TLS pero bloquea el establecimiento de WebSocket podía agotar ese tiempo sin probar HTTP polling. Llamadas ya tenía una alternativa, pero varios errores encolados podían programar más de un reemplazo.

Los tres módulos comparten ahora la política de transporte: intento directo limitado a 2.500 ms y, si falla, un reemplazo mediante polling con posibilidad de pasar a WebSocket. Se mantiene el límite de diez segundos para el transporte compatible, con el fin de conservar funcionamiento en conexiones lentas. El límite de un intento no equivale al tiempo total de establecimiento de audio o video.

La alternativa conserva autenticación, afinidad de sala y el motor de medios. Un control atómico permite programar solo un reemplazo por socket. Se comprueban cuenta y sesión activa antes de reconectar. Al salir de una sala de voz se invalida y desconecta el socket antes de esperar la respuesta HTTP, evitando que un error tardío inicie otra conexión. Las mediciones de voz usan un reloj monotónico.

## Pruebas

- 164 pruebas unitarias de llamadas, voz, LIVE, ICE y transporte en 44 suites: cero errores y cero fallos.
- Tres pruebas instrumentadas con el cliente Java de Socket.IO en Android 15: conexión a los tres espacios del servidor por ambos transportes, alternativa ante WebSocket bloqueado y descarte de alternativa después de abandonar una sesión.
- El servidor de prueba TLS aceptó la conexión pero dejó el establecimiento WebSocket sin respuesta. Android pasó a polling y quedó conectado en **2.674 ms**, con un solo reemplazo; autenticación y afinidad conservadas.
- Contra el servidor público real, sin entrar a llamadas, salas o emisiones: llamadas 1.075 ms directo / 1.089 ms compatible; voz 1.069 / 500 ms; LIVE 1.068 / 543 ms. Son muestras individuales de señalización, no promedios ni tiempos de audio/video.
- APK y APK instrumentada compiladas. Instalación sobre el emulador sin borrar datos. Se retiró del dispositivo el archivo privado de configuración de las pruebas.

## Pendiente de este bloque

Continúa la revisión entre dos cuentas en emuladores: recepción y cancelación de llamadas, negociación ICE y audio, cámara/pantalla en salas, y primer video remoto al entrar en LIVE. Esta prueba de transporte no acredita todavía la fluidez de imágenes, 60 FPS ni el funcionamiento en todas las redes móviles.

El arranque en frío de la APK de depuración registró 5.043 ms en esta sesión. El objetivo de 2,5–3 segundos sigue pendiente y debe medirse por separado de la conexión de medios.

## Referencias oficiales

- [Socket.IO Java: transportes, autenticación, TLS y límites de conexión](https://socketio.github.io/socket.io-client-java/initialization.html).
- [Socket.IO: alternativa de WebSocket a polling](https://socket.io/docs/v4/client-options/).
- [WebRTC: señalización, ICE, STUN y TURN](https://webrtc.org/getting-started/peer-connections).
