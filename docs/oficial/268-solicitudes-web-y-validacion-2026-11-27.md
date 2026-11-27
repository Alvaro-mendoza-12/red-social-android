# Solicitudes de mensajes en la web

27 de noviembre de 2026 · Continúa la revisión de mensajería a partir de la [entrega Android 192](265-android-192-lectura-y-notificaciones-2026-11-27.md).

El control de solicitudes guardaba la conversación actual en una referencia durante el renderizado. Una decisión pendiente también podía llegar después de salir del chat y volver a él. El control ahora identifica cada visita una vez montada. La consulta, las decisiones y las restricciones del compositor usan el estado de esa visita.

La prueba de regresión pasó por dos conversaciones y volvió a la primera mientras su decisión original seguía pendiente. El botón de la visita nueva quedó disponible al completarse la decisión anterior. También pasaron los casos de aceptación en la cuenta correcta y del límite de un mensaje mientras la otra persona decide.

GitHub había rechazado la comprobación web por tres errores del archivo del aviso. La verificación de la candidata obtuvo cero errores de ESLint y nueve advertencias preexistentes, 1.362 pruebas Jest en 211 suites y 171 pruebas de Node aprobadas. La compilación comprobó las 22 páginas públicas y la exclusión de rutas privadas del índice. El ajuste del chat queda pendiente de desplegar y probar con una cuenta en producción. [Ejecución que registró los tres errores](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/45979984794/job/140441135816).
