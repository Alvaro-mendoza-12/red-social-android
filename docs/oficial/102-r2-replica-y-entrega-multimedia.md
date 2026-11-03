# R2: preparación de réplica y entrega multimedia

## Estado al 2 de noviembre de 2026

El propietario pidió integrar Cloudflare R2 conservando los originales en Ubuntu,
con posibilidad de volver a almacenamiento exclusivamente local. Esta petición
es independiente de la revisión Android 101, que sigue abierta.

**R2 sigue desactivado en producción.** Se prepararon el proceso de réplica y
la entrega de originales mediante un Worker con respaldo local. La API activa
sigue sirviendo desde Ubuntu. No se han creado un bucket ni
credenciales, copiado archivos a Cloudflare o migrado el esquema de producción.
El control de computadora rechazó dos intentos de inspeccionar Brave porque no
pudo determinar la URL actual con suficiente confianza. No se intentó recuperar
cookies ni eludir esa comprobación mediante otro canal.

## Evidencia de lectura del servidor

Se consultaron por SSH los montajes del contenedor API y una agregación de
`fallback_storage_objects` en una transacción `READ ONLY`. El volumen local es
`conectate-fallback-storage`, montado en `/data/storage`. La imagen observada fue
`novvin-api:audit101-notification-transport-20261102`.

| Estado registrado | Objetos | Bytes registrados |
|---|---:|---:|
| clean | 1.007 | 1.927.301.388 |
| legacy_unverified | 235 | 927.792.873 |
| Total | 1.242 | 2.855.094.261 |

Esta consulta cuenta registros; no verifica la existencia de cada archivo ni
demuestra que los datos actuales estén ya copiados en R2. El contenido heredado
debe completar el análisis antivirus existente antes de replicarse. No se marca
como limpio por antigüedad, extensión o por esta consulta.

## Trabajador preparado

`api/scripts/storage-r2-mirror.js` ofrece cinco modos explícitos:

- `--inventory`: inventario agregado de origen, sin escribir.
- `--status`: estados y tamaños registrados en el diario de réplica.
- `--migrate`: crea explícitamente el esquema del diario; requiere rol migrador.
- `--once`: procesa una tanda autorizada.
- `--run`: procesa tandas sucesivas, con apagado ordenado.

El trabajador está desactivado por defecto. Su overlay de Compose es opcional y
monta el volumen de archivos como solo lectura. No reemplaza ni borra originales
locales. La tabla `fallback_storage_r2_mirrors` registra cada destino, revisión,
hash, tamaño, estado y próximo reintento antes de escribir en la nube. Un bloqueo
asesor PostgreSQL evita dos procesadores simultáneos. No depende de Redis.

Los nombres remotos son opacos e inmutables por identidad/revisión, hash, tipo y
ruta lógica. El trabajador comprueba ruta canónica, tamaño y SHA-256 locales,
sube mediante el SDK S3 con multipart acotado y descarga la copia para verificar
su SHA-256 real. Si un objeto cambia o desaparece durante la copia, esta no se
declara lista. Los fallos mantienen su diario y reintentan con espera creciente.
Las revisiones obsoletas se eliminan únicamente del destino R2 correspondiente;
la copia local no es objeto de esas eliminaciones. Un cambio de bucket/destino
no hereda las verificaciones del destino anterior.

El límite inicial configurable de 9.000.000.000 bytes evita reservar más bytes
de objetos nuevos en ese destino. **No es un límite de facturación de Cloudflare**:
no abarca operaciones, otros buckets, partes multipart abandonadas, retenciones
mensuales ni servicios adicionales. La cuenta puede facturar excedentes. Hace
falta vigilar uso y configurar el aborto de multipart incompleto en el bucket.

La réplica sigue el registro confirmado de PostgreSQL. Existe una ventana entre
la publicación local y la verificación en R2: un apagón durante esa ventana deja
el archivo nuevo pendiente de réplica. No se promete copia sincrónica ni
continuidad completa de la API/base de datos durante un corte de electricidad.
Esta réplica propaga borrados; no sustituye los respaldos con retención.

## Entrega de originales desde R2

El Worker se instala en la ruta `/storage/v1/object/*` de un origen API con
proxy Cloudflare. Ubuntu sigue autorizando cada petición GET, incluidas las
lecturas privadas y de invitado. Solo si el diario confirma una copia verificada
de esa misma revisión responde al Worker con sus metadatos. El Worker obtiene
los bytes del bucket privado, verifica identidad y tamaño y entrega el archivo
completo o el rango pedido. Al faltar la copia o fallar la consulta a R2, vuelve
a la misma URL en Ubuntu. Esto evita cambiar los enlaces persistidos y enviar
URLs reutilizables de contenido privado al cliente. Si la transmisión falla
después de enviar sus cabeceras, el cliente debe volver a solicitar los bytes.

HEAD, peticiones condicionales, rangos no compatibles, variantes WebP derivadas
y archivos con lease de una visualización siguen la ruta original. El Worker
impide caché compartida en las respuestas aceleradas. La comprobación de
permisos sigue requiriendo que Ubuntu esté disponible.

## Recorridos para activar entrega rápida

| Recorrido | Preparación de réplica | Pendiente para entrega en producción |
|---|---|---|
| PUT, formulario y TUS | Se descubre la fila confirmada, independientemente del protocolo | Ensayo real de cada protocolo, ACK perdido y recuperación |
| Los 18 buckets del uploader | Prueba de participación de cada bucket con prueba antivirus | Copia real e inventario conciliado en Cloudflare |
| Importación/históricos | Se descubre el registro; legado sin escaneo queda pendiente | Completar backfill antivirus y comprobar archivos faltantes |
| Imágenes, video, audio y documentos | Copia binaria y entrega del original completo o por rango | Reproducción, descargas y CORS con el Worker y el bucket reales |
| Chats, grupos y comunidades | Bucket privado y autorización de la API antes de leer R2 | Comprobar bloqueos, bajas, expiración y borrados en un entorno conectado |
| Archivos de una sola visualización | Réplica privada y entrega local acotada por lease | Ensayo en clientes conectados |
| Reportes/evidencias | Réplica privada, sin publicación | Mantener acceso firmado y autorización administrativa |
| Miniaturas WebP derivadas | Los originales permanecen recuperables | Integrar `_variants`, identidad de variante y reglas de caché |
| Catálogos o URLs externos sin objeto local | No se descargan por mera presencia de una URL | Auditar consumidores y contratos; no asumir propiedad de contenido externo |
| Borrado de objeto/cuenta y cambio de revisión | Diario detecta desaparición/revisión y retira claves antiguas | Ensayo de borrado real en R2, limpieza de variantes y revocación de entrega |
| Android y web | Contratos actuales de storage conservados | Confianza de hosts, redirecciones, sesiones, caché y recuperación por cliente |

Un bucket público expondría adjuntos y evidencias. La entrega rápida necesita
una ruta que conserve los controles actuales de permisos. No se activará una
redirección general a URLs prefirmadas: una URL reutilizable o una caché pública
puede romper la revocación por bloqueos/borrados o el uso único de un archivo.

## Configuración y activación pendientes

1. Crear el bucket privado y credenciales de objetos limitadas a ese bucket.
   Guardar las claves fuera de Git y de los clientes. El archivo
   `infrastructure/local-fallback/r2-mirror.example` contiene únicamente una
   plantilla sin credenciales.
2. Verificar privacidad del bucket, CORS según orígenes reales y limpieza de
   multipart incompleto. Configurar una alerta presupuestaria, recordando que
   una alerta no detiene automáticamente el consumo.
3. Crear respaldo verificable antes de migrar producción. Dar al rol del
   trabajador únicamente lectura de `fallback_storage_objects` y lectura/escritura
   de su diario; el proceso recurrente no necesita un rol propietario de la base.
4. Construir una imagen candidata fijada, migrar el diario y ejecutar una tanda
   pequeña. Verificar SHA-256 con Cloudflare real, conservación local, reintento
   tras caída, reemplazo y borrado. Mantener el servicio desactivado si falla.
5. Preparar un origen API con proxy de Cloudflare y alcance limitado a la ruta
   indicada. El origen `*.ts.net` de Tailscale no admite instalarle una ruta de
   nuestra zona DNS. Primero comprobar que el nuevo origen conserva login,
   subidas y realtime. Conservar los orígenes anteriores para enlaces guardados.
   Configurar `R2_EDGE_TARGET` en la API con la URL S3 de cuenta y el nombre del
   bucket, y `R2_EDGE_SECRET` en ambos servicios con el mismo secreto aleatorio de
   32 bytes en hexadecimal. El rol de la API necesita `SELECT` sobre el diario.
   La plantilla `r2-edge/wrangler.example.toml` conserva el binding privado.
   Probar los clientes, variantes y límites de caché de la tabla. Comparar tiempos
   y caudal desde una red externa para medir la mejora real.
6. Para retirar R2, detener nuevas copias, conciliar todos los originales locales
   y sus hashes, verificar que clientes y rutas sirven desde Ubuntu y solo
   entonces retirar la infraestructura R2. El diario de otro destino no prueba
   que una nueva cuenta/bucket tenga los objetos.

La reversión de esta preparación consiste en no iniciar o detener el trabajador.
La API original no depende del diario ni de R2. Las claves y las copias remotas
se administran aparte; no se borran como efecto de deshabilitar la configuración.

## Comprobaciones

Pruebas locales: diez casos aprobados, incluyendo archivos reales, cada uno de
los 18 buckets y un servidor HTTP S3 de prueba con firma AWS V4. La copia se
verifica descargando bytes; la prueba distingue contenido corrupto de metadatos.

Un caso adicional contra PostgreSQL 16 aislado aprobó reproducción de tandas,
cambio MIME, borrado, reintento persistente, aislamiento entre destinos, límite
de bytes y exclusión entre trabajadores. El contenedor de prueba no usa volúmenes
de producción ni credenciales de usuarios. Esta evidencia no es una prueba de
Cloudflare real ni un despliegue de R2.

La comprobación final en Linux con Node **22.23.4**, instalación limpia desde
lockfile y PostgreSQL 16 aislado aprobó **102 casos, cero fallos y cero omisiones**.
Incluyó copia multipart grande y aborto al fallar una parte, junto con las
regresiones existentes de antivirus, lectura privada, HEAD/Range y borrado.
También pasó la resolución de dependencias de las fuentes. El contenedor
PostgreSQL de prueba se detuvo y eliminó automáticamente; la API de producción
no fue recreada. Un error de terminación CRLF del envoltorio SSH ocurrió después
de esas pruebas; se releyó el informe que confirma su resultado satisfactorio.

La tanda posterior aprobó **103 casos** en Linux con PostgreSQL aislado. Incluye
una prueba HTTP que conecta el Worker con las rutas reales de la API y un binding
R2 simulado. Comprueba reproducción por rango, HEAD y 304 a través de Ubuntu,
revocación, lease de una visualización y retorno a Ubuntu ante una copia ausente
o fallo de consulta del diario. Queda pendiente el ensayo contra Cloudflare.

Wrangler compiló el Worker en modo de simulación. Un ensayo separado con
workerd y un bucket R2 local comprobó los bytes y `Content-Length` en respuestas
200 y 206, el rechazo sin autorización y el retorno al origen al faltar el
objeto. El servidor aceptó la configuración de Compose en modo de validación.

`npm audit` informó nueve avisos moderados en ramas existentes de ExcelJS/Google
GAX y sus dependencias. No señaló avisos en los nuevos SDK AWS. Estos avisos no
se presentan como corregidos por esta preparación ni se ejecutó una actualización
general de dependencias ajena al cambio.
