# Continuación 182 · Saldo confirmado y errores de actualización del monedero

Fecha: 17 de noviembre de 2026. La revisión integral sigue abierta. Esta selección corrige la lectura de saldo/deuda y su confirmación visible; no cierra el monedero, las ganancias, los regalos ni las compras.

## Fallos y comportamiento final

La lectura usaba RPC y un acceso REST compatible. Si ambos respondían con errores, los dos devolvían null y el cliente fabricaba WalletBalance con saldo y deuda cero. El Result se consideraba exitoso y borraba la instantánea previa. Un cuerpo HTTP exitoso pero sin los campos de saldo también se interpretaba como ceros. Además, sesión o configuración ausentes devolvían éxito sin haber consultado nada.

Ahora la lectura REST distingue una lista vacía **confirmada por HTTP exitoso** de una consulta fallida; una respuesta fallida o malformada conserva los últimos datos. Se requieren saldo entero no negativo y, cuando viene la columna, deuda entera no negativa. Se mantiene la compatibilidad con el RPC ausente y una fila válida o lista vacía confirmada de la API REST. No se cambia el contrato del servidor propio de Ubuntu.

fetchBalance captura la visita antes de pasar al trabajo de E/S y usa accountScopedCall: renueva credenciales, comprueba la visita tras obtenerlas y entre RPC y fallback, y protege tanto la publicación como el resultado. Cambiar A→B→A durante token o respuesta invalida el resultado anterior. La ausencia de sesión o configuración es un fallo recuperable, no una confirmación.

WalletViewModel.refresh actualizaba siempre la hora, aunque el repositorio devolviera fallo. Solo una lectura confirmada cambia la hora; el fallo conserva la fecha previa y muestra un aviso. Reintentar con éxito elimina ese aviso específico. El bloqueo de carga se toma antes de programar el trabajo, y el control se libera también ante excepción o cancelación. No se convierten cancelaciones en errores comunes.

Monedas y pagos ahora observa el error y ofrece Reintentar. Antes de la primera confirmación muestra «Sin confirmar», en lugar de presentar el cero inicial como saldo verificado. La recarga, el catálogo y el envío de regalos mantienen sus contratos; la selección no inicia compras, retiros ni cargos.

## Comprobaciones

Evidencia externa: `D:/Novvin-Entregas/evidencias/2026-11-17-mobile-audit182-wallet/`.

- Referencia inicial: **7 fallos de aserción en 10 casos**, con el repositorio original del commit feature 41aa5790c8a6617a315004a16d7be4a90ec32555. Esas dos fuentes del monedero no cambiaron en 181.
- Referencia ampliada `reference-refresh-v2`: **9 fallos en 14 casos**. Añade el ViewModel real y reproduce la fecha falsa de primera carga y de actualización fallida. Son variantes de comportamiento, no nueve defectos independientes.
- Candidata `prepared-v2`: **14/14 casos JVM directos aprobados**, con huellas estables. Los dos archivos de producción y ambas pruebas copiados a los worktrees tienen exactamente esas mismas huellas. Los controles incluyen saldo/deuda reales ficticios, errores503/401, cuerpo inválido, cero confirmado, fallback compatible, sesión/configuración ausentes, visitas antiguas, reintento y bloqueo de una recarga activa.
- Gradle Audit: **25/25 casos en cinco clases, cero fallos/errores/omisiones**, entradas y HEAD estables, sin cambios de huellas privadas y con los cambios ajenos preservados. Comprueba compilación Kotlin/Java/Compose/Hilt de las fuentes aplicadas y las cinco clases de saldo, actualización, WalletPayoutStateTest, GiftSendCoordinatorTest y PlayPurchaseQueueTest. No equivale a un ensayo de cobro.

Se conservan los intentos intermedios reference-refresh/prepared que no compilaron porque se consultó la metadata temporal del módulo mientras Gradle la regeneraba. No se cuentan como defectos del producto. Las repeticiones v2 usaron la metadata de producción ya terminada y son los recibos válidos.

No hay una APK nueva ni ensayo físico. La APK QA 9.1.43/183 identificada en 180 no incluye 181 ni 182. Los HTTP son del cliente real Ktor con MockEngine y datos ficticios; no se cambia ni se valida mediante cargos el servidor productivo.

## Alcance pendiente

Siguen abiertos las cargas de ganancias y movimientos, el ciclo completo de cuenta de los demás estados del ViewModel, la coordinación del saldo con regalos/compras simultáneos, las demás operaciones del repositorio y sus recorridos de dispositivo. La lectura de archivos completos se separa de la validación de estos métodos: WalletRepository y WalletViewModel se examinaron completos, pero esta selección no afirma validación de todas sus funciones; CoinsAndPaymentsScreen conserva lectura parcial. Ninguna prueba de esta selección cierra módulos no revisados.

La publicación incluye solo tres fuentes Android propias, dos pruebas, este informe y cobertura desde índice; main y feature/live-gifts conservan el mismo árbol y los cambios ajenos quedan intactos.
