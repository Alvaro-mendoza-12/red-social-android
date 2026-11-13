# 129 · Menú lateral e invitaciones durante llamadas web

13 de noviembre de 2026, America/Bogota. Corrección del recorrido web solicitado por el propietario. Continúa abierta la revisión integral de Android, web y ecosistema; no es una APK nueva ni una certificación para marketing masivo.

## Resultado

El menú lateral de escritorio se contrae a 76 px, conserva los iconos, sus nombres accesibles, indicadores y títulos, y recuerda la preferencia del navegador. El botón permite expandirlo de nuevo. Nowy y Llamadas también tienen acceso directo. En ventanas bajas conserva desplazamiento vertical para llegar a todos los accesos. La navegación móvil continúa en su barra inferior.

Mensajes, Grupos, Comunidades y Voz usan columnas proporcionadas y un mínimo de 44 px de altura de pulsación. Se conserva el desplazamiento independiente del historial y de las conversaciones. El ajuste no aplica una eliminación general de scroll.

El anfitrión de una llamada conectada puede abrir **Invitar**, buscar un contacto y enviar la invitación. El selector excluye a quienes ya están conectados, evita envíos simultáneos, conserva el contacto si falla la confirmación y muestra el vencimiento de 45 segundos con opción de reinvitar. No necesita cortar ni reiniciar la captura ni la señalización de quienes ya estaban en la llamada.

Cada participante remoto conserva su propio medio y control de estado. La interfaz muestra la llamada grupal, mantiene los demás medios al salir una persona y conserva los medios recibidos al renegociar la cámara. Rechazar una invitación adicional no termina la llamada. Colgar como anfitrión termina la sesión para todos.

## Permisos y compatibilidad

`POST /v1/data/calls/:callId/invite` exige una llamada activa y su anfitrión aceptado. Canoniza identidades, verifica bloqueos con los participantes, valida pertenencia en grupos/comunidades y serializa el cupo de ocho personas mediante el bloqueo transaccional de la sesión. Los reintentos reutilizan la autorización existente; las invitaciones vencidas pueden renovarse y no consumen cupo pendiente. La aceptación sigue verificando el cupo, la vigencia y los bloqueos actuales.

Ampliar una llamada privada concede acceso únicamente a esa llamada. La persona invitada no se añade al chat original ni recibe su identificador en la alerta canónica. Un bloqueo introducido tras invitar impide aceptar, sin expulsar a quienes ya estaban conectados por culpa de una invitación pendiente.

La inicialización añade `fallback_call_sessions.expanded` con valor predeterminado falso mediante DDL aditivo. Las llamadas anteriores conservan su comportamiento. Una reversión de código conserva la columna y los datos; no requiere borrarlos. Solo se aplicó en PostgreSQL del laboratorio. El despliegue de API debe preceder al uso productivo de la nueva invitación. Los contratos anteriores de Android no se eliminan; la interoperabilidad grupal Android–web todavía requiere ensayo propio.

## Evidencia local

La [evidencia estructurada](../quality/evidence/129-resultados.json) identifica fuentes, pruebas, huellas y límites. Las capturas utilizan exclusivamente cuentas sintéticas.

- TypeScript/Vite y generación de 21 páginas públicas aprobados. Lint de los cinco archivos de producción modificados, sin errores ni avisos.
- Suite web general: 1 201 pruebas en 188 suites aprobadas. Después se añaden cuatro pruebas del selector y una de conservación de medios; la suite final de llamadas ejecuta veinte pruebas en cuatro suites, todas aprobadas. Son ejecuciones solapadas, no cifras para sumar como recorridos distintos.
- Suite general de API: 1 090 pruebas aprobadas y 24 omitidas por dependencias de entorno, sin fallos. Se corrigió una fixture anterior de privacidad que no contemplaba la consulta de bloqueos vigente; no se alteró esa política de producción. Las comprobaciones focalizadas posteriores cubren el código final y 67 aserciones internas de acceso a llamadas.
- Nueve controles HTTP/PostgreSQL con Node 22.23.4: permisos, bloqueo bilateral, cinco invitaciones concurrentes sin duplicado, privacidad del chat, vencimiento/reinvitación, bloqueo posterior, salida, rechazo y cierre por anfitrión.
- Navegador Chrome con tres contextos independientes, API/gateway/señalización y PostgreSQL reales en loopback. Pistas sintéticas de micrófono/cámara. Se comprueban 1440, 1355, 1153, 768 y 375 px, persistencia del menú, desplazamiento, invitación, recepción de medios, renegociación de cámara y cierre. El informe JSON conserva la lista exacta de controles aprobados.

[Menú plegado](../quality/evidence/129-menu-plegado.png), [llamada de tres personas](../quality/evidence/129-llamada-grupal.png) y [llamada en ancho móvil web](../quality/evidence/129-llamada-movil-web.png).

## Continuidad

El propietario priorizó este ajuste web durante las pruebas de cambio de cuenta de LIVE. El bloque 127 ya estaba publicado. Las modificaciones y pruebas móviles 128 permanecen locales y sin validar; no forman parte del commit de este bloque ni de una APK entregada.

CALL-02 queda validado parcialmente en web local. Faltan Android–web, dispositivos físicos, redes distintas, selección real de TURN, capacidad concurrente y despliegue efectivo. No se declara resuelto el paquete completo de 359 entradas. La [cobertura por archivo](review-coverage.json) separa lectura completa, parcial y pendiente. Se mantienen intactos los cambios de otros colaboradores, `correciones` y la rama experimental.
