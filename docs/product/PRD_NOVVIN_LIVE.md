# Directos, anfitriones e invitados

**Recorrido de referencia.** El anfitrión crea una sesión en preparación y confirma el inicio. El servicio conserva la misma sesión cuando se reintenta con el mismo identificador. El invitado recibe una invitación con vencimiento, decide y recibe los permisos de medios correspondientes. Salir de invitado conserva el directo del anfitrión.

| Acción | Responsable | Comprobación |
| --- | --- | --- |
| Iniciar y terminar | Anfitrión | Estado persistido visible para el espectador durante el directo. |
| Invitar y responder | Anfitrión e invitado | Repetir la misma petición devuelve la misma invitación. Una aceptación previa no produce una segunda incorporación. |
| Comentar | Participante autorizado | Un mismo identificador conserva un solo comentario. |
| Silenciar y retirar | Anfitrión o moderador según la operación | Validar tanto permiso persistido como retirada de pista en una SFU de staging. |
| Recuperar sesión | Cliente y servidor | Comprobar segundo plano, pérdida de red y retorno antes y después de la expiración. |
| Programación, encuestas y preguntas LIVE | Producto y QA | Verificar por plataforma con dos cuentas antes de ofrecerlas como disponibles. |

Los ensayos de API, navegador y medios físicos tienen registros separados en la [línea base](../quality/BASELINE_TESTS.md).
