# 79 — Indexación pública y presentación de Novvin

Fecha: **27/10/2026, America/Bogota**. Web: `https://red-social-android.vercel.app`. Publicación y conexiones en la [revisión 78](78-capacidad-host-y-acceso-web-2026-10-27.md).

## Alcance publicado

Las **19 páginas públicas canónicas** incluyen inicio, qué es Novvin, acceso web, mensajes, vídeos, salas de voz, descarga, Social, Momentos, comunidades, juegos, asistente, funciones y seis guías de ayuda. Entregan HTML completo sin esperar JavaScript, con título y descripción propios, encabezado principal, enlaces internos, canonical, datos estructurados y rastreo permitido.

La portada ahora se titula **«Novvin | Red social, mensajes y videos»** y describe el producto en español. Se retiró de la portada y de la guía web el párrafo de variantes ortográficas que el resultado mostrado por el usuario usaba como descripción. La guía específica conserva esa ayuda en contexto. Sin nombres falsos de organización, valoraciones inventadas ni acciones de búsqueda inexistentes.

Se añadieron **seis PNG públicos de 1.200 × 800** sobre chats, grupos, comunidades, vídeos, Social y Momentos, con SVG reproducibles. Son ilustraciones con datos de ejemplo identificados, no capturas de conversaciones reales. Están insertadas como imágenes HTML con texto alternativo, dimensiones y pies, declaradas en datos estructurados y sitemap de imágenes. `/funciones` muestra las seis, las guías sus vistas correspondientes y la portada tres. Sin rediseñar pantallas autenticadas.

El sitemap recoge las 19 URL reales y las imágenes. Sus fechas reflejan cambios, sin renovar artificialmente todo. Se conservaron logo, favicon, imagen social, manifest y pruebas de propiedad. `robots.txt` permite páginas e imágenes públicas y excluye API y archivos privados. Las pantallas de sesión, conversaciones, invitaciones y aplicación mantienen `noindex`; los endpoints privados siguen autenticados. No se generaron índices de conversaciones ni URL de medios firmadas.

## Evidencia técnica

Compilación de producción y **66 pruebas** de páginas/relé aprobadas. El comprobador público ejecutó **69 controles, todos aprobados**: metadatos únicos, HTML sin JavaScript, canonical, redirecciones permanentes, JSON-LD permitido por CSP, 404 reales, exclusión de pantallas privadas, PNG/dimensiones, favicon, verificación y sitemap.

Sondas HTTP con identificadores Googlebot, bingbot, OAI-SearchBot y ChatGPT-User recibieron la guía completa. Demuestran accesibilidad HTTP, no una visita real ni aceptación del buscador.

## Estado de los buscadores

Google Search Console mostró **12 páginas indexadas y 17 clics**. El informe, con datos del 22/10, mostró siete «Descubierta: actualmente sin indexar»: privacidad y seguridad, descargar, juegos, Novvin Web, salas de voz, Social y vídeos. La validación figura iniciada el 26/10. La portada está indexada; el informe no demuestra que Google haya recapturado esta publicación.

IndexNow aceptó con **HTTP 200** el aviso de nueve páginas cambiadas: inicio, Novvin Web, mensajes, comunidades, vídeos, Social, Momentos, descargar y funciones. Es acuse de recibo, no confirmación de indexación. No se enviaron URL privadas ni rutas sin cambios.

El 27/10 se completó la verificación de propiedad pendiente en Bing mediante la etiqueta existente y se accedió al panel del sitio. Bing informa que sus datos pueden tardar hasta 48 horas en reflejarse. La solicitud individual de recaptura de portada en Google devolvió **«Cuota superada»**; no fue aceptada y no se insistió ni se intentó eludir reCAPTCHA. Las pruebas de consola permanecen fuera de Git.

Google aceptó el reenvío de `sitemap.xml` el **27/10/2026**: el panel confirma «Se ha enviado el sitemap correctamente», estado «Correcto» y 19 páginas descubiertas. Su última lectura indicada sigue siendo el 26/10; no se presenta como una nueva indexación ya realizada. El envío del mismo sitemap en Bing devolvió dos veces «An unexpected error occurred on the server side», pese a estar verificada la propiedad y accesible el XML. Se utilizó la alternativa oficial **URL Submission**, que aceptó las **19 páginas públicas**: listado 1–19 de 19, 19 enviadas hoy y cuota restante 81 de 100. IndexNow también quedó aceptado. El error específico del formulario de sitemap de Bing permanece como limitación externa, sin bloquear estas notificaciones.

Los estados **publicado**, **notificado**, **rastreado** e **indexado** se registran por separado. No se programó un monitor recurrente ni se aceptaron servicios de pago o telemetría adicional.

## Límites y mantenimiento

Google, Bing y Brave deciden qué indexan, texto, enlaces secundarios, correcciones ortográficas y orden. Estas señales facilitan su trabajo; no garantizan aparecer primero ni igualar el resultado de Facebook o Instagram. Cada buscador mantiene su índice y plazos, independientemente del navegador.

`/app`, `/explore` y `/u` siguen siendo la aplicación interactiva. No se convirtió en páginas estáticas ni se publicó contenido de cuentas en esta entrega. Ampliar a perfiles o publicaciones públicas exige respetar visibilidad, eliminaciones y canonical del servicio de compartición; no basta con quitar `noindex` a toda la aplicación.

Para cambios públicos: editar la página, ejecutar `scripts/update-public-seo-policy.mjs` con las URL cambiadas, compilar, probar `tests/public-home.test.mjs`, publicar, ejecutar `scripts/check-production-seo.mjs` y después `scripts/submit-indexnow.mjs`. Los JSON-LD nuevos requieren actualizar hashes CSP en ambos `vercel.json`. No repetir envíos sin cambios.

Referencias: [imágenes en Google](https://developers.google.com/search/docs/appearance/google-images), [descripciones](https://developers.google.com/search/docs/appearance/snippet), [títulos](https://developers.google.com/search/docs/appearance/title-link), [IndexNow](https://www.bing.com/indexnow/getstarted), [rastreo de Brave](https://search.brave.com/help/brave-search-crawler).
