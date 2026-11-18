# 193 · Búsqueda por visita de cuenta

18 de noviembre de 2026. Continúa la [auditoría móvil](187-auditoria-integral-y-criterios-de-produccion-2026-11-18.md).

La búsqueda unificada comparaba solo el identificador de cuenta. Al pasar rápidamente de A a B y volver a A, una visita anterior podía conservar la consulta, sus resultados o la página pendiente. También se reprodujo el caso de cambio de visita sin emisión del flujo que observa la pantalla. N013 agrupa estos casos de gravedad alta.

El ViewModel observa la generación de sesión junto con la cuenta. Cuando cambia de visita, invalida la búsqueda y su paginación, limpia la consulta y carga el historial de la cuenta actual. Las lecturas comprueban la visita antes y después de esperar la respuesta. Las escrituras de historial verifican la visita dentro de la misma protección que aplica el cambio de cuenta.

El código anterior falla en cinco de los 25 casos de la selección. La candidata pasa los 25. La selección incluye seis casos nuevos con el ViewModel y el controlador reales, repositorios controlados y cambios de cuenta programados. Conserva la consulta inicial que llega desde Vídeos, los resultados iniciales y la escritura en el historial de la cuenta actual. La ruta activa ya usa el controlador raíz según [171](171-cierres-precarga-avatares-y-fondos-2026-11-16.md).

La evidencia está en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit193-search-visits/`. Los ensayos físicos y la validación de los demás buscadores permanecen pendientes.

Para verificar el recorrido físico con cuentas de prueba, abre una sugerencia relacionada desde Vídeos y confirma que la búsqueda conserva el texto y que Atrás vuelve al vídeo. En búsqueda, deja una página por cargar, cambia de cuenta y regresa a la primera. Comprueba que la consulta se limpia, que los resultados anteriores desaparecen y que las búsquedas guardadas corresponden a la cuenta activa. Busca de nuevo y verifica que se pueden cargar más resultados de la nueva consulta.
