# 132 · Visitas de cuenta, juego y canjes de puntos LIVE

13 de noviembre de 2026, America/Bogota. Continuación del bloque móvil 128, que permanecía local durante los ajustes web 129–131. La revisión integral sigue abierta. Este documento separa las regresiones reproducidas, las pruebas posteriores, los ensayos de emulador y los despliegues; no representa una APK final de producción.

## Defectos reproducidos y correcciones

Las catorce regresiones iniciales fallaron antes de corregir el código: nueve en puntos y cinco en el juego. Había lecturas que podían usar el token devuelto después de A → B → A, respuestas que sobrescribían el saldo o la palabra privada de otra visita, doble envío de canjes, bloqueo de carga tras cancelación, pérdida del historial ante error y un acuse de dibujo que añadía un trazo a la ronda siguiente. La evidencia anterior conserva las aserciones y las huellas de sus fuentes; no se deduce el fallo de una inspección o de un inventario.

Puntos y juego capturan la visita de cuenta antes de encolar el trabajo, verifican las credenciales antes del envío y rechazan su resultado si cambia la visita. La guardia de autenticación publica su generación después de un cambio real de sesión, incluso si la mutación se interrumpe. Un refresco ordinario del token conserva la misma visita. El estado compartido de puntos y juego se limpia al invalidarse su propietario.

El controlador de puntos agrupa lecturas pendientes, evita acciones simultáneas y conserva el último historial confirmado cuando falla una actualización. Los trabajos quedan ligados a su panel, con limpieza de carga en `finally`. Pausar o cambiar de cuenta invalida las respuestas anteriores.

El juego separa bloqueos, suscripción, consulta inicial y sondeo por visita. Registra el consumidor de eventos antes del primer estado REST y agrupa sus avisos de actualización sin bloquear la recepción de trazos. El ViewModel serializa su limpieza con las publicaciones y cancela los hijos de esa visita. El diálogo libera su identificador concreto al pausar o salir; una limpieza antigua no cierra un panel reanudado o el de otro ViewModel. La palabra privada se filtra por cuenta antes de presentarla y un acuse de dibujo comprueba juego y ronda. Los mensajes de error existentes se conservan.

## Canjes recuperables y propiedad en la API

El identificador de un canje se guarda antes de enviar la petición, por cuenta, directo y recompensa, en un diario `AtomicFile` bajo `noBackupFilesDir`. No contiene contraseñas, tokens ni imágenes. Ante confirmación perdida, cancelación o salida de la cuenta se conserva para el reintento, incluida la reconstrucción del repositorio. Una escritura fallida impide enviar; un archivo ilegible no se reemplaza silenciosamente por un identificador nuevo. Una confirmación antigua no borra una operación nueva.

La comprobación HTTP/PostgreSQL reprodujo tres fallos adicionales del servidor: un identificador de otra persona devolvía su canje; el mismo identificador aceptaba otra recompensa; y cinco reintentos concurrentes devolvían 201/409 en vez de recuperar la misma operación. La corrección serializa por directo e identificador con un bloqueo transaccional de PostgreSQL, comprueba usuario y recompensa, y admite los alias de identidad ya verificados por el servidor. Repetir la operación devuelve el mismo recibo sin volver a descontar saldo ni stock. Un conflicto responde sin incluir el canje ajeno. No se modifica el esquema ni se trata a los puntos como un medio de pago.

## Evidencia de validación

Las pruebas focalizadas posteriores aprobaron 55 controles de juego, puntos, modelos, mensajes y guardia de sesión; después del diario durable, 57 en cinco clases, incluidos ocho del diario. Son tandas solapadas. Se comprueban almacenamiento real de `AtomicFile` con Robolectric, recreación del repositorio, cambio de cuenta, pérdida de acuse, concurrencia de instancias, corrupción y escritura interrumpida. Eso no equivale a matar y restaurar un proceso Android físico.

La suite general de API aprobó 1106 pruebas y omitió 25 por dependencias de entorno. La nueva prueba PostgreSQL está omitida en esa ejecución general y se ejecutó explícitamente aparte: sus cuatro escenarios más el grupo y el control anterior de rutas aprobaron seis resultados del runner, sin fallos. El escenario concurrente espera que cinco conexiones estén bloqueadas simultáneamente antes de liberar el recurso; comprueba un canje, saldo 90 desde 100 y stock 9 desde 10. Usa una autenticación sintética de prueba: valida HTTP, rutas, transacciones y bloqueos, no el acceso productivo.

`service-ci.yml` incorpora un job de PostgreSQL 16 para esta regresión. Su preparación independiente, limitada a los campos del directo que consumen estas rutas, se ejecutó localmente sobre una base vacía y aprobó los cuatro escenarios y su grupo. La ejecución alojada del workflow es un resultado distinto y todavía no se afirma aprobada.

La batería JVM general aprobó **2817 pruebas en 528 clases**, sin fallos, errores ni omisiones. Compilaron el APK debug y el instrumental. En el emulador Android 15 / API 35 / x86_64 pasaron tres recorridos de Compose en 19,764 segundos: cerrar durante la consulta inicial, pausar/reanudar y cambiar de cuenta con la palabra privada visible. El [registro instrumental](../quality/evidence/132-emulador.txt) identifica sus nombres. La API y el socket de ese ensayo son fixtures: no certifica una sesión de red productiva ni un teléfono físico.

El paquete de laboratorio es **9.1.40-debug / 180**, SHA-256 `392bfe4a3404f159312bfeab584de720d790ffaad9ffb29a976df0cf37775798`, con firma debug verificada. Se compiló desde la base `b58f05e8` con las fuentes candidatas identificadas por blob en la evidencia; no es una nueva entrega de producción ni el AAB histórico firmado. Lint K1 finalizó con cero errores y 342 avisos. La [evidencia estructurada](../quality/evidence/132-resultados.json) separa todos los resultados y huellas. El primer intento de Lint con 1,5 GiB se interrumpió tras detectar agotamiento de heap; se conservó su diagnóstico y se completó con los 4 GiB previstos por el proyecto, sin modificar su configuración versionada. Los nueve avisos situados en archivos de producción modificados son sugerencias KTX sobre instrucciones que ya existían en la base; no se declara auditado ni resuelto el conjunto de los 342 avisos.

## Límites y continuidad

No se desplegó la API propia de Ubuntu, no se verificó una llamada o un LIVE entre redes físicas y no se generó una APK final identificada para entrega. El diario se prueba ante interrupciones simuladas y reconstrucción de instancias; falta la muerte real del proceso y la comprobación con el servicio productivo. El ciclo completo de propiedad de la sesión LIVE, los formularios de sus herramientas, las invitaciones, los medios y el resto de los módulos conservan su revisión pendiente. Los criterios de regalos, pagos y Gift-to-Game no se marcan cumplidos por estas correcciones de puntos del canal.

La API corregida debe preceder a los clientes que dependan de recuperar un canje simultáneo. No hay DDL nuevo. Deben conservarse revisión e imagen previas, respaldo y comprobaciones de salud antes de desplegar. El código terminado se publica en `main` y `feature/live-gifts` con árboles iguales, preservando los cambios ajenos y sin incorporar el plan experimental 105.

También se corrigen cuatro campos de texto con codificación dañada en la evidencia 130–131; sus resultados numéricos y sus huellas de implementación se conservan.

La herramienta de cobertura admite `--from-index` para registrar los blobs de la candidata sin mezclar las modificaciones ajenas que siguen en el directorio de trabajo. Cuatro pruebas verifican la separación entre índice y trabajo, archivos nuevos y rechazo de lecturas no incorporadas; junto a la trazabilidad aprobaron doce pruebas. Este modo no declara que un archivo esté leído por haberlo inventariado. La matriz conserva 368 entradas y el requisito de continuar la revisión móvil sigue `EN_CURSO`.
