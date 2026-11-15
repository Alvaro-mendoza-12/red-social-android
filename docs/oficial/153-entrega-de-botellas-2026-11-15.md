# 153 · Entrega de botellas pendientes

Se corrigieron dos recorridos de la función existente. La API recorría las 25 botellas más antiguas al renovar la recepción, incluidas ofertas todavía vigentes para otros usuarios. Una botella en espera podía quedar fuera de ese lote. La web renovaba la disponibilidad con el mismo intervalo que la caducidad del registro y descartaba la botella devuelta por ese mismo POST.

La API devuelve primero la oferta vigente de la persona. Si su bandeja está libre, busca por antigüedad entre botellas elegibles para esa cuenta. Omite sus propias botellas, remitentes bloqueados, pares que ya conversan, intentos anteriores y ofertas vigentes de otras personas. Una oferta vencida deja de ocupar la bandeja. Los bloqueos, el límite de conversaciones, la caducidad y el anonimato siguen formando parte del contrato.

La web lee la oferta directamente al renovar disponibilidad. Renueva a los cinco segundos de cada respuesta, con consultas de entrada y salida independientes y cancelables. Al cerrar o pausar la recepción, la disponibilidad deja de renovarse y expira en el servidor. Esto evita que una pestaña que sale desactive otra que acaba de abrir la bandeja.

## Comprobaciones

- La API anterior dejó una botella sin entregar detrás de 26 ofertas vigentes en un PostgreSQL local en memoria con PGlite. Con el parche, la cuenta receptora entró después del envío y recogió la botella. La pasó, una tercera cuenta la aceptó y el remitente y el receptor vieron el mismo chat con el texto original. Se verificó exclusión de un remitente bloqueado y ausencia de identidad del autor en la oferta.
- Aprobaron 13 controles de proximidad y botellas y las cinco pruebas web focalizadas. La regresión web comprueba la recepción aunque la consulta de salida siga pendiente.
- La entrega es a una persona elegible que active su bandeja. La botella puede quedar en espera hasta su caducidad. Este recorrido no incorpora notificaciones de segundo plano.
