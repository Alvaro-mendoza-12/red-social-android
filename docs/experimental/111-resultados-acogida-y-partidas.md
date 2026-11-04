# Resultados, primera contribución y partidas acordadas

Plan de continuación del 4 de noviembre de 2026 para `codex/experiencias-con-proposito`. El propietario autorizó completar los modos de 105 y volver a comprobar su servidor. Las correcciones de Social y avatares se mantienen separadas en las ramas estables. Este documento no declara implementados ni desplegados los recorridos que siguen.

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
