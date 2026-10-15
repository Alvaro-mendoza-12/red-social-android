# Backfill antivirus histórico: propuesta administrativa aislada

Estado: propuesta de código y pruebas, sin aplicar al producto y sin ejecutar sobre producción. No modifica Android ni crea un endpoint, tarea automática o excepción de lectura. Su aceptación no certifica toda la aplicación.

## Contrato

`storage-historical-av-backfill.js` ofrece tres operaciones administrativas:

1. `planHistoricalAvBackfill(pool, options)` inventaría únicamente filas `legacy_unverified`. Usa cursor UUID estable, máximo de objetos y presupuesto de bytes. Devuelve un plan privado y su SHA256. No escanea, copia, cambia filas ni sirve contenido.
2. `applyHistoricalAvBackfill(pool, plan, options)` exige el digest revisado, reserva disco antes de copiar y ejecuta los objetos en serie. Escanea una copia temporal privada mediante el coordinador existente, publica una revisión nueva y conserva el archivo histórico original.
3. `reconcileHistoricalAvBackfill(pool, options)` lee el diario privado. Por defecto no cambia nada. Solo con el digest del diario revisado puede liberar una reserva cuyo COMMIT esté confirmado por fila, diario SQL, revisión y SHA256 de los bytes. Los demás casos permanecen bloqueados para una decisión administrativa.

El plan y el diario contienen rutas, identificadores y metadatos privados. Se mantienen en el servidor protegido, fuera de registros públicos, reportes enviados a usuarios y repositorios. Los resultados resumidos identifican los objetos mediante SHA256 truncado a 24 caracteres.

## Límites medibles

| Control | Valor o condición |
| --- | --- |
| Plan por defecto | 20 objetos, 512 MiB de entrada |
| Máximo configurable por plan | 100 objetos, 4 GiB de entrada |
| Tamaño por archivo | 1 byte a 500 MiB, debe coincidir con el registro |
| Trabajo del lote | Un objeto a la vez; se detiene ante fallo o liberación no confirmada |
| Presupuesto de disco | Mismo `createStorageUploadAdmission`; factor de cuatro copias, cuotas globales/usuario y disco mínimo |
| Exclusión | Mismo bloqueo asesor `bucket/name` del coordinador; conflicto 409 |
| Publicación | Comparación completa de `to_jsonb(fila)`; debe modificar exactamente una fila |
| Prueba | Revisión UUID, SHA256, bytes exactos, motor, versión de firmas, fecha y digest de política |
| Recuperación | No vence automáticamente la reserva de un lote histórico; solo conciliación explícita |
| Diario | JSONL privado con creación exclusiva, permisos 0600 y `fsync` por evento |
| Barreras de disco | `fsync` de copia, revisión y directorios; el error impide COMMIT `clean` |
| Conciliación | Diario máximo 32 MiB; debe estar dentro de `.backfill-journal` y sin enlaces simbólicos |

Si el siguiente objeto supera el presupuesto de bytes del plan, el cursor no avanza sobre ese objeto. El operador debe aumentar el límite del siguiente lote hasta cubrirlo o registrar una excepción inaccesible; no saltarlo silenciosamente.

## Publicación y preservación

La copia inicial abre el origen sin seguir el enlace final cuando el sistema lo permite. Comprueba cada componente de la ruta, inode/dispositivo/tamaño/mtime/ctime antes y después de copiar, y vuelve a comprobar la identidad del archivo mientras se escanea. Rechaza rutas externas, directorios, enlaces simbólicos, tamaño cambiado y propietario desconocido.

Antes de escanear y antes del sello vuelve a ejecutar `validateUpload`, la política de cargas existente: bucket permitido, límite específico del bucket, magic bytes, compatibilidad de MIME declarado con bytes y tipo permitido. El MIME histórico es una declaración, no una prueba. Agrega una prohibición administrativa explícita de extensiones APK/AAB, ejecutables, scripts, HTML y SVG. Un HTML que afirma ser PNG, APK limpio para Clam o objeto de chat de 60 MiB + 1 byte no puede convertirse en `clean`. Los errores de esta política conservan la fila histórica como no verificada; el operador debe resolverlos como excepción inaccesible. No altera MIME, URL ni bytes para forzar su aceptación.

El coordinador escanea los bytes de la copia privada y vuelve a calcular su digest. Su `seal` inicia la transacción. El trabajador crea la revisión por enlace exclusivo dentro del mismo volumen y compara toda la fila anterior. Solo cambia `local_path`, los ocho campos de la prueba y `updated_at`. Conserva id, bucket, nombre, propietario, tipo, tamaño, URL, metadatos y referencias de mensajes/publicaciones. No llama a la carga normal que prohíbe reemplazar objetos publicados.

El coordinador borra su `sourcePath` después de confirmar. Por eso el trabajador le entrega `.tmp/admitted-ID-UUID`, nunca el archivo histórico original. No se borran originales, respaldos ni revisiones confirmadas de otros trabajos.

La copia privada se sincroniza con su directorio antes del escaneo. La creación del diario sincroniza el archivo y su directorio; cada evento posterior sincroniza contenido. La instalación sincroniza el inode de entrega y los directorios afectados antes de la comparación SQL y COMMIT. Los directorios existentes también se sincronizan, porque pueden proceder de un intento cuya barrera anterior falló. Un error de sincronización no se ignora y no confirma `clean`. El ejecutor requiere un sistema que permita sincronizar directorios; Node en Windows no ofrece esa garantía y no se considera una plataforma de ejecución del worker final.

El volumen debe permitir enlaces físicos entre `.quarantine` y `.objects`. Debe ser propiedad del servicio, sin escritura por usuarios ni otros procesos sin confianza. Node no ofrece una cadena portátil de `openat` para excluir un cambio malicioso de directorios por un administrador local; las comprobaciones de ruta no reemplazan los permisos del volumen. Hay pruebas de PostgreSQL/archivos en Windows y Linux en un volumen Docker de fixtures. Todavía debe comprobarse que los permisos y características del volumen desplegado sean equivalentes antes del primer lote real.

## Rechazo y fallo cerrado

Un rechazo conocido del escáner se guarda en `fallback_storage_scan_attempts` y cambia la fila a `rejected` mediante comparación de versión y el mismo bloqueo de objeto. Conserva el archivo original para resolución administrativa, sin publicarlo como verificado. Si la fila cambió mientras se intentaba guardar el rechazo, el resultado es fallo y queda señalada la falta de confirmación.

La inaccesibilidad depende de que las rutas de entrega tengan la exigencia de antivirus activada y de que Nginx no sirva el volumen directamente. Este módulo no activa esa configuración ni convierte su estado `rejected` en una promesa sobre un servidor con lectura histórica abierta. Un backfill de producción debe ejecutarse con entrega restringida o en una ventana de mantenimiento, tras ensayar restauración y compatibilidad. No reabrir `STORAGE_SCAN_ENFORCE=false` para resolver un outage.

Firma no disponible, escáner caído, prueba incorrecta, archivo cambiado, propietario desconocido, falta de presupuesto, pérdida de reserva o comparación fallida no marcan la fila como `clean`. El lote se detiene por defecto. Un objeto ya `clean` no se vuelve a escanear automáticamente.

## Diario y COMMIT incierto

El diario privado guarda el plan y su digest antes de reservar; luego reserva, prueba sellada y resultado. La reserva administrativa se cambia a `expires_at='infinity'` antes de copiar. Un reinicio o confirmación incierta conserva sus bytes prometidos, por lo que el reaper ordinario no libera cuota mientras una revisión desconocida siga en disco.

Si el COMMIT devuelve error, el trabajador intenta confirmar la revisión bajo el bloqueo de objeto, con prueba completa, diario SQL `clean` y SHA256 real del archivo. Si lo confirma, registra recuperación y libera su reserva. Si no lo confirma, conserva revisión y cuota, registra `uncertain` y detiene el lote.

La conciliación exige también el bloqueo de la reserva. Una petición/trabajo que aún conserve alguno de los bloqueos no se considera terminado. La operación revisada solo libera una reserva de revisión `clean` confirmada. Puede repetirse: una reserva ya liberada se informa como tal. No elimina la revisión confirmada ni el original.

**Decisión administrativa pendiente para casos negativos:** cuando no se puede demostrar el COMMIT, no se ha implementado un borrado automático. Se debe revisar el diario privado, fila actual, intentos de escaneo, reservas y archivos bajo sus bloqueos. Una revisión sin referencia puede conservarse en cuarentena privada o retirarse con una operación específica revisada; solo después se libera el presupuesto. Objetos rechazados, propietarios desconocidos, fuentes faltantes y casos inciertos deben tener resolución documentada e inaccesible antes de activar el servicio completo.

## Ejemplo de integración, todavía sin ejecución real

Desde un proceso administrativo que usa el volumen y esquema protegidos:

```js
const { Pool } = require('pg')
const { createStorageUploadAdmission, closeStorageUploadPools } = require('./storage-upload-admission')
const { planHistoricalAvBackfill, applyHistoricalAvBackfill } = require('./storage-historical-av-backfill')
// La URL de conexión se obtiene del entorno privado; nunca se imprime.
const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 4 })
const root = process.env.STORAGE_ROOT
const admission = createStorageUploadAdmission({ pool, root })
const prepared = await planHistoricalAvBackfill(pool, { root, maxObjects: 5, maxInputBytes: 64 * 1024 ** 2 })
// Guardar prepared.plan con permisos 0600 en el servidor y revisar su digest.
// La siguiente llamada se ejecuta solo después de esa revisión concreta.
const result = await applyHistoricalAvBackfill(pool, prepared.plan, {
  root, admission, reviewedPlanDigest: prepared.digest,
})
// Guardar el resultado privado; no declarar cobertura global por result.complete.
await closeStorageUploadPools(pool)
await pool.end()
```

Este fragmento es ilustrativo, no un script desplegado. La admisión necesita el lote de esquema/ACL validado y el cierre de pools disponible en la propuesta del finalizador. La biblioteca no imprime credenciales, no inicializa tablas importadas y no necesita superusuario. El ejecutor operativo debe escribir el resumen y cerrar pools en `finally`, conservar cursor, manejar señales y programar una pausa entre lotes. Esas decisiones de operación deben definirse antes del primer lote real.

## Evidencia y condiciones de aceptación pendientes

La evidencia Windows anterior `synthetic-review-latest.json` conserva 15 casos hoja más la suite, 16/16 TAP, sin omisiones, sobre el SHA `849f07d8...` previo a las barreras completas de disco. No se presenta como aceptación del SHA final. La suite final se ejecuta en Linux: 16 casos hoja más la suite, 17/17 TAP, sin omisiones. Usa PostgreSQL 16 y archivos reales de fixtures, rol de trabajo DML sin los cinco privilegios administrativos, y adaptador sintético que devuelve la prueba de los bytes exactos. El espacio libre se simula abundante. Los errores de respuesta COMMIT se inyectan alrededor de transacciones PostgreSQL reales. La pérdida de reserva termina una sesión real mediante `pg_terminate_backend`.

`linux-real-review-latest.json` registra 12 grupos de aceptación, incluida la suite PostgreSQL completa, con fecha y SHA exactos: imagen API `ff2db273...` con overlay del módulo final, rutas `41a83076...` y admisión `47350c05...`; PostgreSQL 16 y Clam `c474e0cd...`, motor 1.4.6, firmas 28147, política `958f7db2...`. Cada digest completo está en el reporte. Incluye:

- Escaneo limpio real con SHA de origen/entrega, URL, dueño y metadatos preservados.
- Firma propia para texto inerte: fila y diario SQL terminal `rejected`, original conservado, ninguna revisión publicada.
- Política de cargas existente: HTML, APK, MIME PNG falso, bucket desconocido y chat sobre 60 MiB; cero filas marcadas `clean`.
- Inyección de cuatro errores `EIO` en sincronización de copia, revisión, diario y directorio: ningún COMMIT `clean`, ninguna reserva retenida en esos fallos conocidos, originales preservados y ninguna revisión instalada.
- `SIGKILL` de un proceso hijo real después del escaneo Clam y antes del sello: fila `legacy_unverified`, intento `scanning`, temporal/cuarentena privados conservados, reserva activa `infinity` y conciliación bloqueada. El reaper no libera la cuota. Esta retención requiere decisión administrativa; no es recuperación automática completa.
- `SIGKILL` después de COMMIT PostgreSQL real y antes del acknowledgment: fila y entrega `clean`, evento `sealed` durable, sin evento final. Conciliación solo lectura conserva cuota; la operación con digest revisado confirma fila/diario/SHA, libera solo su reserva y temporal, preserva original y revisión, y puede repetirse.
- Digest de diario distinto, revisión física faltante, bloqueo de reserva en otra sesión y ausencia de prueba limpia: no liberan cuota ni borran objetos. No se modifica la fila para simular un éxito.

Se retiraron los tres contenedores propios, red interna, esquemas/rol de fixtures y scratch; la aceptación final requiere `sourceStable=true`. No se publicaron puertos ni se montaron volúmenes de producción. No hubo outage del daemon en esta prueba del módulo; las pruebas de outage HTTP del lote antivirus son evidencia separada. No se usa malware, base de producción ni archivos de usuarios. Los ensayos fallidos se conservan: un mount de prueba mal ubicado, y un ensayo cuyos casos pasaron mientras se actualizaba una barrera de directorios (`sourceStable=false`), no se cuentan como aceptación final.

Antes del primer lote real deben completarse:

- Aplicación revisada del manifiesto y regresión con el nuevo pool de finalización y la imagen exacta.
- Completar escenarios de archivo cifrado, límite Clam y caída/recuperación dentro del backfill si se aceptan esas categorías en el inventario; los límites de bucket y el rechazo propio ya están cubiertos. La prueba HTTP antivirus real es separada.
- Ensayar en el volumen del candidato un cierre durante copia y corte del host completo si forma parte de su criterio de recuperación. Las dos pruebas SIGKILL actuales matan el worker en escaneo/COMMIT; no equivalen a corte eléctrico del host, y el caso previo a sello conserva cuota y orfandad para revisión administrativa.
- Restauración conjunta de DB, medios y `.backfill-journal`; los diarios no deben faltar en el respaldo.
- Inventario real con estados agrupados y 100% de resolución documentada. Un lote `complete` significa éxito de ese lote, no cobertura total del almacenamiento ni certificado de producción.
- Presupuesto de conexiones por réplica: pool API + leases (slots globales + 1) + finalizador (slots globales), más el worker administrativo. Con valores por defecto son 4 + 7 + 6, además del worker; medir contra `max_connections` y margen de operación.
