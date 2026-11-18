# Continuación 181 · Reportes, cargas parciales y confirmación del servidor

Fecha: 17 de noviembre de 2026. La revisión integral móvil sigue abierta. Esta selección corrige consultas de «Mis Reportes» y cuatro contratos de lectura/modificación de MessageReportStore; no certifica todos los reportes, el panel de administración ni el resto de la APK.

## Fallos reproducidos y corrección

- **Privacidad y sesión:** la pantalla conservaba reportes ya mostrados al salir o cambiar de cuenta. Una petición antigua también podía reemplazar la respuesta de una recarga o de una visita nueva a la misma cuenta. Se observa la visita de sesión, se limpia la pantalla al cambiar, se cancela la carga anterior y se comprueban propietario y generación antes de publicar.
- **Conexión y datos incompletos:** el fallo de una fuente se convertía en una lista vacía sin aviso; incluso podía mostrarse «No tienes ningún reporte». Usuarios y videos se consultaban secuencialmente. Ahora comienzan juntas, cada una tiene un plazo de 15 segundos que incluye la renovación de credenciales, y los resultados parciales conservan sus elementos con un aviso de la fuente pendiente y un botón Reintentar. Solo dos consultas confirmadas vacías muestran el estado vacío.
- **Confirmación HTTP:** updateReportStatus y deleteReport ignoraban el estado de la respuesta. Exigen éxito HTTP y la representación del reporte afectado; una actualización exige también el estado solicitado. Un 403, 503, JSON inválido, lista vacía o fila de otro reporte no se comunica como éxito. Se conserva el contrato de compatibilidad del servidor propio: PATCH/DELETE devuelven filas mediante sendPostgrestRows, y el cliente solicita return=representation.
- **Resultados y credenciales por visita:** getMyReports, getAllReports, updateReportStatus y deleteReport usan el helper accountScopedCall existente. Comprueban la visita antes/después de renovar o recuperar el token y después de recibir la respuesta, incluyendo A→B→A. La cancelación no se convierte en un error ordinario.
- **Datos privados en registros:** las lecturas ya no imprimen cuerpos de reportes ante errores HTTP o de deserialización.

La lectura de videos sigue usando VideoRepository, conservado por la reserva del otro asistente. El ViewModel protege su entrada, plazo y resultado. No se modifica el envío de reportes con evidencias ni los métodos nativos del panel de administración; esos recorridos requieren su propia revisión.

## Evidencia

Carpeta externa: `D:/Novvin-Entregas/evidencias/2026-11-17-mobile-audit181/`.

- Referencia ampliada contra los tres archivos originales del commit feature 41aa5790c8a6617a315004a16d7be4a90ec32555: **62 casos ejecutados, 39 fallos de aserción**. No son 39 defectos independientes; incluyen cuatro operaciones y variantes de sesión. `reference-expanded/result.json`, `inputs.json`, `compile.log` y `junit.log` conservan versiones y resultados.
- Candidata ampliada: **62/62 casos JVM directos aprobados**, con compilación de los dos archivos productivos, helper compartido y dos clases de prueba; huellas estables. Se ejercitan carga parcial, vacío confirmado, concurrencia, recarga antigua, cierre/cambio de cuenta, plazos, reintento, HTTP, representación y fronteras de credenciales.
- Gradle Audit: **69/69 casos en tres clases, cero fallos/errores/omisiones**, entradas y HEAD estables y huellas privadas sin cambios. Compila la pantalla Compose, Kotlin/Java, Hilt y pruebas con la nueva inyección de AuthService. El motor HTTP Ktor MockEngine se añade únicamente a testImplementation, en la versión 2.3.13 ya usada en producción.
- Se conservan los ensayos preliminares: el primer arnés carecía del motor MockEngine; la segunda versión no instalaba ContentNegotiation en el cliente ficticio y no se usa como evidencia del producto. La referencia corregida inicial reprodujo 22 fallos de 36 casos, antes de ampliar los controles a 62.

Los ensayos HTTP usan el cliente Ktor real con un motor controlado y datos ficticios. La lectura del contrato Ubuntu es de código; no se hacen cambios ni operaciones administrativas en producción. No hay ensayo físico nuevo, publicación de Play ni APK nueva: la entrega QA 9.1.43/183 identificada en 180 no incluye esta selección.

## Continuidad

La auditoría continúa abierta. La lectura posterior del monedero encontró una posible conversión de fallos de saldo a ceros; su referencia independiente reproduce 7 fallos en 10 casos y se corregirá en la selección siguiente, separada de estos resultados. También siguen pendientes otros recorridos privados del monedero, sus regalos/compras, el envío de evidencias, dispositivo y demás módulos.

Publicación de esta selección: únicamente las seis rutas Android propias, este informe y las huellas de cobertura del índice; ambas ramas estables conservarán árboles iguales y los cambios ajenos. La cobertura marca lectura completa solo en el ViewModel, pantalla y dos pruebas examinadas; MessageReportStore y app/build.gradle.kts permanecen parciales.
