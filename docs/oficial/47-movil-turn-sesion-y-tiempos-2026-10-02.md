# TURN por sesión y tiempos nativos de llamadas

Fecha: 2026-10-02. Android conserva 117 / 9.0.20.

## Cambios

- Las llamadas reutilizan las credenciales TURN vigentes. Una conexión de señalización ya no fuerza otra descarga si la inicialización acaba de obtenerlas. Se renuevan cuando faltan, vencen o se solicita recuperación forzada.
- Al cambiar de cuenta se descarta la caché de TURN. Las respuestas pendientes solo pueden aplicarse si coinciden la generación de sesión y el token que las solicitó.
- El motor WebRTC reutilizado recibe callbacks nuevos para la llamada y cuenta actuales. Se evita emitir ofertas, respuestas o candidatos desde callbacks de una llamada anterior o de otra cuenta.
- Las mediciones usan el reloj monotónico de Android y distinguen señalización de medios conectados. Las llamadas salientes indican que incluyen la espera hasta que el destinatario contesta; las entrantes empiezan al aceptar.

No se cambiaron el backend, sus permisos ni la versión Android.

## Validación realizada

Pasaron **1.087 pruebas en 241 suites**, sin fallos, errores ni omisiones. Las pruebas nuevas cubren vigencia y renovación de TURN, aislamiento por generación/token, cambio de cuenta y medición de tiempos. Se generaron ambas APK y se verificó su clase generada de arranque.

Antes de que el usuario solicitara continuar exclusivamente con emuladores, se completaron dos llamadas entre Motorola con LTE y tablet con Wi-Fi. Ambas recibieron paquetes de audio en los dos extremos; tuvieron una oferta inicial, cero pérdidas reportadas en las muestras y ningún evento ICE de desconexión. Ambos procesos sobrevivieron a colgar.

| Receptor, reloj nativo desde aceptar | Primera llamada | Repetición |
| --- | ---: | ---: |
| Señalización conectada | 1.006 ms | 766 ms |
| Medios conectados | 3.542 ms | 3.292 ms |
| Solicitudes de TURN, por dispositivo | 1 | 0 |

La referencia anterior solicitaba TURN dos veces en la primera llamada y una vez al repetir. La caché vigente se reutilizó en ambos extremos en esta repetición. No se afirma que eliminar solicitudes garantice por sí solo una mejora determinada del tiempo total.

Los tiempos salientes hasta medios fueron 10.048 ms y 8.769 ms e **incluyen el tiempo que la herramienta tardó en contestar**. No son una medición de demora exclusiva del servidor. El RTT observado fue aproximadamente 276–327 ms; no equivale a latencia de voz en un sentido. La medida completa de la primera llamada entrante es 3.542 ms, aunque el tramo posterior a construir el motor sea más corto.

Resultados y hashes: [evidencia sanitizada](../evidencias-movil-turn-sesion-2026-10-02/resultados.json).

## Límites y siguientes comprobaciones

- **La conexión de medios inferior a un segundo sigue pendiente.** Las muestras completas permanecen alrededor de 3,3–3,5 segundos.
- El aislamiento de cambio de cuenta está cubierto por pruebas de lógica y revisión del código; falta su comprobación integrada con dos cuentas en emuladores. No se certifican todas las transiciones de sesión.
- No se repitió la cancelación sin aceptar con esta última APK ni se forzó una red que bloquee WebSocket en estas dos muestras.
- No se certifican calidad acústica, todas las opciones de llamadas, salas, LIVE ni la aplicación completa.
- A petición del usuario, las pruebas siguientes se realizan **solo con emuladores**, en orden desde arranque y Mensajes. La regresión visual de estado/reintento de stickers es el siguiente bloque.

## Reversión

Revertir este commit y recompilar restaura la renovación anterior y sus mediciones. No requiere migración de base de datos ni reversión del servidor.
