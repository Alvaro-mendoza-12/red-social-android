# 80 — Auditoría SEO y candidato local pendiente de autorización

Fecha: **27/10/2026, America/Bogota**. Algunas evidencias registran el 28/10 UTC, equivalente a la noche del 27/10 en Bogotá. Continúa la [revisión 79](79-indexacion-publica-2026-10-27.md).

**Estado de esta entrega:** correcciones en código local y pruebas ejecutadas. **Sin nuevo despliegue, push, publicación ni envío a buscadores.** El usuario exige aprobar la publicación después de revisar las regresiones. La API de Vercel confirmó conexión GitHub y rama de producción `main`; subir a esa rama puede publicar automáticamente. No se cambiaron permisos, configuración remota ni la integración Git.

Las fuentes y la documentación del candidato se sincronizaron en las dos ramas locales (`main` y `feature/live-gifts`), sin commits nuevos ni push. La modificación local previa de versión Android 168 en la segunda rama se conservó fuera de esta entrega. Las pruebas se ejecutaron sobre el árbol principal; la igualdad de los archivos revisados se comprobó por SHA-256.

## A. Diagnóstico inicial

La web es React 19/TypeScript/Vite, con prerenderizado estático de 19 páginas informativas y una aplicación interactiva independiente. El servidor usa Node/Express/PostgreSQL; el transporte público pasa por Vercel y los canales existentes. Android no necesita cambios para esta entrega. No se cambió de framework, autenticación, contratos API ni base de datos.

| Gravedad | Hallazgo y evidencia | Acción |
| --- | --- | --- |
| Alta, prevención | El pipeline compilaba la web pero no ejecutaba las pruebas SEO/HTTP/multimedia/relé de `tests/`. Un cambio podía mantener el build y romper indexación. | Puerta SEO dentro del build y batería Node en CI después de compilar. |
| Alta, privacidad preventiva | Las vistas de compartición de perfiles no expresaban una política de exclusión para buscadores. Compartir y permitir indexar son decisiones distintas; no se encontró un consentimiento independiente de indexación por propietario. | `noindex` en HTML y cabeceras de esas vistas y sus tarjetas; caché `no-store` para tarjetas de cuentas privadas. Se conserva su acceso invitado existente. |
| Media, confirmada | Las 18 páginas informativas internas con barra final no redirigen a su URL canónica. `/mensajes/` respondió 404 mientras `/mensajes` respondió 200. | Redirecciones permanentes limitadas a esas páginas, sin normalización global de API, sockets o archivos. |
| Media, mantenimiento | Sitemap y `lastmod` dependían de actualizar manualmente una lista de URL. No había huella de contenido que detectara una fecha o mapa desactualizados. | Generación a partir del registro real y estado de contenido; el build bloquea cambios no registrados. |
| Media, herramientas | IndexNow comprobaba HTTP 200, pero no canonical/noindex; carecía de historial, exclusión de duplicados, bloqueo concurrente y backoff. | Preparación por defecto; envío explícito, validación y recibos persistentes con límites. |
| Media, dependencias de desarrollo | La auditoría actualizada encontró avisos altos/medios en herramientas. | Parches compatibles de cinco dependencias, sin cambios mayores de framework. |
| Baja, compilación/UI | Dos errores de lint en componentes, además de errores sobre maquetas temporales fuera del producto. | Se elimina un cursor mutable de renderizado de mensajes, se separan opciones de apariencia y se excluyen solo directorios temporales/generados. |
| Baja, robustez | Una fecha importada inválida podía lanzar `RangeError` al construir HTML de perfil. | Se conserva la publicación y se omite únicamente la fecha inválida. |

No se encontró un `noindex` general en la portada canónica publicada, un HTML público vacío ni autenticación necesaria para las 19 páginas informativas. Las pruebas de recursos, metadatos, CSP y canonical existentes siguen siendo válidas.

## B. Qué explica y qué no explica la desaparición

La revisión previa de Search Console mostró **12 páginas indexadas, 17 clics y siete páginas descubiertas sin indexar**, con informe del 22/10. La portada figuraba «La URL está en Google». Esa evidencia no demuestra una desindexación recurrente completa del dominio. Una consulta por marca, una variante ortográfica o un buscador diferente puede dejar de mostrar un resultado aunque la URL siga indexada.

El defecto de barra final es real y puede perjudicar enlaces que apunten a esa variante. La falta de controles de publicación y de estado de contenido eran riesgos de regresión demostrables en el repositorio. **No se atribuye a ninguno de ellos una decisión histórica de Google que no se ha observado.**

Siguen sin demostrar: una exclusión por penalización, bloqueos intermitentes de bots verificados, cambio de canonical elegido por Google, errores DNS históricos o la pérdida exacta de posiciones durante uno o dos días. Para resolver esa causalidad hay que comparar el historial de cobertura/rendimiento e inspección de las URL y fechas concretas. Las sondas con un User-Agent de bot prueban acceso HTTP, no identidad real del rastreador.

La auditoría HTTP extendida sobre la producción que sigue publicada ejecutó **87 controles: 69 aprobados y 18 fallidos**, estos últimos correspondientes a la falta de redirección de las variantes con barra final. No se presentan como aprobados ni como arreglados en producción; la corrección está preparada localmente.

## C. Cambios realizados

- `scripts/public-page-model.mjs` comparte entre prerenderizado y política SEO los mismos componentes, texto, imágenes y JSON-LD. No hay contenido diferente por User-Agent.
- `scripts/public-sitemap.mjs` y `scripts/public-seo-state.json` registran huellas del contenido significativo y de sus imágenes. Una recompilación sin cambios conserva `lastmod`. Se rechazan fechas imposibles/futuras, duplicados, URL con parámetros, rutas retiradas y tamaño superior al protocolo. Con 19 URL no hace falta dividir el sitemap; el límite impide publicar uno sobredimensionado.
- `scripts/update-public-seo-policy.mjs` detecta el contenido modificado, actualiza las huellas y los hashes CSP y genera el mapa desde el registro. `npm run seo:config` es la preparación explícita; `npm run build` genera el sitemap del artefacto y falla si el estado no coincide. No se cambian fechas por cada despliegue.
- `scripts/check-built-seo.mjs` bloquea HTML o cabeceras públicas `noindex`, canonical incorrecto, metadatos duplicados, robots bloqueante, recursos faltantes, CSP incompatible, rutas sin reescritura/redirección, 404 falsas y pérdida de la aplicación privada.
- Ambos `vercel.json` reciben 18 redirecciones de barra final con patrón explícito `(/)`. La configuración no cambia globalmente las barras finales, para conservar `/realtime/`, `/socket.io/`, llamadas, LIVE y las URL firmadas.
- `scripts/indexnow-notifier.mjs` añade preparación sin POST, validación del HTML publicado, historial por huella, bloqueo local concurrente, límite entre intentos, `Retry-After`, tres intentos máximos para respuestas transitorias y distinción entre HTTP 200 y 202. Una eliminación exige un recibo previo de URL pública y HTTP 404/410. El historial queda fuera de Git y Vercel. Se reconoce que un timeout después de un POST puede dejar un resultado externo incierto; no se promete entrega exactamente una vez.
- `scripts/serve-seo-candidate.mjs` sirve el artefacto solo en `127.0.0.1`, reproduce las rutas necesarias para QA y bloquea API. No conecta pruebas de interfaz a datos productivos. Es una herramienta de prueba, no un servidor nuevo de producción.
- `package.json` integra la puerta en el build y `test:node`; `service-ci.yml` ejecuta la batería después de compilar. No contiene pasos nuevos de publicación ni cron.
- Se conservan nombres, contenido y funciones de las páginas existentes. No se añadieron páginas vacías, backlinks, texto oculto, tráfico artificial ni valoraciones inventadas.

### Contenido social y privacidad

Las áreas `/app`, `/explore`, `/u`, conversaciones e invitaciones conservan su política de exclusión. Las pruebas de API verifican autorización y las de compartición comprueban que no se publiquen campos privados, perfiles no disponibles ni publicaciones restringidas. El indicador de cuenta privada mantiene la lógica existente: no se muestran sus publicaciones en la vista invitada. No se afirma que `robots.txt` sustituya permisos.

No se habilitó indexación masiva de cuentas o publicaciones. Una futura `ProfilePage` requiere consentimiento/control de búsqueda, URL canónica estable, retirada al cambiar privacidad y pruebas del contenido público real. No existe ahora una página pública de foro que justifique `DiscussionForumPosting`; añadir ese marcado a una guía informativa sería incorrecto. El feed interactivo conserva su funcionamiento. Su paginación para indexar contenido generado por usuarios depende primero de establecer ese contrato público y de retirada, y no se simula con URL vacías.

## D. Pruebas y rendimiento

| Comprobación ejecutada | Resultado |
| --- | --- |
| Compilación de producción local, TypeScript y puerta SEO | Aprobados; 19 páginas públicas completas. |
| Regresión Jest web completa | **790 aprobadas en 144 suites**, cero fallos. Incluye autenticación, mensajes, multimedia, perfiles y navegación cubiertos por la batería existente. |
| Batería Node web | **93 aprobadas**, cero omisiones/fallos: HTTP de todas las páginas, redirects, medios, sockets, IndexNow y fallos SEO inyectados. |
| Lint web | Antes: 11 errores/9 advertencias, incluyendo maquetas temporales. Después: **0 errores/7 advertencias** anteriores en otros módulos. No se ocultaron reglas del código de producción. |
| API completa con dependencias del lockfile | **940 casos: 929 aprobados, 11 omitidos, cero fallos**. Las omisiones no cuentan como aprobadas. |
| Compartición de perfiles | Seis casos aprobados, incluida la fecha importada inválida y acceso real HTTP a una vista/tarjeta sin indexación ni caché privada. |
| Auditoría npm web completa | Antes: 14 avisos (10 bajos, dos medios, dos altos). Después: **nueve bajos**, cero medios/altos/críticos. Los bajos de herramientas permanecen documentados; no se forzó una bajada mayor de Jest/PWA. |
| Auditoría npm web de producción | **Cero avisos conocidos** en la consulta realizada. |
| IndexNow real en esta entrega | Solo `--dry-run`: `submitted:false`; pruebas de POST y errores usan respuestas simuladas, sin envío externo. |

La primera ejecución completa de API falló porque `node_modules` tenía Nodemailer **10.0.10**, mientras el lockfile ya fija **10.1.4**. Se ejecutó `npm ci`, sin cambiar el lockfile del backend; la batería completa posterior aprobó. No se atribuye ese fallo a un cambio SEO ni se oculta el primer resultado.

Se aplicaron parches web dentro de versiones compatibles: Vite **7.3.7**, fast-uri **3.1.9**, qs **6.17.0**, serialize-javascript **7.1.4** y Zod **4.6.7**, todos en herramientas de desarrollo. El build y la regresión completa se repitieron después.

Lighthouse **13.6.0**, perfil móvil y Chromium aislado:

| Entorno | Rendimiento | Accesibilidad | Buenas prácticas | SEO | LCP | CLS | TBT |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Portada publicada | 100 | 100 | 100 | 100 | 1,166 s | 0 | 8 ms |
| Candidato local | 100 | 100 | 100 | 100 | 0,923 s | 0 | 10,5 ms |

Son muestras de laboratorio. La diferencia de red entre producción y localhost impide atribuir la diferencia de LCP al código. Se conserva el resultado sin regresión observada, no se anuncia una mejora causal. Las páginas informativas cargan **cero bytes de JavaScript de aplicación**. Los bundles grandes de la aplicación autenticada permanecen fuera de ese recorrido y requieren su propia optimización medida.

No se midió INP real de usuarios ni se certifican los Core Web Vitals de campo. El objetivo se evalúa en el percentil 75 con datos suficientes; TBT no sustituye INP. Esta auditoría tampoco prueba llamadas/LIVE entre dispositivos ni todos los recorridos físicos Android.

## E. Estado por buscador

| Motor | Configuración y avisos comprobados | Lo que no está confirmado |
| --- | --- | --- |
| Google | Propiedad verificada; 12 páginas indexadas en el informe revisado. Sitemap de 19 páginas aceptado en la revisión 79. | Nueva recaptura, posiciones y las siete pendientes. La solicitud individual anterior agotó cuota; no se usó Indexing API genérica. |
| Bing | Propiedad verificada; 19 URL y nueve avisos IndexNow aceptados anteriormente. | Rastreo e indexación posterior. El formulario de sitemap dio error externo, con alternativa URL Submission aceptada. |
| Yahoo | La documentación oficial remite la clasificación a Bing. | Aparición concreta de Novvin; no se inventa un envío independiente. |
| DuckDuckGo | Estándares y HTML accesibles; fuentes propias y enlaces tradicionales mayoritariamente de Bing según su documentación. | Rastreo/indexación de Novvin y plazo de propagación. |
| Brave Search | Índice propio y acceso compatible con sus requisitos comunes de rastreo. | Posición/rastreo nuevo. No se utilizó el formulario de retirada para solicitar indexación ni se activó telemetría WDP. |
| Yandex | Sitemap común y protocolo IndexNow compatibles; Yandex documenta su soporte. | Propiedad Webmaster verificada e indexación concreta. No se creó una cuenta ni se hicieron envíos nuevos. |
| Ecosia | Páginas, imágenes y sitemap usan estándares comunes accesibles. | Proveedor efectivo para cada consulta y presencia concreta; no se promete propagación automática ni se afirma un mecanismo de envío no verificado. |

## F. Pasos pendientes y autorización

1. Revisar este candidato y sus diferencias locales. Está preparado para probarse en una versión protegida de Vercel, pero **no se ha publicado** por la instrucción del propietario.
2. Con autorización, comprobar allí los 18 redirects en el enrutador real de Vercel, cabeceras, llamadas a API, login, archivos y WebSockets. El emulador HTTP local no acredita por sí solo el comportamiento del CDN. Después aprobar la promoción productiva y la corrección de vistas de compartición en el servidor.
3. Tras publicar y pasar los 87 controles HTTP: autorizar los avisos de URL efectivamente modificadas. `node scripts/submit-indexnow.mjs /ruta --dry-run` prepara; `--submit` envía. No están conectados a cada build automáticamente.
4. Para investigar una desaparición histórica: revisar consultas, fechas y URL exactas en cobertura/rendimiento/inspección de Google y Bing. Si se necesita API oficial, configurar OAuth de lectura; no se extrajeron credenciales del navegador.
5. Para Yandex, verificar una propiedad con la cuenta del propietario si se desea usar su consola. Un dominio propio puede evaluarse aparte; no se compró ni cambió dominio y no se promete ranking por ese cambio.

## G. Prevención y reversión

El build falla antes de desplegar si aparece un bloqueo público, desaparece una ruta o cambia el contenido sin regenerar su política. Las pruebas introducen errores de forma deliberada y comprueban su rechazo. Las páginas privadas y las URL desconocidas siguen teniendo pruebas independientes. Las reejecuciones de `seo:config` sin contenido nuevo conservan huellas y fechas.

No se activó una automatización, cron, alertas por correo ni monitor con coste. El comprobador HTTP puede ejecutarse manualmente o conectarse a una infraestructura autorizada tras la publicación.

Para revertir este candidato basta retirar su diff antes de publicar; no hay migraciones de base ni cambios Android. La revisión 78 identifica el despliegue anterior utilizable si luego se autoriza publicar y aparece una regresión. No se restauraría una base antigua para revertir SEO.

## H. Límites de la conclusión

Se corrigieron defectos comprobables y se prepararon protecciones adicionales. Las pruebas disponibles no observaron regresiones, pero no demuestran el funcionamiento de toda opción en cualquier dispositivo o sesión real. Quedan las omisiones indicadas, siete advertencias de lint, nueve avisos bajos de herramientas y la comprobación del candidato en Vercel antes de promoverlo.

El código no garantiza primera posición, autocorrección de marca, sitelinks, indexación universal ni una fecha de aceptación. No se afirma que la desaparición reportada haya quedado causalmente explicada. **La entrega es un candidato local probado, pendiente de autorización para publicación.**

Fuentes primarias: [sitemaps y lastmod de Google](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap), [canonicalización](https://developers.google.com/search/docs/crawling-indexing/consolidate-duplicate-urls), [configuración Vercel](https://vercel.com/docs/project-configuration/vercel-json), [Web Vitals](https://web.dev/articles/vitals), [Yahoo/Bing](https://uk.help.yahoo.com/kb/SLN35619.html), [DuckDuckGo](https://duckduckgo.com/duckduckgo-help-pages/results/sources), [Brave](https://search.brave.com/help/brave-search-crawler), [Yandex/IndexNow](https://yandex.ru/support/webmaster/ru/indexing-options/index-now.html).

Evidencias locales fuera de Git: `artifacts/seo-deep-audit-20261027/`. No publicar sus respuestas privadas de configuración ni logs brutos.
