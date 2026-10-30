# 91 — Cable de 30 m, dominio/correo y opción dLocal

Fecha: 29/10/2026, Bogotá. Actualiza **las preferencias de compra y de proveedor** después del [cierre de auditoría 90](90-cierre-produccion-red-y-presupuesto-2026-10-29.md); no repite sus pruebas ni cambia los artefactos firmados 170.

## Decisiones vigentes del propietario

- El tramo largo de cable requerido es de **30 metros**.
- Se mantiene la prioridad de un switch gigabit alrededor de S/50.
- Debe presupuestarse **dominio más correo corporativo**, no sólo el dominio.
- **UPS y respaldo fuera del local quedan pospuestos** por decisión expresa del propietario. Se conserva el respaldo externo local existente; no se elimina ni desactiva ninguna copia o temporizador.
- PayPal no es la opción preferida. El propietario recuerda un proveedor con un nombre parecido a «de local», con distintas monedas y conversión; **dLocal es una hipótesis fundamentada, todavía por confirmar**. No se habilitan retiros ni se contrata un proveedor basándose sólo en esa identificación probable.

## Cableado

Para los 30 m y el objetivo de un enlace de 1 Gbps, Cat6 correctamente especificado es suficiente; no se necesita pagar Cat7 para eliminar el límite actual de 100 Mbps. La referencia técnica de la revisión 90 admite incluso Cat5e para 1000BASE-T hasta 100 m. Revisar material/conectores y todos los puertos de la ruta, incluido router → switch. La tarjeta del servidor ya soporta gigabit; no se debe forzar su negociación remotamente contra un puerto de 100 Mbps.

El [catálogo de Memory Kings](https://www.memorykings.pe/listados/857/cables-red-patch-cord) muestra un Cat6 Delcom de **30 m a S/31**, referencia de precio con envío aparte. Esa ficha no acredita por sí sola que el conductor sea 100 % cobre. Confirmar material y especificación del modelo antes de comprar; la reserva puede ser mayor que la referencia más económica. No se presume que hagan falta dos cables de 30 m: verificar el segundo tramo según la instalación real.

## Dominio y una casilla de pago

La cifra anterior de **S/60 correspondía a reservar para el dominio solo**. No era una oferta de dominio y correo de pago incluidos. La observación del propietario de «ochenta y tantos» puede ser compatible con un conjunto económico, pero falta conocer proveedor, nombre, impuestos y cambio aplicado a la tarjeta.

Ejemplo verificable por separado, para un dominio ordinario y **una** casilla:

- [Porkbun .com](https://porkbun.com/tld/com): **US$11,08 al año** como referencia publicada; comprobar nombre disponible, precio de renovación y total del carrito.
- [Zoho Mail Lite](https://www.zoho.com/mail/compare/overview.html): entrada de **US$1 por usuario/mes con cobro anual**, equivalente a **US$12 al año** para un usuario. El [detalle del plan](https://www.zoho.com/mail/zohomail-pricing.html?src=ft) especifica facturación anual.
- Total de ese ejemplo: **US$23,08 al año**, antes de impuestos, conversión y posibles cargos del medio de pago. No se fija un equivalente exacto en soles sin conocer el cobro.

Dominio y correo pueden contratarse con proveedores diferentes. Una promoción inicial puede renovarse a otro precio y una casilla adicional aumenta el coste. El correo humano de soporte no sustituye automáticamente la configuración transaccional de recuperación de cuentas que ya funciona en el servidor.

## Presupuesto actualizado

Reservas orientativas dentro de S/300, no una compra ni una cotización cerrada:

| Partida | Reserva |
|---|---:|
| Switch gigabit | S/50 |
| Cable Cat6 de 30 m, material y conectores por verificar | S/70 |
| Dominio ordinario y una casilla por un año | S/100 |
| Saldo sin asignar para envío, diferencias de precio o próxima mejora | S/80 |
| **Total** | **S/300** |

No es necesario gastar el saldo restante. No se reserva ahora dinero para UPS o respaldo fuera del local. Si la cotización de correo/dominio supera S/100 o el cable de cobre supera S/70, revisar las partidas antes de comprometer dinero; estos topes no garantizan disponibilidad de un modelo específico.

## Identificación del proveedor de retiros

La [documentación de dLocal para Perú](https://docs.dlocal.com/docs/peru-payouts-v3) contempla payouts en PEN y transferencias bancarias; sus opciones concretas dependen del método y la habilitación comercial. Esto concuerda con la descripción, pero no demuestra que sea el proveedor que el propietario vio.

La [API de cotizaciones de dLocal](https://docs.dlocal.com/docs/quotes-configuration-payouts-v3) permite obtener una conversión junto con los cargos aplicables al indicar el importe, con un identificador y vigencia. Requiere activación del servicio. Para que la app explique cuánto se retirará, habría que integrar la cotización del servidor y mostrar saldo disponible, moneda destino, tipo aplicado, comisiones y neto; no basta identificar el país o convertir la cifra visualmente.

La [información comercial de payouts](https://www.dlocal.com/our-solution/payouts/) indica precios acordados según países, volumen y métodos; pueden existir componentes mínimos y margen cambiario. Confirmar admisión del proyecto y condiciones antes de elegir. No se contactó al proveedor, no se abrió una cuenta y no se asumió que un correo corporativo garantice aprobación.

La integración actual preparada en el repositorio es PayPal y permanece desactivada en producción. **No se ha implementado dLocal**: este documento registra una alternativa por investigar/confirmar, no una función disponible para usuarios. La elección final requerirá su propia integración, pruebas de cotización, límites, idempotencia, notificaciones y conciliación, conservando el saldo real bajo control del servidor.

## Estado del producto

La web sigue operativa y los APK/AAB 9.1.30/170 conservan su verificación local de la revisión 90. Siguen pendientes publicación y comprobaciones de Play, aceptación física entre redes y habilitación del proveedor de retiros. Esta actualización documental no presenta esos pendientes como resueltos ni reinicia pruebas ya aprobadas sin cambios funcionales.
