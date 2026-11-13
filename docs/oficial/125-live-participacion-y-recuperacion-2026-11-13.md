# LIVE: participación, permisos y recuperación

13 de noviembre de 2026. Continuación local de la revisión 124. La revisión integral permanece abierta y este bloque no se desplegó en producción.

Puntos de recuperación de código: `09aa3e76` en `main` y `87297059` en `feature/live-gifts`. Ambos contienen el árbol `46a3eef5bb76a8e74c56402a28bf9a9ae457f7bf`. La documentación posterior añade las evidencias sin modificar ese código. Los cuatro cambios preexistentes de SEO/Vercel en el worktree de `feature/live-gifts` se conservaron fuera de estos commits.

## Cambios implementados

- Encuestas propias del directo, con dos a seis respuestas, duración, cierre, resultado y selección de la cuenta. Una persona conserva un voto aunque reintente o cambie su elección. Solo el anfitrión o un moderador puede publicar o cerrar encuestas.
- Preguntas de la audiencia, apoyo gratuito, respuesta del anfitrión, destacado y retirada. Las preguntas conservan el borrador cuando falla el envío y se vacían tras confirmación. Los permisos de bloqueo, privacidad y moderación se comprueban en la API.
- Acceso desde las herramientas LIVE de Android, incluidas las pantallas activas y las antiguas compatibles, y desde la web. La API anuncia la capacidad `audienceToolsEnabled`. Si un servidor anterior no la anuncia, los clientes conservan el resto de controles sin mostrar esta entrada.
- El canal `live-audience:<id>` transmite una invalidación. Las consultas autenticadas recuperan el contenido autorizado. El cliente agrupa actualizaciones y conserva una lectura periódica mientras el diálogo está en primer plano. Android cancela las tareas al suspenderse y usa la guardia de visita de la cuenta, también en A → B → A.
- Android esperaba únicamente la apertura TCP y enviaba todas las suscripciones juntas. El gateway admite cuatro autorizaciones simultáneas. El cliente ahora espera `connected`, limita a tres las peticiones pendientes y libera cada plaza tras su respuesta. Conserva los canales deseados al renovar el token y descarta respuestas de conexiones anteriores. Desuscribir una autorización pendiente no libera prematuramente la plaza ni deja una incorporación tardía activa.
- Un invitado aceptado de un LIVE privado recibía 404 al abrirlo. Ahora conserva acceso mientras la invitación siga aceptada y lo pierde al salir. Los bloqueos y expulsiones siguen teniendo prioridad.
- La web confirma el inicio antes de mostrar que el anfitrión está emitiendo. Al terminar apaga primero cámara y micrófono y espera la confirmación del servidor. Si falla, muestra cierre pendiente y permite reintentar. También propaga el fallo de salida del espectador y evita reactivar una captura durante el cierre.
- Se actualizaron resoluciones transitivas de la API dentro de los rangos del proyecto. El análisis de dependencias de ejecución informa cero avisos en API, gateway, señalización, chatbot y web. Esto no constituye una auditoría integral de vulnerabilidades.

La encuesta abierta conserva prioridad en la consulta aunque se publiquen más de cien preguntas. Se muestran hasta ochenta participaciones recientes y destacadas. La paginación del historial anterior sigue pendiente y se comunica en el diálogo.

## Evidencia separada por entorno

| Control | Resultado y alcance |
| --- | --- |
| Android JVM | 2 777 pruebas en 524 suites, sin fallos, errores ni omisiones. Incluye los cinco casos de suscripción reproducidos primero como fallos, reintentos con el mismo nonce y cuatro casos del estado de participación por visita de cuenta. |
| Android compilado | `assembleDebug`, `assembleDebugAndroidTest` y `testDebugUnitTest` aprobados con JDK 21. Paquete de pruebas 9.1.40-debug / 180. Su huella está en la evidencia. No es una nueva entrega firmada para Play. |
| Emulador API 35 | Doce controles de componente, importación de GIF/WebP originales, entrega y gestos. Incluye publicar las opciones escritas y conservar el borrador fallido. No sustituye un LIVE completo Android–web con medios físicos. |
| API y PostgreSQL | Veinte pruebas del ejecutor Node, con cero fallos y omisiones, sobre votos concurrentes, moderación, reintentos, vencimiento, permisos, temporizador, contabilidad de prueba y adaptador/diario R2. Varias suites ejecutan aserciones internas adicionales. |
| Dos navegadores | Siete recorridos aprobados contra la API, gateway, señalización y PostgreSQL reales del laboratorio. Inicio fallido con reintento, encuesta recibida y votada, pregunta respondida y destacada, retirada, cierre, recarga, salida fallida del espectador y cierre fallido del anfitrión. Las pistas locales quedan apagadas durante el cierre pendiente. |
| Privacidad | Cuenta invitada entra al LIVE privado tras aceptar y recibe 404 después de salir. Una tercera cuenta nunca accede. Los ensayos de política conservan bloqueos y expulsiones. |
| Runtime de servicios | Node 22.23.4, correspondiente al runtime fijado por el Dockerfile, PostgreSQL 16, escucha solo en loopback y cuentas sintéticas. El proxy HTTPS de laboratorio y las herramientas web son procesos locales independientes. |
| Web | 1 196 pruebas en 187 suites, construcción de 21 páginas públicas y lint sin errores, con siete advertencias existentes. El navegador también comprobó el diálogo a 390 px sin desbordamiento horizontal. |

Los resultados y las huellas se conservan en [125-resultados.json](../quality/evidence/125-resultados.json). [Encuesta](../quality/evidence/125-encuesta-web.png), [pregunta y respuesta](../quality/evidence/125-pregunta-web.png) y [ancho móvil web](../quality/evidence/125-pregunta-movil-web.png) muestran únicamente cuentas sintéticas.

## Dependencias y recuperación

El cambio de la API resuelve el aviso de `fast-xml-parser` y evita la cadena vulnerable de `glob`/`minimatch` sin introducir una actualización mayor de las dependencias directas. Se verificaron los avisos primarios [GHSA-474x-hrwr-fgx4](https://github.com/advisories/GHSA-474x-hrwr-fgx4), [GHSA-3g3m-2fjr-6pw4](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4) y [GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249). No se afirma que todas las dependencias sean sus versiones más nuevas.

La inicialización añade dos tablas de participación e índices mediante DDL aditivo. Solo se ejecutó en PostgreSQL de laboratorio. Un despliegue autorizado debe verificar permisos y respaldo antes de arrancar la nueva API. Volver al código anterior conserva esas tablas y sus datos. No requiere borrarlas. Los clientes antiguos siguen usando sus contratos habituales.

El ensayo PostgreSQL del diario R2 ahora crea un esquema propio por ejecución. Repetirlo ya no colisiona con sus tablas anteriores ni obliga a borrar evidencia. Esto cambia el aislamiento del ensayo, no las tablas productivas del almacenamiento.

## Pendientes que impiden el cierre integral

Validar el recorrido autenticado de la nueva participación entre Android y web, SFU/TURN y revocación de medios en redes distintas, R2 externo, avisos con la aplicación cerrada, proveedor económico y restauración operativa. Continuar los módulos y archivos abiertos de la [cobertura](review-coverage.json) y los requisitos de la [trazabilidad](../product/REQUIREMENT_TRACEABILITY.csv). Los siete documentos de entrada se conservaron íntegros en ambas ramas estables. No se modificaron `correciones` ni la rama experimental.
