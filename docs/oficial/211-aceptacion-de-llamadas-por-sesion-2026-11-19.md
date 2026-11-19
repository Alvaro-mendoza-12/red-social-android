# 211 · Una aceptación de llamada por cuenta y sesión

19 de noviembre de 2026. El servidor ya serializaba las respuestas por cuenta y llamada. Después del bloqueo, reconocía como reintento cualquier nueva aceptación de esa cuenta. Dos teléfonos con sesiones distintas podían recibir confirmación sobre la misma invitación. Una acción tardía del teléfono que seguía sonando también podía terminar la llamada del otro.

La primera aceptación guarda el identificador de sesión autenticado en la fila del participante, dentro de la transacción existente. El cliente conserva su contrato HTTP. La misma sesión puede repetir la aceptación, renovar su token, reconectar y terminar la llamada. Una segunda sesión recibe el conflicto `CALL_ANSWERED_ELSEWHERE` al intentar aceptar, abrir señalización o cerrar esa respuesta. El control se aplica también a las notificaciones de respuesta. El emisor conserva sus controles. Una nueva invitación explícita a un participante que ya salió libera la asociación anterior.

La columna opcional se añade de forma idempotente. Las filas de llamadas ya aceptadas antes de la actualización conservan el comportamiento anterior durante su vida restante. La protección se aplica a las nuevas aceptaciones. Se usa el identificador validado por el servidor y nunca se devuelve en la respuesta de llamada.

## Verificación

Dos sesiones independientes iniciadas con la misma cuenta de prueba respondieron a la vez mediante HTTP contra la API y PostgreSQL 17 en loopback. La cuenta llamante usó una tercera sesión. En la segunda ronda se invirtió el orden de envío. Los resultados se muestran en el orden de las dos solicitudes.

| Ronda | Primera solicitud | Segunda solicitud |
| --- | ---: | ---: |
| 1 | 200 | 409 |
| 2 | 200 | 409 |
| 3 | 409 | 200 |

Con la versión anterior, las dos sesiones recibieron HTTP 200 en las tres rondas de referencia. Con la corrección hubo una sola aceptación por ronda. La ganadora mantuvo acceso tras renovar el token. El aviso tardío de llamada perdida de la otra sesión recibió el conflicto y la cuenta llamante siguió conectada. La sesión ganadora terminó la llamada. Los 76 controles del almacén de llamadas también aprobaron, incluida la reinvitación en un grupo. Las huellas y los resultados están en `D:/Novvin-Entregas/evidencias/2026-11-19-mobile-audit211-call-answer-session/`.

Quedan pendientes la activación en Ubuntu, el cese automático del timbre y el texto de «respondida en otro dispositivo» en el teléfono que no interactúa, además del ensayo físico de notificaciones en segundo plano. El informe 210 cubre medios con dos extremos sintéticos. Esta continuación cubre la decisión del servidor cuando compiten dos sesiones de un mismo destinatario.
