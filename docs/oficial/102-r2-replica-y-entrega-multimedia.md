# R2: réplica y entrega multimedia

## Avatares animados y chat de voz, 2 de noviembre de 2026

La preparación de medios revisó 140 objetos limpios de perfiles, grupos y
comunidades y dejó 280 variantes disponibles, sin fallos. El único archivo
GIF/WebP del conjunto ocupa 359.750 bytes. Su variante web de 160 píxeles
ocupa 5.074 bytes. Las variantes con `animated=1` conservan los fotogramas y
sus tiempos. Las doce portadas de los seis avatares de video ocupan 105.388
bytes en total. Las solicitudes normales del original conservan su contrato
de reproducción. Los GIF/WebP externos siguen su contrato de origen.

El JPEG histórico con cabecera SOS inconsistente ya admite una miniatura de
3.190 bytes. El generador usa el decodificador de fotogramas acotado para ese
error concreto. La prueba comprueba que el original conserva su hash. La
prueba de animación comprueba múltiples fotogramas y sus tiempos, y la prueba
HTTP comprueba imagen derivada autorizada, rechazo sin permiso y rango del
video original. La interpretación de animación se contrastó con las
[instrucciones de sharp](https://sharp.pixelplumbing.com/api-constructor/).

La web pide variantes pequeñas para avatares GIF/WebP propios, prioriza los
dos primeros autores de Social y reutiliza imágenes y clips cortos en una
caché acotada por cuenta y generación de sesión. Al cargar una foto conserva
una fuente de imagen válida en el elemento. Se compactó la sala de voz,
moviendo información bajo el escenario y conservando el chat a la derecha.
Las barras de desplazamiento de conversaciones y chat de voz están ocultas,
con el desplazamiento disponible. La validación visual se registra aparte.

El chat de voz vence al cumplir 60 minutos desde el envío. Android y web
limitan también los mensajes que conservaban un plazo anterior de 90 minutos.
El servidor conserva el vencimiento inicial en reintentos y retransmisiones
entre nodos. Su diario en memoria guarda metadatos de entrega. El texto y los
JPEG pequeños viajan por el canal temporal y Pub/Sub, sin crear objetos de
storage o réplicas R2. La limpieza de Redis elimina los eventos de chat
antiguos que hubieran quedado en Streams de una versión previa.

La API activa es `novvin-api:r2-animated-thumbnails-20261102`, con imagen
`sha256:5aaac072246f67f658199ee9eba2b7b5a93e861bcf13c4d8f3bf7707f031c256`.
Señalización usa `novvin-signaling:room-chat-hour-20261102`, con imagen
`sha256:543dd35388d8ac727985fd90eb79242acb489a54b350251c64ad884a90c37808`.
Ambos servicios aprobaron disponibilidad después de recrearse. La copia
anterior de Compose está en
`/home/alvaro/.config/novvin/media-hour-deploy.5wmU7u3u/`.
Se compararon las fuentes previas de señalización con Git antes de aplicar
la capa que cambia el plazo y sus comprobaciones periódicas.

Pasaron 128 pruebas web seleccionadas y 16 del service worker. Pasaron 79
pruebas de API en Linux con ffmpeg y 12 de señalización, sin fallos ni casos
omitidos. Pasó el build web con sus comprobaciones de SEO. Android 9.1.38 /
178 incorpora las portadas de avatar y el plazo de una hora. Su paquete y las
comprobaciones finales se registran al terminar su construcción. La entrega
177 previa a esta nueva política queda sustituida por ese candidato.

## Avatares y latencia, 2 de noviembre de 2026

Android evita descargar en segundo plano el original completo después de
mostrar una miniatura de avatar. Conserva la miniatura para uso sin conexión
y obtiene el original al abrir la foto completa. La prueba recorre ambas
entradas mediante la factoría real de solicitudes y el interceptor de Coil.
El código está en `efdd7453`, con versión `9.1.36` y código `176`.
Pasaron 79 pruebas de lectura multimedia en seis clases, con cero fallos,
errores y omisiones. La entrega firmada se detalla más abajo.

En web, la foto principal de `ProfileHeader` comienza su lectura al montar el
componente y tiene prioridad en la cola acotada de imágenes. Los avatares de
listas se solicitan al acercarse a la zona visible. La caché de imágenes
pertenece a la generación de cuenta y comparte las lecturas simultáneas.
Pasaron 96 pruebas seleccionadas de lectura, caché y avatares, y la compilación
de producción de Vite con generación SEO.

La API activa usa `novvin-api:r2-avatar-prewarm-20261102`, con imagen
`sha256:0b69f59f723ecadc7d8d7565c8ffa229eda51e8517fbcfa7da8a4f348f5613d3`.
La capa prepara los perfiles WebP de 160/78 y 384/86 después de aceptar una
foto JPEG o PNG de avatar. Son los perfiles canónicos de las peticiones web y
Android. El marcador de release de `/ready` identifica la base
`novvin-r2-edge-http-20261102`. Se verificaron disponibilidad y coincidencia
de la fuente desplegada tras normalizar terminaciones de línea, con SHA-256
`495c4a27ea94e07dddf86f5ca3d107bc970630dbc94045477dc6b11ceb55142b`.
El respaldo previo de Compose está en
`/home/alvaro/.config/novvin/avatar-prewarm-deploy.URFwOLEv/docker-compose.override.before.yml`.

La preparación acotada de las 99 fotos JPEG/PNG limpias encontró 52 variantes
existentes y generó 144. Se comprobaron 196 variantes disponibles. Las dos
variantes de una foto antigua fallan con
`VipsJpeg: Invalid SOS parameters for sequential JPEG`. Un reintento por la
función real confirmó el fallo y la selección del original como respuesta.
Este archivo queda identificado como excepción de conversión. La consulta
del registro se ejecutó en transacción de solo lectura y el proceso solo
escribió derivados en `_variants`.

El Worker publicado es `f7b5df4c-180d-4a72-bdb0-aea72d93f2d9`.
Responde directamente las consultas CORS previas de GET/HEAD del dominio web
de producción para las cabeceras permitidas de lectura. El navegador puede
reutilizar esa autorización previa durante 600 segundos. Cada lectura real
se autoriza en Ubuntu antes de entregar bytes del bucket privado. El Worker
añade tiempos separados de autorización en origen y consulta al bucket.
Pasaron diez pruebas de almacenamiento y Worker. Se comprobó en producción
un rango autorizado `206` desde R2 y una sesión inválida `401`.

En cinco mediciones de la consulta previa, el intervalo observado pasó de
0,68–1,71 s a 0,43–0,47 s. Estas cifras miden OPTIONS en esa conexión. El
tiempo completo de una imagen o video también incluye autorización,
transferencia y decodificación. Una respuesta de video registró 343 ms en
el origen y 173 ms en la lectura de R2.

Vercel publicó `dpl_3TnSMViAo8Q4QK4ZVSprhhCqFhoq`, servido como
`red-social-android-6zrbx2kta-alvaro-s-projects1.vercel.app`. Chrome verificó
el dominio público, el reproductor con `readyState=4`, cinco respuestas `200`
y una `206` de R2 mediante service worker, y una foto de perfil decodificada
de 160 píxeles. El primer ensayo agotó su espera durante el arranque de la
sesión nueva. La inspección posterior encontró tres videos listos y cero
errores de consola, y el nuevo ensayo conectado aprobó. El punto previo de
reversión de Vercel es
`red-social-android-1150ophh0-alvaro-s-projects1.vercel.app`.

Se consultaron las instrucciones oficiales de Cloudflare sobre ubicación de
R2, Placement y Cache API. Las sugerencias de ubicación de bucket se aplican
al crearlo. Smart Placement necesita tráfico y mediciones para decidir dónde
ejecutar el Worker. Cache API requiere dominio propio o ruta y carece de
efecto en el despliegue actual `workers.dev`. La optimización de esta ronda
consiste en las miniaturas, la prioridad de carga y la consulta previa
resuelta en el Worker. Fuentes consultadas el 2 de noviembre de 2026.
[Ubicación de R2](https://developers.cloudflare.com/r2/reference/data-location/),
[Placement](https://developers.cloudflare.com/workers/configuration/placement/),
[Cache API](https://developers.cloudflare.com/r2/examples/cache-api/).

### AAB firmado 9.1.36 / 176

La compilación `bundleRelease` terminó correctamente. El AAB de producción
usa `conectate.com`, minSdk 24, targetSdk 36 y el origen multimedia del Worker
comprobado dentro de los DEX. Incluye las correcciones Android anteriores de
la revisión 101, la activación de R2 y la reducción de descargas de avatar de
`efdd74532d86`. La copia local de entrega es
`artifacts/releases/9.1.36-176/Novvin-9.1.36-176.aab`.

Se verificó criptográficamente la firma contra el certificado de subida del
AAB anterior, con SHA-256
`623CDB12BAD08FCCA8A4A2F62EB6B5C91583D2CD14CED4AE63864066E036D84D`.
La huella SHA-256 del nuevo archivo es
`7A1F63A9C1C07EDC771D11650E1F9E7E2F32180F4037F2A2DCE1A7A79E4B98EE`.
Pasaron R8 para 17 componentes declarados, alineación ZIP de 16 KiB y las
14 bibliotecas nativas de 64 bits. Pasaron los cuatro presupuestos de tamaño.
El AAB mide 132.551.459 bytes y la descarga máxima comprimida calculada por
bundletool es 66.915.026 bytes, equivalentes a 63,82 MiB. Los informes locales
están en `build/reports/r2-avatar-release176/`.

La revisión integral sigue abierta. La validación física y la publicación de
Android en Google Play corresponden al propietario.

## Entrega verificada desde Cloudflare, 2 de noviembre de 2026

La API `novvin-r2-edge-http-20261102` añade únicamente el soporte R2 a la
imagen de producción anterior. La verificación directa y pública de `/ready`
pasó. La imagen tiene la huella
`sha256:81f4c5b5f410fc676d2f37a3d2c686a13b0a967814a78d90ba628df104b43a62`.
El Worker `a42aa3b5-0693-426c-bb41-a4928c2bd931` se publicó con el origen
HTTPS de Ubuntu y el binding privado de `novvin-media`. El rol de la API
solo tiene permiso de lectura sobre el diario.

Una sesión diagnóstica temporal verificó bytes idénticos para una foto
completa (`200`) y un rango de video (`206`) por la API y el Worker. La
cabecera de origen confirmó que ambos cuerpos llegaron de R2. Una sesión
inválida recibió `401` en ambos orígenes. La variante de avatar se sirvió
desde Ubuntu y el ensayo HTTP del Worker comprobó el retorno al original
cuando falta la copia. Desde el dominio web de producción, Chrome leyó cuatro
rangos con una sesión de invitado y CORS real. Tres llegaron de R2 y uno de
Ubuntu. Una lectura autenticada de un documento de Word de chat devolvió
`200` con el mismo SHA-256 en Ubuntu y R2. La prueba de audio seleccionada
recibió `404` desde la ruta directa y queda pendiente una muestra autorizada
para verificar entrega de audio en producción. El primer despliegue se
revirtió tras detectar una
cabecera `Content-Length` inválida en la respuesta `204`. La corrección retira
esa cabecera antes de que la API responda al Worker. La copia de reversión de
Compose y del marcador de release quedó en `r2-edge-deploy.EgyV0e4u`.

Android y web admiten un origen multimedia opcional, con rutas y enlaces
persistidos en Ubuntu. La selección requiere que coincidan el origen activo
y el configurado del backend. Los archivos con enlace firmado, lease de una
sola visualización y variantes solicitadas por ancho usan la ruta directa.
Pasaron 78 pruebas de lectura de Android, 85 del cliente de lectura web,
16 del service worker web, dos del Worker de Cloudflare y la compilación de
producción web. Chrome reprodujo video en el candidato web con el mismo origen
que usa Vercel. Con 18 errores `503` provocados en el Worker, recuperó cinco
rangos desde Ubuntu y el reproductor volvió a estar listo.

Vercel promovió `dpl_DWeDPDk5byeRQscCLatkjjtQW7pt` al dominio público de
Novvin. El service worker servido coincidió con el código validado y, como
invitado en el dominio público, Chrome dejó listo el reproductor y observó
19 respuestas servidas por R2. En el mismo despliegue, el navegador reprodujo
tras 19 errores `503` simulados y recuperó diez rangos desde Ubuntu. El
despliegue previo de Vercel fue
`red-social-android-9iz2ue35e-alvaro-s-projects1.vercel.app`.

El origen opcional se fija con `VITE_MEDIA_EDGE_ORIGIN` al compilar la web y
con `NOVVIN_MEDIA_EDGE_ORIGIN` o `MEDIA_EDGE_ORIGIN` en `secrets.properties`
al compilar Android. Los dos valores son una URL pública del Worker. Las
claves S3 y el secreto de la consulta privada permanecen en el servidor y en
Cloudflare según su uso.

El candidato Android `9.1.35-qa` (175) se compiló con el origen multimedia
habilitado. Se verificaron la firma APK, la alineación de 16 KiB y la presencia
del origen del Worker en el APK. La variante se instala como `conectate.com.qa`.
La validación en dispositivo y la entrega en Google Play se registran aparte.

En tres lecturas del mismo video completo de 7.266.761 bytes desde Windows
por la red privada, Ubuntu tardó entre 0,93 y 1,05 s y R2 entre 4,88 y 7,36 s.
Esta comparación corresponde a una conexión con acceso directo a Ubuntu por
Tailscale. La prueba inicial forzando el relay público desde Ubuntu agotó 25 s
con 592.767 de 1.048.576 bytes. Las mediciones por red se conservan separadas.
Desde Windows y fijando la resolución al relay público, el video completo
tardó 11,31 s por Ubuntu y 3,28 s por R2, con SHA-256 idéntico. El primer byte
llegó a los 0,29 s desde Ubuntu y 1,98 s desde R2. La muestra demuestra una
mejora de tiempo total en esa descarga, con más espera inicial.

## Activación de la réplica en Ubuntu, 2 de noviembre de 2026

El trabajador de réplica está en ejecución. Ubuntu conserva los originales en
`conectate-fallback-storage` y el trabajador solo puede leer ese volumen. Se
verificaron las credenciales S3 limitadas a `novvin-media` mediante escritura,
lectura con SHA-256 y borrado de una muestra. Las credenciales quedan en un
archivo con modo `0600` fuera del repositorio. Antes de crear el diario se
guardó un volcado PostgreSQL con lista restaurable y suma SHA-256.

El rol del trabajador solo tiene lectura del registro y permisos para mantener
su diario. La primera tanda verificó archivos reales. Hubo una interrupción en
la copia, con reintentos registrados, y el trabajador volvió a verificar objetos
después de reiniciarlo. A las 19:53 (Bogotá), PostgreSQL registraba 111 copias
listas con 330.980.076 bytes y una en curso. El límite operativo es de
9.000.000.000 bytes. Los archivos históricos que siguen sin verificación
antivirus esperan el procedimiento de revisión y asociación de propietario.
El trabajador se incorporó a la configuración de Compose que usa la operación
normal, con `restart: unless-stopped`, para conservarlo tras reconciliar el
stack. La configuración se comparó contra el overlay de réplica y el resto de
los servicios antes de aplicarla.

La carga inicial sigue en curso. La consulta de producción registró 530 copias
verificadas por 965.240.746 bytes y 19 filas en reintento. El inventario de
origen tenía 1.008 registros limpios y 235 históricos sin verificación. Se
observó `ETIMEDOUT` al conectar al endpoint S3 durante una tanda, seguido de
respuestas del endpoint al recuperarse. El diario conserva el siguiente
reintento de los objetos pendientes.

Durante otra pausa, la conexión directa del host a S3 respondió por IPv6
mientras IPv4 agotaba el tiempo de espera. El replicador conservó su red
predeterminada para PostgreSQL y recibió una red puente IPv6 dedicada de
Docker, `novvin-r2-egress`. Un contenedor aislado y luego el replicador activo
verificaron la consulta PostgreSQL y un objeto real mediante S3 autenticado.
El diario registró nuevas copias después del cambio. La red quedó declarada
en Compose para ese servicio. La copia previa de la configuración está en
`r2-mirror-ipv6.eCRgHBvL`.

La cuenta carece de una zona DNS disponible. Se publicó el Worker independiente
en `workers.dev`, fijado al origen HTTPS de Ubuntu y limitado a lecturas de
storage. El bucket conserva su acceso privado. La prueba de preparación del
Worker comprobó la coincidencia de una respuesta 404 con Ubuntu y el rechazo de
rutas ajenas al almacenamiento.

## Estado al 2 de noviembre de 2026, 19:36 (Bogotá)

El propietario autorizó Wrangler desde el navegador. Se creó `novvin-media`
con almacenamiento Standard, sin URL `r2.dev` pública ni dominios conectados.
Una prueba desde Windows subió un archivo sintético, descargó sus bytes,
comparó SHA-256 y retiró ese archivo del bucket. En Cloudflare se publicó
`novvin-media-delivery`, versión `250d05d1-3288-4587-b2c0-7359271b1e14`, con
acceso al bucket y sin rutas ni URLs públicas. No recibe tráfico de Novvin.

Falta la clave S3 de lectura y escritura limitada al bucket para el trabajador
de Ubuntu. El OAuth disponible para Wrangler recibió HTTP 403, código 9109,
al consultar la administración de claves de la cuenta. El archivo local
protegido ya tiene el endpoint y el nombre del bucket. Los campos de las dos
claves siguen vacíos. La cuenta devuelve cero zonas DNS y ya tiene un
subdominio de Workers. Usar ese subdominio requiere adaptar y verificar los
clientes para que utilicen un origen multimedia separado del origen API.

En Ubuntu, la API activa estaba saludable y el diario de réplica seguía sin
aplicar. Se comprobó la imagen candidata y la integridad del respaldo externo
`20261102T081648Z`. El inventario de archivos heredados en modo de solo lectura
encontró 53 candidatos para análisis posterior y 182 sin propietario. Los 182
requieren resolver su propiedad antes de replicarlos. El inventario no cambió
filas ni archivos. La réplica y la entrega desde R2 siguen pendientes de
activación y verificación en los clientes.

## Preparación anterior del 2 de noviembre de 2026

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
