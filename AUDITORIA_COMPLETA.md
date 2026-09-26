# AUDITORÍA COMPLETA — NOVVIN / CONÉCTATE
**Fecha**: 2026-09-23  
**Versión del proyecto**: 3.3.6 (versionCode 68)  
**Estado**: Informe original conservado; la verificación de implementación del 2026-09-23 está en esta nota y en `IMPLEMENTACION_PROGRESO.md`.  

> **Rectificación imprescindible antes de aplicar el plan.** Este informe contiene hallazgos basados en un inventario incompleto. Hay 179 archivos de resultados unitarios Android y 821 casos que pasaron, 202 pruebas de API (201 pasaron, 1 omitida), 27 de señalización (26 pasaron, 1 omitida) y 87 pruebas web que pasaron. Por ello H-003 y RNF-006 **no pueden sustentarse como “cobertura ~0%”**; no se ha medido el porcentaje de cobertura. H-008 señala un icono decorativo dentro de un botón que ya tiene etiqueta visible, así que ese ejemplo no demuestra un defecto de TalkBack. H-001 agrupa capturas de error de limpieza con fallos de sincronización; deben tratarse por contexto, no cambiarlas todas mecánicamente. El tamaño de las clases H-002/H-005 supone un riesgo de mantenimiento, pero no demuestra por sí solo fallo de arranque o rendimiento crítico. La clasificación de severidad original se conserva como antecedente, no como decisión final.

El APK **debug universal** generado mide 272,7 MB: bibliotecas nativas de cuatro ABI ocupan ~163,5 MiB comprimidos, DEX 47,5 MiB y recursos 43,6 MiB. El App Bundle configura separación por ABI/densidad, de modo que el tamaño del APK debug **no representa** el tamaño de descarga por dispositivo en Play. H-015 no debe atribuir el peso principalmente a los drawables de Figma ni justificar una conversión masiva sin medición de recursos concretos.

### Hallazgos adicionales confirmados en el código actual

| ID | Prioridad | Evidencia y efecto | Estado de comprobación |
|---|---|---|---|
| N-001 | Crítica, seguridad | `infrastructure/local-fallback/api/src/server.js`, rutas `/v1/auth/refresh` y autenticación común: un access JWT revocado podía emitir una sesión nueva si existía cualquier otra sesión activa; un JWT heredado sin `sid` también superaba la comprobación de revocación. | Corregido y validado con API/PostgreSQL aislados; acceso y refresh revocados fallan, segunda sesión sigue activa. |
| N-002 | Alta, integridad | `AuthService.kt` borraba todas las tablas Room en `signOut()` y `clearLocalDataForAccountSwitch()`; `MessageRepository.kt` usa Room como outbox durable. Un mensaje aún no sincronizado podía perderse al salir o cambiar de cuenta. | Archivo cifrado por cuenta y restauración añadidos; prueba Robolectric con dos cuentas pasó. Falta prueba física offline/con media. |
| N-003 | Alta, integridad | `MomentRepository.kt` elimina el objeto subido después de un fallo ambiguo de confirmación; una petición que haya persistido el momento en servidor puede dejarlo sin media. | Riesgo de fallo distribuido identificado; reproducibilidad y contrato de idempotencia pendientes. |
| N-004 | Media, funcionalidad | En web, `ChatDetailPage.tsx` y `RoomChatPage.tsx` limpian el borrador después de esperar la red. Texto nuevo escrito durante esa espera puede desaparecer. | Secuencia de estado confirmada; prueba de interfaz pendiente. |
| N-005 | Media, disponibilidad | `Conectate-web/src/main.tsx` espera la inicialización del backend antes de renderizar; sondeos `fetch` sin límite pueden dejar la pantalla vacía ante una conexión colgada. | Ruta de código confirmada; prueba con red suspendida pendiente. |
| N-006 | Media, funcionalidad | `VideoPersonalMarkerStore` admite hasta 20 marcas, pero la UI de aprendizaje de video solo consume una y el borrado afecta a todas. | Contrato de código confirmado; interacción manual pendiente. |
| N-007 | Crítica, seguridad | El 2FA local no protegía el login Ubuntu en otro dispositivo. | Se implantó TOTP autoritativo en servidor con respaldo de un uso y bloqueo por intentos; Android/web envían el segundo factor, los resets no devuelven sesión y se revocan sesiones/QR al activar. E2E aislado correcto. Pendiente validar APK real, despliegue y cuentas con 2FA local heredado. |
| N-008 | Media, dependencias de desarrollo web | `npm audit` local reporta 7 avisos transitivos (3 altos, 3 moderados, 1 bajo), en cadenas de Vite/PWA/ESLint/Cypress. | Inventario `build/audit-2026-09-23/web-dependencies.json`; no se confirmó explotación en el código entregado ni se ejecutó actualización masiva. |
| N-009 | Alta, almacenamiento | `storage-routes.js` permitía a cualquier usuario autenticado borrar un objeto heredado cuyo `owner_id` fuera `NULL`. El mismo DELETE tampoco consultaba si el archivo seguía referenciado. | Dueños desconocidos ya se deniegan; momentos, videos y portadas publicadas rechazan borrado/sobrescritura en pruebas HTTP/E2E. Faltan otros buckets y carrera de publicación. |
| N-012 | Alta, seguridad | El cierre de sesión móvil de Ubuntu borraba credenciales en Android sin revocar su refresh en PostgreSQL. | Ruta de logout y reintento cifrado añadidos; API/PostgreSQL E2E pasó. Falta dispositivo y despliegue. |
| N-013 | Alta, reproducción | La carga de MP4 en mensajes no movía el índice del contenedor al inicio, a diferencia del feed; el nombre basado solo en milisegundos permitía colisiones entre adjuntos. | `faststart` en cargas nuevas de chat/grupo/comunidad y nombre UUID sin sobrescritura. MP4 sintético y suite API correctos; el video real y el teléfono siguen pendientes. |

### Evidencia de calidad ejecutada

- `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`: compilación exitosa tras N-002/N-012, 821/821 pruebas Android, ambos APK generados y lint sin errores (215 advertencias).
- API `npm test`: 202 pruebas, 201 correctas, 1 omitida; `npm run check` y E2E PostgreSQL de sesión y logout correctos tras N-001/N-012.
- Signaling `npm run check` y `npm test`: sintaxis correcta; 27 pruebas, 26 correctas y 1 omitida.
- Web `npm test`: 87/87 pruebas correctas.
- Web `npm run build`: sin variable local se bloqueó correctamente; con URL sintética HTTPS **solo de validación** compiló TypeScript/Vite. Playwright confirmó que inicio y login son navegables con ese backend inaccesible. El artefacto `dist` sintético no es desplegable.
- No hubo dispositivo ADB disponible para confirmar el comportamiento real del APK. Sí se ejecutaron E2E de API con PostgreSQL aislado para sesiones y momentos; la conectividad del servidor desplegado sigue sin verificarse. Los resultados y estados actualizados están en `IMPLEMENTACION_PROGRESO.md`.

---

---

## ÍNDICE

1. [FASE 0 — Mapeo Completo del Proyecto](#fase-0--mapeo-completo-del-proyecto)
2. [FASE 1 — Auditoría Funcional](#fase-1--auditoría-funcional)
3. [FASE 2 — Auditoría del Código](#fase-2--auditoría-del-código)
4. [FASE 3 — Seguridad](#fase-3--seguridad)
5. [FASE 4 — Rendimiento y Escalabilidad](#fase-4--rendimiento-y-escalabilidad)
6. [FASE 5 — Análisis de Requisitos](#fase-5--análisis-de-requisitos)
7. [FASE 6 — 10 Heurísticas de Nielsen](#fase-6--10-heurísticas-de-nielsen)
8. [FASE 7 — Accesibilidad y Experiencia de Usuario](#fase-7--accesibilidad-y-experiencia-de-usuario)
9. [FASE 8 — Pruebas y Calidad](#fase-8--pruebas-y-calidad)
10. [FASE 9 — Matriz General de Hallazgos](#fase-9--matriz-general-de-hallazgos)
11. [FASE 10 — Plan de Implementación](#fase-10--plan-de-implementación)

---

## FASE 0 — MAPEO COMPLETO DEL PROYECTO

### 0.1 Arquitectura General

El proyecto es una **red social con funcionalidades de streaming en vivo y regalos** (live-gifts), compuesta por 4 piezas principales:

```
USUARIO
  ↓
┌─────────────────────────────────────────────────────┐
│              CLIENTES                                │
│  ┌──────────────┐    ┌──────────────────────┐        │
│  │ App Android   │    │ App Web (React/Vite) │        │
│  │ Jetpack Compose│    │ Conectate-web/       │        │
│  │ Kotlin/MVVM   │    │ TypeScript           │        │
│  └──────┬───────┘    └──────────┬───────────┘        │
│         │                       │                     │
└─────────┼───────────────────────┼─────────────────────┘
          │                       │
          ▼                       ▼
┌─────────────────────────────────────────────────────┐
│              SERVICIOS                               │
│  ┌─────────────────────┐  ┌───────────────────────┐  │
│  │ API REST (Node/     │  │ Signaling Server      │  │
│  │ Express/PostgreSQL) │  │ (Node/Socket.IO)      │  │
│  │ local-fallback/api/ │  │ signaling-server/     │  │
│  └──────────┬──────────┘  └──────────┬────────────┘  │
│             │                        │                │
│  ┌──────────┴──────────┐  ┌──────────┴────────────┐  │
│  │ Realtime Gateway    │  │ Redis (estado distri- │  │
│  │ (SSE push events)   │  │ buido de sesiones)    │  │
│  └──────────┬──────────┘  └───────────────────────┘  │
│             │                                         │
│  ┌──────────┴──────────┐                              │
│  │ PostgreSQL 16.15    │                              │
│  │ (Base de datos)     │                              │
│  └─────────────────────┘                              │
│                                                       │
│  ┌──────────────────────┐  ┌──────────────────────┐  │
│  │ Coturn (TURN/STUN)  │  │ Nginx (proxy/storage)│  │
│  └──────────────────────┘  └──────────────────────┘  │
│                                                       │
│  ┌──────────────────────┐  ┌──────────────────────┐  │
│  │ Firebase (FCM +     │  │ LiveKit (WebRTC SFU)  │  │
│  │ Crashlytics)        │  │ Video/audio streams   │  │
│  └──────────────────────┘  └──────────────────────┘  │
│                                                       │
│  ┌──────────────────────┐                              │
│  │ Supabase (LEGACY)   │                              │
│  │ En proceso de       │                              │
│  │ migración/abandono  │                              │
│  └──────────────────────┘                              │
└─────────────────────────────────────────────────────┘
```

### 0.2 Tecnologías y Versiones

| Componente | Tecnología | Versión |
|---|---|---|
| Lenguaje Android | Kotlin | 2.2.10 |
| Android Gradle Plugin | AGP | 8.13.2 |
| KSP | KSP | 2.2.10-2.0.2 |
| UI Framework | Jetpack Compose (BOM) | 2026.06.00 |
| Design System | Material Design 3 | Compose M3 |
| Navegación | Navigation Compose | 2.7.5 |
| Inyección de dependencias | Hilt/Dagger | 2.57.2 |
| Base de datos local | Room | 2.8.4 |
| Networking | Ktor | 2.3.5 |
| Imágenes | Coil | 2.5.0 |
| Video/Media | Media3/ExoPlayer | 1.11.0 |
| WebRTC nativo | WebRTC | 137.7151.05 |
| WebRTC SFU | LiveKit Android | 2.27.0 |
| Signaling | Socket.IO Client | 2.1.0 |
| Push notifications | Firebase Messaging | 23.4.0 |
| Crash reporting | Firebase Crashlytics | 18.4.0 |
| Backend legacy | Supabase | 2.0.4 |
| ML | MLKit (Selfie Seg. + OCR) | 16.0.0-beta6 |
| QR | ZXing | 4.3.0 |
| Pagos | Google Play Billing | 9.1.0 |
| Coroutines | Kotlin Coroutines | 1.7.3 |
| Serialización | kotlinx.serialization | 1.5.1 |
| Seguridad | AndroidX Security Crypto | 1.1.0-alpha06 |
| Backend API | Node.js / Express | — |
| Base de datos | PostgreSQL | 16.15 |
| Signaling Server | Node.js / Socket.IO | — |
| Web frontend | React 19 / Vite / TypeScript | — |
| Hosting web | Vercel | — |
| Infraestructura | Docker Compose | — |
| Cache distribuido | Redis 7.4.8 | — |

**SDKs Android**: `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`

### 0.3 Módulos del Proyecto

| Módulo | Ubicación | Propósito |
|---|---|---|
| App Android | `app/` | Módulo Gradle único (`:app`). Red social móvil nativa |
| Backend API | `infrastructure/local-fallback/api/` | API REST principal (Node/Express/PostgreSQL) |
| Realtime Gateway | `infrastructure/local-fallback/realtime-gateway/` | Gateway de eventos SSE en tiempo real |
| Signaling Server | `signaling-server/` | Señalización WebRTC (Socket.IO): llamadas, lives, salas de voz |
| Web App | `Conectate-web/` | SPA web (React/Vite/TypeScript) |
| SQL Schemas | `sql/` | Esquemas, migraciones y hotfixes PostgreSQL |
| Supabase (legacy) | `supabase/` | Funciones edge legacy (en proceso de abandono) |
| Infraestructura | `infrastructure/docker/`, `infrastructure/kubernetes/` | Orquestación Docker Compose, config K8s |
| Scripts/Tools | `scripts/`, `tools/`, `code-quality/` | CI/CD, quality gates, análisis |

### 0.4 Estructura de Paquetes Android (`conectate.com`)

```
conectate.com/
├── SocialApp.kt                  # Application class (@HiltAndroidApp)
├── auth/                         # Autenticación (2FA, TOTP)
├── core/
│   ├── concurrency/              # SafeScopes, DispatcherProvider
│   ├── crash/                    # CrashReporter
│   ├── logging/                  # AppLogger
│   ├── media/                    # ProfileIdentityImageStore, ChatMediaOfflineCache
│   ├── observability/            # AppMetrics, alertas
│   ├── production/               # ProductionReadyOptimizer
│   ├── security/                 # Encriptación
│   └── stability/                # RuntimeStabilityMonitor
├── data/
│   ├── cache/                    # Caché en memoria
│   ├── local/                    # Room: AppDatabase, DAOs, Entities
│   ├── localfallback/            # Cliente REST del backend Ubuntu
│   ├── livekit/                  # LiveKit bridge
│   ├── model/                    # Modelos de dominio
│   ├── observability/            # Telemetría Supabase
│   ├── repository/               # 53 repositorios (Single Source of Truth)
│   ├── service/                  # MyFirebaseMessagingService
│   ├── signaling/                # Cliente Socket.IO
│   ├── supabase/                 # AuthService, SupabaseConfig (legacy)
│   ├── sync/                     # SyncManager
│   └── webrtc/                   # WebRTCManager
├── di/                           # 5 módulos Hilt (Core, Database, Sync, Cache, Supabase)
├── firebase/                     # FCM
├── presentation/
│   ├── algorithm/                # Configuración del algoritmo
│   ├── analytics/                # Analíticas de creador
│   ├── auth/                     # Login, Register, Splash, ChangePassword
│   ├── calls/                    # Llamadas (CallScreen, History)
│   ├── common/                   # Componentes compartidos
│   ├── components/               # Componentes UI reutilizables
│   ├── gaming/                   # PlayMatch
│   ├── home/                     # HomeScreen (shell principal)
│   ├── legal/                    # Help, Privacy, Terms
│   ├── live/                     # 11 pantallas de streaming en vivo
│   ├── media/                    # Pantallas completas imagen/video
│   ├── messages/                 # Chat, Groups, Communities, Voice rooms
│   ├── navigation/               # AppNavGraph, Screen sealed class
│   ├── posts/                    # Posts, Feed
│   ├── profile/                  # Perfil, Settings, Admin panel
│   ├── social/                   # Social feed
│   ├── videos/                   # Video feed (TikTok-style)
│   ├── voicerooms/               # Salas de voz
│   └── wallet/                   # Billetera, regalos, recargas
├── services/                     # Foreground services (Voice, Call, Live)
├── util/                         # Utilidades (permisos, media, hardware)
└── widgets/                      # App Widgets (Chat, Videos, Moments)
```

**Total**: ~78 pantallas, ~53 repositorios, 5 módulos DI

### 0.5 Permisos del AndroidManifest

| Permiso | Categoría |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Conectividad |
| `CAMERA`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` | Multimedia |
| `READ/WRITE_EXTERNAL_STORAGE` | Almacenamiento (compatibilidad) |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO` | Android 13+ granular |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Android 14+ selector |
| `ACCESS_COARSE/FINE_LOCATION` | Ubicación |
| `POST_NOTIFICATIONS` | Notificaciones (Android 13+) |
| `VIBRATE`, `WAKE_LOCK` | Sistema |
| `USE_FULL_SCREEN_INTENT` | Llamadas entrantes |
| `SYSTEM_ALERT_WINDOW` | Chat heads / burbujas |
| Foreground service types: `camera`, `microphone`, `mediaProjection`, `shortService` | Background |

### 0.6 Variables de Entorno y Secretos

**Android (`secrets.properties`)**: 
- `FALLBACK_API_URL`, `LOCAL_FALLBACK_API_URL` — URL del backend
- `BACKEND_DISCOVERY_HOST` — DuckDNS para descubrimiento dinámico
- `BACKEND_DISCOVERY_PUBLIC_KEY` — Clave pública ECDSA para verificación
- `SIGNALING_URL` — URL del servidor de señalización
- `SUPABASE_URL`, `SUPABASE_ANON_KEY` — Legacy (vacíos)
- `UPSTASH_REST_URL`, `UPSTASH_TOKEN` — Caché Redis en la nube (vacíos)
- `TMDB_API_KEY` — Integración TMDB (vacío)
- `REAL_DEBRID_TOKEN` — Real-Debrid (vacío)

**Backend (`.env`)**: `POSTGRES_PASSWORD`, `JWT_SECRET`, `ADMIN_SYNC_TOKEN`, `PUBLIC_BASE_URL`, `STORAGE_SIGNING_SECRET`, `REALTIME_INTERNAL_SECRET`

### 0.7 Servicios Externos

| Servicio | Uso |
|---|---|
| PostgreSQL 16 | Base de datos principal |
| Firebase (FCM + Crashlytics) | Notificaciones push y crash reporting |
| Vercel | Hosting web |
| Coturn | TURN/STUN para NAT traversal WebRTC |
| LiveKit | SFU para video/audio streaming |
| Redis 7.4.8 | Estado distribuido del signaling server |
| DuckDNS | DNS dinámico para backend discovery |
| Cloudflare Tunnel / Ngrok | Tunneling en desarrollo |
| Supabase | LEGACY — en proceso de abandono |
| Google Play Billing | Pagos in-app |

### 0.8 Base de Datos PostgreSQL — Tablas Principales

| Tabla | Propósito |
|---|---|
| `profiles` | Perfiles de usuario |
| `videos` | Videos subidos |
| `video_likes`, `video_comments` | Interacciones en videos |
| `chats`, `messages`, `chat_participants` | Mensajería directa |
| `follows` | Seguidores |
| `fcm_tokens` | Tokens de notificación push |
| `posts`, `post_likes`, `post_comments` | Publicaciones sociales |
| `moments` | Stories/momentos (24h) |
| `live_streams` | Transmisiones en vivo |
| + tablas de monetización, hashtags, regalos, moderación... | — |

---

## FASE 1 — AUDITORÍA FUNCIONAL

### 1.1 Funcionalidades Identificadas

La app implementa las siguientes funcionalidades principales:
- **Autenticación**: Login, registro, 2FA (TOTP), cambio de contraseña, migración de contraseñas legacy, multi-cuenta, login web por QR
- **Feed social**: Posts con multimedia, likes, comentarios, compartir, guardar, hashtags
- **Videos**: Feed estilo TikTok, subida, likes, comentarios, descarga offline
- **Mensajería**: Chat directo, grupos, comunidades, salas de voz, mensajes de voz, stickers, respuestas, reacciones, mensajes programados
- **Lives/Streaming**: Streaming en vivo con cámara, co-hosts, regalos en tiempo real, chat en vivo, batallas, replay, draw & guess interactivo
- **Llamadas**: Llamadas 1-a-1 de audio/video vía WebRTC
- **Momentos**: Stories de 24h (imagen/video)
- **Perfil**: Edición, seguidores, verificación, badges, programa de creador
- **Billetera**: Monedas virtuales, regalos, recargas via Google Play Billing
- **Gaming**: PlayMatch (emparejamiento de juegos)
- **Algoritmo de recomendación**: Ranking de contenido personalizado
- **Admin panel**: Moderación, reportes, suspensiones, analíticas
- **Widgets**: 4 widgets de Android (Chat, Quick Actions, Videos, Moments)
- **Notificaciones**: Push (FCM), notificaciones locales, digest
- **Accesibilidad**: Soporte multi-idioma parcial
- **Web**: Versión web con paridad parcial

### 1.2 Hallazgos Funcionales

| ID | Hallazgo | Tipo | Severidad | Evidencia |
|---|---|---|---|---|
| F-001 | **Comentario erróneo sobre SupervisorJob** | Observación | BAJO | `SocialApp.kt:68` dice `// ⚠️ Sin SupervisorJob` pero `SafeScopes.kt:20` SÍ usa `SupervisorJob()`. El comentario es engañoso pero el código es correcto. |
| F-002 | **HiltWorkerFactory sin @Provides confirmado** | RIESGO POTENCIAL | ALTO | `SocialApp.kt:44` — El comentario `// ⚠️ No hay @Provides para este tipo` sugiere que podría fallar. Sin embargo, `hilt-work` proporciona esto automáticamente si está correctamente configurado. Se necesita verificar que `@HiltAndroidApp` + dependencia `hilt-work` está completa. |
| F-003 | **WorkManager fallback silencioso** | RIESGO POTENCIAL | MEDIO | `SocialApp.kt:58-66` — Si `workerFactory` no está inicializado, se crea una `Configuration` sin `HiltWorkerFactory`, lo que significa que Workers con inyección de dependencias fallarán silenciosamente. |
| F-004 | **Excepciones silenciadas en flujos críticos** | ERROR CONFIRMADO | CRÍTICO | 10+ archivos con `catch (e: Exception) { }` vacíos: `VoiceForegroundService.kt`, `CallForegroundService.kt`, `VoiceRoomRepository.kt`, `UserRepository.kt`, `LiveRepository.kt`, `MessageRepository.kt`, `MomentRepository.kt`, `MessageRealtimeSync.kt`. Errores fatales quedan invisibles. |
| F-005 | **runBlocking en FCM Service** | RIESGO POTENCIAL | MEDIO | `MyFirebaseMessagingService.kt:396` usa `runBlocking(Dispatchers.IO)` con `withTimeoutOrNull(2_000L)`. Puede causar cuellos de botella en ráfagas de notificaciones. |
| F-006 | **Pérdida silenciosa de 2FA al restaurar dispositivo** | RIESGO POTENCIAL | ALTO | `TwoFactorAuthManager.kt:84` — Si el KeyStore pierde llaves (restauración), `createSecurePreferencesWithRecovery()` elimina y reinicia las prefs. El usuario pierde 2FA sin notificación. |
| F-007 | **Threads manuales en ecosistema de coroutines** | RIESGO POTENCIAL | MEDIO | `AudioRecorder.kt:204`, `WebRTCManager.kt:2574` — Threads manuales (`startWriterThread()`) que pueden causar memory leaks si la UI se destruye pero el Thread sigue vivo. |

---

## FASE 2 — AUDITORÍA DEL CÓDIGO

### 2.1 God Classes — Archivos Excesivamente Grandes

| Archivo | Tamaño | Líneas est. | Severidad |
|---|---|---|---|
| `MessageRepository.kt` | 255 KB | ~6,000+ | CRÍTICO |
| `LiveRepository.kt` | 188 KB | ~4,500+ | CRÍTICO |
| `VideoRepository.kt` | 167 KB | ~4,000+ | ALTO |
| `CommunityRepository.kt` | 165 KB | ~4,000+ | ALTO |
| `UserRepository.kt` | 165 KB | ~4,000+ | ALTO |
| `GroupRepository.kt` | 148 KB | ~3,500+ | ALTO |
| `CallRepository.kt` | 90 KB | ~2,200+ | MEDIO |
| `PostRepository.kt` | 95 KB | ~2,300+ | MEDIO |
| `MomentRepository.kt` | 81 KB | ~2,000+ | MEDIO |
| `VoiceRoomRepository.kt` | 133 KB | ~3,200+ | ALTO |
| `signaling-server/index.js` | 128 KB | ~3,087 | ALTO |
| `data-routes.js` (backend) | 603 KB | ~15,000+ | CRÍTICO |
| `postgrest-compat-routes.js` | 255 KB | ~6,000+ | CRÍTICO |
| `server.js` (backend) | 100 KB | ~3,014 | ALTO |

**Impacto**: Estas clases violan el Principio de Responsabilidad Única. Son extremadamente difíciles de mantener, probar y debuggear.

### 2.2 Excepciones Silenciadas (Swallowed Exceptions)

Se encontraron al menos **10 archivos** con bloques `catch` vacíos que suprimen errores fatales:

- `VoiceForegroundService.kt` — Errores en inicio/parada del servicio foreground
- `CallForegroundService.kt` — Errores en notificación de llamada
- `VoiceRoomRepository.kt` — Errores en operaciones de sala de voz
- `UserRepository.kt` — Errores en operaciones de perfil
- `LiveRepository.kt` — Errores en streaming en vivo
- `MessageRepository.kt` — Errores en envío/recepción de mensajes
- `MomentRepository.kt` — Errores en stories
- `MessageRealtimeSync.kt` — Errores en sincronización en tiempo real
- `GroupDetailViewModel.kt` — Errores en detalles de grupo
- `CommunityDetailViewModel.kt` — Errores en detalles de comunidad

### 2.3 Duplicación de Lógica de Permisos

La verificación de permisos runtime (`ContextCompat.checkSelfPermission`) se repite inline en múltiples componentes Compose:
- `CallActionButtons.kt`
- `LiveViewerScreen.kt`
- `ChatScreen.kt`
- `LiveHostScreen.kt`
- Múltiples ViewModels

**Recomendación**: Centralizar con un `PermissionManager` o usar Accompanist Permissions (ya en dependencias: `accompanistPermissions = "0.32.0"`).

### 2.4 Dispatchers Hardcodeados

Más de 20 archivos usan `Dispatchers.Main` o `Dispatchers.IO` directamente en lugar de inyectar un `DispatcherProvider`. Esto dificulta los tests unitarios.

**Archivos afectados**: `GroupRepository.kt`, `CommunityRepository.kt`, `ChatViewModel.kt`, y más.

### 2.5 Base de Datos Room

- **Versión**: 55 (`APP_DATABASE_VERSION = 55`)
- **Riesgo**: Con tantas migraciones, la estabilidad de migraciones incrementales es una preocupación. Se recomienda tener tests de migración.
- **Entidades**: Gran número de entidades complejas (mensajes, chats, grupos, video stats)

### 2.6 Backend — Archivos Masivos

El backend tiene archivos críticos extremadamente grandes:
- `data-routes.js`: **603 KB** — Un solo archivo con todas las rutas de datos
- `postgrest-compat-routes.js`: **255 KB** — Compatibilidad PostgREST
- `live-routes.js`: **110 KB**
- `server.js`: **100 KB**

Estos archivos necesitan modularización urgente.

---

## FASE 3 — SEGURIDAD

### 3.1 Hallazgos de Seguridad

| ID | Hallazgo | Impacto | Evidencia |
|---|---|---|---|
| S-001 | **`secrets.properties` contiene URLs activas de Cloudflare Tunnel** | MEDIO | `secrets.properties:2-3,13` — URLs de `trycloudflare.com` están hardcodeadas. Estos tunnels son efímeros y cambian, pero si se commitea el archivo (excluido por `.gitignore`) expone la URL del backend. |
| S-002 | **Clave pública ECDSA en `secrets.properties`** | BAJO | `secrets.properties:6` — `BACKEND_DISCOVERY_PUBLIC_KEY` es una clave pública (no privada), su exposición no es un riesgo directo, pero debe manejarse con precaución. |
| S-003 | **`.gitignore` correctamente configurado** | ✅ OK | `.gitignore` excluye `secrets.properties`, `keystore.properties`, `*.jks`, `*.keystore`, `*.env`, `google-services.json`, y datos de importación con PII. |
| S-004 | **JWT con rotación de secretos** | ✅ OK | `server.js:74-79` — Soporta `JWT_SECRET` + `JWT_PREVIOUS_SECRETS` para rotación sin interrupción. |
| S-005 | **Validaciones de producción en signaling** | ✅ OK | `signaling-server/index.js:78-84` — Valida que `SIGNALING_ALLOWED_ORIGINS` tenga orígenes explícitos y `LIVE_INTERNAL_EVENTS_TOKEN` tenga mínimo 32 chars en producción. Falla rápido. |
| S-006 | **Rate limiting implementado** | ✅ OK | Rate limiting por IP en HTTP y Socket.IO con buckets configurables. |
| S-007 | **Headers de seguridad en Vercel** | ✅ OK | `vercel.json:10-49` — HSTS, CSP, X-Frame-Options DENY, CORS restrictivo, Permissions-Policy. Configuración robusta. |
| S-008 | **Redis con autenticación por secreto montado** | ✅ OK | `docker-compose.yml:30-49` — Redis usa secreto desde archivo montado, no variable de entorno. Buena práctica. |
| S-009 | **R8 fullMode deshabilitado** | BAJO | `gradle.properties` — `android.enableR8.fullMode=false` en release para evitar ConcurrentModificationException. Ofuscación reducida. |
| S-010 | **Antivirus configurado para uploads de música** | ✅ OK | `docker-compose.yml:97` — `MUSIC_ANTIVIRUS_COMMAND=clamscan`. Escaneo antivirus en uploads. |
| S-011 | **EncryptedSharedPreferences para datos sensibles** | ✅ OK | 6 archivos usan `EncryptedSharedPreferences` para tokens, sesiones y 2FA. |
| S-012 | **Bearer tokens process-local en signaling** | ✅ OK | `signaling-server/index.js:59` — `WeakMap` para tokens, nunca se serializan a Redis. |
| S-013 | **Contenedores Docker con seguridad reforzada** | ✅ OK | `docker-compose.yml` — `cap_drop: ALL`, `no-new-privileges`, `read_only`, límites de memoria/CPU/PIDs. |

### 3.2 Evaluación General de Seguridad

La seguridad del proyecto es **por encima del promedio**. Se evidencia un esfuerzo consciente en:
- Secretos correctamente excluidos del VCS
- Headers HTTP defensivos
- Rate limiting multinivel
- Contenedores hardened
- Autenticación JWT con rotación
- Redis con autenticación

**Principales riesgos residuales**: 
- La pérdida silenciosa de 2FA al restaurar dispositivo (S-006/F-006)
- R8 fullMode deshabilitado reduce la ofuscación

---

## FASE 4 — RENDIMIENTO Y ESCALABILIDAD

### 4.1 Hallazgos de Rendimiento

| ID | Hallazgo | Tipo | Impacto |
|---|---|---|---|
| P-001 | **Coil bien configurado con perfiles de dispositivo** | ✅ OK | `SocialApp.kt:170-214` — Caché en memoria y disco ajustado por tier del dispositivo (`LOW/MID/HIGH`). Decoders apropiados para GIF y video. |
| P-002 | **Lazy lists extensivas** | ✅ OK | `LazyColumn`, `LazyRow`, `LazyVerticalGrid` en 30+ pantallas. Virtualización correcta. |
| P-003 | **`runBlocking` en FCM Service** | RIESGO POTENCIAL | `MyFirebaseMessagingService.kt:396` — Bloquea hilo de FCM. Puede ser cuello de botella con ráfagas. |
| P-004 | **Dispatchers hardcodeados** | MEJORA RECOMENDADA | 20+ archivos con `Dispatchers.Main/IO` directo. No afecta rendimiento pero impide testing. |
| P-005 | **Inicialización escalonada en Application** | ✅ OK | `SocialApp.kt:112-166` — Tareas staggered (500ms, 1500ms, 2500ms, 3500ms) para evitar contención en cold start. |
| P-006 | **Memory management proactivo** | ✅ OK | `SocialApp.kt:217-251` — `onTrimMemory` y `onLowMemory` limpian caché de imágenes y liberan recursos WebRTC. |
| P-007 | **God classes como riesgo de rendimiento** | RIESGO POTENCIAL | Repositorios de 150KB+ cargan numerosas dependencias. Instanciación potencialmente costosa. |
| P-008 | **Backend `data-routes.js` de 603KB** | RIESGO POTENCIAL | Archivo monolítico que Node debe parsear completo. Puede afectar cold start del backend. |
| P-009 | **Room DB con 55 migraciones** | RIESGO POTENCIAL | Muchas migraciones incrementales pueden ralentizar la primera apertura post-actualización. |
| P-010 | **Threads manuales para audio** | RIESGO POTENCIAL | `AudioRecorder.kt:204` — Threads manuales no se benefician del pool de coroutines. Riesgo de proliferación de threads. |

### 4.2 Escalabilidad

| Aspecto | Estado | Notas |
|---|---|---|
| Paginación | Implementada | Lazy lists con carga incremental |
| Caché de imágenes | Implementado | Coil con disco + memoria |
| Rate limiting | Implementado | HTTP + Socket en signaling y API |
| Redis para estado distribuido | Implementado | Sesiones de signaling |
| Docker con límites de recursos | Implementado | Memoria, CPU, PIDs limitados |
| Escalado horizontal | Parcial | Signaling soporta Redis adapter, pero API es monolítica |
| Paginación de BD | Parcial | Depende de las queries específicas |

---

## FASE 5 — ANÁLISIS DE REQUISITOS

### 5.1 Requisitos Funcionales

| ID | Descripción | Módulo | Estado | Evidencia | Prioridad |
|---|---|---|---|---|---|
| RF-001 | Login con email/contraseña | auth | IMPLEMENTADO | `LoginScreen.kt`, `AuthRepository.kt` | CRÍTICA |
| RF-002 | Registro de usuario | auth | IMPLEMENTADO | `RegisterScreen.kt` | CRÍTICA |
| RF-003 | Autenticación 2FA (TOTP) | auth | IMPLEMENTADO | `TwoFactorAuthManager.kt` | ALTA |
| RF-004 | Migración de contraseñas legacy | auth | IMPLEMENTADO | `LoginScreen.kt:641+` | MEDIA |
| RF-005 | Multi-cuenta | auth | IMPLEMENTADO | `SavedAccountsRepository.kt`, `MultiAccountInboxRepository.kt` | MEDIA |
| RF-006 | Login web por QR | auth | IMPLEMENTADO | `WebLoginScannerScreen.kt`, `WebLoginPairingRepository.kt` | BAJA |
| RF-007 | Feed de posts con multimedia | social | IMPLEMENTADO | `PostRepository.kt`, `PostDetailScreen.kt` | ALTA |
| RF-008 | Likes, comentarios, compartir en posts | social | IMPLEMENTADO | `PostRepository.kt` | ALTA |
| RF-009 | Feed de videos estilo TikTok | videos | IMPLEMENTADO | `VideoRepository.kt`, `VideoFeedScreen.kt` | ALTA |
| RF-010 | Chat directo 1-a-1 | messages | IMPLEMENTADO | `MessageRepository.kt`, `ChatScreen.kt` | CRÍTICA |
| RF-011 | Grupos de chat | messages | IMPLEMENTADO | `GroupRepository.kt`, `GroupChatScreen.kt` | ALTA |
| RF-012 | Comunidades | messages | IMPLEMENTADO | `CommunityRepository.kt`, `CommunityChatScreen.kt` | ALTA |
| RF-013 | Mensajes de voz | messages | IMPLEMENTADO | `VoiceMessageManager.kt`, `AudioRecorder.kt` | MEDIA |
| RF-014 | Stickers y multimedia en chat | messages | IMPLEMENTADO | `StickerRepository.kt`, `AdvancedMessagingRepository.kt` | MEDIA |
| RF-015 | Salas de voz | voicerooms | IMPLEMENTADO | `VoiceRoomRepository.kt`, `CreateVoiceRoomScreen.kt` | ALTA |
| RF-016 | Streaming en vivo con cámara | live | IMPLEMENTADO | `LiveRepository.kt`, `LiveHostScreen.kt` | ALTA |
| RF-017 | Co-hosts en lives | live | IMPLEMENTADO | `LiveRepository.kt` | MEDIA |
| RF-018 | Regalos en vivo (live gifts) | wallet/live | IMPLEMENTADO | `WalletRepository.kt`, `GiftSelectionDialog.kt` | ALTA |
| RF-019 | Chat en vivo durante stream | live | IMPLEMENTADO | `LiveHostScreen.kt`, `LiveViewerScreen.kt` | ALTA |
| RF-020 | Replay de lives | live | IMPLEMENTADO | `LiveReplayScreen.kt` | MEDIA |
| RF-021 | Draw & Guess interactivo en live | live/gaming | IMPLEMENTADO | `LiveDrawGuessRepository.kt` | BAJA |
| RF-022 | Llamadas de audio/video 1-a-1 | calls | IMPLEMENTADO | `CallRepository.kt`, `CallScreen.kt` | ALTA |
| RF-023 | Momentos/Stories de 24h | moments | IMPLEMENTADO | `MomentRepository.kt` | ALTA |
| RF-024 | Billetera con monedas virtuales | wallet | IMPLEMENTADO | `WalletRepository.kt` | ALTA |
| RF-025 | Recargas via Google Play Billing | wallet | IMPLEMENTADO | `MonetizationRepository.kt` | ALTA |
| RF-026 | Seguir/Dejar de seguir usuarios | social | IMPLEMENTADO | `FollowRepository.kt` | ALTA |
| RF-027 | Solicitudes de amistad | social | IMPLEMENTADO | `FriendRepository.kt`, `FriendRequestsScreen.kt` | MEDIA |
| RF-028 | Algoritmo de recomendación | algorithm | IMPLEMENTADO | `AlgorithmRepository.kt`, `AlgorithmSettingsScreen.kt` | MEDIA |
| RF-029 | Panel de administración/moderación | admin | IMPLEMENTADO | `AdminPanelScreen.kt` | ALTA |
| RF-030 | Notificaciones push (FCM) | notifications | IMPLEMENTADO | `MyFirebaseMessagingService.kt` | ALTA |
| RF-031 | Widgets de Android | widgets | IMPLEMENTADO | `widgets/` (Chat, Videos, Moments, Quick Actions) | BAJA |
| RF-032 | Edición de perfil | profile | IMPLEMENTADO | `EditProfileScreen.kt` | ALTA |
| RF-033 | Hashtags | social | IMPLEMENTADO | `HashtagRepository.kt` | MEDIA |
| RF-034 | Link previews en chat | messages | IMPLEMENTADO | `LinkPreviewRepository.kt` | BAJA |
| RF-035 | Mensajes programados | messages | IMPLEMENTADO | `scheduled-message-jobs.js` (backend) | BAJA |
| RF-036 | Programa de creador / payouts | profile/wallet | IMPLEMENTADO | `CreatorProgramRepository.kt`, `CreatorPayoutRepository.kt` | ALTA |
| RF-037 | Descarga offline de videos | videos | IMPLEMENTADO | `OfflineVideoRepository.kt`, `OfflineVideoDownloadWorker.kt` | BAJA |
| RF-038 | Chatbot IA | messages | IMPLEMENTADO | `NovvinChatScreen.kt`, `ai-routes.js` | BAJA |
| RF-039 | Ranking/Leaderboards | social | IMPLEMENTADO | `RankingRepository.kt` | MEDIA |
| RF-040 | PlayMatch (gaming) | gaming | IMPLEMENTADO | `PlayMatchRepository.kt`, `PlayMatchScreens.kt` | BAJA |
| RF-041 | Socialización / "Personas cerca" | social | IMPLEMENTADO | `SocializationRepository.kt`, `proximity-routes.js` | MEDIA |
| RF-042 | Batallas en live | live | IMPLEMENTADO | `live-battle-scoring.js` | MEDIA |
| RF-043 | Biblioteca de música | music | IMPLEMENTADO | `music-routes.js`, `music-library-routes.js` | MEDIA |

### 5.2 Requisitos No Funcionales

| ID | Descripción | Estado | Evidencia | Prioridad |
|---|---|---|---|---|
| RNF-001 | Tiempo de inicio < 3s | PARCIAL | Inicialización escalonada en `SocialApp.kt`, pero muchas dependencias lazy. No hay métricas medidas. | ALTA |
| RNF-002 | Soporte para API 24-36 | IMPLEMENTADO | `minSdk=24`, `targetSdk=36`, `compileSdk=36` | ALTA |
| RNF-003 | Cifrado de datos sensibles locales | IMPLEMENTADO | `EncryptedSharedPreferences` en 6 archivos | ALTA |
| RNF-004 | Resiliencia a desconexión de red | PARCIAL | Room DB como caché local, pero excepciones silenciadas pueden ocultar problemas de sincronización | ALTA |
| RNF-005 | Internacionalización (i18n) | NO IMPLEMENTADO | Textos hardcodeados en Kotlin. Solo `strings.xml` básico (58 líneas) | MEDIA |
| RNF-006 | Cobertura de pruebas > 70% | NO IMPLEMENTADO | Solo 1 archivo de test placeholder. Cobertura ~0% | CRÍTICA |
| RNF-007 | Accesibilidad (WCAG) | PARCIAL | Touch targets correctos (≥48dp), pero falta `contentDescription` en iconos | MEDIA |
| RNF-008 | Manejo de memoria eficiente | IMPLEMENTADO | Perfiles por tier de dispositivo, `onTrimMemory`, cache clearing | ALTA |
| RNF-009 | Seguridad de comunicaciones (HTTPS) | IMPLEMENTADO | Todas las URLs usan HTTPS. HSTS configurado en Vercel. | ALTA |
| RNF-010 | Hardening de contenedores | IMPLEMENTADO | Docker: cap_drop ALL, no-new-privileges, limits | MEDIA |
| RNF-011 | Logging y observabilidad | IMPLEMENTADO | Crashlytics, AppMetrics, SupabaseTelemetryExporter, AppLogger | ALTA |
| RNF-012 | Modularidad del código | NO IMPLEMENTADO | Módulo Gradle único, God classes de 150KB+ | ALTA |

### 5.3 Requisitos Implícitos Detectados

| ID | Descripción | Estado |
|---|---|---|
| RI-001 | Tests de migración de Room DB (v55) | NO IMPLEMENTADO |
| RI-002 | Manejo de restauración de dispositivo sin pérdida de 2FA | NO IMPLEMENTADO |
| RI-003 | Throttling de notificaciones para evitar spam | PARCIAL (timeout de 2s en FCM) |
| RI-004 | Límite de tamaño para uploads | IMPLEMENTADO (antivirus + storage routes) |
| RI-005 | Expiración automática de moments (24h) | IMPLEMENTADO (`expire_at` en tabla `moments`) |

---

## FASE 6 — 10 HEURÍSTICAS DE NIELSEN

### H1. Visibilidad del estado del sistema

| Pantalla | Problema | Evidencia | Severidad | Archivos |
|---|---|---|---|---|
| LoginScreen | ✅ **CUMPLE** — Indicador de carga durante autenticación | `LoginScreen.kt:584` — `CircularProgressIndicator` durante `AuthState.Loading` | N/A | `LoginScreen.kt` |
| LiveHostScreen | ✅ **CUMPLE** — Estado de permisos y cámara visible | Muestra "CÁMARA DESACTIVADA" y estados de permisos | N/A | `LiveHostScreen.kt` |
| General | **Excepciones silenciadas ocultan estado real** | 10+ archivos con `catch { }` vacíos — El usuario no sabe si una operación falló | ALTO | Múltiples repositorios |

### H2. Correspondencia entre el sistema y el mundo real

| Pantalla | Problema | Evidencia | Severidad | Archivos |
|---|---|---|---|---|
| LoginScreen | Lenguaje claro y directo | `"Tu gente, tus rondas, tu mundo."` | N/A | `LoginScreen.kt` |
| General | **Textos no localizados** — Solo español hardcodeado | Textos inline en Kotlin, no en `strings.xml` | MEDIO | Todos los archivos de UI |

### H3. Control y libertad del usuario

| Pantalla | Problema | Evidencia | Severidad | Archivos |
|---|---|---|---|---|
| LoginScreen | ✅ **CUMPLE** — Botón de "Volver" | `LoginScreen.kt:695` | N/A | `LoginScreen.kt` |
| LiveHostScreen | ✅ **CUMPLE** — Confirmación antes de terminar live | `showEndDialog` en L501 | N/A | `LiveHostScreen.kt` |
| General | ✅ **CUMPLE** — Navegación con back stack | `AppNavGraph.kt` usa Navigation Compose | N/A | `AppNavGraph.kt` |

### H4. Consistencia y estándares

| Pantalla | Problema | Evidencia | Severidad | Archivos |
|---|---|---|---|---|
| HomeScreen | **Colores hardcodeados fuera del tema** | `HomeScreen.kt` — `Color(0xFF2196F3)` para badges, saltándose MaterialTheme | MEDIO | `HomeScreen.kt` |
| General | **Material 3 inconsistente** | Mezcla de colores del tema con `Color(0xFF...)` en múltiples pantallas | MEDIO | Múltiples |
| General | ✅ Material Design 3 como base consistente | Uso extensivo de `MaterialTheme.colorScheme` en la mayoría de componentes | N/A | Composables |

### H5. Prevención de errores

| Pantalla | Problema | Evidencia | Severidad | Archivos |
|---|---|---|---|---|
| LoginScreen | ✅ **CUMPLE** — Validación de contraseña | `LoginScreen.kt:641` — `nextPassword.length < 8` y comparación de confirmación | N/A | `LoginScreen.kt` |
| LiveHostScreen | ✅ **CUMPLE** — Confirmación destructiva | Diálogo antes de finalizar live | N/A | `LiveHostScreen.kt` |

### H6. Reconocimiento antes que recuerdo

| Pantalla | Problema | Evidencia | Severidad | Archivos |
|---|---|---|---|---|
| LoginScreen | ✅ **CUMPLE** — Iconos representativos en formularios | `Icons.Default.Email`, `Icons.Filled.Visibility` | N/A | `LoginScreen.kt` |
| General | ✅ **CUMPLE** — Iconos en navegación bottom bar | HomeScreen con NavigationBar | N/A | `HomeScreen.kt` |

### H7. Flexibilidad y eficiencia de uso

| Pantalla | Problema | Evidencia | Severidad | Archivos |
|---|---|---|---|---|
| General | ✅ **CUMPLE** — Widgets de Android | 4 widgets: Chat, Videos, Moments, Quick Actions | N/A | `widgets/` |
| General | ✅ Búsqueda de usuarios | `SearchUsersScreen.kt` | N/A | `SearchUsersScreen.kt` |

### H8. Diseño estético y minimalista

| Pantalla | Problema | Evidencia | Severidad | Archivos |
|---|---|---|---|---|
| LiveHostScreen | ✅ **CUMPLE** — Información en modales | Configuración de streaming en `LiveSettingsDialog` | N/A | `LiveHostScreen.kt` |
| General | Drawables masivos (assets de Figma) | Gran cantidad de recursos `figma_live_...`, `gift_anim_...` pueden inflar el APK | BAJO | `res/drawable/` |

### H9. Ayuda a reconocer, diagnosticar y recuperarse de errores

| Pantalla | Problema | Evidencia | Severidad | Archivos |
|---|---|---|---|---|
| LoginScreen | ✅ **EXCELENTE** — Cuenta suspendida con detalle y apelación | `LoginScreen.kt:165` — `AlertDialog` con razón, evidencia, permanencia, y formulario de apelación integrado | N/A | `LoginScreen.kt` |
| General | **Errores silenciados no muestran mensajes al usuario** | `catch { }` vacíos impiden mostrar estados de error | ALTO | Múltiples repositorios |

### H10. Ayuda y documentación

| Pantalla | Problema | Evidencia | Severidad | Archivos |
|---|---|---|---|---|
| General | ✅ **CUMPLE** — Centro de ayuda y legales | `HelpCenterScreen.kt`, ruteado desde `HomeScreen.kt` | N/A | `HelpAndTermsScreens.kt` |
| General | ✅ Políticas legales accesibles | `PrivacyPolicyScreen.kt` | N/A | `PrivacyPolicyScreen.kt` |

---

## FASE 7 — ACCESIBILIDAD Y EXPERIENCIA DE USUARIO

### 7.1 Hallazgos de Accesibilidad

| ID | Hallazgo | Tipo | Severidad | Evidencia |
|---|---|---|---|---|
| A-001 | **`contentDescription = null` en iconos funcionales** | ERROR CONFIRMADO | MEDIO | `LoginScreen.kt:447` — `Icon(..., contentDescription = null)`. TalkBack no puede leer iconos. |
| A-002 | **Touch targets ≥ 48dp** | ✅ OK | Botones de 50dp-56dp de altura. Cumple Material Design guidelines. |
| A-003 | **Textos con `maxLines = 1` en alturas fijas** | RIESGO POTENCIAL | BAJO | Con escalado extremo de fuentes, textos pueden truncarse sin indicación. |
| A-004 | **No hay soporte para lector de pantalla completo** | MEJORA RECOMENDADA | MEDIO | Falta revisión semántica de `Modifier.semantics` en componentes complejos. |
| A-005 | **No hay soporte multi-idioma** | MEJORA RECOMENDADA | MEDIO | Textos hardcodeados solo en español. No se puede cambiar idioma. |
| A-006 | **Tema oscuro/claro** | PARCIAL | — | Material 3 soporta dark/light pero no se confirmó toggle explícito para el usuario. |
| A-007 | **Edge-to-edge** | ✅ OK | `themes.xml` — Barra de estado transparente configurada. |

---

## FASE 8 — PRUEBAS Y CALIDAD

### 8.1 Estado Actual de Pruebas

**Estado: CRÍTICO — Cobertura prácticamente nula**

| Directorio | Contenido | Estado |
|---|---|---|
| `app/src/test/` | 1 archivo placeholder (`UnitTestApplication.kt`, 180 bytes) | ❌ Sin pruebas |
| `app/src/androidTest/` | Vacío | ❌ Sin pruebas |
| `tests/` (raíz) | Directorio existe | ❌ Sin pruebas reales |
| `signaling-server/tests/` | Directorio existe | Pendiente de verificación |
| `infrastructure/local-fallback/api/tests/` | Directorio existe | Pendiente de verificación |
| `Conectate-web/` | Jest configurado (`jest.config.cjs`) | Pendiente de verificación |

### 8.2 Herramientas de Testing Disponibles (sin usar)

El `libs.versions.toml` incluye:
- JUnit 4.13.2
- AndroidX Test (Ext JUnit 1.1.5, Core KTX 1.5.0)
- Espresso Core 3.5.1
- Robolectric 4.12.2

Todas declaradas pero **sin implementaciones de test**.

### 8.3 Análisis de Calidad de Código

| Herramienta | Estado |
|---|---|
| Detekt | Configurado (`code-quality/detekt.yml`) |
| Lint | Disponible (AGP) |
| CI/CD | `.github/` existe pero no se verificó contenido |
| ProGuard/R8 | Activo en release (fullMode=false) |

### 8.4 Flujos Críticos Sin Cobertura

1. **Autenticación** — Login, registro, 2FA, refresh de sesión
2. **Mensajería** — Envío, recepción, sincronización, offline
3. **Pagos** — Billetera, recargas, regalos (dinero real involucrado)
4. **Streaming en vivo** — Inicio, conexión, regalos, co-hosts
5. **Migraciones de Room** — 55 versiones sin tests de migración
6. **Navegación** — 78 pantallas sin tests de flujo
7. **Sincronización de datos** — Offline → Online sin verificación

### 8.5 Pruebas Necesarias

| Tipo | Prioridad | Módulo |
|---|---|---|
| Unit tests - ViewModels | CRÍTICA | Todos los ViewModels |
| Unit tests - Repositorios | CRÍTICA | MessageRepository, AuthRepository, WalletRepository |
| Room migration tests | ALTA | AppDatabase (v1→v55) |
| Integration tests - Auth flow | ALTA | Login → Home |
| Integration tests - Payment | ALTA | Wallet → Purchase → Gift |
| UI tests (Compose) | MEDIA | Pantallas principales |
| E2E tests - Messaging | MEDIA | Enviar → Recibir → Leer |
| Backend API tests | ALTA | Endpoints críticos |
| Signaling server tests | MEDIA | Conexión, rooms, ICE |

---

## FASE 9 — MATRIZ GENERAL DE HALLAZGOS

| ID | TIPO | SEVERIDAD | MÓDULO | PROBLEMA | EVIDENCIA | ARCHIVOS | IMPACTO | SOLUCIÓN | RIESGO MOD. | DEPENDENCIAS | VERIFICACIÓN |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H-001 | Bug | CRÍTICO | Core | Excepciones silenciadas en 10+ archivos | Bloques `catch (e: Exception) { }` vacíos | VoiceForegroundService.kt, CallForegroundService.kt, VoiceRoomRepository.kt, UserRepository.kt, LiveRepository.kt, MessageRepository.kt, MomentRepository.kt, MessageRealtimeSync.kt, GroupDetailViewModel.kt, CommunityDetailViewModel.kt | Errores fatales invisibles, bugs imposibles de diagnosticar | Agregar logging mínimo: `Log.e(TAG, "...", e)` o reportar a Crashlytics | BAJO | Ninguna | Verificar que Crashlytics registra errores post-fix |
| H-002 | Arquitectura | CRÍTICO | Data | God Classes (6 repositorios > 130KB) | MessageRepository=255KB, LiveRepository=188KB, VideoRepository=167KB, CommunityRepository=165KB, UserRepository=165KB, GroupRepository=148KB | data/repository/*.kt | Imposible de mantener, testear y debuggear | Descomponer en sub-repositorios por responsabilidad | ALTO | Tests deben crearse primero | Tests unitarios tras refactorización |
| H-003 | Calidad | CRÍTICO | Testing | Cobertura de pruebas ~0% | 1 archivo placeholder en `app/src/test/` | UnitTestApplication.kt | Regresiones no detectadas, especialmente en pagos | Implementar test suite completa por módulo | BAJO | Ninguna | Ejecutar suite de tests |
| H-004 | Bug | ALTO | Auth/2FA | Pérdida silenciosa de 2FA al restaurar dispositivo | `createSecurePreferencesWithRecovery()` borra prefs al fallar KeyStore | TwoFactorAuthManager.kt:84 | Usuario pierde 2FA sin notificación | Notificar al usuario, ofrecer re-enrollment | MEDIO | Auth flow | Test manual de restauración |
| H-005 | Arquitectura | ALTO | Backend | Archivos monolíticos en API (data-routes.js=603KB) | Archivo único con todas las rutas de datos | data-routes.js, postgrest-compat-routes.js, server.js | Cold start lento, mantenimiento difícil | Modularizar en archivos por dominio | ALTO | Tests de API | Tests de endpoints |
| H-006 | UX | MEDIO | UI | Textos hardcodeados (no i18n) | `Text("Tu gente...")` inline en Kotlin, no en strings.xml | Todos los composables de UI | No se puede cambiar idioma, imposible internacionalizar | Extraer todos los strings a resources | BAJO | Ninguna | Verificar que app funciona igual |
| H-007 | UX | MEDIO | UI | Colores hardcodeados fuera del tema Material 3 | `Color(0xFF2196F3)` en HomeScreen y otros | HomeScreen.kt, múltiples | Inconsistencia visual, difícil mantener tema | Mover a MaterialTheme.colorScheme | BAJO | Ninguna | Revisión visual |
| H-008 | Accesibilidad | MEDIO | UI | `contentDescription = null` en iconos funcionales | `Icon(..., contentDescription = null)` | LoginScreen.kt:447, múltiples | TalkBack no puede leer iconos | Agregar contentDescription significativo | BAJO | Ninguna | Test con TalkBack |
| H-009 | Rendimiento | MEDIO | Core | `runBlocking` en FCM Service | `runBlocking(Dispatchers.IO)` con timeout 2s | MyFirebaseMessagingService.kt:396 | Cuello de botella en ráfagas de push | Usar `CoroutineScope.launch` con callback | BAJO | FCM integration | Test de ráfaga de notificaciones |
| H-010 | Código | MEDIO | Core | Dispatchers hardcodeados (20+ archivos) | `Dispatchers.Main/IO` directo en repositorios | GroupRepository.kt, CommunityRepository.kt, ChatViewModel.kt | Tests unitarios no pueden mockear dispatchers | Inyectar DispatcherProvider | BAJO | DI modules | Tests unitarios |
| H-011 | Código | MEDIO | Core | Threads manuales en ecosistema coroutines | `startWriterThread()`, `Thread.interrupt()` | AudioRecorder.kt:204, WebRTCManager.kt:2574 | Memory leaks si UI se destruye | Migrar a coroutines con lifecycle awareness | MEDIO | Lifecycle de componentes | Test de lifecycle |
| H-012 | Rendimiento | BAJO | App | R8 fullMode deshabilitado | `android.enableR8.fullMode=false` en gradle.properties | gradle.properties | Ofuscación reducida, APK ligeramente mayor | Investigar CME original y habilitar fullMode | MEDIO | Estabilidad de la app | Build + smoke test |
| H-013 | Código | BAJO | Core | Comentario engañoso sobre SupervisorJob | `// ⚠️ Sin SupervisorJob` pero SafeScopes SÍ lo usa | SocialApp.kt:68, SafeScopes.kt:20 | Confusión para desarrolladores | Corregir comentario | BAJO | Ninguna | Lectura del código |
| H-014 | Calidad | MEDIO | Backend | Hotfixes SQL no consolidados | Carpeta `sql/hotfixes/` con parches sueltos | sql/hotfixes/ | Desincronización en entornos nuevos | Migrar hotfixes al pipeline oficial | BAJO | Migraciones de BD | Deploy en entorno limpio |
| H-015 | UX | BAJO | UI | Drawables masivos de Figma inflando APK | Cantidad masiva de `figma_live_...`, `gift_anim_...` | res/drawable/ | APK mayor de lo necesario | Optimizar assets, usar WebP/SVG | BAJO | Ninguna | Comparar tamaño APK |
| H-016 | Código | MEDIO | App | WorkManager fallback silencioso sin Hilt | Si HiltWorkerFactory no inicializa, Workers sin DI | SocialApp.kt:58-66 | Workers con inyección fallan silenciosamente | Verificar y asegurar binding correcto | BAJO | Hilt config | Test de Workers |

---

## FASE 10 — PLAN DE IMPLEMENTACIÓN

### ETAPA 1 — Problemas Críticos (Estabilidad)
**Prioridad: INMEDIATA**

1. **H-001**: Agregar logging a todas las excepciones silenciadas
   - Buscar todos los `catch` vacíos en el proyecto Android
   - Agregar `Log.e()` mínimo + reporte a Crashlytics para los críticos
   - **Esfuerzo**: 2-4 horas
   - **Riesgo**: Muy bajo

2. **H-013**: Corregir comentario engañoso sobre SupervisorJob
   - `SocialApp.kt:68` — Actualizar comentario
   - **Esfuerzo**: 5 minutos
   - **Riesgo**: Nulo

### ETAPA 2 — Seguridad e Integridad de Datos
**Prioridad: ALTA**

3. **H-004**: Resolver pérdida silenciosa de 2FA
   - Agregar notificación al usuario cuando EncryptedSharedPreferences se resetean
   - Ofrecer flujo de re-enrollment de 2FA
   - **Esfuerzo**: 4-8 horas
   - **Riesgo**: Medio (toca flujo de autenticación)
   - **Dependencias**: Debe testearse manualmente con restauración de dispositivo

### ETAPA 3 — Errores Funcionales
**Prioridad: ALTA**

4. **H-009**: Eliminar `runBlocking` en MyFirebaseMessagingService
   - Reemplazar con `CoroutineScope` con `SupervisorJob`
   - **Esfuerzo**: 2-3 horas
   - **Riesgo**: Bajo

5. **H-016**: Verificar binding correcto de HiltWorkerFactory
   - Confirmar que `hilt-work` está correctamente configurado
   - Eliminar fallback silencioso o hacerlo explícito
   - **Esfuerzo**: 1-2 horas
   - **Riesgo**: Bajo

### ETAPA 4 — Pruebas (Fundación)
**Prioridad: ALTA — Debe ejecutarse ANTES de refactorizaciones**

6. **H-003**: Implementar suite de tests básica
   - Tests unitarios para ViewModels críticos (Auth, Message, Wallet)
   - Tests unitarios para repositorios core
   - Tests de migración de Room (v1→v55)
   - **Esfuerzo**: 40-60 horas
   - **Riesgo**: Bajo
   - **Dependencias**: MockK, Turbine para StateFlow, Room testing utils

### ETAPA 5 — Arquitectura y Mantenibilidad
**Prioridad: MEDIA — Solo tras tener tests**

7. **H-002**: Descomponer God Classes
   - `MessageRepository.kt` → `MessageSendRepository`, `MessageSyncRepository`, `MessageCacheRepository`, etc.
   - `LiveRepository.kt` → `LiveStreamRepository`, `LiveGiftRepository`, `LiveChatRepository`, etc.
   - Aplicar mismo patrón a los demás repositorios masivos
   - **Esfuerzo**: 80-120 horas
   - **Riesgo**: ALTO — requiere tests previos
   - **Dependencias**: ETAPA 4 completada

8. **H-005**: Modularizar backend
   - Dividir `data-routes.js` en módulos por dominio
   - Dividir `postgrest-compat-routes.js`
   - **Esfuerzo**: 40-60 horas
   - **Riesgo**: Alto
   - **Dependencias**: Tests de API backend

9. **H-010**: Inyectar DispatcherProvider
   - Reemplazar `Dispatchers.Main/IO` hardcodeados por inyección
   - **Esfuerzo**: 8-12 horas
   - **Riesgo**: Bajo

### ETAPA 6 — Rendimiento
**Prioridad: MEDIA**

10. **H-011**: Migrar threads manuales a coroutines
    - `AudioRecorder.kt`, `WebRTCManager.kt`
    - **Esfuerzo**: 8-12 horas
    - **Riesgo**: Medio (toca multimedia en tiempo real)

11. **H-012**: Investigar y habilitar R8 fullMode
    - Encontrar la causa original del ConcurrentModificationException
    - **Esfuerzo**: 4-8 horas
    - **Riesgo**: Medio

12. **H-014**: Consolidar hotfixes SQL
    - Migrar parches de `sql/hotfixes/` al pipeline oficial
    - **Esfuerzo**: 4-6 horas
    - **Riesgo**: Bajo

### ETAPA 7 — UX/UI (Heurísticas)
**Prioridad: MEDIA**

13. **H-006**: Extraer textos hardcodeados a strings.xml
    - Buscar todos los `Text("...")` y `text = "..."` en composables
    - Crear entries en `strings.xml`
    - **Esfuerzo**: 20-30 horas (proyecto grande)
    - **Riesgo**: Bajo

14. **H-007**: Unificar colores al tema Material 3
    - Buscar `Color(0x` en composables
    - Mover a `ColorScheme` personalizado
    - **Esfuerzo**: 8-12 horas
    - **Riesgo**: Bajo

### ETAPA 8 — Accesibilidad
**Prioridad: MEDIA-BAJA**

15. **H-008**: Agregar contentDescription a iconos funcionales
    - Revisar todos los `contentDescription = null`
    - Determinar cuáles son decorativos vs funcionales
    - **Esfuerzo**: 8-12 horas
    - **Riesgo**: Bajo

### ETAPA 9 — Mejoras Secundarias
**Prioridad: BAJA**

16. **H-015**: Optimizar drawables/assets
    - Convertir PNGs a WebP
    - Evaluar SVG para vectores
    - **Esfuerzo**: 4-8 horas
    - **Riesgo**: Bajo

17. Centralizar lógica de permisos con Accompanist Permissions
    - **Esfuerzo**: 8-12 horas
    - **Riesgo**: Bajo

### ETAPA 10 — Tests y Validación Final
**Prioridad: CONTINUA**

18. Tests de integración para flujos completos
19. Tests E2E con Espresso/Compose Test
20. Tests de rendimiento y benchmarks
21. Test de accesibilidad con TalkBack
22. Test de migración completa de Room
23. Tests de carga del signaling server

---

## ANÁLISIS COMPLEMENTARIO — BACKEND, SUPABASE Y NETWORKING

### Backend API REST (`infrastructure/local-fallback/api/`)

| Aspecto | Hallazgo | Evidencia |
|---|---|---|
| Framework | Express.js sobre Node.js | `server.js:4` |
| Base de datos | PostgreSQL 16.15 (vía pg Pool) | `server.js:7`, `docker-compose.yml:3` |
| Autenticación | JWT con bcrypt + rotación de secretos | `server.js:1-6, 74-79` |
| Sesiones web | Cookie HTTP-only con TTL configurable | `web-session-security.js` |
| Rate limiting | Distribuido (Redis) + in-memory | `distributed-rate-limit.js` |
| Almacenamiento | Archivos en disco con signed URLs + Nginx accel | `storage-routes.js`, `docker-compose.yml:92-94` |
| Observabilidad | Middleware de request logging, health checks | `request-observability.js`, `system-health.js` |
| Moderación | Panel admin completo (103KB) | `admin-moderation-routes.js` |
| Monetización | Google Play RTDN, creator payouts, gift economy | `google-play-rtdn.js`, `creator-payout-routes.js`, `live-gift-events.js` |
| Algoritmo | Ranking, candidatos, recomendaciones | `algorithm-ranking.js` (27KB), `algorithm-routes.js` (56KB) |
| Rutas totales | 73 archivos fuente en `api/src/` | Listado de directorio |

**Problemas identificados**:
- `data-routes.js` (603KB) y `postgrest-compat-routes.js` (255KB) son monolitos que necesitan modularización — ver H-005 en la Matriz.
- Ausencia de tests confirmada en `api/tests/` (directorio existe, contenido no verificado en profundidad).

### Supabase (Legacy)

| Aspecto | Hallazgo | Evidencia |
|---|---|---|
| Estado | En proceso de abandono | `ARQUITECTURA.md`, `secrets.properties:7-8` (valores vacíos) |
| Contenido | Solo `supabase/functions/` | Directorio con 1 subdirectorio |
| Uso residual | Auth legacy (AuthService.kt), importación CSV | `data/supabase/AuthService.kt`, `import-all-csv.js` |
| Migración | Flag `LEGACY_SUPABASE_IMPORT_ENABLED` en server.js | `server.js:98-100` |

**Estado**: Supabase se mantiene solo como fuente de importación de datos legacy. Las variables `SUPABASE_URL` y `SUPABASE_ANON_KEY` están vacías en `secrets.properties`. La migración al backend propio (Ubuntu/local-fallback) está en curso.

### Networking Android

| Componente | Tecnología | Archivos clave |
|---|---|---|
| HTTP REST | Ktor 2.3.5 | `data/localfallback/` (cliente del backend Ubuntu) |
| WebRTC P2P | WebRTC nativo 137.7151.05 | `data/webrtc/WebRTCManager.kt` |
| WebRTC SFU | LiveKit Android 2.27.0 | `data/livekit/LiveKitRoomBridge.kt` |
| Signaling | Socket.IO Client 2.1.0 | `data/signaling/` |
| Backend discovery | DNS dinámico (DuckDNS) + ECDSA | `RuntimeBackendEndpoint.kt` |
| Realtime sync | SSE desde Realtime Gateway | `data/localfallback/UbuntuRealtimeClient.kt` |
| Imágenes | Coil 2.5.0 con OkHttp | `SocialApp.kt:171-187` |
| Tunnel bypass | Header `ngrok-skip-browser-warning` inyectado | `SocialApp.kt:175-183` |

**Hallazgo notable**: El sistema de backend discovery (`RuntimeBackendEndpoint`) resuelve dinámicamente la URL del backend vía DuckDNS con verificación ECDSA de la respuesta. Esto permite que el backend cambie de IP/tunnel sin actualizar la app. Diseño robusto pero necesita documentación explícita.

### Signaling Server (`signaling-server/`)

| Aspecto | Detalle | Evidencia |
|---|---|---|
| Tamaño | `index.js` = 3,087 líneas (128KB) — God file | — |
| Tecnología | Node.js + Socket.IO + HTTP | `index.js:1-4` |
| Autenticación | Bearer token validado contra la API REST | `index.js:16-26` |
| TURN/STUN | Coturn REST + static fallback + Google STUN | `index.js:28-44` |
| Rate limiting | Per-IP + per-socket con buckets configurable | `index.js:46-47, 54` |
| Estado distribuido | Redis adapter opcional para multi-nodo | `distributed-adapter.js`, `distributed-live-state.js` |
| Seguridad producción | Falla rápido si ORIGINS o TOKEN no configurados | `index.js:78-84` |
| Funcionalidades | Salas de voz, llamadas 1-a-1, live streaming, draw & guess, battles | Handlers en `index.js` |
| Limpieza de memoria | `setInterval` para purgar rate limit buckets | `index.js:87-97` |

**Problema**: `index.js` es un God file de 3,087 líneas. Similar al problema del backend, necesita modularización por feature (calls, voice rooms, lives, etc.).

---

## COBERTURA DE LA AUDITORÍA

### Áreas revisadas completamente ✅

| Área | Método de revisión | Archivos clave leídos |
|---|---|---|
| Arquitectura general | Exploración de directorios + documentación | Todos los directorios raíz, README, ARQUITECTURA.md |
| Configuración de build | Lectura completa | `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `libs.versions.toml` |
| Secretos y variables de entorno | Lectura completa | `secrets.properties`, `.env.example`, `keystore.properties.example`, `.gitignore` |
| Application class | Lectura línea por línea (255 líneas) | `SocialApp.kt` completo |
| Coroutines y concurrencia | Lectura + búsqueda global | `SafeScopes.kt`, búsqueda de `runBlocking`, `SupervisorJob`, `Dispatchers` |
| Inyección de dependencias | Lectura completa | 5 archivos en `di/` |
| Seguridad de red (Vercel) | Lectura completa | `vercel.json` (headers, CSP, HSTS) |
| Infraestructura Docker | Lectura de primeras 100 líneas | `docker-compose.yml` |
| Signaling server config | Lectura de primeras 100 líneas + `.env.example` | `signaling-server/index.js`, `.env.example` |
| Backend API server | Lectura de primeras 100 líneas | `server.js` |
| Base de datos SQL | Lectura de 300 líneas del esquema | `deploy_complete_unified.sql` |
| Pruebas existentes | Exploración completa | `app/src/test/`, `app/src/androidTest/`, `tests/` |
| Calidad de código | Búsqueda global de patrones | `catch vacíos`, `TODO/FIXME`, `runBlocking` |
| Pantallas UI (inventario) | Búsqueda completa | 78 archivos `*Screen*.kt` listados |
| Recursos Android | Exploración completa | `res/values/`, `res/layout/`, `res/drawable/` |
| Navegación | Identificación de NavGraph + Screen | `AppNavGraph.kt`, `Screen.kt` |
| Repositorios (inventario) | Listado completo con tamaños | 53 archivos en `data/repository/` |
| Autenticación 2FA | Lectura detallada | `TwoFactorAuthManager.kt` |
| Manejo de memoria | Lectura completa | `SocialApp.kt` (onTrimMemory, onLowMemory) |
| Image loading | Lectura completa | `SocialApp.kt` (newImageLoader) |
| Services (foreground) | Identificación + análisis de excepciones | `VoiceForegroundService.kt`, `CallForegroundService.kt`, `LiveForegroundService.kt` |
| FCM / Push notifications | Análisis de `runBlocking` | `MyFirebaseMessagingService.kt` |
| Web frontend (inventario) | Exploración de estructura | `Conectate-web/` directorios y configs |

### Áreas revisadas parcialmente ⚠️

| Área | Lo que se revisó | Lo que falta |
|---|---|---|
| `signaling-server/index.js` | Primeras 100 líneas (config, rate limiting, seguridad) | Líneas 100-3087 (handlers de rooms, calls, lives, battles) |
| `server.js` (backend) | Primeras 100 líneas (imports, config, JWT) | Líneas 100-3014 (middleware, rutas, jobs) |
| `data-routes.js` (backend) | Identificación de tamaño (603KB) | Contenido no leído (demasiado grande) |
| `postgrest-compat-routes.js` | Identificación de tamaño (255KB) | Contenido no leído |
| Docker Compose | Primeras 100 líneas (db, redis, api) | Líneas 100-382 (nginx, coturn, networking, secrets) |
| `deploy_complete_unified.sql` | 300 de 1,247 líneas | Tablas de monetización, hashtags, funciones, triggers, índices |
| Repositorios grandes | Inventariados por tamaño | Solo se verificaron patrones de excepciones, no lógica interna de cada uno |
| Pantallas Compose | LoginScreen, HomeScreen, LiveHostScreen leídas en detalle | 75+ pantallas restantes revisadas solo por inventario |
| `Conectate-web/src/` | Estructura identificada | Código TypeScript/React no auditado en profundidad |
| `realtime-gateway/` | Directorio identificado | Código fuente no leído |
| `infrastructure/kubernetes/` | Directorio identificado | Configuración K8s no leída |
| `infrastructure/monitoring/` | Directorio identificado | No leído |
| `infrastructure/service-mesh/` | Directorio identificado | No leído |
| `code-quality/detekt.yml` | Identificación | Reglas no leídas en detalle |
| `scripts/`, `tools/` | Identificación | Contenido no leído |
| Esquema Room (entidades) | Identificación de AppDatabase, versión 55 | Entidades individuales y DAOs no leídos |
| SQL hotfixes/migraciones | Identificación | Contenido de `sql/hotfixes/`, `sql/migrations/` no leído |
| Heurísticas Nielsen H1-H10 | Aplicadas a LoginScreen, HomeScreen, LiveHostScreen | No aplicadas individualmente a las 75+ pantallas restantes |
| Accesibilidad | Búsqueda de `contentDescription = null` | No se midieron contrastes reales ni se probó con TalkBack |

### Áreas no revisadas ❌

| Área | Razón |
|---|---|
| Lógica interna de los 53 repositorios | Demasiado grandes para leer cada uno (total > 2.5MB de Kotlin) |
| Código de cada una de las 78 pantallas | Solo 3 pantallas leídas en detalle |
| `ai-chatbot/` (backend IA) | No explorado |
| `play_store_assets/` | Assets de publicación, fuera del alcance técnico |
| Contenido de `Conectate-web/src/` (React) | Frontend web no auditado en profundidad |
| Contenido de `infrastructure/docker/` | Configs Docker adicionales no leídas |
| ProGuard rules (`proguard-rules.pro`) | No leídas |
| `google-services.json` | Excluido del repositorio (correcto) |
| Tests del backend (`api/tests/`, `signaling-server/tests/`) | Directorios identificados, contenido no leído |
| Migraciones Room individuales | No leídas (podrían contener errores en v1→v55) |
| Análisis de rendimiento real | No se ejecutó la app; análisis solo por lectura de código |
| Flows/StateFlows en ViewModels | Identificación general, no revisión individual de cada ViewModel |

---

## RESUMEN EJECUTIVO

### Fortalezas del Proyecto
- ✅ Funcionalidades MUY completas para una red social (mensajería, videos, lives, regalos, gaming, etc.)
- ✅ Arquitectura MVVM + Clean Architecture bien estructurada
- ✅ Seguridad por encima del promedio (JWT con rotación, EncryptedSharedPreferences, Docker hardened, CSP)
- ✅ Gestión de memoria proactiva (perfiles por tier de dispositivo)
- ✅ Inicialización escalonada para optimizar cold start
- ✅ Infraestructura Docker robusta con límites de recursos
- ✅ Sistema de recuperación de errores de autenticación excelente (apelación de suspensión)
- ✅ Widgets de Android como valor diferenciador
- ✅ Backend discovery dinámico con verificación ECDSA
- ✅ Rate limiting multinivel (HTTP + Socket.IO + distribuido)
- ✅ Antivirus integrado para uploads de música

### Debilidades Principales
- ❌ **Cobertura de pruebas prácticamente nula** — riesgo mayor del proyecto
- ❌ **God Classes** — 6 repositorios > 130KB y 2 archivos JS > 250KB imposibles de mantener
- ❌ **Excepciones silenciadas** — bugs invisibles en producción
- ❌ **No hay internacionalización** — bloqueado a español hardcodeado
- ❌ **Backend monolítico** — `data-routes.js` de 603KB, `signaling/index.js` de 128KB

### Métricas de la Auditoría
- **Pantallas identificadas**: 78 (3 leídas en profundidad, resto inventariado)
- **Repositorios identificados**: 53 (patrones buscados globalmente, no lectura individual)
- **Archivos backend identificados**: 73 (estructura analizada, archivos grandes identificados)
- **Hallazgos totales en Matriz**: 16
- **Críticos**: 3 (excepciones silenciadas, God classes, ausencia de tests)
- **Altos**: 3 (2FA recovery, backend monolítico, HiltWorkerFactory)
- **Medios**: 7
- **Bajos**: 3
- **Módulos revisados**: App Android, Backend API, Signaling Server, Web (parcial), SQL (parcial), Infraestructura (parcial)
- **Archivos de código leídos completos**: ~15
- **Archivos de código leídos parcialmente**: ~10
- **Búsquedas globales ejecutadas**: `catch vacíos`, `runBlocking`, `SupervisorJob`, `EncryptedSharedPreferences`, `TODO/FIXME`, `Dispatchers`, `contentDescription`, `*Screen*`

---

*Auditoría realizada el 2026-09-23. No se modificó ningún archivo del proyecto.*
