# Línea base y continuación 125

La [continuación 125](../oficial/125-live-participacion-y-recuperacion-2026-11-13.md) supera 2 777 pruebas JVM en 524 suites y doce controles de emulador API 35. Sus veinte pruebas Node de API/SQL/R2 y la comprobación del gateway se ejecutaron con Node 22.23.4, además de siete recorridos web con cuentas sintéticas. Se separan los componentes Android del recorrido autenticado Android–web aún pendiente. [Evidencia 125](evidence/125-resultados.json).

## Línea base del candidato 124

Ejecución local entre el 12 y el 13 de noviembre de 2026, hora de Bogotá. PostgreSQL 16, datos sintéticos, navegador Chromium de escritorio, API y gateway propios. El servidor de producción no formó parte del ensayo. El resumen verificable se encuentra en [resultados](evidence/124-resultados.json).

| Recorrido | Método y observación |
| --- | --- |
| Android estable 9.1.40, código 180 | `:app:assembleDebug :app:testDebugUnitTest`. 2 768 pruebas JVM, 522 suites, cero fallos, errores u omisiones. |
| Android en dispositivo virtual | Se instalaron 9.1.40-debug y el APK de instrumentación en un emulador API 35 efímero. Pasaron 10 pruebas de importación de GIF y WebP originales, gestos, entrega y controles de reintento de stickers. |
| Web | Compilación TypeScript/Vite y prerender de 21 páginas públicas. 1 196 pruebas Jest en 187 suites. Lint sin errores, siete advertencias. |
| API y gateway | 50 pruebas seleccionadas de servicios, otras 20 de datos/proximidad, suite focalizada LIVE, gateway real y 34 pruebas de señalización. Las cifras pertenecen a ejecuciones distintas y se solapan. |
| Privados | Dos inicios de sesión desde la interfaz. Envío único al reintentar, recepción real, historial recuperable, contacto y listas diferentes, cierre de sesión y ruta privada tras recarga. |
| Grupo y comunidad | El receptor ya estaba en la sala cuando el emisor adjuntó un TXT desde la interfaz. Se comprobó descarga con idénticos bytes, persistencia al recargar y recuperación de un mensaje tras desconectar el navegador receptor. |
| Documentos y stickers | PDF de una página visible y descarga con firma PDF. PowerPoint sintético de 11 084 008 bytes enviado y descargado por otra cuenta con igualdad byte a byte. WebP animado confirmado por distintos fotogramas en la pantalla receptora. Selector con 49 elementos, filtros y desplazamiento interno. |
| Voz y vídeo | Llamada desde búsqueda de contacto, aceptación, pistas remotas en dos contextos de Chrome y cierre. Videollamada con audio y vídeo sintéticos. Micrófono denegado y aviso visible. |
| LIVE y juego | Crear e iniciar, repetir comentario e invitación, aceptar y rechazar, transmitir un trazo por WebSocket al segundo jugador, responder, puntuar, salir y finalizar. Dos temporizadores concurrentes consumen una sola ronda vencida. |
| Integración R2 en laboratorio | Diez pruebas del diario PostgreSQL y réplica, y tres del adaptador S3 contra un servidor de prueba sobre loopback. Incluyen reintentos, verificación del contenido y recuperación de una subida multipart incompleta. El servicio externo quedó fuera del ensayo. |
| PostgreSQL adicional | Paginación de 10 000 mensajes y mezcla de datos históricos; contadores de publicación al reabrir la conexión. |
| Rendimiento observado | 12 lecturas locales consecutivas por ruta. Historial p50 de 12,5 ms, p95 de 30,0 ms. Seguidores p50 de 16,0 ms, p95 de 24,1 ms. El p95 por rango más cercano corresponde al máximo de esta muestra pequeña. |

**Repetición.** Desde `NoWing-web` usar `npm test -- --maxWorkers=2`, `npm run lint` y `npm run build`. El ensayo de temporizadores espera una base PostgreSQL vacía e independiente llamada `novvin_live_draw_timer_test` y su URL local en `LIVE_DRAW_TIMER_TEST_DATABASE_URL`. Desde la raíz ejecutar `node --test infrastructure/local-fallback/api/tests/live-draw-timer.postgres.test.js`. Para el gateway, `node infrastructure/local-fallback/realtime-gateway/tests/server.test.js`. Los tamaños y fotogramas del navegador se guardan en la evidencia. La aplicación de depuración se instaló en el emulador. Los ensayos con equipos físicos, pagos y la publicación quedan pendientes.
