# Correcciones web del 10 de noviembre de 2026

Base revisada: `455d26999111116f59ae8bd76dae56a5e137aee3`. Este candidato contiene cambios de código y comprobaciones locales. No acredita un despliegue ni el cierre integral de la aplicación. No modifica deliberadamente `main` ni `feature/live-gifts`.

## Cambios

- **W2, contraseñas:** Ajustes, el restablecimiento antiguo y los formularios de migración conservan los caracteres exactos de la contraseña y su confirmación. Las pruebas incluyen espacios iniciales/finales y caracteres multibyte. No se cambiaron credenciales reales.
- **W3, contraste:** el enlace Crear cuenta de Explore recupera el color `--on-primary`; la regla genérica de enlaces ya no lo reemplaza por el texto de la página.
- **W4, monetización:** cuando no está disponible el contrato web, se explica qué operaciones no pueden hacerse y se ofrecen enlaces a Perfil y Ayuda. Se retiran las instrucciones de tablas, CSV y VM de la pantalla del usuario. Esto no implementa un proveedor de retiros ni certifica pagos.
- **W5, compartir/reenviar:** ambos flujos reutilizan `PublicationDialog`, que abre un diálogo modal nativo, gestiona su cancelación y devuelve el foco al disparador. El buscador tiene nombre accesible y no permite editar mientras se envía.
- **W6, Momentos:** Cerrar, Escape y el fondo consultan antes de descartar un borrador modificado; cancelar mantiene su contenido. Una descarga de página con cambios pendientes activa el aviso del navegador.
- **Juegos:** el catálogo busca en el servidor mediante el contrato paginado existente `/v1/data/playmatch/games/search`, muestra el número de resultados cargados, ofrece más resultados y diferencia carga, error, catálogo vacío y búsqueda sin coincidencias. Se descartan respuestas atrasadas. La selección queda deshabilitada durante una acción pendiente para no mostrar resultados de A con el filtro de B.
- **Videos:** la navegación anterior/siguiente se sitúa en el margen izquierdo, separado de las reacciones del derecho, también con comentarios y en ventanas bajas.
- **Social:** el enlace al contador de comentarios tiene texto y nombre accesible incluso con cero comentarios, y usa singular cuando corresponde.
- **Títulos:** las secciones de la aplicación, Explore y recuperación tienen títulos de pestaña contextuales; se conserva el contador compartido de mensajes sin leer.
- **Dependencias:** actualización compatible y exclusiva del lockfile de `systeminformation` 5.33.1 a 5.33.19, sin `npm audit fix --force` ni cambios de versión principal.

## Comprobaciones

Desde `NoWing-web`:

- `npm test -- --runInBand`: **170 suites, 956 pruebas aprobadas, cero fallos**. Se reprodujeron primero las regresiones funcionales corregidas. La suite mantiene avisos de React en pruebas existentes.
- `npx tsc -b`: aprobado.
- `VITE_FALLBACK_API_URL=https://novvin-test.invalid npm run build`: aprobado, incluidos prerender y los controles SEO de 19 páginas. Se empleó un destino sintético, no una configuración de producción. Persiste el aviso de un fragmento JavaScript de aproximadamente 1,1 MB sin comprimir.
- Después del build, `npm run test:node`: **98 pruebas aprobadas, cero fallos**. Estas pruebas necesitan el artefacto `dist` recién generado.
- `npm run lint`: cero errores y siete avisos preexistentes de dependencias de efectos React. La corrección no añade avisos.
- `git diff --check`: aprobado.
- Revisión independiente: 36 pruebas dirigidas, comprobación TypeScript y diff sin errores; se corrigió la carrera de selección del catálogo detectada en esa revisión.
- `npm audit --json`: de 19 avisos en esta ejecución inicial a **16: cero críticos, cero altos, nueve moderados y siete bajos**. Los restantes incluyen dependencias transitivas de herramientas (`deepmerge`, `glob`, `minimatch`); no se aplicaron migraciones incompatibles ni degradaciones sugeridas automáticamente. Este resultado es fechado y no equivale a ausencia de vulnerabilidades futuras.

## Límites y siguientes controles

1. Falta comprobar el candidato en un navegador real: recorrido por teclado de los diálogos, restauración de foco, contraste por tema y acento, vistas móviles/zoom y geometría de Videos con comentarios. El arranque local de Chromium quedó bloqueado por restricciones de sockets del entorno; no se presentó una simulación DOM como prueba visual.
2. El aviso de Momentos no conserva un borrador al navegar entre rutas de la SPA o usar Atrás. `beforeunload` no se dispara en esas transiciones. La recuperación durable por cuenta o un bloqueo de navegación compatible con el router sigue pendiente. Las rutas de cierre indicadas arriba sí están cubiertas.
3. Monetización informa de su indisponibilidad; el contrato completo de ganancias y retiros sigue pendiente.
4. Quedan los avisos moderados/bajos de dependencias, los siete avisos de lint y el presupuesto de tamaño del fragmento mayor. No se consideran cerrados por haber aprobado el build.
5. No se ha comprobado el SHA del sitio publicado ni se han ejecutado envíos, publicaciones, cambios de contraseña o pagos contra cuentas reales. La validación local no sustituye esos ensayos controlados ni autoriza desplegar.
