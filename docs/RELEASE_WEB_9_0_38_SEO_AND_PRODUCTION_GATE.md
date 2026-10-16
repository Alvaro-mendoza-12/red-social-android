# Novvin Web 9.0.38 — SEO y compuerta de producción

## Cambios

- Portada y once guías públicas como HTML completo, con la misma presentación para personas y rastreadores. No descargan la aplicación privada, autenticación, llamadas ni conexiones al backend. Usan una hoja de estilo pública de menos de 15 KB.
- Nombre de marca coherente, títulos y descripciones propios, URL canónica, Open Graph, tarjeta social de 1200 × 630, favicon real e iconos del manifiesto. Se conserva el logo oficial.
- Datos estructurados de WebSite, Organization y WebPage; las guías incluyen BreadcrumbList. La política CSP admite solo las firmas de esos datos, sin habilitar scripts en línea de forma general.
- El build falla si una firma de datos estructurados queda fuera de la política de seguridad. El comando npm run seo:config actualiza deliberadamente las firmas al editar metadatos; npm run test:seo valida el resultado.
- Se eliminan las reescrituras indiscriminadas a la aplicación. Las URL públicas inexistentes deben responder 404 real. Las rutas conocidas de aplicación conservan su shell y noindex. Los HTML duplicados redirigen permanentemente a su URL canónica.
- El manifiesto deja de anunciar iconos, capturas y manejadores de archivos o protocolos que no existen. Los accesos declarados apuntan a Mensajes, Videos, Voz y Social. No se afirma disponibilidad sin conexión desde cero.
- Se prepara la verificación de la propiedad oficial en Google Search Console mediante la etiqueta pública facilitada por la cuenta de Novvin. Esa etiqueta debe conservarse para mantener la verificación.
- Se añade npm run check:production-seo, una auditoría de solo lectura sobre el despliegue real: HTML, canonicals, datos estructurados, CSP, cabeceras, 404, redirecciones, exclusión de pantallas privadas, iconos, imagen social y manifiesto.

## Comprobaciones locales

438 pruebas web en 71 suites; 33 pruebas de SEO/HTML; 9 pruebas de multimedia. Compilación de producción correcta. Sin errores ni avisos de lint en los archivos TypeScript modificados. npm audit de dependencias de producción no comunica vulnerabilidades.

Navegador real: 48 combinaciones de las doce páginas públicas a 320, 390, 768 y 1440 píxeles; imágenes cargadas, sin desbordamiento horizontal ni peticiones de JavaScript o backend de la aplicación. Las peticiones ajenas de una extensión instalada del navegador se excluyen y registran aparte; la medición de rendimiento usa un navegador aislado. Otras 21 comprobaciones de formularios y 11 de regresión de feed/mensajes verifican que el nuevo punto de entrada sigue abriendo la aplicación.

## Criterios para cerrar la entrega

Después del despliegue se repiten las comprobaciones del paquete publicado y el control HTTP. La aceptación de Search Console, el sitemap, la inspección de URL y la notificación IndexNow se registran en la auditoría local; un HTTP 200 de esos servicios no se interpreta como indexación garantizada.

El estado consolidado de producción se documenta aparte. La validación técnica de esta entrega no equivale a una certificación externa, una auditoría de penetración, una prueba de carga ni aceptación de llamadas y notificaciones en todos los dispositivos.

## Buscadores y límites

Se documentan 60 consultas relacionadas con las doce páginas públicas en WEB_SEO_SEARCH_INTENTS_2026_10_16.md. No se crean cientos de variaciones de palabras ni opiniones inventadas. Se mantienen los nombres alternativos visibles que ya existían y la marca preferida Novvin. Google genera automáticamente el nombre visible, el icono, el título y los enlaces secundarios; su implementación correcta no garantiza aparición, posición ni tiempo de rastreo.

Referencias oficiales:

- [Nombre del sitio](https://developers.google.com/search/docs/appearance/site-names)
- [Favicon en resultados](https://developers.google.com/search/docs/appearance/favicon-in-search)
- [Datos de organización](https://developers.google.com/search/docs/appearance/structured-data/organization)
- [Enlaces secundarios](https://developers.google.com/search/docs/appearance/sitelinks)
- [Solicitud de rastreo](https://developers.google.com/search/docs/crawling-indexing/ask-google-to-recrawl)
- [Core Web Vitals](https://developers.google.com/search/docs/appearance/core-web-vitals)
- [IndexNow](https://www.indexnow.org/documentation)
