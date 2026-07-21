# Arquitectura del proyecto — Conéctate

> Mapa del proyecto organizado por módulos. El objetivo es que cualquiera pueda entender la
> estructura y **encontrar rápido dónde está cada cosa**.
> Última actualización: 2026-07-20.

---

## Visión general

Conéctate es una red social multiplataforma con 4 piezas que se despliegan por separado:

| Módulo | Carpeta | Tecnología | Se despliega en |
|---|---|---|---|
| **App Android** | `app/` | Kotlin + Jetpack Compose | Google Play (APK/AAB) |
| **App Web** | `Conectate-web/` | React 19 + Vite + TypeScript | Vercel |
| **Backend Ubuntu** | `infrastructure/local-fallback/` | Node/Express + PostgreSQL + Docker | Mini-PC (Docker Compose) |
| **Servidor de señalización** | `signaling-server/` | Node + Socket.IO | Mini-PC / VPS |

Flujo: las apps (Android/Web) hablan con el **Backend Ubuntu** (API REST + almacenamiento) y con el
**servidor de señalización** (WebRTC para llamadas/voz/directos). El backend usa **PostgreSQL**
(datos), almacenamiento de objetos propio, y **coturn** (TURN) para NAT traversal.

---

## 1. App Android — `app/`

Módulo Gradle único (`:app`, namespace `conectate.com`). Solo se compila `app/src/main/java`.

```
app/src/main/java/conectate/com/
├── data/                      # CAPA DE DATOS
│   ├── model/                 # Modelos/DTOs (User, Message, Video, Community…)
│   ├── repository/            # Repositorios (fuente única de verdad por dominio)
│   ├── local/                 # Room: AppDatabase, DAOs, entities, migraciones
│   │   ├── dao/               #   - Data Access Objects
│   │   ├── entity/            #   - Entidades Room
│   │   └── prefs/             #   - DataStore / preferencias
│   ├── localfallback/         # Cliente del Backend Ubuntu (LocalFallbackApiClient)
│   ├── supabase/              # (Legado) cliente Supabase — migración en curso
│   ├── webrtc/                # WebRTCManager (llamadas/voz P2P)
│   ├── signaling/             # SignalingConfig (config del servidor de señalización)
│   ├── service/               # Servicios de datos (FCM push, receivers)
│   └── cache/                 # UpstashCacheService
├── presentation/              # CAPA DE UI (Compose)
│   ├── auth/                  # Login, registro, 2FA
│   ├── home/                  # HomeScreen (NavHost anidado con pestañas)
│   ├── messages/              # Chat, grupos, comunidades
│   ├── social/                # Feed social, posts, retos
│   ├── video/ · videos/       # Feed de videos, reproductor
│   ├── live/                  # Directos (host/viewer/setup/discovery)
│   ├── voicerooms/            # Salas de voz
│   ├── calls/                 # Llamadas 1:1
│   ├── profile/ · settings/   # Perfil, ajustes
│   ├── components/            # Componentes reutilizables
│   └── navigation/            # Screen (rutas), AppNavGraph, MainNavigationRoutes
├── di/                        # Inyección de dependencias (Hilt): módulos
├── core/                      # Utilidades transversales
│   ├── security/              #   - EncryptionManager, OperationGuard
│   ├── media/                 #   - MediaUploadGuard
│   ├── feed/                  #   - FeedRankingEngine
│   └── concurrency/           #   - newSafeScope (coroutines seguras)
├── services/                  # Foreground services (Voice/Call/Live/ChatHead)
├── firebase/                  # Integración Firebase (mensajería)
└── util/                      # Helpers varios
```

Archivos de build clave: `app/build.gradle.kts` (config, firma, R8), `app/proguard-rules.pro`
(reglas R8/ProGuard), `gradle/libs.versions.toml` (versiones), `app/src/main/AndroidManifest.xml`.

---

## 2. App Web — `Conectate-web/`

```
Conectate-web/src/
├── features/          # Por dominio: messages, social, reels, voice, profile, live…
├── core/              # auth, hooks, runtimeBackend, fallbackData
├── layout/            # AppLayout, EnterpriseLayout
├── components/        # Componentes compartidos
└── __tests__/         # Tests (Jest)
```
Build: `package.json` (Vite/tsc/eslint/jest). Config de backend: `public/backend-config.json`.

---

## 3. Backend Ubuntu — `infrastructure/local-fallback/`

```
infrastructure/local-fallback/
├── docker-compose.yml         # Orquesta: db, api, edge(nginx), signaling, realtime, coturn, ai
├── api/
│   ├── Dockerfile
│   └── src/
│       ├── server.js                  # Entrypoint Express, auth JWT, rate limiting
│       ├── data-routes.js             # Rutas de datos (perfil, posts, videos, storage-ownership)
│       ├── postgrest-compat-routes.js # Compatibilidad estilo PostgREST
│       ├── storage-routes.js          # Almacenamiento de objetos (subida/servido)
│       ├── admin-moderation-routes.js # Panel admin (hard-delete, moderación)
│       ├── live-routes.js · moments-routes.js · proximity-routes.js · ai-routes.js
│       ├── push-notifications.js      # FCM
│       └── request-security.js        # Rate limiting, IP whitelist
├── db/
│   ├── init/001_schema.sql            # Esquema inicial (se ejecuta al crear la BD)
│   └── migrations/                    # Migraciones numeradas (aplicar en orden)
├── realtime-gateway/                  # Gateway WebSocket (pub/sub de eventos)
├── nginx/default.conf                 # Reverse proxy (edge)
├── scripts/                           # Operación mini-PC: backup, restore-drill, TURN, deploy…
└── secrets/                           # (gitignored) firebase-service-account.json, .env
```

**Escalabilidad (estado actual, verificado):**
- **Índices BD:** 137 índices; tablas calientes cubiertas. ✅
- **Async:** Node async/await en todo. ✅
- **Caching:** en memoria del proceso (`ttlCache`) + headers HTTP `Cache-Control`. ⚠️ **No** hay
  Redis. El estado (caché, rate-limit, señalización) es **de un solo proceso**: funciona con 1
  instancia, pero escalar a 2+ instancias requiere **Redis** (caché + adaptador Socket.IO).

---

## 4. Servidor de señalización — `signaling-server/`

`index.js` (Socket.IO: WebRTC offer/answer/ICE, salas, autorización por-mensaje), `resolver.js`,
`Dockerfile`, `COTURN_PRODUCTION.md`. Estado en memoria de un solo proceso (ver nota de escalado).

---

## 5. Otras carpetas (raíz)

| Carpeta | Qué es |
|---|---|
| `docs/` | Documentación (guías, diagnósticos, informes archivados) |
| `sql/` | Scripts SQL (migraciones, hotfixes, diagnósticos) |
| `scripts/` | Scripts de operación/infra del proyecto |
| `gradle/` · `gradlew*` | Wrapper de Gradle |
| `keystores/` | `debug.keystore` (el de release NO se versiona) |
| `supabase/` | (Legado) config Supabase — migración en curso hacia Backend Ubuntu |

---

## Cómo desplegar (resumen)

- **Android:** configurar `keystore.properties` (firma release) → `./gradlew :app:assembleRelease`
  (APK) o `:app:bundleRelease` (AAB para Play). Ver `CORRECCIONES_REALIZADAS.md §R3-4`.
- **Web:** Vercel (build `npm run build`).
- **Backend:** en la mini-PC, `cd infrastructure/local-fallback && docker compose up -d`. Para
  actualizar solo el API: `docker compose up -d --no-deps --build api`.

---

## Documentos de referencia

- `README.md` — introducción.
- `AUDITORIA_COMPLETA.md` — auditoría técnica completa (hallazgos por severidad).
- `CORRECCIONES_REALIZADAS.md` — bitácora de correcciones aplicadas.
- `docs/` — guías y documentos históricos.
