# Publicar la web de Novvin

Proyecto Vercel: red-social-android. El dominio principal es https://novvinapp.com y el contacto público es administracion@novvinapp.com. El frontend está en NoWing-web y los servicios de datos se ejecutan en Ubuntu. Las opciones y capacidades del backend activo se describen en [la documentación oficial](oficial/README.md).

Desde NoWing-web, ejecuta npm run build, npm run test:node y npm test -- --runInBand con las variables de compilación del proyecto. Si cambias contenido público, regenera el sitemap y los hashes de CSP con npm run seo:config antes del build. Revisa las dos configuraciones vercel.json y el cambio de fecha de las páginas afectadas.

Para publicar, prepara una copia que solo contenga las fuentes versionadas del despliegue. Conserva .vercelignore. Usa el proyecto ya vinculado y sus variables de producción. Registra el despliegue que sirve el dominio con vercel inspect https://novvinapp.com. Genera el candidato con vercel deploy --prod --skip-domain. Si el candidato requiere autenticación, vercel curl permite comprobar las páginas, los recursos y el sitemap con tu sesión del proyecto.

Promueve la URL comprobada con vercel promote y ejecuta npm run check:production-seo desde NoWing-web. Comprueba además los recorridos modificados en el navegador. Si la comprobación falla por la entrega nueva, vuelve a promover el despliegue anterior. La recuperación por correo tiene una configuración independiente en Ubuntu; publicar la web no configura SMTP.
