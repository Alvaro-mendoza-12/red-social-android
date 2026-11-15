# 159 · Ajustes web de Juegos y validación Android

15 de noviembre de 2026. Continúa la [entrega 158](158-playmatch-juegos-estable-2026-11-15.md). El cambio de producto corresponde a la cuarta pestaña en web. Android usa el mismo código de Juegos de la entrega anterior.

## Cambios web

- **Jugadores** muestra los filtros de la petición aplicada junto a los resultados. Editar el formulario conserva esa indicación hasta buscar de nuevo. Paginación y filtros mantienen el mismo alcance.
- **Partidas** presenta los criterios que usará la búsqueda y resalta el estado de la sesión. La lista de coincidencias identifica al jugador y su compatibilidad.
- **Actividad** tiene encabezados por bandeja, conteos, avatares y la acción Actualizar en un lugar fijo. El estado de carga inicial evita mostrar una bandeja vacía antes de tiempo.

## Validación

- La compilación web aprobó TypeScript, Vite y las comprobaciones de las 21 páginas públicas. La suite aprobó 1.280 pruebas. Chromium aprobó 320, 390, 768 y 1440 píxeles con una cuenta y respuestas de prueba. Se revisaron desbordamientos, filtros aplicados y paginación, criterios e inicio y cancelación de partida, recepción y aceptación de solicitudes y el enlace resultante al chat.

El preflight de Android aprobó `validateStableReleaseBackend` y `verifyReleaseSigningConfiguration`. Estas tareas verifican la configuración local del origen HTTPS y de firma. La clave de carga registrada en Play se verifica contra el artefacto firmado al preparar una publicación.
