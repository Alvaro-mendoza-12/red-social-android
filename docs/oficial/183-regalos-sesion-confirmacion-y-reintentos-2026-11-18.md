# Continuación 183 · Regalos, visitas de sesión y reintentos duraderos

Fecha: 18 de noviembre de 2026. Continúa la auditoría móvil, con prioridad en acciones que pueden descontar monedas. Esta selección corrige autorización de envío, comprobantes y persistencia del reintento; no cierra el monedero ni la revisión integral.

## Correcciones

- La confirmación retenida y el trabajo programado podían obtener la cuenta después del clic. Ahora el diálogo conserva su visita y el ViewModel la pasa explícitamente al repositorio. Un botón de una visita anterior se rechaza; A→B→A también invalida la confirmación anterior.
- El repositorio reutiliza accountScopedCall para renovación/token y respuesta. Comprueba cancelación/visita después del catálogo, después de guardar la clave y antes del POST, y antes de aceptar el comprobante. Las respuestas de otra visita no se publican como regalos confirmados ni eliminan su clave pendiente antes de comprobar su propietario.
- El coordinador confirma otra vez el guardado de la clave en cada reintento. SharedPreferences puede actualizar memoria aunque commit() devuelva false; la presencia en memoria ya no autoriza un POST sin confirmar durabilidad. Se conserva el algoritmo de claves para no perder intentos anteriores. La consulta de pendientes usa la misma identidad de cuenta normalizada que el envío.
- Un HTTP exitoso exige transacción y saldo entero no negativo. Los campos adicionales del comprobante, cuando llegan, deben confirmar destinatario, regalo, directo, visibilidad y éxito del mismo intento. Se conservan los comprobantes compatibles que omiten esos campos; no se cambia el servidor Ubuntu ni sus contratos importados.
- El ViewModel reserva el envío antes de programarlo, separa la operación por objeto/visita, libera el botón también al cancelar antes de empezar y limita la operación a 45 segundos, incluyendo credenciales. El cambio/salida de cuenta cancela la operación y elimina recibos/avisos anteriores. La finalización antigua no libera otra operación nueva.
- El diálogo cierra al cambiar de visita y no entrega al directo un comprobante de otra cuenta/destinatario/directo. Cambiar destinatario o directo descarta la confirmación y vista previa; variar la condición de alto valor o visibilidad reinicia su aceptación y cuenta atrás. Los callbacks conservados comprueban la visita y que la confirmación siga abierta.
- Si una respuesta anterior se perdió, un rechazo del reintento no demuestra que no hubiera cargo. Se conserva la clave y se comunica el estado pendiente, sin afirmar «No se descontaron monedas». El servidor puede comprobar su disponibilidad antes de buscar la transacción previa; se conserva esta compatibilidad sin alterar producción.

## Evidencia

Carpeta externa: `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit183-gifts/`.

- Referencia completa contra código estable 182 de feature 1c2f7092d5085a8f9eda86ce4889dc3ad1bd5c32: **24 escenarios, 19 fallos de aserción**. Son variantes de sesión/HTTP/confirmación, no diecinueve defectos independientes. Los recibos conservan fuentes copiadas de Git y huellas de las pruebas.
- Candidata completa: **24/24 aprobados**, con compilación Kotlin/Compose de cuatro fuentes productivas y dos clases de prueba; entradas estables. ViewModel y WalletRepository son los reales, Ktor usa MockEngine y preferencias/servicio de autenticación están controlados. Se ejercitan clic retenido, cola anterior al cambio, A→B→A, token, catálogo, guardado fallido, respuesta tardía, vacío de sesión visual, comprobantes incompatibles, reintento ambiguo y plazo.
- Gradle Audit: **49/49 casos en siete clases, cero fallos/errores/omisiones**, compilación Kotlin/Java/Compose/Hilt correcta, entradas y HEAD estables, huellas privadas sin cambios y diferencias ajenas preservadas. La selección comprende las dos clases nuevas y los controles previos de saldo, refresh, retiros, idempotencia y cola Play.
- La prueba existente `gift-transaction-flow.test.js` del servidor aprobó catálogo, duplicados, concurrencia, reembolso y reversión ante errores de transacción. Usa un pool ficticio; los dos errores23514 impresos son fallos deliberados de sus pruebas, no de la base productiva. Fuentes estables y ningún archivo de servidor modificado.
- Se conservan las etapas preliminares: referencia9/12, ampliada15/20, final18/23 y completa19/24; candidatas20/20,23/23 y24/24. La ampliación añade confirmación retenida, normalización de cuenta, metadatos de comprobante y mensaje del reintento.

Las pruebas no acreditan dispositivos, pérdida física de red, persistencia tras reinicio real ni cargos de Google Play. No hay APK nueva ni despliegue de servidor. La QA 9.1.43/183 identificada en el informe180 no incluye automáticamente estos commits.

## Continuidad y publicación

Se publican únicamente cuatro fuentes Android propias, dos pruebas, este informe y cobertura del índice en ambas ramas, conservando un árbol común y los cambios ajenos. La lectura de GiftSelectionDialog permanece parcial; los demás archivos propios fueron examinados completos. La cobertura de lectura no equivale a validar todas sus funciones.

Siguen pendientes Ganancias y otros estados privados del monedero, historial/paginación, coordinación del saldo con operaciones simultáneas, precio/valoración del regalo y los demás contratos y recorridos de compra. Tampoco se cierra aquí la gestión de errores locales después de una confirmación conocida. El bloque184 se preparará aparte y no forma parte de estos resultados.
