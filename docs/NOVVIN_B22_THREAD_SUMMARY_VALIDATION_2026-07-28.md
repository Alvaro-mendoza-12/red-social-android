# Novvin B22 — resúmenes de hilos con fuentes

Fecha de validación: 2026-07-28

## Resultado

Se implementó el bloque B22 del benchmark como una función voluntaria en el
detalle de cada publicación:

- botón `Resumir hilo` y actualización manual del resumen;
- resumen extractivo local de entre dos y seis puntos;
- cada punto enlaza al comentario original y permite desplazarse hasta él;
- aviso visible de que el resumen puede omitir contexto o matices;
- invalidación automática del resumen al crear, editar o eliminar comentarios;
- respuestas privadas sin almacenamiento intermedio ni caché compartida;
- mensajes de error comprensibles para el usuario.

El modelo tiene la identidad estable `novvin-thread-extractive-v1`. No inventa
texto ni fuentes: selecciona oraciones existentes y la API vuelve a comprobar
que cada identificador y fragmento pertenecen al hilo solicitado.

## Privacidad y causa encontrada

El detalle de una publicación podía cargarse por identificador sin aplicar en
todos los casos la misma frontera de visibilidad que el listado. Eso habría
permitido que una función de IA recibiera comentarios de una publicación
privada. Se corrigió el cargador común antes de incorporar el resumen:

- una publicación pública puede resumirse para cualquier usuario autenticado;
- una publicación privada solo puede verla y resumirla su propietario o una
  persona autorizada por la relación de seguimiento;
- un usuario ajeno recibe `404`, tanto al abrir el detalle como al pedir el
  resumen;
- el contenido privado se excluye antes de llamar al servicio de resumen.

Los resúmenes solo se calculan bajo petición, no se guardan en la base de datos
y la respuesta declara `Cache-Control: private, no-store`. También se limita la
entrada a 500 comentarios de texto y a diez solicitudes por usuario y minuto.

## Archivos principales

- `infrastructure/local-fallback/ai-chatbot/src/thread-summary.js`
- `infrastructure/local-fallback/ai-chatbot/tests/thread-summary.test.js`
- `infrastructure/local-fallback/api/src/data-routes.js`
- `infrastructure/local-fallback/api/scripts/verify-thread-summary-e2e.js`
- `app/src/main/java/conectate/com/data/model/PostThreadSummary.kt`
- `app/src/main/java/conectate/com/data/localfallback/LocalFallbackApiClient.kt`
- `app/src/main/java/conectate/com/data/repository/PostRepository.kt`
- `app/src/main/java/conectate/com/presentation/posts/PostDetailScreen.kt`
- `app/src/main/java/conectate/com/presentation/posts/PostDetailViewModel.kt`

## Verificación

- AI `npm run check`: aprobada.
- AI: pruebas de servidor, conocimiento, búsqueda semántica y resumen aprobadas.
- API `npm run check`: aprobada.
- API `npm run test:data-routes`: aprobada.
- API `npm run test:ai`: aprobada.
- API `npm run test:security`: aprobada.
- Android Debug: compilado desde un `clean` y con todas las tareas repetidas.
- Android Release: compilado con R8, reducción de recursos y
  `lintVitalRelease`; APK sin firma oficial.
- `git diff --check`: limpio.
- Canary público con cuentas ficticias: aprobado.
  - resumen únicamente extractivo;
  - enlaces y textos de fuentes verificados;
  - publicación privada bloqueada para un tercero;
  - petición explícita del propietario permitida;
  - respuesta no cacheada;
  - identidad estable del modelo.
- Limpieza posterior en PostgreSQL: `users=0`, `posts=0`.
- Salud pública e interna de la API: aprobada.

## Entrega

- Commit funcional: `941f6178496e`
- Release servidor: `conectate-mini-pc-20260728T234947Z`
- Backup previo al despliegue:
  `/home/alvaro/conectate-backups/20260728T234949Z`
- Imágenes activas:
  - API `fbf971be518d`;
  - AI `6a9766d8a49e`;
  - realtime `6b986ba632e2`;
  - signaling `7ad77ae84f5c`.
- APK Debug:
  `app/build/outputs/apk/debug/app-debug.apk`
  (205.895.882 bytes).
- APK Release sin firmar:
  `app/build/outputs/apk/release/app-release-unsigned.apk`
  (184.987.013 bytes).

## Límites conocidos

El primer alcance cubre hilos de comentarios de publicaciones. El resumidor es
extractivo, local y en español; no reemplaza todavía un modelo multilingüe para
conversaciones muy extensas o especializadas. Las comunidades y los chats
privados no se incorporaron para evitar ampliar el acceso a mensajes sin un
contrato de permisos específico.

La interfaz y la navegación a fuentes se verificaron mediante compilación y
pruebas automatizadas, pero no se declaran validadas físicamente en un teléfono
porque este bloque no incluyó una sesión ADB.
