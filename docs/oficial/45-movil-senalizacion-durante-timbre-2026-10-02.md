# Preparar la señalización durante el timbre

Fecha: 2026-10-02. Android sigue en 117 / 9.0.20.

## Implementación

Al recibir una invitación válida se inicia una conexión autenticada del namespace de llamadas. Todavía no se envía `join-call`, no se crea WebRTC y no se activa el micrófono del receptor. Al aceptar, se toma esa conexión si coincide con la invitación, cuenta, token y endpoint actuales y tiene menos de 45 segundos. Si no coincide o ya falló, se utiliza el camino de conexión habitual.

La conexión opcional se cierra al cancelar, rechazar, descartar la invitación, finalizar o vencer. Al cambiar la cuenta de la suscripción también se descarta una preparación de otra cuenta. La sesión aceptada restaura sus opciones normales de reconexión. Se impide emitir dos veces `join-call` para el mismo socket, incluso si coincide el evento de conexión con la comprobación de una conexión ya abierta. La siguiente reconexión puede volver a unirse.

## Pruebas y resultados físicos

Pasaron **1.077 pruebas en 240 suites**, sin fallos, errores u omisiones. Las cuatro pruebas nuevas cubren identidad de llamada/cuenta/token/endpoint, vencimiento, retroceso del reloj y exclusión del token del texto de diagnóstico. Ambas APK se instalaron conservando datos y se verificó su clase generada de arranque.

Se hicieron llamadas de una cuenta real del Motorola a la cuenta ficticia de Novvin QA de la tablet, con LTE y Wi-Fi respectivamente. La tablet permaneció bloqueada: se contestó desde la pantalla entrante de Novvin. En la primera muestra se preparó y reutilizó la señalización, con una sola unión al namespace/sala después de aceptar. El intervalo de inicialización nativa a medios conectados fue de **2.787 ms**, frente a **4.246 ms** de la muestra previa. Es una comparación de muestras, no un percentil ni una garantía para otras redes.

Una repetición llegó a fallback de polling: la señalización del receptor tardó 10.646 ms desde la marca previa a pulsar. Esa prueba se canceló por la herramienta antes de observar medios, porque esperaba doce segundos después de pulsar. Se corrigió la herramienta para esperar realmente la conexión, hasta treinta segundos, y conservar luego una muestra de tráfico. No se cuenta esa prueba como una llamada de medios satisfactoria.

La muestra posterior sí conectó ambos caminos y recibió paquetes de audio en ambos extremos, sin pérdida reportada y sin eventos ICE de desconexión. La marca previa a pulsar «contestar» a medios fue de 3.972 ms, incluyendo ADB; reutilizó el motor ya existente y no tiene una marca de inicialización nueva. No fue una prueba forzada de bloqueo de WebSocket.

Finalmente se llamó y canceló sin aceptar: apareció la UI entrante y luego se cerró; no quedó el servicio de llamada entrante activo en la tablet. En el receptor se observó preparación de señalización, **ningún `join-call`, ninguna inicialización de WebRTC y ninguna conexión de medios**. Los procesos de ambas apps sobrevivieron a las pruebas con esas comprobaciones, conservando las sesiones. Los datos sanitizados están en [la evidencia](../evidencias-movil-senalizacion-timbre-2026-10-02/resultados.json).

## Observaciones abiertas

El arranque inferior a un segundo sigue sin cumplirse. La preparación ayuda al camino que llega listo a la aceptación; el fallo de WebSocket seguido de polling todavía produce una demora alta. Se debe evitar repetir el mismo intento de WebSocket cuando la preparación ya lo detectó fallido, medir DNS/TLS/transporte en ese caso y comprobar la capacidad del cliente compartido para varias peticiones largas concurrentes.

En el chat QA del Motorola también se verificaron respuesta a un documento con nombre y autor «Tú», último mensaje visible sobre el teclado y envío de un sticker conservando el panel abierto. No se presenta esto como validación de todas las opciones de mensajería ni se certifica la aplicación completa, FCM de Play o la calidad audible con una persona escuchando.

## Reversión

Revertir este commit y recompilar Android restaura el arranque sin socket preparado. No hay migración ni despliegue del backend en este bloque.
