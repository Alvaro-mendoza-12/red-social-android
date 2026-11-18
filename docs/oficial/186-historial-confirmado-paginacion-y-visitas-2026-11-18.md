# Continuación 186 · Historial confirmado, páginas y visita de cuenta

Fecha: 18 de noviembre de 2026. Continúa la auditoría móvil después de [185](185-escritura-efectiva-y-reintentos-tras-limpieza-fallida-2026-11-18.md). No representa el cierre integral ni una APK nueva.

## Fallos y cambios

El historial ocultaba errores HTTP y respuestas inválidas: la pantalla podía mostrar «Todavía no hay movimientos» sin haber confirmado una lista vacía. Tampoco distinguía una primera carga fallida de una página adicional fallida. Ahora conserva la última lista confirmada y su continuación, muestra el error y permite reintentar la operación que falló. Solo una lista vacía confirmada se presenta como ausencia de movimientos.

La consulta captura la visita antes de cambiar de dispatcher, renueva las credenciales y comprueba la visita después de renovarlas y antes del HTTP. Una cuenta ausente o un entorno sin configurar ya no producen éxito. El plazo de quince segundos incluye credenciales y respuesta; la cancelación externa se propaga y libera el progreso sin inventar un fallo de conexión.

Una reserva de página impide dos solicitudes simultáneas de la misma continuación. Una actualización nueva reemplaza a la anterior: sus respuestas o errores tardíos no pueden sobrescribir el resultado vigente. El desplazamiento cuenta filas consumidas, no las filas que quedan después de eliminar duplicados. Se siguen solicitando cincuenta movimientos y una fila de anticipación; se eliminan identificadores duplicados también en la primera página para evitar claves repetidas en la lista móvil.

El lector exige movimientos con referencia e importes enteros válidos; no convierte cuerpos vacíos, null, elementos primitivos o importes ausentes/fraccionarios en movimientos o saldos cero. Comprueba el propietario si el servidor lo incluye. Conserva la respuesta heredada de un solo objeto válido, importes con signo y los dos nombres de saldo posterior. No cambia contratos ni despliega el servidor.

La pantalla consume filas, continuación y visita en un único estado inmutable: no asocia por separado las filas antiguas con la visita nueva al actualizar varios flujos. Oculta los datos de otra visita, inicia la consulta si cambia su propietario y cierra el recibo, el panel de límites y la recarga retenidos al cambiar de cuenta. Los callbacks retenidos de abrir/exportar un recibo comprueban la visita. El cálculo de gasto no se presenta como completo mientras el historial esté sin confirmar, cargando o en error.

El botón Cargar más reserva su estado antes de lanzar la corrutina y lo libera también por cancelación o cierre antes de ejecutar. El reintento distingue una continuación fallida de una primera página; un error de continuación de otra visita nunca hace saltar la primera página de la visita nueva.

## Evidencia y límites

Carpeta externa: `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit186-history/`.

- Referencia final válida: **29 casos del repositorio, 27 fallos de aserción**. Son escenarios distintos, no 27 defectos independientes. Tres snapshots productivos se cotejaron con el padre `9c79c1bb8c95225eedebf270175f703f0b5ca656`.
- Candidata integrada: **37/37 casos JVM directos aprobados**, con las tres fuentes productivas reales, incluida la pantalla Compose, y dos clases nuevas: 29 controles HTTP/historial y ocho de interacción del ViewModel. Las cinco huellas aplicadas coinciden con la selección compilada; `reference-parent.json` conserva la comprobación.
- Gradle Audit: **164/164 casos en 16 clases, cero fallos/errores/omisiones**, BUILD SUCCESSFUL; Kotlin/Java/Compose/Hilt compilados. Recibo `gradle-history/result.json`, de 13:20:53 a 13:37:31 UTC, con HEAD, entradas y huellas privadas estables. El verificador confirma las cinco huellas propias y la preservación de cambios ajenos. La selección incorpora los122 controles anteriores, los37 nuevos y cinco de presentación del monedero. Compilación y pruebas se distinguen de ensayos físicos.
- La exploración inicial dio22 fallos en24 casos del repositorio y su candidata aprobó24/24. Una comparación ampliada de34 casos incluía controles del ViewModel cuyo IO no estaba gobernado por el planificador de Main de referencia; ese número no se usa para acreditar defectos. La referencia final se limita a los29 casos del repositorio; los ocho del ViewModel son controles de la candidata.
- Los contextos Android, preferencias, cuentas, HTTP y respuestas de estas pruebas son controlados. No se acreditan cargos reales, un ensayo de cuenta de producción ni una comprobación visual en teléfono. La pantalla real compila, pero su navegación y diálogos físicos siguen pendientes.

Se leyó el consumidor real del servidor propio: applyOrder en postgrest-compat-routes.js admite solo un campo. Se conserva `created_at.desc`; no se introduce un orden compuesto que esa implementación ignoraría. La paginación ante inserciones concurrentes y empates temporales del servidor requiere su revisión específica. No se marca el archivo completo del servidor como leído por examinar esa función.

No hay APK nueva ni despliegue de API. Las entregas históricas no contienen automáticamente este commit.

## Publicación y continuidad

Se publican exclusivamente tres fuentes Android, dos pruebas nuevas, este informe y cobertura calculada desde el índice. Los cinco archivos propios se examinaron completos. Se conservan las fuentes, documentos, índices temporales y reservas del otro asistente. main y feature/live-gifts reciben árboles iguales.

Quedan abiertos los otros estados del refresco general del monedero y Google Play, precios/catálogo de regalos, escrituras heredadas, pruebas físicas y restantes módulos de la APK. La revisión integral continúa abierta; lectura de código, pruebas, APK y despliegue conservan alcances separados.
