# 192 · Orden de las respuestas de saldo del monedero

18 de noviembre de 2026. Continuación de la [auditoría móvil](187-auditoria-integral-y-criterios-de-produccion-2026-11-18.md) y de la [corrección de regalos](191-precio-consentido-y-comprobantes-de-regalos-2026-11-18.md).

Una lectura iniciada con mil monedas podía restaurar ese saldo después de confirmar un regalo que lo dejaba en 990, una compra que lo elevaba a 1.100 o una consulta posterior que confirmaba 700. Una respuesta antigua también podía marcar como actualizado el saldo después de un fallo o cancelación. El registro clasifica estos casos como un hallazgo de gravedad alta, N012.

Cada consulta reserva el derecho a publicar su resultado antes de renovar las credenciales. Una consulta posterior reemplaza esa reserva. El envío de un regalo y la verificación de Google Play invalidan las lecturas pendientes al comenzar y al salir de la operación. Esto cubre respuestas ambiguas, cancelación y pérdida del comprobante. La salida de una lectura antigua solo libera su propia reserva. Se conservan las comprobaciones de sesión y el último saldo confirmado.

La referencia falla en siete de diez casos con el repositorio anterior. Con el parche pasan los diez casos y 246 pruebas de regresión en 24 clases. Los ensayos usan Ktor con respuestas controladas. Se cubren las lecturas fuera de orden, una compra confirmada, un regalo confirmado, un resultado ambiguo y la cancelación. El orden entre dos cargos simultáneos, las actualizaciones de otros dispositivos y el ensayo físico siguen pendientes.

La batería Gradle completa registró 3495 pruebas en 582 clases, con 0 fallos, 0 errores y 0 omisiones. Las tres rutas Android se verificaron con las mismas huellas en ambos worktrees. Los logs y recibos están en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit192-balance-order/`.
