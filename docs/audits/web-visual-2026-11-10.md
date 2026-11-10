# Novvin web: primer incremento visual

Fecha: 10 de noviembre de 2026. Base: `fc3c796dc9a50a864d8b95e555622f11cece08a5`, rama `correcciones-dot`. No se modifican `main` ni `feature/live-gifts`; no hay despliegue productivo. Esta entrega necesita aceptación visual en Preview y no cierra la revisión integral de la web.

## Antes y propuesta implementada

- La portada pública observada en https://red-social-android.vercel.app/ mostraba un primer viewport casi negro, un logo grande repetido y dos acciones principales. Esa observación no acredita el SHA productivo. La propuesta conserva los accesos y la información, introduce una paleta azul noche/cian con acentos discretos y muestra una conversación ilustrativa marcada expresamente como ejemplo. No simula actividad real, personas conectadas ni resultados de uso.
- El código base tenía una barra lateral de 84 px en escritorio. Se conservan los cinco destinos y la barra móvil. Desde 1440 px, las etiquetas pasan a un panel de 216 px; entre 1100 y 1439 px se conserva el ancho de 84 px. Crear publicación y Explorar enlazan recorridos existentes. La barra tiene desplazamiento para pantallas bajas.
- Social obtiene una introducción compacta, un selector con nombre accesible y una jerarquía de superficies que respeta las preferencias existentes. Se mantienen Social, Momentos, Rondas, el editor, los callbacks y los estados de ruta.
- La exploración pública mejora espacios, títulos, selección y superficies sin alterar errores, contenido vacío, sesión invitada ni permisos.
- Las preferencias de tema/acento no cambian. El escenario de Videos conserva el escenario multimedia negro, pero su navegación usa la superficie e información del tema seleccionado.

No se modifica PlayMatch, su cálculo de compatibilidad o disponibilidad, monetización, mensajería, publicaciones, medios, sesiones ni borradores. No se añaden funciones de ranking, instrumentación, seguimiento o servicios externos.

## Fuentes de diseño

Discord explica coherencia visual y espaciado entre móvil/escritorio: https://discord.com/blog/improving-mobile-with-squircles-styles-and-spacing . Bluesky presenta elecciones explícitas del feed: https://bsky.social/about/blog/7-27-2023-custom-feeds . Aquí se aplica a visibilidad de destinos ya existentes; Novvin no adquiere feeds personalizados con esta entrega.

Contraste y foco: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html y https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html . Son criterios para verificar, no una certificación WCAG de la web. Estas referencias no demuestran conversión, retención ni encaje de mercado.

## Verificación reproducible

En `NoWing-web`:

- `npm test -- --runInBand`: base 174 suites / 992 pruebas aprobadas. Candidato: 177 suites / 1001 pruebas aprobadas; 9 nuevas.
- `npx tsc -b --pretty false`: aprobado.
- `VITE_FALLBACK_API_URL=https://synthetic.novvin.invalid npm run build`: compilación y prerender aprobados; 19 páginas públicas, cero JavaScript público; comprobaciones de HTML, metadatos, CSP, robots, sitemap, assets, rutas canónicas, shell privado y 404 aprobadas. El origen es sintético, no un backend de producción.
- `npm run test:node`: 103 pruebas aprobadas, incluidas 5 nuevas de contrato de tema. Estas últimas inspeccionan las reglas CSS y una muestra de contraste; no ejecutan un motor de diseño de navegador.
- ESLint sobre los cuatro TSX de producción modificados: aprobado. La configuración del proyecto excluye los archivos de pruebas del lint.
- `git diff --check`: aprobado.

El build sigue advirtiendo de un chunk de aplicación superior a 1000 kB. La portada estática no carga ese JavaScript; esta entrega no corrige el tamaño de todos los módulos autenticados.

## Fallos detectados y corregidos durante la validación

1. La primera versión excedía el presupuesto existente de 15 KB para CSS público. Se consolidaron reglas duplicadas y se retiraron únicamente estilos obsoletos del logo decorativo. CSS final: 14.145 bytes; no se elevó el límite.
2. El avatar podía añadir su nombre al enlace Perfil. Se oculta la parte decorativa al árbol accesible y se conserva la etiqueta y el conteo completo de no leídos por separado. Regresión observada en rojo y después en verde.
3. En tema claro, el fondo negro fijo de la navegación de Videos entraba en conflicto con la nueva acción Explorar. Se emparejan fondo y texto con los tokens de tema. Prueba de contrato observada en rojo y verde.
4. Un acento gris aceptable sobre blanco no tenía contraste suficiente sobre su superficie tintada. El texto de Social utiliza el foreground del tema; el acento queda para decoración/indicadores. Dos pruebas de contrato fallaron antes de corregirlo y pasaron después.

5. La revisión posterior detectó otro enlace de bajo contraste en el pie de las tarjetas de exploración: el acento gris sobre la superficie clara tintada. El enlace conserva el subrayado y utiliza el foreground del tema. Una prueba con el color declarado y la superficie #fafafa falló con el acento y pasó tras la corrección.

## Aceptación visual pendiente

No hay capturas «después» del candidato ni pruebas de reflujo en navegador real en esta entrega. El navegador local no pudo arrancar por una restricción de sockets; el navegador conectado no alcanzó el servidor local. No se intentó sortear la restricción con túneles ni despliegues alternativos.

Antes de dar por aceptado el diseño, comprobar en un Preview de `correcciones-dot`:

- 320, 390, 768, 1280 y 1440 px; viewport de poca altura y zoom del 200 %.
- Portada, guías/descarga, Social, Momentos, Rondas, mensajes, conversación, Videos y exploración invitada con datos sintéticos.
- Tema claro, oscuro, sistema y acentos personalizados gris/blanco/negro.
- Acceso con teclado, foco visible, apertura/cierre de Explorar con restauración del foco, nombres y conteos para lector de pantalla.
- Ausencia de recortes, scroll horizontal, texto ilegible o controles multimedia desplazados por el panel lateral.
- Comparativa de capturas antes/después del mismo recorrido y viewport.

El éxito de Jest, TypeScript y SEO no sustituye esta aceptación. La posterior promoción productiva es una decisión separada.
