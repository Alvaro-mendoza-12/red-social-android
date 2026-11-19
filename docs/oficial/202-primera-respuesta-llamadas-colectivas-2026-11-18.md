# 202 · Primera respuesta en llamadas de grupo y comunidad

18 de noviembre de 2026.

Cuando alguien aceptaba una llamada colectiva saliente en Android, el anfitrión enviaba `cancel` a los demás invitados. El contrato de llamadas de Ubuntu interpreta la orden autenticada `cancel` del anfitrión como cierre de la sesión, por lo que también denegaba el acceso a quien acababa de aceptar. Además, al conectar los medios se descartaban los invitados que seguían esperando y podían quedar fuera de los avisos al colgar.

La primera respuesta detiene el tono del anfitrión e inicia la espera de conexión. Los demás invitados conservan su entrada y siguen incluidos en los destinatarios del cierre. Se mantienen la validación y los permisos de acceso existentes en el servidor.

## Evidencia

- Dos fallos en tres casos sobre la referencia con 200. La candidata final pasó 44 pruebas JVM en nueve clases. La compilación y la selección Gradle Audit de este estado integrado siguen pendientes.
- El contrato real del servidor se ejecutó con una base sintética: se comprobó en grupos y comunidades que `cancel` del anfitrión cierra la sesión y que, sin esa orden, los dos invitados pueden aceptar. El cierre explícito sigue funcionando. Pasaron además 67 casos de la batería existente de acceso a llamadas.
- Recibos en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit202-group-call/`.

Quedan pendientes las llamadas de más de dos participantes con dispositivos físicos, la entrega simultánea de avisos, el cambio de red y la comprobación de sesiones con cámaras y micrófonos reales.
