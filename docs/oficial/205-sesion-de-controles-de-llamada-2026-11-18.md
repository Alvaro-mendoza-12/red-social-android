# 205 · Sesión propietaria de controles de llamada en Android

Las operaciones de preparar, aceptar y terminar una llamada se autenticaban con la cuenta activa al recuperar el token. Si cambiaba la cuenta durante la renovación, una operación iniciada en la visita anterior podía salir con otra credencial o dar por válida una respuesta tardía. El mismo límite afectaba los avisos diferidos y el mensaje de inicio de una llamada colectiva.

La API de control usa la visita que inició la llamada para renovar credenciales y validar la respuesta. Las alertas y mensajes diferidos conservan esa visita. Un cierre local sigue limpiando la llamada aunque haya cambiado la cuenta. Cada aceptación confirmada genera su aviso sin repetir la misma mutación en el servidor. Cuando un intento se cancela, la pantalla de llamada saliente libera el indicador y permite reintentar.

## Evidencia

- La referencia falló en cuatro de seis casos de control HTTP simulado. Los controles de sesión corriente y de respuesta de ocupado aprobaron.
- En la referencia, cancelar el inicio dejaba el indicador activo. La candidata permite iniciar una segunda llamada y recibe su navegación.
- La candidata integrada pasó 51 pruebas JVM con las fuentes Kotlin y Compose reales. Los registros, las huellas de entrada y las pruebas de referencia están en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit205-call-account/`.

Esta prueba usa sesiones ficticias y transporte simulado. Las sesiones simultáneas en teléfonos, la revocación de medios, la entrega de avisos y el enrolamiento en Play siguen pendientes. La compilación Audit y los artefactos Release del commit exacto se registran con recibos independientes.
