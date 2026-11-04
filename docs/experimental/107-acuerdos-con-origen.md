# Acuerdos con origen. Primer recorrido experimental

Trabajo del 4 de noviembre de 2026, exclusivamente en `codex/experiencias-con-proposito`. Continúa la segunda prioridad del [plan 105](105-experiencias-con-proposito.md). La entrega estable conserva su estado en la [revisión 104](../oficial/104-camara-y-reacciones-android.md). La revisión integral sigue abierta.

## Alcance del primer recorrido

Dos personas de un chat privado pueden proponer un acuerdo desde un mensaje visible, consultar lo propuesto, confirmar, pedir cambios, retirar su confirmación y revisar el texto. La persona que propuso puede cancelar. Proponer conserva la autoría y deja las dos confirmaciones pendientes. Modificar el texto inicia otra revisión y reinicia ambas confirmaciones. La pantalla muestra quién respondió y permite volver al mensaje de origen cuando siga disponible.

El servidor determina los dos participantes a partir de la conversación y sus identidades canónicas. Exige acceso vigente, ausencia de bloqueos y una solicitud de mensajes ya aceptada. Los chats de grupo, comunidades, invitaciones adicionales, IA, notificaciones push nuevas y el cliente web quedan para ampliaciones posteriores. Esta selección permite comprobar el consentimiento y la concurrencia antes de extenderlos a más participantes.

La API se activa explícitamente con `NOVVIN_EXPERIMENTAL_AGREEMENTS=true`. El valor predeterminado mantiene el experimento desactivado. El móvil consulta esa capacidad y oculta las entradas cuando el servidor no la ofrece. La implementación de esta rama no implica activar el experimento en el servidor de producción.

## Diseño antes de implementar

La pantalla se organiza alrededor del texto acordado y las respuestas de las dos personas. Se conserva la tipografía de la cuenta, con título y lectura alineados a la izquierda y ancho máximo para pantallas grandes. Índigo `#535BC1` identifica acciones, menta `#B7E5DF` las confirmaciones, melocotón `#F8D1BB` las solicitudes de cambio y tinta `#263149` los textos sobre esos fondos. Fondo y superficies proceden de MaterialTheme. Las respuestas llevan texto además de color.

```text
Volver              Acuerdos             Actualizar

Guitarra el sábado
La propuesta aparece aquí con espacio para leerla.

[ Ana ]  Por confirmar
[ Tú  ]  Confirmaste esta revisión

[ Ver mensaje de origen ]
[ Confirmar ]       [ Pedir cambios ]

Revisión 2          Propuesto por Ana
[ Editar propuesta ]             [ Cancelar acuerdo ]
```

Crear o editar abre un editor breve. La lista no mantiene formularios abiertos. Los botones se ajustan al espacio y al tamaño de letra. Errores y envíos pendientes conservan el texto y ofrecen una acción concreta. La inspección del plan elimina indicadores decorativos y porcentajes de aprobación que podrían hacer parecer obligatorio el consentimiento. Se muestran las respuestas reales de ambas personas.

Se toman como referencias de organización los [ejemplos oficiales Compose, Reply](https://github.com/android/compose-samples/tree/main/Reply) y la [guía Android de tamaños adaptables](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes). La serialización de escrituras utiliza transacciones y bloqueos según la [documentación de PostgreSQL](https://www.postgresql.org/docs/current/explicit-locking.html). El módulo se escribe sobre los contratos existentes del proyecto.

## Contrato y aceptación

- Cada escritura incluye identificador de operación y revisión esperada. El servidor liga la clave al actor y al contenido. Repetir una operación confirmada devuelve el estado vigente sin aplicar dos veces su efecto. Reutilizar la clave con otro contenido se rechaza.
- Dos escrituras sobre la misma revisión no se pisan. Una gana y la otra recibe un conflicto para actualizar. Una respuesta pendiente no se atribuye a otra persona y una aceptación anterior no confirma un texto editado.
- Se comprueban nuevamente cuenta, conversación, participantes y permisos al leer y escribir. Cambiar de cuenta o de chat invalida las respuestas móviles anteriores.
- El origen se consulta bajo los permisos del lector. El acuerdo no guarda una copia automática del mensaje original. Los mensajes borrados o de una sola vista no sirven como origen de propuestas nuevas.
- El resultado aparece confirmado después del acuse del servidor. Una pérdida de respuesta mantiene el identificador de operación para reintentar. El borrador permanece ante fallo.
- Los eventos de tiempo real contienen referencias. El receptor vuelve a leer el estado bajo autorización. La pantalla también permite actualizar y relee al volver a primer plano.

Las pruebas se registrarán con sus fuentes, distinguiendo dominio, HTTP, PostgreSQL, interfaz y ejecución efectiva. El cifrado local del espacio privado no se presenta como prueba del cifrado de extremo a extremo de este nuevo contrato compartido.
