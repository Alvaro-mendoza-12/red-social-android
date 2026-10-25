# Publicación e indexación de Novvin — 23 de octubre de 2026

## Despliegue

- Sitio: https://red-social-android.vercel.app/
- Proyecto Vercel: `alvaro-s-projects1/red-social-android`
- Entorno: producción, estado `READY`.
- Deployment ID: `dpl_4VQjcXEvQ1AZZs8kJe5JsVpMpVXH`
- Alias de producción asignado por Vercel: `https://red-social-android.vercel.app`
- La portada devuelve HTTP 200 y contiene la etiqueta pública de verificación de Bing. La respuesta no incluye `noindex`.
- La compilación se generó en Vercel desde `NoWing-web`; el build terminó correctamente. Vite emitió el aviso existente de un chunk JavaScript de aproximadamente 1,101 kB.

## Comprobación pública

`NoWing-web/scripts/check-production-seo.mjs` se ejecutó contra producción después del despliegue: 63 de 63 comprobaciones aprobadas. Revisa páginas públicas, contenido HTML sin depender de JavaScript, canonicals, sitemap, robots, metadatos, datos estructurados, CSP, imágenes de marca, rutas privadas con `noindex`, respuestas 404 y acceso HTTP con distintos user agents. No mide ranking, Core Web Vitals de campo ni aceptación interna de los buscadores.

## Estado por buscador

### Google

- Search Console ya tenía la propiedad verificada.
- El sitemap `https://red-social-android.vercel.app/sitemap.xml` aparece como correcto, fue leído por Google el 22 de octubre y reportó 19 páginas descubiertas.
- La inspección de la portada indica que está en Google y el test en vivo del 23 de octubre indicó que puede indexarse.
- La solicitud manual de rastreo de la portada fue rechazada por la cuota diaria de Search Console. No se considera enviada.
- La inspección de `/que-es-novvin` también indicó que está indexada y que Google eligió la canonical esperada.
- El informe de rendimiento de tres meses mostró una muestra pequeña: 11 clics, 19 impresiones, CTR de 57.9% y posición media de 2.9 en los días con datos del 15 al 21 de octubre. Esto no garantiza aparecer igual para cada persona, país o consulta.

### Bing y otros motores que usan IndexNow

- La metaetiqueta `msvalidate.01` de la propiedad se publicó en la portada de producción.
- La notificación IndexNow de la portada se aceptó con HTTP 200. Este recibo confirma el aviso enviado, no que la URL ya esté indexada.
- La propiedad de Bing Webmaster Tools sigue pendiente de verificación; el navegador autenticado quedó deshabilitado en este entorno, así que no se pudo pulsar ni comprobar el botón de verificación. Bing puede descubrir el sitemap publicado en `robots.txt`, pero la confirmación de propiedad y el registro visible del sitemap siguen pendientes.

## Interpretación

Un navegador no mantiene un índice propio del sitio: los resultados dependen del buscador configurado en ese navegador, la consulta, la ubicación, el idioma y el tiempo de rastreo. La evidencia de Search Console confirma que Novvin ya aparece en Google; no confirma resultados idénticos en todos los dispositivos ni que una IA vaya a recomendar la web. Los buscadores deciden cuándo rastrear e indexar, y ninguna metaetiqueta garantiza una posición.

Las rutas privadas e interactivas siguen excluidas mediante `noindex`; el sitemap conserva solo las 19 páginas públicas.

## Evidencias locales

- `artifacts/seo-vercel-deploy-20261023.log`: build y estado del despliegue Vercel.
- `artifacts/seo-production-20261023-after-deploy.json`: 63 comprobaciones HTTP posteriores al despliegue.
- `artifacts/indexnow-production-20261023.json`: recibo de notificación IndexNow.
- `artifacts/google-index-quota-20261023.png`: límite diario mostrado por Search Console.
- `artifacts/google-search-performance-20261023.png`: muestra del informe de rendimiento.
