# Novvin B21 — búsqueda semántica privada y segura

Fecha de validación: 2026-07-28

## Resultado

Se implementó el bloque B21 del benchmark sin eliminar la búsqueda de usuarios
existente:

- pestañas separadas para buscar personas y contenido;
- búsqueda de publicaciones y videos públicos por significado, no solo por
  coincidencia textual;
- navegación directa desde los resultados a la publicación o al video;
- ranking local con identidad estable `novvin-semantic-local-v1`;
- exclusión de perfiles ocultos y contenido privado antes de enviar el corpus al
  ranker;
- mensajes de error comprensibles para el usuario;
- límite acotado del corpus para evitar consumo descontrolado de memoria.

## Causa encontrada

La búsqueda anterior solo consultaba usuarios. La API no construía un corpus de
contenido visible ni existía un contrato interno para puntuar relevancia
semántica. Añadir el ranker directamente sobre todos los registros habría
expuesto contenido privado, por lo que el límite de privacidad se aplicó en la
API antes de cualquier puntuación.

El despliegue también reveló dos problemas operativos independientes:

- la resolución DNS de Docker fallaba de forma intermitente durante `npm ci`;
- una sesión SSH prolongada podía cerrarse mientras se reconstruían imágenes.

Se configuró red de host únicamente durante la construcción de imágenes y se
añadieron keepalives al script de despliegue. La red de ejecución de los
contenedores continúa aislada por Docker Compose.

## Archivos principales

- `infrastructure/local-fallback/ai-chatbot/src/semantic-search.js`
- `infrastructure/local-fallback/ai-chatbot/tests/semantic-search.test.js`
- `infrastructure/local-fallback/api/src/data-routes.js`
- `infrastructure/local-fallback/api/scripts/verify-semantic-search-e2e.js`
- `infrastructure/local-fallback/docker-compose.yml`
- `scripts/deploy-mini-pc-current.ps1`
- `app/src/main/java/conectate/com/data/model/SemanticSearchResult.kt`
- `app/src/main/java/conectate/com/data/localfallback/LocalFallbackApiClient.kt`
- `app/src/main/java/conectate/com/data/repository/PostRepository.kt`
- `app/src/main/java/conectate/com/presentation/search/SearchUsersScreen.kt`
- `app/src/main/java/conectate/com/presentation/search/SearchUsersViewModel.kt`

## Verificación

- AI `npm run check`: aprobada.
- AI: pruebas de servidor, conocimiento y búsqueda semántica aprobadas.
- API `npm run check`: aprobada.
- API `npm run test:data-routes`: aprobada.
- API `npm run test:ai`: aprobada.
- API `npm run test:security`: aprobada.
- Android `:app:compileDebugKotlin`: aprobada.
- Android Debug: compilado desde un `clean` completo.
- Android Release: compilado con R8 y `lintVitalRelease`; APK sin firma oficial.
- `git diff --check`: limpio.
- Canary público con cuentas ficticias: aprobado.
  - búsqueda por significado;
  - únicamente contenido público;
  - exclusión privada antes del ranking;
  - identidad estable del modelo.
- Limpieza posterior en PostgreSQL: `users=0`, `posts=0`.
- Salud pública e interna de la API: aprobada.

## Entrega

- Commit funcional: `17d04e9`
- Commit operativo final: `b1cfc79cfa65`
- Release servidor: `conectate-mini-pc-20260728T223659Z`
- Backup previo al despliegue final:
  `/home/alvaro/conectate-backups/20260728T223706Z`
- Imágenes activas:
  - API `07a348329ebf`;
  - AI `75bcd87275ef`;
  - realtime `d8e5c4563dd7`;
  - signaling `48cd4cfdb79b`.
- APK Debug:
  `app/build/outputs/apk/debug/app-debug.apk`
- APK Release sin firmar:
  `app/build/outputs/apk/release/app-release-unsigned.apk`

## Límites conocidos

El ranker es local y usa una ontología controlada en español con tolerancia a
variaciones y errores tipográficos. Es adecuado para el volumen actual y no
genera costos por consulta, pero no sustituye todavía un modelo multilingüe de
embeddings para una escala mayor. El corpus está limitado a los 800 documentos
públicos más recientes.

La interacción visual se compiló en Android, pero no se declara validada
físicamente en un teléfono porque este bloque no incluyó una sesión ADB.
