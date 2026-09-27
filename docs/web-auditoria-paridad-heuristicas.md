# Auditoría web frente a Android — `feature/live-gifts`

Fecha: 2026-09-26. Referencia de UX: [10 heurísticas de Jakob Nielsen](https://www.nngroup.com/articles/ten-usability-heuristics/).

## Alcance y evidencia

Se compararon las rutas de `app/src/main/java/conectate/com/presentation/navigation/Screen.kt` con `Conectate-web/src/App.tsx`, la navegación compartida y los componentes de cada función. Se compiló la web y se inspeccionaron portada, inicio de sesión y registro en 375 × 812 y 1440 × 900 mediante Playwright. La sesión autenticada no pudo revisarse visualmente: el backend de prueba rechazó el origen `http://127.0.0.1:5173` por CORS. Por ello, compilación y lectura de código confirman integración, pero no sustituyen una prueba de extremo a extremo con cuenta y servidor disponibles.

## Paridad funcional

| Área Android | Estado web comprobado por código | Brecha concreta |
| --- | --- | --- |
| Mensajes, grupos, comunidades, Novvin | Rutas, páginas y creación de grupos/comunidades disponibles | Comprobar envío, adjuntos, llamadas y reconexión con sesión real. |
| Videos, publicación y reproducción | Rutas disponibles; el publicador web permite recortar, elegir portada y previsualizar | Faltan descarga para uso sin conexión y herramientas avanzadas del editor Android (música, texto, stickers, velocidad y exportación). |
| Social, publicaciones, momentos y retos | Rutas disponibles, algunas funciones agrupadas en Social | Revisar todos los flujos de creación y detalle en navegador autenticado. |
| Juegos / PlayMatch | Sección añadida con perfil, catálogo, horarios, preferencias, búsqueda, detalle de jugador, conexiones, matchmaking y fotos JPG/PNG/WEBP | Falta avatar GIF/video corto y prueba con servidor activo. |
| Voz, llamadas, directos y proximidad | Rutas disponibles y ahora visibles en navegación; creación de salas de voz añadida | Faltan verificación visual e interacción real de permisos de cámara, micrófono y ubicación. Repetición de directos no tiene ruta web dedicada. |
| Perfil y ajustes | Perfil, edición y ajustes disponibles | Varias categorías de ajustes móviles están agrupadas; comprobar equivalencia de controles individuales. El selector de idioma web aún no traduce toda la interfaz. |
| Mis reportes | Ruta de consulta añadida | API devuelve hasta 12 reportes recientes; falta historial paginado si se necesita consultar más. |
| Monetización | Centro web existente | Mantener la restricción acordada de retiros. No se modificó ese flujo. |
| Inicio de sesión y registro | Páginas existentes; contraste corregido en etiquetas, campos, ayuda y estado QR | Validar autenticación con backend que admita el origen de prueba. |

## Diez heurísticas: criterios y resultado actual

| # | Criterio de aceptación verificable | Resultado de esta revisión |
| --- | --- | --- |
| 1 | Cada carga o acción muestra estado y resultado. | Juegos y reportes muestran carga, proceso, éxito y error. Otras rutas pendientes de revisión interactiva. |
| 2 | Términos comprensibles y coherentes con Android. | Navegación principal usa los mismos cinco nombres; se añadieron rótulos para funciones secundarias. |
| 3 | El usuario puede salir de menús y cancelar acciones. | Menú móvil se cierra con botón, fondo, Escape o navegación; matchmaking admite cancelar. |
| 4 | Acciones iguales mantienen nombres y patrones iguales. | Barra principal común y navegación secundaria común; revisar formularios heredados. |
| 5 | Entradas inválidas se previenen antes de enviar. | Formularios de Juegos exigen nombre y plataforma; botones se bloquean durante petición. Revisar validación de otros módulos. |
| 6 | Opciones visibles sin depender de memoria. | Funciones web existentes se exponen en lateral y menú “Más”; se mantuvieron las pestañas de Juegos. |
| 7 | Filtros y accesos rápidos facilitan tareas frecuentes. | Juegos permite buscar jugador, filtrar por juego y disponibilidad; menú directo a cada área. |
| 8 | Contenido legible, sin superposición ni ruido excesivo. | Se corrigieron contraste de acceso/registro y altura del error QR; Juegos y reportes usan rejillas adaptables y texto que puede partirse. Faltan capturas autenticadas en varias anchuras. |
| 9 | Mensajes de error explican recuperación. | Juegos traduce códigos frecuentes y ofrece reintento; reportes ofrece reintento. Errores heredados requieren auditoría. |
| 10 | Hay ayuda contextual donde la tarea lo exige. | Se añadió Centro de ayuda con respuestas y accesos directos; faltan ayudas específicas en algunos flujos complejos. |

**Criterio de salida:** ninguna pantalla debe declararse conforme sin abrirla en móvil y escritorio, ejecutar sus acciones principales con backend real, comprobar teclado, foco, contraste, estados vacíos/errores y revisar los diez puntos. Esta auditoría deja explícitas las áreas que aún no han superado ese criterio.

## Siguientes trabajos identificados

1. Habilitar un entorno de prueba web con CORS para el origen local o un dominio de staging y usar cuentas de prueba sin datos personales.
2. Completar la paridad PlayMatch: avatar GIF/video corto y confirmación de cada flujo con servidor activo.
3. Crear flujo web de videos sin conexión y edición, verificando soporte real de navegador y almacenamiento.
4. Auditar con capturas todas las rutas autenticadas en 360, 768 y 1440 px, incluidos tema oscuro y texto grande.
5. Revisar mensajes de error, ayudas y navegación por teclado en los módulos antiguos; corregir cada hallazgo y repetir la evaluación heurística.
