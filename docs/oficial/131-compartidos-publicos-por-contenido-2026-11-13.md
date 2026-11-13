# 131 · Vistas previas públicas del objeto compartido

13 de noviembre de 2026, America/Bogota. Corrección de código y validación local, separadas del despliegue en Ubuntu/Vercel y de una comprobación física en WhatsApp.

## Defecto y resultado

La lectura HTTP del sitio activo confirmó el defecto de las capturas: los enlaces históricos `/u/:id` y `/app/feed/post/:id` entregaban el título y la imagen genéricos de la portada. `/p/:id` ya tenía metadatos de perfil propios, pero su tarjeta visual destacaba la marca. Ese diagnóstico no implica haber actualizado el servidor activo.

El middleware sirve a los rastreadores de compartidos el HTML público correspondiente al perfil, publicación o video. Las personas conservan sus destinos de aplicación. Se incluyó expresamente `/app/reels/:id`, la ruta real de detalle de videos; las formas antiguas de enlace conservan resolución para rastreadores. El origen de API permanece fijo en el servidor propio y se retiran cookies, Authorization y parámetros de consulta de las peticiones de metadatos redirigidas. Una cabecera de rastreador selecciona presentación y nunca concede acceso a datos privados.

Las tarjetas de perfil muestran nombre, identificador elegido, biografía pública y avatar disponible. Las publicaciones muestran autor, texto y foto pública; los videos, título y miniatura disponible. Las tarjetas son JPEG de 1200 × 630. Si no hay una imagen pública válida, conservan datos del objeto en vez de reutilizar la portada genérica. Los enlaces nuevos usan `novvinapp.com` y un parámetro de versión de vista previa. Los QR de perfil conservan `/u/:id`, que reconocen los clientes Android ya instalados; ampliar su lector a otras rutas es trabajo móvil independiente.

El HTML privado de la SPA deja de anunciar la portada mediante Open Graph, Twitter o canonical. Las páginas públicas estáticas mantienen sus metadatos propios. Los campos Open Graph siguen la [especificación oficial](https://ogp.me/); la selección HTTP se implementa con el [middleware y las reescrituras de Vercel](https://vercel.com/docs/routing/rewrites). Los rastreadores pueden conservar tarjetas ya obtenidas: estos cambios y los parámetros nuevos no garantizan borrar las cachés externas existentes.

## Privacidad y límites de recursos

Se reutilizan los lectores públicos del backend y sus reglas de visibilidad. No se publican posts privados, para seguidores o sensibles, ni contenido de cuentas privadas, ocultas o restringidas. Se comprueban las identidades locales y heredadas para suspensión y eliminación. Las mismas comprobaciones protegen la petición directa de la imagen. Un error de consulta de seguridad impide servir una vista parcial.

Las imágenes se solicitan sin credenciales únicamente al origen HTTPS de almacenamiento propio. Los localizadores heredados se canonizan a ese origen sin contactar con el host anterior. Se rechazan redirecciones, imágenes SVG, rutas privadas y parámetros de firma; se limita la descarga a dos MB, 2,5 segundos y doce megapíxeles de entrada, y se recodifica el raster. Se cancela la lectura descartada. Se reutiliza el límite distribuido por IP para los endpoints públicos de contenido: sesenta peticiones por minuto. Las respuestas no se almacenan en la caché HTTP controlada por esta implementación. Esto no constituye un ensayo de capacidad masiva.

## Evidencia

- API: 22 pruebas focalizadas aprobadas de metadatos, tarjetas, escapado, privacidad, identidades y descarga acotada. La suite general aprobó 1106 pruebas y omitió 24 por dependencias de entorno, sin fallos; el ajuste final de la ruta de video se volvió a comprobar focalizadamente.
- Web: 42 pruebas en seis suites aprobadas de enlaces, interpretación de videos, diálogos de compartido, middleware real de Vercel, aislamiento de cookies y selección de rastreadores. TypeScript y lint complementan estos controles.
- Nueve recorridos HTTP/PostgreSQL reales: subida autenticada y lectura pública de raster; tarjeta de avatar; post con foto; privacidad y sensibilidad; cambio público → privado; privacidad del autor; suspensión; video con miniatura; eliminación. Se comprueba un píxel del raster subido dentro de cada tarjeta, además de dimensiones, tipo, contenido y cabeceras. La fila de video es una fixture de metadatos: no prueba codificación ni reproducción de video.
- El [resultado HTTP/PostgreSQL](../quality/evidence/131-http-postgres.json) y las tarjetas de [perfil](../quality/evidence/131-perfil.jpg) y [publicación](../quality/evidence/131-publicacion.jpg) contienen solamente datos ficticios. El raster naranja identifica la fixture técnica; no se presenta como una foto de usuario real ni como material de marketing.

Script reproducible: `infrastructure/local-fallback/api/scripts/verify-public-sharing-local.js`. Exige una API y una base de datos de laboratorio en loopback mediante `QA_API_URL` y `QA_DATABASE_URL`, y escribe en `QA_SHARING_OUTPUT`. Crea una cuenta sintética nueva y conserva sus fixtures en ese laboratorio. En esta ejecución, un preload exclusivamente de pruebas mapeó las descargas HTTPS del almacenamiento propio al servidor local; no certifica TLS externo ni R2 productivo. La [evidencia estructurada](../quality/evidence/130-131-resultados.json) identifica las fuentes y comprobaciones finales.

## Publicación y operación pendiente

La incorporación se prepara para las dos ramas estables con árboles iguales, preservando los cambios ajenos. La API debe desplegarse antes del middleware que consume los endpoints nuevos. Antes de operar: identificar imagen y revisión actuales de Ubuntu, guardar configuración y respaldo, construir la candidata con la revisión publicada, comprobar salud y endpoints públicos, y conservar la imagen anterior para reversión. No hay migración de esquema en este bloque. En Vercel se debe verificar después cada ruta con agente de rastreador y de navegador y comprobar que los assets públicos siguen disponibles.

No se hizo ese despliegue, no se generó una APK ni se envió contenido de usuarios reales. Quedan WhatsApp físico, cachés externas, interoperabilidad Android, carga concurrente y recuperación operativa. No se declara cerrado el requisito completo de compartidos ni la preparación integral para marketing masivo.
