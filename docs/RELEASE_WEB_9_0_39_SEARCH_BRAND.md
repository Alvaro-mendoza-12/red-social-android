# Novvin Web 9.0.39 — nombre del sitio y búsquedas de marca

Fecha: 16 de octubre de 2026. Web: https://red-social-android.vercel.app/ .

## Lo observado

- En la captura del usuario figura **Vercel**, el proveedor de alojamiento, encima del título de Novvin. Google elige automáticamente ese nombre del sitio. La portada actual ya indica Novvin en el título, encabezado, `og:site_name` y datos estructurados. El título de la captura coincide con una presentación anterior; no se afirma que el nombre Vercel sea texto nuevo de la aplicación.
- La segunda captura consulta **Novvyn**, con «y». Google ofrece resultados de otras entidades. No se considera que esas entidades o sus productos sean Novvin, ni se puede imponer cómo corregirá el buscador una palabra.
- Una comprobación real del término **Novvin**, sin limitar la búsqueda al dominio, encontró la web en la primera página de **Brave Search**, **Bing** y **DuckDuckGo**. En esta observación DuckDuckGo la mostró primero y Bing segundo, después de Google Play. La posición de Brave no se certifica; aparece después de otras entidades y un bloque de videos.
- Las comprobaciones se realizaron desde el navegador Brave. El navegador utilizado y el servicio de búsqueda son cosas distintas: se puede consultar Google, Bing o DuckDuckGo desde Brave. No se extrapolan estos resultados a otros países, preferencias o fechas.

## Cambios

1. Se conserva **Novvin** como nombre preferido de WebSite. Las alternativas, en orden, son **Novvin Web** y el hostname real **red-social-android.vercel.app**, conforme al recurso de respaldo recomendado por Google. No se declara Vercel como nombre de marca ni Novvyn como otra empresa de Novvin.
2. La explicación «¿Buscabas Novvyn, Noving, Nowing o NoWin?» queda abierta y visible en la portada, con enlaces para entrar y conocer la web. Indica expresamente el nombre Novvin, N-O-V-V-I-N.
3. La guía `/novvin-web` incluye una sección visible para quienes escribieron Novvyn con «y». No se crean páginas duplicadas para cada error ni listas ocultas de palabras.
4. Se actualizan las firmas de los datos estructurados en ambas políticas CSP y las fechas de modificación del sitemap. La compilación comprueba que los datos no queden bloqueados por CSP. No se amplía el permiso para scripts en línea.
5. La comprobación HTTP de producción exige nombre y `og:site_name` coherentes, además de las alternativas ordenadas.

## Validación

- Compilación de producción correcta.
- 450 pruebas web en 74 suites y 36 pruebas SEO, todas aprobadas.
- Sin errores ni avisos de lint en los dos componentes TypeScript modificados.
- La aceptación del despliegue, la comprobación HTTP, las vistas responsivas y las notificaciones a buscadores se registran en `audit/2026-10-07-integral/web-9039-final.json`.
- Capturas reales en `audit/2026-10-07-integral/output/playwright/web-seo-9039/`: `brave-novvin-result.jpg`, `bing-novvin.jpg` y `duckduckgo-novvin.jpg`.

## Límites que siguen vigentes

Publicar las señales correctas no cambia inmediatamente un resultado ya generado. Google no permite cambiar manualmente su nombre seleccionado, y puede necesitar días o semanas para procesar la página. No se promete posición fija ni aparición con todas las variantes. Brave, Bing y DuckDuckGo mantienen sus propios resultados.

El certificado de los recorridos web y sus pendientes generales se mantiene en [la auditoría de 9.0.38](WEB_PRODUCTION_AUDIT_2026_10_16.md). Esta entrega modifica únicamente señales y contenido público de búsqueda; no vuelve a certificar funciones móviles, multimedia histórica o redes físicas.

Referencias oficiales:

- [Google: nombre del sitio, nombres alternativos y hostname de respaldo](https://developers.google.com/search/docs/appearance/site-names).
- [Google: solicitudes de rastreo y plazos](https://developers.google.com/search/docs/crawling-indexing/ask-google-to-recrawl).
- [Brave: descubrimiento y rastreo de páginas](https://search.brave.com/help/brave-search-crawler).

