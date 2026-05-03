# Auditoria FODA 2026-04-19

## Fortalezas

- La aplicacion ya tiene una arquitectura por capas razonable: repositorios, ViewModels, Compose, observabilidad y servicios separados para auth, mensajes, llamadas y notificaciones.
- Existe infraestructura real de estabilidad: `RuntimeStabilityMonitor`, `CrashReporter`, `AppFeedbackHost`, `RepositoryCallHandler` y telemetria.
- El producto ya cubre un rango amplio de funciones sociales: mensajeria directa, grupos, comunidades, llamadas, directos, retos, feed, moments y moderacion.
- Hay varios puntos defensivos contra regresiones: pruebas unitarias e instrumentadas, limites de rendimiento y auditorias SQL.

## Debilidades

- El manejo de errores aun es inconsistente entre capas: algunas pantallas muestran mensajes amigables y otras exponen texto crudo del backend o del SDK.
- Hay demasiada logica critica repetida alrededor de auth, sesiones y notificaciones, lo que aumenta riesgo de comportamientos distintos ante el mismo fallo.
- El sistema de llamadas dependia demasiado del orden de llegada de eventos y push, lo que favorecia invitaciones fantasma en redes lentas.
- La experiencia de formularios en pantallas de entrada no estaba protegida de forma uniforme frente al teclado en dispositivos pequenos.

## Oportunidades

- Unificar todos los errores de producto bajo una sola capa de mensajes amigables y trazabilidad interna.
- Convertir el cambio rapido de cuentas en una funcion robusta de nivel comercial con refresco de sesion, validacion gradual y recuperacion segura.
- Formalizar contratos de notificacion por tipo para que llamadas, mensajes, moderacion y social tengan textos, destinos y acciones coherentes.
- Llevar la observabilidad actual a un tablero operativo con alertas por session expiry, errores de push, demoras de llamada y restricciones de Supabase.

## Amenazas

- La app depende de varios componentes distribuidos y sensibles a red: Supabase Auth, Realtime, FCM, WebRTC y el signaling server. Si fallan de forma parcial, aparecen estados inconsistentes.
- El crecimiento funcional rapido aumenta deuda operativa: mas features significan mas rutas, mas payloads, mas edge cases y mas necesidad de contratos estables.
- COBOL no resolveria estos riesgos en esta base. El problema aqui no es el lenguaje sino la consistencia de arquitectura, seguridad operacional, validacion de estados y observabilidad.
- Los limites de cuota, storage y egress en Supabase siguen siendo un riesgo real de continuidad si no se monitorean como parte de la operacion.

## Mejoras aplicadas en esta pasada

- Endurecimiento del flujo de cuentas guardadas con validacion de sesion mas tolerante a latencia y con refuerzo posterior de token.
- Unificacion parcial de mensajes de error para auth, ajustes y notificaciones.
- Mejora del lenguaje de notificaciones de llamada y su navegacion al historial.
- Supresion de invitaciones de llamada fantasma cuando llega un `call_cancel` antes que el `call_invite`.
- Ajuste de formularios de login y registro para convivir mejor con el teclado.

## Siguiente fase recomendada

- Centralizar todos los errores de repositorio y UI en un mismo mapper con codigos internos y mensajes localizados.
- Crear pruebas dedicadas para auth switch, push tardio de llamadas y navegacion desde notificaciones.
- Instrumentar metricas de tiempo real para login, switch account, carga de feed, carga de chats y establecimiento de llamadas.
- Revisar seguridad de extremo a extremo: secretos, backups, retention, rotacion de llaves, politicas RLS y hardening del signaling server.
