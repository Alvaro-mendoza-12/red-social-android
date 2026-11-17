# 179 · Retiros: cuenta, reintentos duraderos y respuestas de pantalla

Continuación de la [auditoría móvil 178](178-auditoria-movil-sesion-red-y-plazos-2026-11-17.md), sobre `main 67bc545a` y `feature/live-gifts 77b67fe8`. La revisión integral sigue abierta; este bloque examina las cuatro operaciones del repositorio de retiros y sus consumidores del monedero. No habilita pagos ni cambia los contratos del servidor.

## Fallos y correcciones, por prioridad

| Prioridad | Defecto | Corrección |
| --- | --- | --- |
| Alta | Renovar una credencial o recibir una respuesta después de cambiar de cuenta permitía continuar con la sesión equivocada. El identificador no distingue A → B → A. | Cada operación captura la visita iniciadora. Esa visita acompaña al repositorio, la renovación y la publicación de estado. El monedero cancela la operación anterior y vacía el resumen de retiros al cambiar la visita. |
| Alta | `request()` generaba otro UUID al reintentar una respuesta perdida. El servidor deduplica por creador y clave, por lo que una clave nueva representa otra solicitud. | El diario privado guarda cuenta, monto y clave antes de enviar. Un resultado incierto conserva la misma clave, también al reconstruir el repositorio y su almacenamiento. Cambiar el monto queda bloqueado hasta comprobar el intento anterior. Una confirmación permite iniciar otra intención; un rechazo de negocio inequívoco permite corregir los datos. |
| Alta | Un fallo de escritura local podía dejar una entrada solo en memoria. Un reintento no debe confundirla con un guardado duradero. | Cada intento vuelve a exigir un `commit()` correcto antes de autorizar HTTP. Los datos ilegibles se conservan y bloquean el envío. No se borra el diario para aparentar que no hay pendientes. |
| Alta | La cuenta podía cambiar durante la limpieza local posterior a un ACK, después de la última comprobación de red. | Se comprueba de nuevo la visita después de esa suspensión, también para resultados negativos que requieran limpiar un rechazo conocido. |
| Media | El bloqueo de retiros se activaba dentro del trabajo de E/S; dos pulsaciones podían pasar la comprobación anterior al lanzamiento. Los errores y cancelaciones podían dejarlo activo. | El controlador reserva una única operación al recibir la acción, captura su visita y libera el progreso en `finally`. La finalización antigua no libera ni muestra errores en una operación nueva. |
| Media | Un fallo de la recarga podía sustituir el éxito de una operación ya confirmada por un error genérico. | La pantalla distingue confirmación y recarga fallida. La limpieza fallida del diario conserva el ACK, avisa de su pendiente y retiene la clave para evitar duplicar el intento. |

Se conservan las firmas públicas anteriores del repositorio. La nueva dependencia `CreatorPayoutRetryStore` se inyecta en producción; las pruebas del repositorio usan la misma frontera de autorización y controlan únicamente credenciales, almacenamiento y API.

Los rechazos de habilitación, proveedor, verificación, cuenta y mínimo se evalúan en el servidor antes de buscar una solicitud anterior. Si existía un intento incierto, estos rechazos conservan su clave: no demuestran su inexistencia. Un rechazo inequívoco se guarda como tal antes de limpiar; así una limpieza fallida no impide corregir el monto cuando el almacenamiento se recupera. Se mantiene compatibilidad con entradas anteriores del diario.

## Evidencia

La referencia anterior reprodujo **doce fallos en dieciséis pruebas**: cuatro operaciones con renovación A → B, renovación A → B → A y respuesta antigua; los cuatro controles de cuenta vigente aprobaron. Una candidata intermedia reprodujo después un fallo del ViewModel real al perder el significado del ACK durante una recarga fallida. Otro control reprodujo la falta de comprobación tras la limpieza local; ambos se conservan separados de la referencia inicial. `before-server-compatibility` reprodujo otros dos fallos de compatibilidad antes de la corrección final.

La selección de esta continuación incluye **40 casos**: dieciséis de cuenta, siete del controlador de operaciones, cinco del ViewModel real y doce del diario/reintento. Los casos del diario ejercitan reconstrucción con datos persistidos, confirmación perdida, cambio de monto, separación de cuentas, cancelación, escritura fallida con memoria ya modificada, limpieza fallida y datos ilegibles. La reconstrucción usa un modelo de las preferencias Android con disco y memoria separados; no se presenta como una muerte física del proceso en un teléfono.

La batería Gradle Audit completa aprobó **3.222 pruebas en 562 clases**, sin fallos, errores ni omisiones, y `assembleAudit` terminó correctamente. Verificó la nueva dependencia en Hilt. Esa ejecución precede al último ajuste de compatibilidad de tres archivos; la selección final compiló las fuentes Kotlin reales y aprobó los **40 casos** con huellas estables en `compatible-final`. No se atribuye la batería anterior a fuentes posteriores.

El primer arranque de Gradle se interrumpió sin ejecutar casos porque el verificador retenía el lock que la propia configuración de Gradle exige. Se conservaron el resultado de salida 1, las huellas estables y cero casos; se corrigió solo el verificador externo. La ejecución posterior es independiente de ese intento.

Registros, fuentes de referencia y huellas: `D:/Novvin-Entregas/evidencias/2026-11-17-mobile-audit179/`. `baseline`, `before-confirmation-message` y `before-final-cleanup-guard` conservan las reproducciones; `durable-candidate` y `final` contienen los 37 controles anteriores a la última guardia; `gradle-full` conserva la interrupción de laboratorio y `gradle-full-v2` la comprobación general Audit anterior al ajuste de compatibilidad.

## Límites y continuación

La batería completa no certifica los módulos no examinados ni sustituye el recorrido de un teléfono. Siguen pendientes el fallo físico de almacenamiento, la muerte real del proceso, la navegación y diálogos físicos, y la integración real con el proveedor de pagos. Las compras, regalos, ganancias y demás opciones del monedero mantienen su revisión propia abierta; no se les atribuye el cierre de los retiros. Mis reportes conserva como candidato de lectura su manejo de fallos parciales. La matriz priorizada de 178 sigue vigente para los demás módulos.

El empaquetado de laboratorio produjo Audit `9.1.43-qa` / `183`, anterior al último ajuste de compatibilidad. También contenía el ajuste local ajeno de CommentItem presente durante la compilación. No se entrega como APK de esta selección final ni como publicación de Play.

Se preservaron las rutas de Social/CommentItem, SEO, documentación y demás trabajo ajeno. La promoción de Errores solicitada expresamente al otro asistente se coordina por separado; no se incluye en esta selección.
