# Novvin Web 9.0.40 — búsqueda, navegación y recuperación del acceso

Fecha: 16 de octubre de 2026. Web: https://red-social-android.vercel.app/ .

## Incidente de acceso: QR y correo

La mini PC del servidor, `conectate`, figura desconectada en Tailscale desde las 06:34 de hoy, hora de Colombia. La PC de Windows está conectada y su servicio Tailscale funciona. La mini PC no responde al ping privado ni a SSH. La dirección pública del backend tampoco completa la conexión TLS; `/health` y `/ready` no responden. Esto impide generar el QR y validar las credenciales. El usuario indicó que no puede acceder físicamente a la mini PC ahora.

**El servicio de acceso real sigue pendiente de recuperación de ese equipo.** Publicar una web en Vercel no restablece una mini PC desconectada. No se declara resuelto el acceso real ni se sustituyen las cuentas o los datos del servidor por datos de prueba.

## Correcciones de acceso

- Las solicitudes de autenticación, incluidas las del QR, tienen un límite de espera de 20 segundos. Cancelar una solicitud conserva la cancelación del llamador. Se limpian los temporizadores al terminar.
- El límite se aplica a `/v1/auth/`; no recorta los tiempos permitidos para subir archivos ni entregar mensajes pendientes.
- No se repiten automáticamente los POST de contraseña, registro ni consumo del QR. Se conserva la política de reintento segura existente.
- El error de conexión se presenta como «No pudimos conectar con Novvin. Vuelve a intentarlo en unos momentos.». Los errores del QR pasan por el filtro de mensajes seguros, sin mostrar URL internas ni diagnósticos de base de datos.
- El QR fallido permite **Reintentar QR**. Los campos del acceso por contraseña se conservan tras un fallo y el botón vuelve a habilitarse. Los errores se anuncian de forma accesible.
- Los efectos de consulta y consumo del QR dependen de su identificador, nonce y estado, conservando una sola operación de consumo por código aprobado.

## Mejoras web y búsqueda

- Dos nuevas páginas públicas: `/descargar`, con el enlace a la ficha de Novvin en Google Play, y `/ayuda/encontrar-novvin`, con instrucciones para entrar desde distintos navegadores, buscar la marca y abrir enlaces compartidos desde X u otras aplicaciones.
- Navegación pública común con acceso, descarga y ayuda; guía agrupada en Conversar, Descubrir y Tu cuenta. Se evita la fila extensa de enlaces acumulados. Cada página señala la opción actual y muestra una ruta de navegación coherente con sus datos estructurados.
- Catorce páginas públicas completas, accesibles sin ejecutar JavaScript ni contactar al backend. Títulos, descripciones, URLs canónicas, redirecciones permanentes, sitemap y firmas CSP incluyen las páginas nuevas.
- Se conserva Novvin como nombre preferido del sitio y las alternativas Novvin Web y el hostname real. Las variantes de escritura se explican en contenido visible. No se crean opiniones ficticias ni miles de palabras ocultas.
- La página de descarga enlaza a Google Play; no ofrece un APK, instalador de escritorio o versión móvil distintos. El manifiesto conserva los accesos implementados.

## Comprobaciones

- Compilación correcta; 459 pruebas web en 76 suites y 44 de SEO, aprobadas.
- Lint del TypeScript de producción modificado sin errores ni avisos.
- Las catorce páginas públicas se comprobaron a 320, 390, 768 y 1440 px, tanto en Chrome como en Brave: 56 combinaciones por navegador, sin desbordamiento horizontal. Las páginas no cargan JavaScript ni peticiones del backend de la aplicación. Las peticiones ajenas de una extensión instalada se registran aparte; no se cambia esa extensión.
- Prueba de navegador del acceso con API sintética: fallo de contraseña con campos conservados, error seguro del QR, reintento manual, recuperación del código y acceso al responder el servicio, sin repetir automáticamente los POST. Tres tamaños de ventana sin desbordamiento.
- La prueba sintética **no demuestra que la mini PC esté operativa**. No se usaron cuentas reales ni se crearon cuentas temporales durante este incidente.
- El despliegue, los controles HTTP del sitio publicado, las comprobaciones posteriores y las notificaciones a buscadores se registran en `audit/2026-10-07-integral/web-9040-final.json`.

## Alcance y pendientes

Chrome, Brave, Edge, Opera y DuckDuckGo son navegadores o productos con navegador; Google, Bing, Brave Search y DuckDuckGo mantienen servicios de búsqueda. La configuración del navegador determina cuál se usa. Una web puede mejorar su contenido y rastreo, pero no imponer la posición de todas las consultas ni certificar todos los navegadores con pruebas de Chromium.

En esta PC se encontraron los ejecutables de Chrome y Brave. No se presenta Edge, Opera, Safari, Firefox o el navegador integrado de X como ejecutados en estas comprobaciones. El contenido público usa HTML y CSS comunes; la aceptación de cada función interactiva en esos otros navegadores sigue siendo un alcance distinto.

Las comprobaciones anteriores de búsqueda de marca se conservan en `web-9039-search-engines.json`: Novvin apareció en la primera página de Brave Search, Bing y DuckDuckGo. No se presentan como nuevas posiciones garantizadas ni como prueba de aparición con Novvyn.

Para cerrar este incidente falta restablecer corriente/red de la mini PC, confirmar `/ready` y repetir QR y acceso por correo contra el servidor real. Las limitaciones generales de producción siguen documentadas en [la auditoría web](WEB_PRODUCTION_AUDIT_2026_10_16.md).

Referencias: [nombre del sitio en Google](https://developers.google.com/search/docs/appearance/site-names), [rastreo de Brave](https://search.brave.com/help/brave-search-crawler), [notificación IndexNow](https://www.indexnow.org/documentation).
