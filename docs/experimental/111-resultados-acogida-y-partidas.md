# Resultados, primera contribución y partidas acordadas

Plan y comprobaciones del 4 de noviembre de 2026 para `codex/experiencias-con-proposito`. El propietario autorizó completar los modos de 105 y volver a comprobar su servidor. Las correcciones de Social y avatares se mantienen separadas en las ramas estables. Los resultados efectivos se distinguen del plan original a continuación.

## Contratos que se conservan

Social ya tiene publicaciones y comentarios; el resultado se vinculará a ellos, sin crear otra red de respuestas. La lectura comprobará el permiso de la publicación y la vigencia de las aportaciones. Sólo su autor podrá abrir el seguimiento, concluirlo, revisarlo o retirarlo. Cada cambio tendrá revisión esperada y clave de operación; repetir un envío no duplicará su efecto. La conclusión expresa la valoración de su autor.

Comunidades ya tiene preguntas, respuestas y eventos. La primera contribución será opcional y ofrecerá únicamente actividades reales que se puedan consultar. Las preguntas retiradas, eventos cancelados y permisos perdidos invalidarán el destino. Quien quiera acompañar deberá ofrecerse explícitamente con vencimiento y podrá retirar esa oferta. No se enviarán mensajes automáticos. Abrir un destino no se presentará como una contribución realizada.

PlayMatch ya tiene búsqueda, sesiones breves y conexiones aceptadas. La propuesta de partida empezará desde una conexión aceptada y conservará el acceso adulto y los bloqueos. El juego debe pertenecer a ambos perfiles. Objetivo, duración y fecha se acordarán por revisión; modificar esos datos reiniciará la aceptación y reconfirmación. Cada participante podrá rechazar, cancelar y registrar voluntariamente su propio resultado. El vencimiento será calculado con la hora del servidor, y el móvil mostrará la fecha en la zona de su dispositivo.

Las operaciones nuevas usarán tablas separadas con prefijo `experimental_`, acceso autenticado, límites de tamaño y tiempo, y una opción de activación de servidor. El control de eliminación de cuentas incluirá esos registros. No se copiarán mensajes privados a un procesador nuevo. No se promoverán las pantallas a `main` ni a `feature/live-gifts`.

## Diseño antes de implementar

Se conserva la tipografía configurada por la cuenta: `headlineSmall` para el resultado o la partida, `titleMedium` para decisiones y `bodyMedium` para su contexto. Paleta: índigo de acción `#535BC1`, menta de confirmación `#B7E5DF`, melocotón pendiente `#F8D1BB`, azul de origen `#D2DFFC` y tinta sobre acentos `#263149`. Las superficies y estados de error usan el tema actual. No se añaden fuentes remotas ni animaciones automáticas.

La composición queda alineada a la izquierda, con ancho máximo de 560 dp, desplazamiento y acciones que se distribuyen al aumentar la letra. El contenido real ocupa el primer lugar; la edición aparece después de elegir una acción. Se conservan los borradores durante un error. Los acuses pendientes tienen un reintento explícito con la misma operación.

```text
Social: resultado de esta publicación
Texto de la petición de origen
Conclusión del autor / Sigue abierta
Aportaciones que ayudaron, con acceso a su contexto
Fecha y revisión
[Añadir conclusión / Revisar]  [Retirar conclusión]

Comunidad: tu primera contribución
[Responder] [Participar] [Pedir orientación]
Pregunta abierta / próximo encuentro / guía disponible
Contenido real y su fecha o vencimiento
[Abrir pregunta / Ver encuentro / Ver guía]
[Puedo acompañar] [Retirar mi disponibilidad]

Gaming: la partida que acordamos
Juego y fecha local
Objetivo y duración
Personas: por confirmar / aceptó / reconfirmó
[Aceptar] [Rechazar] [Reconfirmar]
[Replanificar] [Cancelar]
Después: [Jugamos] [No se realizó] [Omitir]
```

La revisión del diseño descarta una pantalla inicial con tres formularios iguales. Cada entrada vive en el objeto que le corresponde: la publicación, la comunidad o la conexión de juego. Las propuestas parten de esos datos, y las acciones muestran su efecto concreto. No habrá estadísticas ficticias, clasificación punitiva ni marcas de solución universal.

## Comprobación prevista

Se separarán lógica de estados, HTTP/PostgreSQL, transporte Android, navegación y emulador. Se comprobarán cambios simultáneos, pérdida de acuse, cambio de cuenta, permisos revocados, origen eliminado, referencias bloqueadas, vencimiento y fechas. Un control de salud no sustituye el recorrido autenticado. La activación y migración efectivas se registrarán con imagen, fuentes y respaldo; la prueba de preferencia frente a otras aplicaciones seguirá requiriendo personas.

## Implementación y operación comprobadas

Los cuatro módulos nuevos de API implementan revisiones esperadas, recibos de operación, transacciones y limpieza al borrar cuentas. Android conserva borradores y envíos sin acuse en su almacén cifrado por cuenta; reintentar conserva la operación y abrir no envía. Si cambia el contenido durante la edición, se presenta la versión recibida antes de permitir reemplazarla. Cerrar la composición detiene sus lecturas.

| Modo | Entrada en el prototipo | Recorrido implementado |
| --- | --- | --- |
| Social | Menú de la publicación → «Cómo terminó esta petición» | Seguimiento y conclusión del autor; hasta cinco aportaciones reales con acceso a su comentario; revisión y retirada. |
| Comunidad | Información de la comunidad → menú → «Tu primera contribución» | Preguntas vigentes, encuentros futuros y guías con consentimiento temporal; apertura del destino existente y declaración voluntaria posterior. |
| PlayMatch | Perfil de una conexión aceptada → «Proponer partida»; inicio de Juegos → «Mis partidas acordadas» | Propuesta, dos aceptaciones, reconfirmación, replanificación con consentimientos reiniciados, cancelación y resultado privado. |

La invitación de partida se guarda en la transacción y se despacha después del commit; repetir la operación no duplica el aviso. Abrir un destino de comunidad no se presenta como participación realizada. No se envían mensajes automáticos a contactos.

Para probar una partida entre dos personas, ambas necesitan esta versión experimental. Las aplicaciones estables anteriores y la web todavía no contienen su pantalla para responder a una invitación. Las pruebas de persistencia y selección de ruta no certifican recepción física de FCM ni navegación desde esos clientes anteriores. La prueba con personas debe usar dos cuentas que aceptaron conectarse en PlayMatch y perfiles con el acceso adulto vigente.

La repetición aislada en Ubuntu, PostgreSQL 16.15 y el esquema real aprobó **11 entradas del ejecutor, sin fallos ni omisiones**: seis casos de dominio y cuatro recorridos dentro de una suite HTTP/SQL. Una selección de regresiones de API aprobó **45 entradas**, sin fallos ni omisiones. Algunas entradas agrupan aserciones; no se presentan como 56 recorridos de usuario distintos. Acuerdos se repitió por separado en Ubuntu con 22 pruebas aprobadas.

Antes del despliegue se detectó una consulta a `is_deleted` en una tabla que usa borrado físico. Se sustituyó por comprobación del registro real y, cuando corresponde, de importaciones. Otro ensayo utilizó un nombre de estado de fixture inválido; se corrigió el fixture. Se conservan las ejecuciones fallidas. Los adaptadores ficticios de identidad y notificaciones pertenecen exclusivamente a QA.

El backend se activó el **5 de noviembre de 2026 a las 03:35:43 UTC**: `novvin-api:purpose111-20261104_4c4350e0`, con trece fuentes desde `4c4350e0e371b02dfd211232133b35ba180febdf`, sobre la corrección estable 110. La [operación 112](../oficial/112-pruebas-android-studio-y-api-experimental.md) registra respaldo, huellas, esquema, salud y rechazo sin sesión. La proyección de la incidencia de Social conservó cuatro reacciones y dos comentarios. No se añadieron fixtures a producción.

Las pantallas permanecen sólo en esta rama. El propietario las evaluará antes de autorizar su promoción. Las comprobaciones Android de este bloque, la fluidez física y el inicio de sesión real se registran por separado; una prueba de API no los sustituye. El plan y la revisión integral permanecen abiertos.

Para evaluar la propuesta conviene completar una tarea en cada modo. En
**Videos → + → Rutas con propósito**, elegir un tema y ver si una ruta corta
ayuda a encontrar y conservar algo útil. En un **chat directo → Acuerdos**,
acordar una acción entre dos personas y volver al mensaje que la originó. En
**Social**, comprobar si se encuentra la conclusión y el comentario que ayudó.
En **Comunidades**, ver si la primera contribución resulta clara. En
**PlayMatch**, comprobar si dos contactos pasan de conectar a coordinar una
partida. El valor a validar es qué se puede resolver mejor en cada modo. La
comparación con otras aplicaciones depende de esos recorridos con personas.
