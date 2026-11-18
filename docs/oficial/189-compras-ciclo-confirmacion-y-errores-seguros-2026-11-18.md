# 189 · Compras: cierre, comprobantes y errores seguros

18 de noviembre de 2026. Continúa [188](188-refresco-sesion-y-disponibilidad-confirmada-2026-11-18.md), dentro del mandato [187](187-auditoria-integral-y-criterios-de-produccion-2026-11-18.md). Son correcciones del flujo existente; no se habilitan compras ni se cambia el catálogo del servidor.

## Hallazgos y cambios

| ID | Gravedad | Antes | Corrección |
| --- | --- | --- | --- |
| N004 | Media | debugMessage del SDK y message de errores del servidor podían llegar al diálogo | Mensajes públicos fijos o códigos permitidos. El registro interno conserva operación y código, sin tokens ni payload del SDK |
| N005 | Alta | Cerrar el controlador no impedía emitir callbacks, consultas o verificaciones posteriores | Cierre idempotente, cancelación del plazo y generación para ignorar respuestas antiguas |
| N006 | Alta | Los tokens pendientes y el mutex de la visita anterior podían bloquear la siguiente; un cierre antiguo podía soltar el indicador nuevo | Jobs reservados antes del despacho, cancelación por visita y finalización solo de la identidad vigente. La visita nueva usa su propia exclusión |
| N007 | Media | Pantalla y modal podían tener conexiones de compra simultáneas | El padre cierra su controlador al abrir el modal. Los catálogos locales se reinician por visita, productos y ciclo del controlador; ensayo visual pendiente |
| N008 | Alta | Un ACK con estado credited pero sin saldo, bandera o monedas podía confirmar éxito y publicar cero | Validación estricta de estado, bandera, cantidad y saldo, renovación de token y comprobación de visita después del HTTP. pending no modifica saldo; un replay válido no vuelve a acreditar monedas |
| N009 | Alta | Un callback de conexión o catálogo ausente dejaba el selector esperando | Plazos de quince segundos, resultado visible y reconsulta explícita. Se evita iniciar conexiones simultáneas y se ignoran respuestas de intentos vencidos |

La verificación HTTP y la actualización posterior de saldo también tienen plazo del ViewModel. Un fallo ambiguo informa que no pudo verificarse, conservando el modelo de comprobación del servidor; no afirma que Google no haya cobrado. Los callbacks de pantallas llevan su visita y no escriben, abren compras o limpian mensajes de otra visita. El diálogo se cierra si pierde autorización, sin bloquear la salida por una verificación de la cuenta anterior.

La identidad de cuenta se normaliza con Locale.ROOT para el identificador ofuscado. El servidor propio ya valida obfuscatedExternalAccountId antes de acreditar y rechaza tokens acreditados a otra identidad. La lectura de ese bloque y sus pruebas no se convierte en auditoría del archivo completo del servidor.

## Evidencia

- `2026-11-18-mobile-audit189-billing/reference-complete`:16 casos y15 fallos de aserción; candidata16/16. Incluye cierre antes/después de setup, catálogo y compras, callback cancelado, arranque repetido, cierre idempotente, mensajes, ausencia de callback, cola cancelada y deduplicación.
- Los trece escenarios de comprobantes y los del ViewModel se conservan en188/reference-receipts y candidate-receipts, dentro de41 casos. Se prueba ACK correcto, replay, pending, importes faltantes/negativos, estado desconocido, renovación y respuesta de otra visita.
- La integración directa final aprueba226/226. Batería Gradle completa: 3475 pruebas en 580 clases; cero fallos, cero errores y 0 omitidas. Fuentes, huellas privadas y HEAD estables; las doce entradas propias y los cambios ajenos se conservaron.
- `server-coin-flow.log` termina con éxito del script de compras: catálogo, idempotencia, crédito RTDN, revisión de reembolso y conciliación de deuda. Pool y respuestas de Google ficticios; no verifica cargos ni credenciales productivos.

Las cifras de casos no equivalen al número de defectos. N007 se sustenta en la lectura del ciclo de ambos consumidores y su compilación, con ensayo físico de interfaz pendiente. Los casos de SDK usan el controlador real y un BillingClient controlado; no acreditan ofertas, países o cobros de una cuenta real de Google Play.

Se siguió la [documentación oficial de integración de Play Billing](https://developer.android.com/google/play/billing/integrate): la verificación y el consumo siguen en servidor; los mensajes de diagnóstico se usan para depuración y se evita mantener clientes activos redundantes. No se sustituyen precios ni se simula una acreditación.

## Continuidad

Se publican exclusivamente los cambios propios con188 y187, mediante commits separados por rama con árbol común y push sin forzar. No se tocan credenciales, datos productivos ni el trabajo de Errores/CommentItem/SEO de otro asistente.

Siguen pendientes Google Play real, interrupción física de red, dispositivos, APK nueva, precio/consentimiento de regalos, saldo concurrente y demás módulos. La revisión jurídica global no se cierra con estas correcciones. La compilación de producción se verifica por separado con fuentes de un commit limpio.
