# 187 · Auditoría integral y criterios de producción de Novvin

18 de noviembre de 2026. **En ejecución; no se acredita preparación integral para producción.** Este seguimiento responde al mandato adjunto del propietario y conserva la prioridad Android antes de web e integración. Se trabaja sin agentes, sin borrar trabajo ajeno y sin promover el plan experimental105.

El registro estructurado [187-auditoria-produccion-registro-2026-11-18.json](187-auditoria-produccion-registro-2026-11-18.json) separa módulos, defectos, riesgos, evidencia y requisitos externos. Sus estados pendientes no se convierten en aprobados por búsquedas, inventario o compilación.

## Base, alcance y ramas

La base inicial del mandato es main `981d8da4bf8cff8262e394d6f75f63cce71e45b2` y feature/live-gifts `56e55094f2a66f578cac9009aaf602186da001c7`, con árbol `21cc60df722273b9f4214de026179691f734eac9`. Se usa el worktree existente de feature/live-gifts para cambios controlados y main para las comprobaciones acordadas. La rama real se llama `feature/live-gifts`; no existe una ref `live-gifts`. No se renombra ni crea otra rama estable para aparentar igualdad.

El propietario confirmó que habilitó todos los países en Google Play Console. Se registra esa declaración como alcance del mercado, conservando pendiente la comprobación de pista y listado efectivo de Console. La disponibilidad de la tienda no acredita por sí sola cumplimiento jurídico en cada jurisdicción.

El propietario confirmó solamente el correo administracion@novvinapp.com y explicó que no tiene una entidad registrada ni un domicilio comercial definido. Esto no se convierte en la afirmación de que deba tener una oficina o sociedad. No se inventan nombre legal, país, dirección, representación ni registros. La identificación del responsable y el análisis profesional por mercados siguen pendientes.

## Hallazgos de esta continuación

Se registran doce familias nuevas: ninguna crítica confirmada en esta selección, nueve altas y tres medias. N001–N009 quedaron publicados con188/189. N010/N011 se corrigen en [191](191-precio-consentido-y-comprobantes-de-regalos-2026-11-18.md), con incorporación efectiva y migración del contrato heredado todavía pendientes. Esto no demuestra ausencia de problemas críticos en los módulos pendientes.

| ID | Gravedad | Hallazgo | Corrección/evidencia |
| --- | --- | --- | --- |
| N001 | Alta | Estados de saldo/productos conservados o restaurados entre visitas | [188](188-refresco-sesion-y-disponibilidad-confirmada-2026-11-18.md): propiedad de visita, estado atómico y rechazo de respuestas antiguas |
| N002 | Alta | Una sección bloqueada detiene todo el refresco sin plazo |188: secciones independientes con plazo y liberación por cancelación |
| N003 | Media | Respuesta inválida/fallida parece tienda deshabilitada |188: contrato validado, fallo visible y reintento |
| N004 | Media | Diagnósticos técnicos del SDK/servidor visibles |[189](189-compras-ciclo-confirmacion-y-errores-seguros-2026-11-18.md): mensajes públicos controlados y diagnóstico mínimo sin tokens |
| N005 | Alta | Un controlador cerrado ejecuta callbacks o inicia consultas |189: cierre idempotente, generación y rechazo de callbacks obsoletos |
| N006 | Alta | La cola anterior bloquea o altera una verificación nueva |189: cancelación por visita, reserva previa y finalización por identidad |
| N007 | Media | Padre y modal mantienen clientes de compra simultáneos |189: consumidor padre cede su conexión al modal; lectura de código y compilación, ensayo visual pendiente |
| N008 | Alta | ACK incompleto confirma monedas o convierte saldo ausente en cero |189: comprobante estricto, renovación y propiedad de cuenta |
| N009 | Alta | Google Play no responde y el selector queda esperando |189: plazos de conexión/catálogo y reconsulta explícita |
| N010 | Alta | Cambiar el precio almacenado permite cobrar15 tras mostrar5 |191: precio consentido obligatorio en v2; migración heredada pendiente |
| N011 | Alta | Repetir un regalo devuelve95 aunque otro cargo dejó90 |191: saldo actual e importe original en ambos contratos; activación pendiente |
| N012 | Alta | Una lectura antigua puede restaurar el saldo tras un regalo, una compra o una lectura posterior | [192](192-orden-de-respuestas-de-saldo-2026-11-18.md): reservas por lectura e invalidación al intentar un cargo |

Las correcciones tienen evidencia controlada, recibos Gradle y publicación propia. La incorporación efectiva y las pruebas físicas se registran por separado. El registro no usa estos hallazgos para cerrar el monedero completo.

## Evidencia

- 188: referencia corregida25 casos/20 fallos; al ampliar confirmación y consumidores, referencia41 casos/33 fallos y candidata41/41. Se conserva el primer arnés HTTP sin ContentNegotiation; no se cuenta como defecto del producto.
- 189: referencia16 casos/15 fallos y candidata16/16, incluidos cuatro controles de la cola. No son compras reales.
- Integración directa final:226/226, seis fuentes de producción reales incluidas Compose, cinco clases nuevas y regresiones del monedero. Se compiló con el mismo nombre de módulo para no mezclar métodos internos con nombres ABI distintos.
- El script Node de compras aprueba catálogo, idempotencia, RTDN y conciliación de reembolsos con proveedores/pool ficticios. No se presenta como validación de PostgreSQL productivo ni de una transacción monetaria real.
- Batería JVM completa: 3475 pruebas en 580 clases; cero fallos, cero errores y 0 omitidas. Fuentes, huellas privadas y HEAD estables; las doce entradas propias y los cambios ajenos se conservaron. La ejecución original fue interrumpida sin resultado terminal; sus logs se conservan en gradle-full. Se comprobaron los dos HEAD, doce huellas e índices intactos y locks libres antes de reanudar en gradle-full-restarted. No se atribuye aprobación al intento interrumpido.
-191: referencia Android236 casos/10 fallos y candidata236/236; Gradle236/23clases, sin fallos, errores ni omisiones. API28/28, seis casos HTTP locales, y tres scripts de regresión aprobados con base/proveedores controlados. No se atribuye la batería completa del padre a estas fuentes nuevas.
- [190](190-revision-juridica-inicial-por-mercados-2026-11-18.md): contraste inicial de fuentes oficiales por mercados; alcance profesional y demás países pendientes.
- 192: referencia con 7 fallos en 10 casos. Candidata con 246 casos focalizados aprobados. Gradle sin filtros registró 3495 pruebas en 582 clases, con 0 fallos, 0 errores y 0 omisiones.
- Compilación de producción, firma y dispositivos del commit exacto: pendientes. El antiguo checkout de validación tiene modificaciones ajenas y no se sobrescribe; la preparación de una copia limpia se documentará antes de usarla.

Los logs y recibos están fuera del repositorio en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit188-refresh/` y `2026-11-18-mobile-audit189-billing/`. La última APK histórica no incluye automáticamente estas correcciones.

## Continuidad y criterios de cierre

La matriz de cobertura anterior registra4.666 archivos:3.589 pendientes,264 parciales y813 leídos a una huella. Estas cifras son un inventario histórico de lectura, no pantallas validadas. La nueva publicación recalcula huellas desde el índice y solo marca las doce rutas Android examinadas. Los módulos del registro siguen abiertos hasta validar sus recorridos, permisos, interrupciones, regresión y dispositivos relevantes.

Continúan las revisiones de precio/consentimiento de regalos, saldo concurrente, catálogo, cuenta/menores, demás funciones móviles, web, API, base de datos, dependencias, rendimiento medido, políticas y despliegue. La ley y la información del propietario deben contrastarse antes de cambiar políticas públicas; los proveedores que deban declararse por transparencia no se ocultan bajo la regla de evitar diagnósticos internos.

La salida requiere pruebas generales y focalizadas aprobadas, revisión de todos los módulos, resolución o impedimento explícito de hallazgos, paquete de producción verificable y decisión jurídica/operativa con datos reales del responsable. No se acredita cumplimiento legal del 100%, una mejora de velocidad sin medición ni seguridad integral por compilar.

Cada publicación usa selección propia, commit en main y feature/live-gifts, push sin forzar y comparación de árboles y remotos. Sus recibos conservan hashes y fuentes ajenas intactas. No se usa reset destructivo ni se añade trabajo ajeno para aparentar un árbol de trabajo limpio.
