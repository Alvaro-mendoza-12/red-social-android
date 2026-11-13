# Límites de confianza y controles

| Activo | Condición a proteger | Defensa y evidencia | Pendiente |
| --- | --- | --- | --- |
| Lista de seguidores | Solo recibe los perfiles que su relación y privacidad permiten | Bloqueo en ambos sentidos, perfil privado y supresión de relaciones importadas revocadas. Se probaron cuentas sintéticas. | Revisar todas las superficies de recomendaciones y ranking. |
| Documentos de conversación | La cuenta que inició la descarga sigue activa | Origen confiable, token de la identidad actual, cancelación al cambiar de cuenta y límites de bytes. | Dispositivo móvil, archivos grandes y redes intermitentes. |
| Mensajes colectivos | Un evento no entrega contenido a toda una sala privada | El canal compartido invalida datos. La consulta aplica acceso de historial. | Revocación mientras el cliente se encuentra en segundo plano. |
| LIVE y juego | El rol y la sala determinan qué se publica y se recibe | Acceso al canal de juego, palabra reservada, invitación idempotente y transacción de cierre. | Dos redes, SFU real y expulsión en plena transmisión. |
| Saldos | Una operación definitiva produce un solo movimiento | Transacciones y pruebas de idempotencia con fixtures. | Ensayo de conciliación, reembolsos y restauración. |

Se usó revisión defensiva de código y ejercicios funcionales aislados. ASVS sirve como referencia para permisos, identidad y manejo de datos. [OWASP ASVS](https://owasp.org/www-project-application-security-verification-standard/)

El bloque 125 comprueba la participación de LIVE en cada lectura y escritura. Usa la identidad canónica para votos únicos, transacciones, nonce de creación, límite de solicitudes y roles de moderación. El WebSocket entrega una invalidación, sin respuestas o preguntas privadas. La aceptación concede acceso a invitados de directos privados mientras no haya salida, bloqueo o expulsión. Android descarta resultados de una visita anterior aunque se vuelva a la misma cuenta. [Evidencia y límites](../oficial/125-live-participacion-y-recuperacion-2026-11-13.md).
