# Novvin · Revisión nocturna 124

**13 de noviembre de 2026. Candidato local apto para staging.** Se leyeron los siete documentos del paquete del propietario, incluida la versión Word del prompt maestro. Las dos ramas estables contienen el mismo árbol de código probado. El ámbito actual incluye los recorridos de las capturas y defectos reproducidos durante las pruebas.

| Fase | Causa encontrada | Cambio | Resultado |
| --- | --- | --- | --- |
| Bandeja y contacto | El encabezado y los filtros ocupaban altura y el contacto solo mostraba cifras | Bandeja compacta, filtros que caben en portátil, historial desplazable sin barra, dos listas navegables, menú de tres puntos y cierre de sesión visible | Probado en tres anchos, con seguidores y seguidos diferentes y con bloqueo bilateral. [Captura del contacto](../docs/quality/evidence/124-contacto.png) |
| Documentos y stickers | La descarga privada añadía una dependencia de service worker. En el selector se repetía el texto de carga por casilla | Descarga autenticada en la página con límites y cancelación, tarjeta con apertura y descarga independientes, cuadrícula de stickers con desplazamiento propio | PDF abierto y descargado. PPTX de 11 084 008 bytes recuperado idéntico en otra cuenta. Se verificó animación en el receptor y se ensayó un catálogo de 49 elementos. [Captura del selector](../docs/quality/evidence/124-stickers.png) |
| Grupos y comunidades | El gateway resolvía un canal colectivo como chat individual. La pantalla podía perder la actualización al incorporarse | Autorización contra la colección correcta, límite de suscripciones simultáneas y recuperación cuando el gateway confirma el canal | Dos archivos enviados desde la interfaz, recibidos sin recarga y descargados con bytes iguales. El receptor también recuperó un mensaje después de desconectarse. |
| Llamadas | La entrada de la bandeja requería conocer el identificador del usuario | Búsqueda de contacto, rutas de voz y vídeo, errores de permisos y acciones de cierre | Dos contextos de Chrome recibieron pistas remotas. Se verificó también el micrófono denegado. |
| LIVE y juego | Un reintento de invitación repetía eventos. El canal del juego carecía de autorización en el gateway. La consulta de rondas vencidas fallaba en PostgreSQL | Operaciones idempotentes, controles de rol/bloqueo, autorización por canal y cierre transaccional de rondas | Dos cuentas invitaron, aceptaron, jugaron y salieron. El segundo jugador recibió el trazo por WebSocket. Dos procesos cerraron una sola vez una ronda vencida. |
| Android y servicios | Era necesario distinguir compilación, pruebas y entrega | Se reconstruyó Android de depuración y se ejecutaron los controles de la [línea base](../docs/quality/BASELINE_TESTS.md) | 2 768 pruebas JVM y 10 en emulador API 35. Se comprobó que importar GIF y WebP conserva el original animado. |

## Entrega y recuperación

| Módulo | Commit en main | Commit en feature/live-gifts | Archivos principales |
| --- | --- | --- | --- |
| Privacidad y laboratorio | `e9426d6a` | `deafa44b` | `data-routes.js`, `proximity-routes.js`, `blocked-profile-ids.js`, enlaces de escucha de API/gateway/señalización |
| Servicios de tiempo real | `4a297977` | `579f3163` | `live-routes.js`, `live-draw-guess-routes.js`, gateway y regresión PostgreSQL |
| Interfaz web | `8fced39a` | `92c4f15c` | Mensajes directos y colectivos, perfiles, selector de llamadas, adjuntos, selector de stickers, CSS y pruebas |

El árbol del candidato de código en ambas ramas es `74c78646c7bc1212708ea5a1b8792f693fb5c0b6`. Para obtener la relación exacta de archivos de código usar `git diff --name-status 8e6143e9 8fced39a`. Se conservaron los cambios preexistentes de SEO/Vercel del otro trabajo. La publicación y la intervención en producción quedaron fuera de la ejecución.

La tarjeta de documento permite abrir el archivo desde su nombre y descargar el original desde el botón lateral. [Captura de la tarjeta](../docs/quality/evidence/124-documento.png)

## Siguientes verificaciones

1. Dos equipos en redes independientes para llamadas, TURN, LIVE/SFU, expulsión de un orador y regreso desde segundo plano.
2. Medios privados de R2 en staging, primeros cuadros del feed, identificadores de caché por cuenta y presión de memoria con archivos grandes.
3. Proveedor económico en modo de prueba, reembolso y conciliación. Ensayar copia, restauración y reversión antes de la decisión de salida.
4. Completar revisión de módulos y archivos registrados como pendientes en la [cobertura](../docs/oficial/review-coverage.json). Los siguientes documentos distinguen observación, propuesta y pruebas pendientes.

[Inventario](../docs/product/PRODUCT_INVENTORY.md) · [Matriz de aceptación](../docs/quality/ACCEPTANCE_MATRIX.csv) · [Arquitectura](../docs/architecture/CURRENT_ARCHITECTURE.md) · [Riesgos](../docs/risks/RISK_REGISTER.md) · [Decisión de salida](../docs/operations/PRODUCTION_READINESS.md)
