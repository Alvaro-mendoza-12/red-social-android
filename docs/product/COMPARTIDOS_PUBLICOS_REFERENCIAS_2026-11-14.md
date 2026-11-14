# Compartir perfiles, publicaciones, videos y publicidad

Investigación solicitada por el propietario, consultada el 14 de noviembre de 2026. Es una guía de requisitos y contraste técnico; no certifica la presentación de todas las aplicaciones receptoras.

## Fuentes primarias

- [Open Graph](https://ogp.me/): el HTML declara título, tipo, imagen y URL canónica. Sus propiedades estructuradas permiten declarar formato, dimensiones y texto alternativo. Se aplica a las páginas públicas de cada objeto, sin depender del JavaScript de la sesión.
- [Meta: guía para webmasters](https://developers.facebook.com/documentation/sharing/webmasters): especificar metadatos evita depender de la selección heurística del rastreador. La URL canónica debe excluir datos de sesión. Meta documenta que cachea imágenes por URL y recomienda una URL nueva cuando cambia la imagen. Su depurador puede volver a rastrear una página. Esto no demuestra ni controla el caché de WhatsApp.
- [Meta: rastreadores](https://developers.facebook.com/documentation/sharing/webmasters/web-crawlers): permite identificar y contrastar sus agentes. Un ensayo HTTP con ese agente es una prueba de respuesta del servidor, no un ensayo de la aplicación móvil.
- [Mastodon: PreviewCard](https://docs.joinmastodon.org/entities/PreviewCard/): describe tarjetas generadas desde Open Graph con título, descripción, imagen y datos de autor. Confirma la utilidad de un contrato por contenido para otros consumidores.
- [Vercel: ejemplos de imágenes OG](https://vercel.com/docs/og-image-generation/examples): muestra generación de tarjetas con texto e imágenes y el uso de avatares. Es una referencia de composición; Novvin ya genera JPEG con Sharp en Ubuntu y no necesita migrar de framework para resolverlo.
- [Documentación fuente de Next.js en GitHub](https://github.com/vercel/next.js/blob/canary/docs/01-app/03-api-reference/04-functions/generate-metadata.mdx): referencia de implementación de metadatos por ruta. Se estudia el contrato; no se incorpora código de otro framework.

La dirección histórica de Cards de X redirigió al portal general al consultarla. No se atribuyen a esa página límites actuales de tarjetas que ya no muestra. Las fuentes citadas cambian con el tiempo; la fecha anterior identifica esta consulta.

## Criterios de Novvin

Estas decisiones se derivan del encargo del propietario y de las fuentes anteriores; no son obligaciones universales del protocolo.

| Objeto compartido | Imagen y texto esperados | Destino al abrir |
| --- | --- | --- |
| Perfil público | Avatar real, nombre y usuario de esa persona; iniciales si no tiene foto disponible | Ese perfil |
| Publicación con foto | Foto de la publicación, autor y extracto; conservar contenido sin deformarlo | Esa publicación |
| Publicación de texto | Autor/avatar y extracto legible | Esa publicación |
| Video público | Miniatura real, título y autor; el enlace permite reproducirlo en Novvin | Ese video |
| Publicidad | Creativo real y anunciante identificable; conservar la señal de contenido promocionado | La pieza o su destino público autorizado |
| Objeto privado, retirado o inexistente | Respuesta no disponible, sin revelar foto ni texto privados | No habilitar acceso por compartir |

1. Entregar metadatos en HTML inicial y una URL HTTPS absoluta por objeto. Evitar que la portada genérica sustituya al objeto solicitado. No incluir tokens ni información privada en el enlace.
2. Mantener nombre, autor, imagen y destino coherentes. El avatar no sustituye a la foto de una publicación cuando esa foto es el contenido compartido.
3. Generar una tarjeta raster legible. Novvin usa 1200 × 630 como formato de producto; declarar sus dimensiones, MIME y texto alternativo. La aplicación receptora puede recortar o seleccionar otra presentación.
4. Cambiar la revisión de la URL de imagen cuando cambien avatar, creativo, miniatura o contenido visible. La URL canónica del objeto permanece estable. Una revisión no autoriza servir contenido que dejó de ser público.
5. Validar permisos antes de generar HTML y antes de servir la imagen. Comprobar eliminación, suspensión, sensibilidad y revocación con datos sintéticos. La copia ya descargada por un tercero no se puede retirar desde Novvin.
6. Comprobar imágenes rotas, respuesta lenta, redirecciones y tipo de archivo. La lectura de medios debe seguir acotada y restringida a orígenes autorizados; no aceptar cualquier URL suministrada como destino del servidor.

## Estado contrastado y brechas

La [revisión 136](../oficial/136-integracion-api-compartidos-y-mandato-word-2026-11-14.md) identifica la imagen activa en Ubuntu, nueve pruebas HTTP/PostgreSQL de compartidos y las comprobaciones públicas de dos enlaces proporcionados por el propietario. Sirven tarjetas específicas de perfil y publicación; esas respuestas no prueban el caché ni la interfaz de WhatsApp.

La lectura puntual de `public-profile-share.js` y `public-content-share.js` en 137 detecta revisiones fijas `v=5` y `v=1`. Cambiar el avatar o el contenido puede conservar la misma URL de imagen: se registra como pendiente de corrección y prueba. No se ha demostrado actualización inmediata en aplicaciones externas.

La publicidad necesita contraste de su recorrido específico: una publicación promocionada y una pieza publicitaria independiente no se presuponen equivalentes. No se afirma una tarjeta específica de anuncio sin probar su ruta, autorización y destino. Tampoco se ha ejecutado una publicación real en redes externas ni enviado mensajes a terceros.

## Comprobación para la siguiente integración

Usar cuentas y medios de laboratorio. Obtener HTML e imagen como visitante y agente rastreador; comprobar el objeto exacto, dimensiones y píxeles representativos. Cambiar avatar/miniatura/texto y verificar que cambia la revisión; conservar el enlace canónico. Volver privado o eliminar y comprobar que ambos recursos dejan de revelar contenido. Por separado, ensayar pegar el enlace en las aplicaciones receptoras y registrar versión, red, fecha y efecto de su caché, sin confundir ese ensayo con las pruebas HTTP.
