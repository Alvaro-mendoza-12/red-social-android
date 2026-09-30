# Notificaciones sociales y tarjetas de rondas

Proyecto: `D:\red social - live-gifts`, rama `feature/live-gifts`. Fecha: 30/09/2026.

## Cambios terminados

- La notificación de amistad explica la acción: «[nombre] aceptó tu solicitud de amistad». El servidor completa mensajes vacíos o que solo contenían el nombre; Android aplica la misma protección con textos localizados para amistad y seguimiento. Se conservan los mensajes que ya contienen información.
- Las tarjetas de rondas usan pares de colores del tema con contraste comprobado. El título puede ocupar varias líneas y el botón para actualizar tiene su espacio propio y un área táctil de 48 dp. La altura crece con el contenido y el tamaño de letra.
- Un vencimiento ausente, inválido o pasado no provoca una excepción ni un contador negativo. Los intervalos extremos se limitan al rango válido.
- El diagnóstico de servicios revoca su sesión temporal al terminar, incluso si falla una comprobación.

## Evidencia

- Backend: 276 pruebas, 275 aprobadas, cero fallidas y una omitida por plataforma. Incluye mensajes de aceptación sin cuerpo, con cuerpo vacío y con solo el nombre.
- Android: 902 pruebas unitarias aprobadas, cero fallidas, antes de la última corrección del ciclo de vida WebRTC. Incluye el texto de notificaciones, fechas de rondas y contraste en los 25 temas claros y oscuros.
- Motorola moto g04: prueba instrumentada de tres estados de ronda en modo claro y oscuro, título largo, fuente al 160 % y botón de actualización aprobada. Las capturas se revisaron durante la auditoría.
- API actualizada en el mini-PC después de guardar respaldo y comprobar diferencias. La generación del payload remoto confirma la frase completa.

La entrega de llamadas y el transporte de medios se documentan aparte. Estas comprobaciones no certifican toda la aplicación sin errores.
