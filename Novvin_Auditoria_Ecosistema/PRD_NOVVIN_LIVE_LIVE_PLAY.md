# PRD — NOVVIN LIVE + LIVE PLAY + REGALOS INTERACTIVOS

## Objetivo
Crear directos donde anfitriones, invitados y audiencia puedan conversar, jugar, competir amistosamente y colaborar. Android y web deben compartir estados coherentes.

## Roles
Host, co-host, invitado, moderador, viewer, servicio de juego y servicios de plataforma.

## Flujo de directo
Preparar título/categoría/privacidad, probar red/cámara/micrófono, configurar moderadores/chat/regalos/juego, previsualizar, iniciar y comprobar estado activo desde backend.

## Invitados
Host busca usuarios; backend valida bloqueos/roles/eligibilidad; se envía invitación con expiración; invitado acepta; verifica permisos; entra a sala de espera si aplica; host aprueba; se conecta media; layout se actualiza; host puede silenciar/retirar/reportar; invitado puede salir sin terminar el directo. Reintentos no deben duplicar participantes.

## Participación
Chat, reacciones, preguntas, encuestas, mensajes fijados, metas colectivas, puntos gratuitos, co-host, batallas y minijuegos. Debe existir moderación, filtros, slow mode y controles de accesibilidad.

## Live Play
Prototipos iniciales sugeridos: trivia, puzzle cooperativo y mascota/carrera comunitaria. Evaluar después defensa de objetivo, retos por equipos y sandbox. No desarrollar decenas de juegos a la vez.

Los regalos pueden activar efectos visuales, objetos, escudos temporales en sandbox, cambios de escenario, eventos ambientales o progreso de metas. No habilitar ventajas pagadas ocultas en modos competitivos clasificados.

## Event Bridge
Catálogo allowlist; validación server-side; evento firmado; idempotency key; secuencia; sesión; actor autorizado; esquema versionado; rate limit; expiración; auditoría; retries; dead-letter queue; métricas. El cliente nunca modifica saldo ni envía comandos arbitrarios.

## Regalos
Catálogo, preview, coste, confirmación, saldo, ledger inmutable, operaciones atómicas, no float, idempotencia, recibo, historial, conciliación, fraude, reembolsos según política. Animaciones optimizadas, cola, agrupación, fallback estático y opción reducir movimiento.

## Seguridad
Bloqueos heredados, reportes, expulsión, mute, filtros, anti-bot, control de solicitudes, protección de menores, límites de gasto, consentimiento de cámara/micrófono y registro de acciones.

## Métricas
Tiempo de inicio, tasa de invitación aceptada, tiempo de conexión, latencia chat/evento, reconexión, frames perdidos, bitrate, concurrencia, retención, participación, errores por dispositivo y coste por hora.

## Aceptación
- Nadie entra sin autorización.
- Usuario bloqueado no puede reincorporarse.
- Invitación expira y no se duplica.
- Invitado entra/sale sin cerrar la emisión.
- Pérdida de red produce estado correcto y reconexión controlada.
- Regalo no debita dos veces.
- Evento de juego no se ejecuta dos veces.
- Cliente no puede falsificar regalos ni comandos.
- Host puede cerrar sesión y revocar participantes.
- Animación no bloquea controles ni consume recursos excesivos.
- Todos los flujos críticos se prueban en Android y web.
