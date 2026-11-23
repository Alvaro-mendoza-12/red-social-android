# 237 · Fase 1: referencias de publicaciones en la API de mensajes

22 de noviembre de 2026. Primer bloque funcional de la Fase 1, no su cierre. Continúa el [diagnóstico 236](236-fase-0-novvin-compartir-juegos-api-2026-11-22.md). No se modifican los clientes publicados, Juegos ni la infraestructura de Ubuntu.

## Contrato y persistencia

El [esquema de referencias](../../infrastructure/local-fallback/api/src/contracts/message-shared-content.schema.json) añade `sharedContent` optativo a los POST existentes de chat, grupo y comunidad. En este bloque el único tipo admitido es `post`. Se requiere un ID estable de mensaje; se admite una nota de hasta 800 caracteres. La referencia se guarda como JSONB en el mismo mensaje y bajo las mismas reglas de conversación, membresía, canal y presupuesto. No se copian adjuntos ni metadatos del post. El inicializador de la API añade una columna nullable a `fallback_messages` y `fallback_room_messages`; los mensajes existentes no se reescriben ni necesitan migración de datos. No se añaden dependencias.

El historial, los recibos y los eventos conservan el tipo `shared_content` y la nota, pero no exponen la referencia guardada ni el resumen. El contenido de un aviso de ese tipo es neutro. La ruta autenticada `GET /v1/data/messages/{chats|groups|communities}/{scopeId}/messages/{messageId}/shared-content` vuelve a verificar el acceso al mensaje y al post vigente, incluyendo audiencia, ocultación, bloqueo y canal activo. Devuelve un título y un resumen acotado, no una URL de medios. Una publicación sensible no devuelve su texto en la tarjeta. El resultado no se almacena en caché HTTP. Si el mensaje o el post no son accesibles, se devuelve el mismo 404 sin vista previa.

El reintento idéntico en el destino autorizado devuelve el recibo anterior, incluso si el post se borró después de enviar. No crea otra fila ni otra notificación. Reusar el mismo ID para otra referencia o nota devuelve 409. El endpoint legado de escritura no admite este tipo reservado. La baja para todos quita la referencia de la fila; la baja para uno solo no la elimina para los demás. Los enlaces de texto antiguos no se reinterpretan.

## Comprobaciones

- Integración HTTP local: chat directo, grupo y comunidad; referencia persistida y nota opcional; lectura por destinatario; rechazo de tercero y de sesión ausente; repetición concurrente y conflicto por cambio de carga; bloqueo, cambio de audiencia, post eliminado, baja del mensaje y retirada de acceso a grupo/canal. Incluye compatibilidad con texto normal.
- Selección de regresión API con PGlite: **66 resultados aprobados**, cero fallos y omisiones. Incluye mensajería, presupuesto de comunidad, historial por membresía, reacciones, compatibilidad y avisos.
- Repetición de los dos resultados del archivo nuevo en PostgreSQL nativo **16.9** aislado: dos aprobados, sin fallos ni omisiones. Se ejecutaron rutas Express y consultas reales, con tablas temporales en una base local dedicada. La autenticación y la entrega al gateway se aislaron; Firebase no estuvo configurado. Se verificaron las dependencias de código fuente y la sintaxis de los módulos cambiados.
- Una tanda intermedia heredó la URL de un PostgreSQL de laboratorio ya apagado y falló con `ECONNREFUSED`; se repitió con la variable eliminada para usar PGlite. No fue un fallo de permisos ni de persistencia de la API.

## Límites, continuación y reversión

El siguiente bloque conecta `UniversalShareHost`, el diario y los renderizadores de chat Android al nuevo contrato y valida el recorrido con clientes. Después se incorporan los demás tipos, emisores y web, con autorización específica por fuente. No hay modal universal nuevo ni validación de entrega Android–web, invitaciones nuevas o Juegos en este commit. El despliegue de API, la prueba con mensajería en tiempo real y los dispositivos siguen pendientes.

Revertir el commit de API no requiere borrar las columnas: son aditivas. No habilitar envíos de clientes antes de disponer de la API compatible; revertir la versión del servidor que interpreta las referencias deja temporalmente inactiva su resolución, no convierte los mensajes en enlaces externos ni borra filas.