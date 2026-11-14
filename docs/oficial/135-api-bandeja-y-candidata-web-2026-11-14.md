# 135 · API de bandeja activada y candidata web pendiente de integración

14 de noviembre de 2026, America/Bogota. Continúa el bloque [134](134-ultimo-mensaje-y-diagnostico-de-historial-2026-11-13.md). Distingue código publicado, servicio efectivamente actualizado y candidata web sin asignación del dominio. La revisión integral sigue abierta.

## API efectiva

La API sirve `novvin-api:review134-b299955fa874-modes`, índice OCI `sha256:a2d746a6f748568bf09d415f549e445c7b4df472602aab0169e954ea1ec700c4`. Se construyó sobre la imagen inmutable que estaba en servicio, `sha256:564f4bdc8165cf99fd38ffe067490bef2482a49bbf166a99ae731c4922ad46cc`. La etiqueta anterior era `novvin-api:review122-6c9d8141`; el marcador de salud todavía decía 116 y no identificaba por sí solo esa imagen.

Se aplicó exclusivamente el delta 134 de `data-routes.js` y `message-deletion-policy.js`. Se conservaron los otros 178 archivos, sus propietarios y permisos, el usuario de ejecución y los contratos de importación. La fuente desplegada contiene también las funciones previas del servidor; no se atribuye todo su árbol al commit estable `4a2801326a96d752a8fd6c34e830791bec53addb`. Los experimentos del plan 105 permanecen fuera de las dos ramas estables.

El servicio identifica `novvin-api-review134`, la revisión estable que aporta el delta y la huella de superposición `c6ff0627a9b914250bc38a293262be3c833c7449091fe146ce4199aab35cc557`. La comprobación final contrastó las huellas de los dos archivos dentro del contenedor y ejecutó la vista previa con un sticker sintético: devolvió «Sticker». No leyó el contenido de mensajes reales. El proceso principal corre como UID 10001.

Pasaron salud interna, gateway y HTTPS público, y la bandeja real `/v1/data/messages/inbox` rechazó una solicitud sin sesión con 401. Se conservaron las 25 capacidades anunciadas, las variables de entorno, las opciones de arranque y de contenedor, los montajes, los dos indicadores experimentales existentes y los identificadores de los otros catorce contenedores. No se ejecutó una migración de esquema ni un proceso de recuperación, traslado o borrado de historiales.

## Respaldo, primera activación y reversión

El respaldo privado permanece en el servidor: configuración Compose, entorno, marcador, inspección de contenedor, imagen anterior y volcado PostgreSQL. Se comprobaron SHA-256, todos los blobs y descriptores del archivo OCI y el catálogo de restauración del volcado. La huella del índice OCI es distinta de la de su configuración; el primer ensayo del comprobador se corrigió para comprobar cada descriptor. El volcado es una instantánea consistente de PostgreSQL; no representa una instantánea atómica conjunta con el almacenamiento multimedia. No se descargaron esos secretos ni datos de personas al repositorio.

La activación usó el bloqueo de Compose, comprobaciones de imagen y configuración antes de escribir y una reversión preparada. La primera candidata superó salud, huellas, configuración y prueba sintética. El ensayo final de autorización usó por error `/v1/messages/inbox`, una ruta inexistente que devolvió 404; el procedimiento restauró la imagen anterior y confirmó su salud. No fue un fallo demostrado de la ruta de bandeja ni una recuperación de datos. Se corrigió el ensayo, se comprobó previamente el 401 de la ruta correcta y se repitió la activación sobre la configuración restaurada. La segunda activación terminó correctamente a las **05:14:59 UTC del 14 de noviembre**. La [evidencia estructurada](../quality/evidence/135-operacion.json) conserva los dos resultados.

## Web: compilada y sin promoción

Se empaquetó una exportación del `main` publicado, sin copiar los cuatro cambios CORS ajenos ni archivos locales de entorno. La candidata de Vercel `dpl_J9ZtQdutvopvpSfDSgLqAa4CjS3U` terminó en `READY`. Se creó con `--skip-domain`; el dominio conserva el despliegue anterior. Una petición autenticada por la herramienta del proyecto comprobó que la portada candidata sirve el título y el contenido públicos de Novvin, sin sustituirlos por el acceso SSO de Vercel. No fue un inicio de sesión en una cuenta de la red social.

La [operación pendiente de 131](131-compartidos-publicos-por-contenido-2026-11-13.md) exige activar primero la API de metadatos que consume el middleware. La inspección de solo lectura del contenedor confirmó que `public-content-share.js`, `public-share-image.js` y `public-share-policy.js` no están instalados y que el servidor no registra `registerPublicContentShareRoutes`. `sharp` 0.35.5 ya está disponible. No se promovió una candidata que depende de esos contratos ausentes. También deben contrastarse los contratos de las otras correcciones web pendientes de integración; su publicación en Git no demuestra que Ubuntu los esté sirviendo.

El siguiente trabajo es preparar y probar la superposición de los cambios de API pendientes, conservando esta corrección y las funciones del servidor; después comprobar los compartidos y promover la web. La candidata no acredita todavía los recorridos reales de WhatsApp ni la carga de todos los medios.

## Alcance abierto

Las pruebas generales de código de 134 —1237 web y 1111 API, con sus omisiones identificadas— y las veinte pruebas de la imagen candidata no se vuelven a sumar como pruebas nuevas de 135. Esta continuación añade evidencia operativa. El diagnóstico de chats vacíos, la proyección importada limitada globalmente, la precisión optimista, seguidores y seguidos, imágenes, grupos/comunidades, menú, asistente, archivos, tonos y densidad siguen en la matriz. No se entrega una APK nueva, no se cierra la revisión móvil ni se declara preparación integral para marketing.
