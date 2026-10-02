# Capacidad compartida y fallback de llamadas

Fecha: 2026-10-02. Android conserva 117 / 9.0.20.

## Cambios implementados

- El cliente HTTP compartido de señalización admite 16 solicitudes concurrentes por host. Polling necesita una petición GET larga para recibir y una POST para enviar; el límite predeterminado de cinco podía hacer competir llamadas, salas, LIVE y una conexión en transición. El límite global sigue siendo 64 y se conserva el timeout de lectura de 60 segundos requerido por polling.
- Si la preparación de una llamada entrante ya falló por WebSocket, aceptar esa misma invitación no repite el intento directo: utiliza polling con posibilidad de upgrade. El resultado se liga a llamada, cuenta, token, endpoint y vigencia de 45 segundos; se descarta al cancelar, finalizar, cambiar cuenta o vencer. Al consumirlo se cancela también su temporizador.
- Las opciones de reconexión y autenticación continúan aplicándose al camino alternativo. No se reduce un timeout para simular una conexión más rápida.

La justificación del presupuesto de solicitudes está en la [documentación oficial de Socket.IO Java](https://socketio.github.io/socket.io-client-java/faq.html). Sus [opciones de transporte](https://socketio.github.io/socket.io-client-java/initialization.html) explican por qué el cliente Java requiere gestionar explícitamente el cambio desde WebSocket hacia polling.

## Validación

Pasaron **1.079 pruebas en 240 suites**, sin fallos, errores ni omisiones. Las dos pruebas nuevas comprueban que un fallo de preparación selecciona directamente el transporte compatible y que el fallback explícito tiene prioridad. Se comprobó la capacidad de 16 solicitudes y la presencia de la clase generada de arranque en ambas APK. Se instalaron debug en el Motorola y audit en la tablet conservando datos, sesiones y la instalación original de Play en la tablet.

Las pruebas físicas usaron el Motorola con LTE y la tablet con Wi-Fi. La tablet seguía bloqueada; se respondió mediante la pantalla entrante de Novvin. Ambas llamadas posteriores a la actualización recibieron paquetes de audio en ambos extremos, con cero pérdidas reportadas, una oferta inicial por llamada y ningún evento ICE de desconexión durante las muestras. Los procesos sobrevivieron a colgar.

| Muestra | Receptor: marca previa a contestar → señalización | Receptor: marca previa a contestar → medios |
| --- | ---: | ---: |
| Referencia anterior, primera | 1.042 ms | 3.625 ms |
| Referencia anterior, repetición | 1.009 ms | 3.556 ms |
| Actualización, primera | 1.390 ms | 3.944 ms |
| Actualización, repetición | 1.019 ms | 3.453 ms |

Estas marcas incluyen aproximadamente un segundo de ejecución de ADB; no son mediciones exactas del toque humano. La inicialización nativa de WebRTC a medios en la primera llamada actualizada fue de **2.976 ms**. Los tiempos del emisor incluyen el intervalo hasta que la herramienta contesta en el receptor y no deben presentarse como demora exclusiva del servidor. En una muestra falta su marca inicial y el dato se conserva como nulo.

Se probó silenciar y reactivar el micrófono: los registros muestran la desactivación y reactivación de la pista local. También se pulsó el control de altavoz y se restauró, conservando la llamada. No se certifica la calidad audible ni la ruta acústica con estos controles automatizados.

La cancelación sin aceptar cerró la pantalla entrante y no dejó activos los servicios entrante ni de llamada en la tablet. En ese receptor no hubo unión a la sala, inicialización de medios ni conexión de audio.

## Comprobación del servidor

Los nueve servicios estaban en ejecución y saludables. El servidor tenía unos 13 GB de memoria disponible. Se midieron seis validaciones autenticadas de la cuenta ficticia, desde el contenedor de señalización hacia la API interna: HTTP 200 en todas, **7,1–41,5 ms**. Este resultado no mide LTE, Wi-Fi, DNS público ni el enlace completo de los teléfonos; únicamente descarta una demora de varios segundos en esa muestra interna de autenticación.

Los resultados y hashes están en [la evidencia sanitizada](../evidencias-movil-capacidad-fallback-2026-10-02/resultados.json).

## Pendientes y límites

- **El objetivo de conexión inferior a un segundo sigue abierto.** Estas muestras del camino directo no prueban una mejora consistente de latencia respecto de las referencias.
- No se reprodujo un fallo de preparación en las llamadas físicas posteriores. El salto directo al fallback está cubierto por pruebas automáticas, pero falta medir físicamente su duración en una red que impida WebSocket. La muestra lenta anterior de 10.646 ms sigue como referencia abierta en el documento 45.
- Falta medir DNS, TLS, cola de solicitudes, upgrade y negociación ICE por fases en las llamadas lentas. El RTT de los candidatos exitosos de una muestra anterior fue de aproximadamente 282–312 ms; no equivale a demora de voz en un sentido.
- El arranque en frío del Motorola tras esta instalación tardó 6.109 ms según `am start -W`; es otra observación pendiente y no debe atribuirse directamente a la API.
- Continúan pendientes las demás opciones de mensajería, grupos/comunidades, salas/LIVE y la auditoría completa. Esta validación no certifica toda la app ni FCM de la instalación de Play.

## Reversión

Revertir este commit y recompilar restaura el comportamiento anterior de fallback y capacidad. No hubo cambios de backend, base de datos ni versión Android en este bloque.
