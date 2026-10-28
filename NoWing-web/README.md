# Novvin Web (`NoWing-web`)

Cliente web adaptable de Novvin, conectado a la misma API Ubuntu y a los mismos canales de tiempo real que Android. La carpeta sustituye a `Conectate-web`; no se conserva esa carpeta eliminada.

## Desarrollo

Node 24, `npm ci --legacy-peer-deps`, `npm run dev`. Para compilar: `npm run build`; pruebas: `npm test -- --ci --runInBand`.

Variables públicas: `VITE_FALLBACK_API_URL` (origen HTTPS del servidor), `VITE_BACKEND_DISCOVERY_PUBLIC_KEY` (clave pública de descubrimiento). La web no debe recibir claves JWT, contraseñas de PostgreSQL ni claves administrativas. La autenticación funciona con contraseña o QR aprobado desde Android. Las cuentas y los permisos se comprueban en el servidor.

`/explore/videos` permite entrar como invitado para leer videos, publicaciones, momentos y perfiles públicos. Usa la identidad de invitado del servidor, con el token solo en memoria y archivos autorizados para esa identidad. No crea una cuenta ni permite enviar mensajes o modificar contenido; al iniciar sesión se descartan sus credenciales y sus recursos de multimedia.

## Vercel

Proyecto existente `red-social-android`, directorio raíz `NoWing-web`, instalación `npm ci --legacy-peer-deps`, compilación `npm run build`, salida `dist`. Las variables se definen en Vercel y se aplican al siguiente despliegue.

Ejecutar `vercel --prod` desde la raíz del repositorio, o `npm run deploy:production` desde `NoWing-web`. La exclusión de subida impide incluir archivos de entorno y datos locales.

En los dominios oficiales de Vercel, `publicBackendTransport.ts` usa el mismo origen público para API y archivos; `middleware.ts` los reenvía al origen lógico firmado y `api/novvin-socket.js` conserva los canales WebSocket. No se debe pedir permiso de red local al visitante. Mantener la lista cerrada de destinos y rutas, autenticación y límites del relé. La [revisión 78](../docs/oficial/78-capacidad-host-y-acceso-web-2026-10-27.md) detalla pruebas y límites.

Las 19 páginas informativas se prerenderizan sin JavaScript. `npm run seo:config` registra el contenido realmente modificado, conserva las fechas del resto y actualiza sitemap/hashes CSP. El build genera el sitemap desde ese estado y ejecuta una puerta SEO que rechaza cambios no preparados, bloqueos de rastreo, canonical incorrecto y pérdida de rutas privadas. Después ejecutar `npm run test:node` y la regresión Jest. `npm run check:production-seo` comprueba el despliegue real; no publica.

`node scripts/submit-indexnow.mjs /ruta --dry-run` prepara un aviso; es también el modo por defecto. `--submit` requiere la autorización y publicación previas. No hay envío automático desde el build. El historial local queda excluido de Git/Vercel. La [revisión 80](../docs/oficial/80-auditoria-seo-y-candidato-local-2026-10-27.md) registra el candidato y sus límites; la [revisión 79](../docs/oficial/79-indexacion-publica-2026-10-27.md) conserva el despliegue anterior. Las ilustraciones se regeneran con `node tools/generate-public-product-images.cjs` desde la raíz, usando Sharp instalado en el backend local.

## Momentos

Seleccionar una foto JPG, PNG o WEBP, o un video MP4, abre el editor sin publicarlo (hasta 35 MB, según la política del servidor). El botón «Publicar momento» confirma la descripción, audiencia, duración, texto y música. El catálogo de comunidad muestra pistas aprobadas y autorizadas para reutilización; se puede recorrer la canción completa para elegir el inicio del fragmento. También se puede usar audio del dispositivo. Las fotos duran seis segundos y los videos respetan su duración hasta veinticinco segundos.

El visor usa el fragmento compatible que devuelve el servidor, sincroniza la música y pausa el audio y el video al mantener pulsado, escribir una respuesta, pausar o cambiar de pestaña. Los videos con audio ya mezclado desde Android no reproducen una segunda pista. El navegador puede exigir tocar «Audio ON» o «Reproducir» para permitir sonido.

El editor permite texto y presentación musical de tarjeta, etiqueta o solo audio. La comparación completa con todas las herramientas creativas de Android sigue pendiente; no incluye un editor de letras sincronizadas ni composición avanzada de video. La publicación requiere conexión y conserva el borrador abierto si falla; no es una cola persistente de publicaciones.

## Verificación

La compilación y las pruebas de código no prueban por sí solas cada acción de la aplicación. El informe de este trabajo enumera por separado las pruebas de navegador, sincronización, archivos y llamadas. El navegador necesita HTTPS y permiso del usuario para cámara, micrófono y notificaciones. No se garantiza una latencia de cero ni ejecución de tareas con el navegador cerrado.
