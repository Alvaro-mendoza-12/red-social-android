# Novvin Web 9.0.38 — auditoría de producción

Fecha: 16 de octubre de 2026. Sitio: https://red-social-android.vercel.app/ .

## Dictamen

La entrega 9.0.38 está publicada y aprobada para los recorridos web comprobados en esta auditoría. No quedan fallos abiertos en esos recorridos. La certificación integral de toda la plataforma sigue pendiente: esta entrega no convierte las pruebas de componentes en aceptación de todas las funciones, dispositivos, redes y datos históricos.

Código desplegado: `d6bf4a6e87e9c2d6d93cbaafc176bd39ad4f2d60`. Despliegue Vercel de producción `dpl_2sNbayKwuyAouBHDCprVgwMKLo7S`, estado READY. URL inmutable: https://red-social-android-6yqd5qn3r-alvaro-s-projects1.vercel.app . Los cambios posteriores de esta entrega son documentación; no cambian el paquete publicado. Android permanece en 9.0.34 (131); esta auditoría no declara una nueva certificación móvil.

## Apartados verificados, uno por uno

| Nº | Apartado | Resultado y alcance |
|---|---|---|
| 1 | Publicación y conexión | **Aprobado.** Las 86 dependencias JS/CSS del paquete público responden 200 con su MIME correcto. Las rutas públicas y privadas conocidas abren; las inexistentes responden 404. TLS válido, API saludable y lista; la ruta administrativa rechaza acceso anónimo con 401. También se verificó `/ready` mediante la IP pública del Funnel con validación TLS. |
| 2 | SEO técnico | **Aprobado.** Doce páginas públicas con HTML completo, títulos y descripciones propios, URL canónica, datos estructurados, imagen social, logo, favicon, robots y sitemap. Las pantallas privadas llevan noindex. 44 controles HTTP sobre el despliegue y 33 pruebas de SEO pasaron. |
| 3 | Entrega a buscadores | **Completada; evolución externa.** Propiedad verificada en Google, portada indexada, prueba en vivo apta para indexación y sitemap Correcto con 12 páginas descubiertas. Brave aceptó la portada e IndexNow aceptó las 12 URL con HTTP 200. Google no señala acciones manuales ni problemas de seguridad en sus informes. Esto no sustituye una auditoría de seguridad del producto. |
| 4 | Acceso y sesiones | **Aprobado en los casos ejecutados.** Registro real en Brave, aprobación autenticada de QR, consumo y rechazo de repetición con 409. Recarga y segunda pestaña conservan la cuenta; el bearer no se persiste en localStorage. El servidor real emite cookie HttpOnly, Secure, SameSite=None y Partitioned. Salir redirige ambas pestañas y la renovación posterior devuelve 401. La aprobación usa una cuenta temporal y API real, sin cámara física. |
| 5 | Mensajes, pendientes y archivos | **Aprobado en los recorridos reales.** Dos cuentas temporales intercambian mensajes; un envío sin conexión queda pendiente y llega automáticamente al reconectar. TXT, ZIP y PNG enviados desde la interfaz se reciben y descargan con bytes idénticos. Álbumes, favoritos y respuestas de stickers, borradores y subida ZIP de 26 segundos pasan las regresiones de navegador con API sintética. |
| 6 | Navegación y distribución | **Aprobado en las medidas registradas.** Cinco pestañas reales: mensajes, videos, social, juegos y perfil; tres cambios de foco sin recargas de documento ni renovaciones de sesión adicionales. Sin errores de ejecución ni desbordamiento horizontal. Las doce páginas públicas pasan 48 combinaciones a 320, 390, 768 y 1440 px. Los chats aprovechan el ancho hasta 3840 px en la regresión. |
| 7 | Formularios, feed y funciones sociales | **Aprobado en regresión de interfaz.** Por navegador, 21 comprobaciones de formularios y 11 de paridad: perfil/ajustes, creación de espacios, asistente e historial, ranking, solicitudes, rondas, feed con cursor sin duplicados y comentarios que siguen al video visible. Estas pruebas utilizan el paquete publicado y API sintética. En Brave, videos e imágenes sociales de invitado cargan además contra el servidor real. No se declara que todos los formularios hayan escrito datos en producción. |
| 8 | Llamadas | **Aprobado en transporte real.** Dos cuentas intercambian RTP de audio y video; funcionan silenciar, ajustes, cambio a pantalla compartida y colgar cerrando ambas conexiones. Tres tamaños de ventana sin desbordamiento. Los medios son generados para la prueba, sin capturar micrófono o cámara personales. Se corrigió y desplegó el borrado prematuro de invitaciones Ubuntu por el listener heredado de Supabase. |
| 9 | Salas de voz y directos | **Aprobado en transporte real.** Dos participantes reciben audio, se activa cámara y ambos salen. En directo restringido a seguidores de prueba, el receptor recibe audio/video, el comentario llega al emisor, cambiar a pantalla mantiene la transmisión y el emisor finaliza. Fuente audiovisual generada, mismo equipo y red. |
| 10 | Calidad y cierre | **Aprobado en el alcance medido.** 450 pruebas web en 74 suites, 33 de SEO y 9 de multimedia; compilación correcta y lint sin errores ni avisos en el código de producción modificado. npm audit de dependencias de producción: cero vulnerabilidades comunicadas. Lighthouse de laboratorio en la portada: 100 en rendimiento, accesibilidad, buenas prácticas y SEO, tanto móvil como escritorio. No equivale a métricas de campo ni a ausencia absoluta de vulnerabilidades. |

Las comprobaciones de formularios, paridad y avisos se repitieron en Chrome y en el ejecutable real de Brave. Ambos pertenecen a Chromium; esto no prueba Safari o Firefox. En esta PC, Tailscale resuelve el backend a una dirección privada: las cuentas de navegador de prueba recibieron permiso temporal de acceso a red local solo para el origen de Novvin. No se desactivó TLS ni se modificaron Shields, extensiones o preferencias del navegador personal.

## Posicionamiento observado y trabajo publicado

Una búsqueda real de **Novvin** en Google desde Brave mostró la web como **primer resultado web en esa comprobación**. Se conserva captura. No se extrapola esa posición a otros usuarios, países, fechas o variantes del nombre. El resultado todavía muestra el nombre de sitio Vercel del índice anterior; la marca, favicon y metadatos nuevos están publicados y su adopción depende del siguiente procesamiento del buscador.

Se publicaron once guías además de la portada y se documentaron **60 consultas útiles** en [WEB_SEO_SEARCH_INTENTS_2026_10_16.md](WEB_SEO_SEARCH_INTENTS_2026_10_16.md). La portada aclara las variantes Noving, Nowing, Novvyn y NoWin. Se usan contenido y enlaces visibles relacionados con la aplicación, sin miles de palabras ocultas ni páginas repetidas.

La petición manual adicional de rastreo fue rechazada por cuota diaria. El sitemap sigue aceptado. No se resolvió ni evitó ningún CAPTCHA. Google advierte que rastrear puede tardar días o semanas, que repetir solicitudes no acelera el proceso y que la inclusión no está garantizada. Los enlaces secundarios también son automáticos.

## Correcciones de esta entrega

1. Mantener estable la identidad de usuario cuando solo se renueva el token evita reiniciar consultas y pantallas. Los cambios reales de cuenta, perfil y seguridad sí se propagan.
2. Los avisos reservan su altura real; descartarlos libera el espacio entre pestañas. Los controles de video se acomodan en horizontal y el sonido no tapa la barra.
3. Una invitación de llamada válida del servidor Ubuntu ya no desaparece cuando no hay cliente Supabase. La desconexión de la cuenta conserva la limpieza correspondiente.
4. El arranque de la aplicación queda separado de las páginas públicas: estas no descargan autenticación, WebRTC ni consultas al backend. Se corrigen redirecciones, 404, manifiesto, favicon y política de datos estructurados.

## Lo que falta para certificar integralmente

| Nº | Pendiente | Condición para cerrarlo |
|---|---|---|
| 1 | Aceptación física y entre redes | Probar la versión exacta entre móvil y web en redes distintas: cámara QR, permisos, micrófono/cámara físicos, reconexión, llamadas y directos. Las fuentes generadas y conexiones de esta auditoría no sustituyen ese recorrido. |
| 2 | Cobertura funcional completa | Asociar cada opción inventariada a una aceptación vigente, incluyendo permisos e historial de grupos/comunidades, menciones, notificaciones y acciones con aplicación cerrada. Los casos de interfaz con API sintética no cierran por sí solos estos requisitos. |
| 3 | Disponibilidad de multimedia histórica | El expediente general previo mantiene pendientes referencias antiguas y objetos aislados. La entrega web no recupera automáticamente esos archivos ni modifica su aislamiento. Cerrar con propiedad/procedencia demostrada y recuperación verificable, o una exclusión funcional acordada. No se declara que todos los archivos antiguos estén disponibles. |
| 4 | Operación y capacidad completas | Revalidar recuperación y carga representativas del conjunto desplegado. Las comprobaciones de salud de hoy y las pruebas anteriores por componentes no son un ensayo integral actual de fallos o capacidad. |
| 5 | Navegadores adicionales | Si se ofrecen Safari y Firefox como compatibles, ejecutar allí los recorridos de acceso, multimedia, pendientes y comunicación. Esta entrega acepta los casos medidos en Chrome y Brave. |
| 6 | Apariencia final en buscadores | Esperar el nuevo rastreo y observar logo, nombre de sitio, fragmentos y guías. No puede certificarse una primera posición permanente ni que todas las variantes aparezcan mañana. |

El expediente general anterior `audit/2026-10-07-integral/production-acceptance.json`, fechado el 13 de octubre, conserva sus requisitos abiertos; esta auditoría no lo sobrescribe ni presenta sus resultados históricos como ejecutados de nuevo. Se distingue de la aprobación de los recorridos web de esta entrega.

## Evidencia y limpieza

Recibos locales: `audit/2026-10-07-integral/`. Resumen comprobable: `web-9038-final.json`, con SHA-256 de los informes utilizados y alcance de cada resultado.

- `web-9038-production-seo.json`, `web-9038-public-assets.json`, `public-health-latest.json`.
- `web-9038-tests-final.log`, `web-9038-seo-final.log`, `web-9038-media-final.log`, `web-9038-build-final.log`, `web-9038-dependencies-final.json`.
- `web-9038-production-public-browser.log`; `web-9038-production-{forms,parity,notice}.log` y equivalentes `web-9038-brave-*`.
- `web-9038-real-accounts.json`, `web-9038-real-calls-fixed.json`, `web-9038-real-rooms-live-fixed.json`.
- `web-9038-brave-real-auth-qr-final.json`, `web-9038-brave-guest.log`, `web-9038-search-engines.json`.
- `output/playwright/web-seo-9038/google-novvin-result-final.jpg`, `google-sitemap-12-final.jpg`, `google-manual-actions-clear.jpg`, `google-security-clear.jpg`, `brave-url-submitted.jpg`.

Se eliminaron mediante la API las 13 cuentas temporales creadas, junto con sus recursos. Diez se verificaron además mediante conteos de solo lectura de la base de datos: cero cuentas, objetos, salas, directos o sesiones asociados. La comprobación adicional de las tres últimas por SSH quedó pendiente porque Tailscale volvió a exigir autenticación; sus respuestas de eliminación de API sí fueron satisfactorias. No se borraron cuentas personales, datos históricos ni archivos del usuario. No se exportaron tokens o contraseñas en los recibos.

La API de cookies de Brave no expone la cookie particionada real en este entorno. Se comprobó su envío en la renovación real, la restauración de sesión y su revocación con 401; los atributos se verificaron en la respuesta HTTPS de consumo de un segundo QR temporal. No se confunde un resultado vacío de la API de cookies con ausencia de la cookie.

## Referencias oficiales

- [Google: solicitar un nuevo rastreo y sus límites](https://developers.google.com/search/docs/crawling-indexing/ask-google-to-recrawl).
- [Google: nombre del sitio](https://developers.google.com/search/docs/appearance/site-names).
- [Google: favicon en resultados](https://developers.google.com/search/docs/appearance/favicon-in-search).
- [Google: enlaces secundarios automáticos](https://developers.google.com/search/docs/appearance/sitelinks).
- [IndexNow: notificación de URL](https://www.indexnow.org/documentation).
- [Brave: envío de URL](https://search.brave.com/submit-url).

