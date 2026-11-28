# Confirmación de mensajes en el chat de la mesa

28 de noviembre de 2026. Corrección de los recursos compartidos por Android y la web.

El chat muestra el progreso junto a «Enviar» y desactiva ese botón durante el envío. Si se pierde la respuesta, consulta la mesa para comprobar si el mensaje está en el historial. Al confirmarlo por su identificador y asiento, muestra «Mensaje enviado» y conserva el texto que el usuario haya escrito después. Si sigue sin confirmar, mantiene el aviso y usar «Enviar» de nuevo con el mismo texto reutiliza el identificador original.

El borrador se limpia al abrir otra mesa, incluso cuando la nueva mesa empieza en espera. Así, un texto escrito para una partida anterior no pasa al chat de otro rival.

Pasaron 59 pruebas de cliente y tres suites de API. En Chromium se probó la pérdida de la confirmación a 360 y 620 píxeles. Cada sesión mostró el mensaje una sola vez y conservó el nuevo borrador. El corte se simuló con una API de laboratorio.

La tarea Gradle `:app:mergeReleaseAssets` produjo los cinco recursos modificados desde la APK 195. Cada archivo de salida coincidió byte por byte con las fuentes actuales. Quedan pendientes el ensamblado y la firma de una APK nueva, las pruebas en teléfonos y el despliegue web.
