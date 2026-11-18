# 188 · Refresco por visita y disponibilidad de compras confirmada

18 de noviembre de 2026. Parte de la auditoría integral [187](187-auditoria-integral-y-criterios-de-produccion-2026-11-18.md), después de [186](186-historial-confirmado-paginacion-y-visitas-2026-11-18.md). El alcance es Android; no es cierre del monedero ni certificación de producción.

## Hallazgos y cambios

- **N001, alta:** el refresco y la confirmación de saldo no estaban asociados a una visita. Al cambiar de cuenta podían conservarse la fecha de confirmación y la disponibilidad anteriores; una respuesta antigua podía reponerlos. Ahora las cargas tienen propietario, generación y cancelación. El cambio de visita borra los estados privados y recarga la nueva visita, también en A→B→A.
- **N002, alta:** el balance, catálogo, historial y disponibilidad se cargaban secuencialmente sin un plazo del controlador. Una espera bloqueaba los recorridos siguientes. Se usan corrutinas hijas independientes: quince segundos por balance, catálogo y disponibilidad; el historial conserva su plazo propio de quince segundos y una envoltura de veinte. La cancelación de una página reemplazada no cancela las otras secciones; el cierre de pantalla sí cancela sus tareas. No se introducen reintentos automáticos de cobros.
- **N003, media:** la consulta de compras usaba el token sin renovación y convertía configuración ausente o ciertos cuerpos inválidos en tienda deshabilitada. Se captura la visita antes de E/S, renuevan credenciales y validan disponibilidad, lista de productos, cantidades y claves únicas. Se conserva la respuesta heredada de un objeto o un único objeto en lista y una desactivación confirmada sin productos. El fallo de conexión/contrato tiene aviso propio y reintento; no se presenta como desactivación confirmada ni invalida un saldo correcto.

La pantalla de monedas y el diálogo consumen un estado inmutable que agrupa visita, confirmación, balance/deuda, disponibilidad y mensajes de compra. Se ocultan los datos de otra visita y los importes sin confirmar. Las actualizaciones posteriores de saldo se leen desde los valores vigentes de los flujos al publicar el estado; no se asocia una confirmación nueva a una fila antigua recibida por otro colector.

La verificación y su confirmación de compra, los callbacks de Google Play y la cola de tokens se documentan en [189](189-compras-ciclo-confirmacion-y-errores-seguros-2026-11-18.md).

## Evidencia

Carpeta: `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit188-refresh/`.

- La referencia inicial corregida, reference-linked, reproduce20 fallos de25 casos; candidata25/25. El primer arnés omitía ContentNegotiation, por lo que sus fallos de serialización no se cuentan como defectos del producto.
- Al incorporar comprobantes y tres controles más del consumidor, reference-receipts reproduce33 fallos de41 casos, con snapshots del padre186. candidate-receipts aprueba41/41. Los casos son variaciones de los hallazgos; no son33 errores independientes.
- Integración final con189: **226/226 pruebas JVM directas**, compilando seis fuentes reales incluidas Compose y las pruebas nuevas/afectadas con el nombre de módulo correcto. Las doce huellas aplicadas se comprobaron contra los archivos usados en el recibo.
- Batería Gradle completa: 3475 pruebas en 580 clases; cero fallos, cero errores y 0 omitidas. Fuentes, huellas privadas y HEAD estables; las doce entradas propias y los cambios ajenos se conservaron.

Las pruebas controlan cuentas, HTTP, proveedor y preferencias. Se ensayan cambio y regreso de visita, respuestas no cancelables, expiración, plazos, secciones bloqueadas, respuesta correcta, reintento y cancelación. No son compras reales, reproducción de una caída física de red ni validación visual en un teléfono. No se afirma una mejora de velocidad de dispositivos a partir de los tiempos de estas pruebas.

La selección usa un worktree con CommentItem ajeno conservado, cuyos cambios no se publican. No se produce una APK con ese árbol mezclado. La compilación de producción del commit exacto queda como comprobación separada en una copia limpia; el checkout antiguo de validación tiene cambios ajenos y permanece intacto.

## Publicación y continuidad

Se publican con189 las fuentes/pruebas propias y el registro187, conservando main y feature/live-gifts con el mismo árbol. Los índices y cambios ajenos se comprueban antes y después; los recibos de publicación conservan los hashes de ambos commits y la verificación remota.

Siguen abiertos el catálogo/precio de regalos, el orden del saldo entre operaciones concurrentes, la integración física de Google Play, la web, los otros módulos Android, las políticas por mercado, la compilación de producción y el cierre integral. No hay APK nueva entregada ni despliegue de servidor por estas correcciones.
