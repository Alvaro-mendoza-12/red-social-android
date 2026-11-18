# 191 · Precio consentido y comprobantes recuperados de regalos

18 de noviembre de 2026. Continúa la auditoría móvil [187](187-auditoria-integral-y-criterios-de-produccion-2026-11-18.md), después de [188](188-refresco-sesion-y-disponibilidad-confirmada-2026-11-18.md) y [189](189-compras-ciclo-confirmacion-y-errores-seguros-2026-11-18.md). La corrección incluye el contrato de API consumido por Android; no acredita un despliegue, una compra real ni el cierre del monedero.

## Defectos reproducidos

**N010, alta:** tras mostrar un regalo de cinco monedas, cambiar a quince su precio almacenado hacía que el mismo cuerpo HTTP usado por el cliente cobrase quince sin una nueva confirmación. La autorización de cuenta y la idempotencia existentes no vinculaban el importe mostrado al cargo.

**N011, alta:** enviar dos regalos de cinco desde un saldo de cien y recuperar el comprobante del primero devolvía noventa y cinco aunque el saldo actual era noventa. El replay no duplicaba la transacción, pero Android podía presentar el saldo anterior a un cargo ya confirmado.

La referencia controlada con el contrato vigente reproduce dos fallos entre tres escenarios, incluido un control de precio sin cambios. No intervienen usuarios, monedas ni pagos productivos.

## Cambios

- Android envía a `send_virtual_gift_v2` el precio del objeto que la persona confirmó. El ViewModel conserva ese importe aunque el catálogo del repositorio cambie antes del envío. La API exige un entero positivo y compara la cotización con el catálogo leído dentro de la transacción; el cliente no puede fijar un precio distinto del servidor.
- Si cambia el precio, el servidor rechaza el cargo y Android pide actualizar el catálogo y confirmar otra vez. El selector ofrece esa actualización explícita y reinicia la confirmación reforzada si cambia el importe. La clave duradera se conserva para recuperar una transacción previa sin duplicarla.
- Los comprobantes de v2 deben incluir importe positivo y una bandera válida de replay. Una operación nueva debe confirmar exactamente el precio consentido. Un replay puede recuperar otro importe: se muestra el cargo original confirmado, sin sustituirlo por el precio actual del catálogo. Una respuesta incompleta no limpia la clave ni confirma el saldo.
- Ambos contratos de servidor devuelven el saldo actual al recuperar un comprobante y añaden el importe almacenado de la transacción. Se corrige tanto la recuperación temprana como el duplicado encontrado dentro del bloqueo. Un saldo actual de cero permanece en cero.
- Se conservan firmas de los consumidores existentes y la ruta heredada. El cliente nuevo no intenta un cargo sin cotización si el servidor responde que v2 no existe.

## Evidencia y alcance

Carpeta: `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit191-gift-quotes/`.

| Comprobación | Resultado | Límite |
| --- | --- | --- |
| Servidor original, cuerpo de petición actual | 3 casos, 2 fallos reproducidos | Pool ficticio; no transacciones productivas |
| Referencia Android con fuentes del padre 188/189 | 236 casos, 10 fallos de aserción | Variantes del contrato, no diez defectos independientes; incluye la expectativa modificada del comprobante de v2 |
| Candidata JVM directa, Repository/ViewModel/Compose reales | 236/236, entradas estables | No dispositivo ni compras de Google Play |
| Contrato de API y precio | 28/28 | Seis casos usan Express y sockets HTTP en loopback, con pool y autenticación controlados; no certifican JWT, TLS ni PostgreSQL productivos |
| Regresiones existentes de regalos, compras y seguridad PostgREST | Tres scripts aprobados, huellas estables | Proveedores y base de datos controlados |
| Gradle de la candidata nueva | 236 pruebas en 23 clases; cero fallos, errores u omisiones. Fuentes, huellas privadas y HEAD estables; ocho entradas propias y cambios ajenos preservados | Selección de 23 clases del monedero; separada de la batería completa del padre |

Los escenarios comprueban cotización coincidente, ausente, nula, negativa, fraccionaria o de tipo incorrecto; precio mayor/menor o cambiado al entrar en la transacción; nueva confirmación con la clave no cobrada; respuesta perdida y catálogo posterior; replays tras otro cargo y con saldo cero; reintentos simultáneos con una notificación; emisor, destinatario, suspensión, sesión y regalo inactivo; importe de comprobante inválido y servidor sin v2.

La batería completa de **3.475 pruebas en 580 clases** pertenece al padre 188/189. No se atribuye esa ejecución anterior a las fuentes nuevas de191. No hay medición de rendimiento físico ni APK de191 entregada.

## Publicación, compatibilidad y pendientes

Las fuentes y pruebas propias se publican en main y feature/live-gifts con árbol común, conservando cambios ajenos e índices temporales del otro asistente. Los recibos externos guardan commits, árboles y comprobación de los remotos. El archivo grande de compatibilidad del servidor solo se revisó parcialmente: esta corrección no lo marca entero como leído.

**Orden de incorporación:** verificar y activar la API compatible con v2 antes de distribuir la APK nueva. Mientras falte v2, el cliente nuevo falla sin recurrir a la ruta sin precio. El contrato heredado permanece para consumidores antiguos; estos aún necesitan una migración comprobada para vincular su precio mostrado al cargo. Esta continuación no elimina esa limitación de los binarios ya instalados ni afirma un despliegue productivo.

N010 queda corregido en el nuevo contrato y consumidor, pendiente de incorporación efectiva y migración heredada. N011 tiene parche en ambos contratos, pendiente de activación. Siguen abiertos el orden entre respuestas concurrentes de saldo, otros dispositivos, comprobación en el PostgreSQL del servicio instalado, red y permisos físicos, Google Play, la APK exacta y los demás módulos. La matriz jurídica inicial se conserva en [190](190-revision-juridica-inicial-por-mercados-2026-11-18.md), sin inventar datos del operador.

## Verificación posterior en PostgreSQL 17

Cuatro escenarios HTTP aprobados con Express, tablas reales del módulo y PostgreSQL 17.5 en una instancia local aislada. Ocho reintentos concurrentes produjeron un solo débito y aviso. Se verificaron el rechazo de un precio antiguo, la recuperación del importe y saldo tras otro cargo y la reversión completa ante un fallo de escritura. El caso inicial se detuvo antes de invocar la ruta porque faltaba la tabla de notificaciones en el entorno de prueba. El segundo creó esa tabla y aprobó los cuatro escenarios. Se usaron cuentas ficticias y un doble de autenticación.

El recibo está en `2026-11-18-mobile-audit191-gift-quotes/postgres17-loopback-v2/`. Sigue pendiente la activación y validación del servicio instalado.
