# Partida de ajedrez entre dos sesiones autenticadas

28 de noviembre de 2026. El ensayo usó la web compilada que incluye el ajuste de acceso 283 y la imagen candidata `novvin-api:qa-board-admin-196`. Una base PostgreSQL temporal y dos cuentas ficticias ejecutaron el flujo por HTTPS desde Chromium en 360 y 620 px. El laboratorio se conectó a Ubuntu por un túnel local y se retiró al terminar.

Las dos cuentas iniciaron sesión desde el formulario y entraron a Juegos. Una encontró a la otra por nombre y la invitó a su mesa privada. El destinatario abrió la invitación guardada en la bandeja de notificaciones y aceptó. Ambos vieron el nombre del rival y recibieron los cambios de la misma partida. El ensayo comprobó la entrega de una reacción, un mensaje, cuatro jugadas de ajedrez y el resultado correcto para cada jugador. Ambos confirmaron la revancha y entraron al tablero nuevo al pulsar «Entrar a la revancha».

El gateway de laboratorio cortó las respuestas de aceptación de la invitación, envío del mensaje y aceptación de la revancha después de recibir éxito de la API. El navegador reintentó algunas conexiones. La consulta final a PostgreSQL comprobó dos mesas en total, una invitación, un aviso, un mensaje de chat, una reacción y cuatro jugadas. La mesa finalizada enlazaba una sola revancha. Chromium registró cero errores de JavaScript.

El ensayo validó la bandeja de avisos con datos persistidos. La entrega física por Firebase, llamadas, salas de voz y Google Play siguen pendientes. El ingreso de Ubuntu continúa con `/health` en 200 y `/ready` en 503. El canal oficial de ClamAV aún anuncia la base diaria 28194, fuera de la ventana de 48 horas de la política de subidas. El candidato sigue en laboratorio.
