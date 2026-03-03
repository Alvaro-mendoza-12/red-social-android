# 🔴 Conéctate - Red Social Móvil

Una plataforma de red social móvil construida con **Kotlin**, **Jetpack Compose** y **Supabase** como backend.

## 📱 Características

- ✅ **Autenticación** con email y número de teléfono
- 💬 **Mensajería en tiempo real** (texto, imágenes, videos, audio)
- 🎥 **Feed de videos** con likes y comentarios
- 👤 **Perfiles de usuario** personalizables
- 🎤 **Salas de voz** de alto rendimiento (WebRTC + Socket.io)
- 🔔 **Notificaciones push** en tiempo real

## 🏗️ Arquitectura

- **Patrón**: MVVM (Model-View-ViewModel)
- **UI**: Jetpack Compose
- **Navegación**: Jetpack Navigation Compose
- **Inyección de Dependencias**: Hilt
- **Backend**: Supabase (Auth, PostgREST, Storage, Realtime)
- **Comunicación de Voz**: WebRTC Native SDK + Custom Node.js Signaling

## 🚀 Configuración Inicial

### Prerequisites

1. **Android Studio** (Hedgehog o superior recomendado)
2. **JDK 17**
3. **Cuenta de Supabase** (necesaria para backend)

### Paso 1: Clonar el Proyecto

El proyecto ya está en `d:\RED SOCIAL`

### Paso 2: Configurar Supabase

El proyecto usa Supabase para autenticación, base de datos (Postgres/PostgREST), almacenamiento y realtime.

1. Crear un proyecto en https://app.supabase.com
2. En `Project Settings > API` copia la `URL` y la `anon key` y colócalas en `secrets.properties`:

```
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
```

3. Crea las tablas necesarias: `profiles`, `videos`, `video_comments`, `video_likes`, `messages`, `chats`, `voice_rooms`, etc. Algunas funciones RPC esperadas por la app son `get_or_create_chat`, `get_user_chats_with_last_message`, `get_unread_messages_count`, etc.

4. Configura reglas de RLS (Row Level Security) y políticas para `profiles`, `messages` y `videos` según la lógica de la aplicación.

### Paso 3: Configurar Servidor de Señalización de Voz

El sistema de salas de voz usa un servidor personalizado Socket.io para la señalización WebRTC.

1. Navega a `signaling-server/`
2. Instala dependencias: `npm install`
3. Inicia el servidor: `npm start`
4. Expón el servidor usando **ngrok** (si pruebas en dispositivo real): `ngrok http 3001`
5. Configura la URL resultante en `VoiceRoomRepository.kt` (`SIGNALING_URL`).

### Paso 4: Sync de Gradle

1. Abrir el proyecto en Android Studio
2. Esperar a que se sincronicen las dependencias de Gradle
3. Si hay errores, ejecutar `File > Invalidate Caches / Restart`

### Paso 5: Ejecutar la Aplicación

```bash
# Conectar un dispositivo Android o iniciar un emulador
# Luego ejecutar desde Android Studio o usar:
./gradlew installDebug
```

### CI en GitHub Actions

El proyecto incluye pipeline en `.github/workflows/android-ci.yml` con:

- `quality-gate`: ejecuta `:app:qualityGateDebug` (`testDebugUnitTest` + `lintDebug`)
- `quality-gate`: corre auditoría SQL de seguridad (`tools/security_sql_audit.py`) y publica reportes
- `release-smoke`: valida `:app:assembleRelease` en cada push
- `release-smoke`: valida budgets de performance de APK/DEX/native (`tools/performance_budget_check.py`)
- Workflow adicional `.github/workflows/android-instrumented-smoke.yml`:
  - smoke test instrumentado en emulador (`connectedDebugAndroidTest`)
  - ejecución manual (`workflow_dispatch`) y semanal (lunes)

### Quality Gate local

```powershell
.\tools\quality_gate.ps1
```

```bash
./tools/quality_gate.sh
```

### Auditoría SQL de seguridad (local)

```bash
python tools/security_sql_audit.py --root . --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH
```

### Performance budgets release (local)

```bash
./gradlew :app:assembleRelease
python tools/performance_budget_check.py --apk app/build/outputs/apk/release/app-release.apk --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json
```

Documentación adicional:
- `docs/SECURITY_AUDIT_CHECKLIST_2026.md`
- `docs/PERFORMANCE_BUDGETS_2026.md`

Para que funcione en tu repositorio remoto, configura estos secretos en GitHub:

```
SUPABASE_URL
SUPABASE_ANON_KEY
```

## 📁 Estructura del Proyecto

```
app/src/main/java/conectate/com/
├── SocialApp.kt                    # Aplicación Hilt
├── MainActivity.kt                 # Activity principal
├── di/                             # Módulos de inyección de dependencias
│   └── AppModule.kt
├── data/
│   ├── model/                      # Modelos de datos
│   │   ├── User.kt
│   │   ├── Message.kt
│   │   ├── Video.kt
│   │   └── ...
│   └── repository/                 # Repositorios
│       ├── AuthRepository.kt
│       ├── UserRepository.kt
│       ├── MessageRepository.kt
│       └── VideoRepository.kt
├── presentation/
│   ├── theme/                      # Tema de Compose
│   ├── navigation/                 # Navegación
│   ├── auth/                       # Pantallas de autenticación
│   ├── messages/                   # Pantallas de mensajería
│   ├── videos/                     # Pantallas de videos
│   ├── profile/                    # Pantallas de perfil
│   └── voicerooms/                 # Pantallas de salas de voz
├── service/
└── util/
    └── Constants.kt                # Constantes de la app
```

## 🔐 Seguridad (Supabase + RLS)

Este proyecto utiliza Supabase (Postgres) y Row Level Security (RLS). Recomendaciones rápidas:

- Habilita RLS para tablas sensibles (`profiles`, `messages`, `videos`, `chat_participants`).
- Ejemplo de políticas para `profiles`:

```sql
CREATE POLICY "profiles_select_own" ON public.profiles
  FOR SELECT USING (auth.uid() = id);

CREATE POLICY "profiles_update_own" ON public.profiles
  FOR UPDATE USING (auth.uid() = id);
```

- Para mensajes, restringe inserción a `from_user_id = auth.uid()` y lectura al participante del chat.
- Revisa RPCs y funciones para asegurarte de que no exponen datos: usa `SECURITY DEFINER` con cuidado.

No comitees `secrets.properties`. Si `secrets.properties` fue expuesto, rota las claves en Supabase.

### Storage (Supabase)

Supabase Storage se usa para buckets (videos, avatars, images, audios). Recomendaciones:

- Crea buckets con políticas públicas/privadas según necesidad. Por ejemplo, avatars pueden ser públicos, videos privados con URLs firmadas.
- Verifica permisos de objetos y la generación de URLs públicas/firma (presigned URLs).

Ejemplo: permitir lectura pública en un bucket `avatars` y control de escritura por usuario mediante RLS en metadatos.

## 🛠️ Tecnologías Utilizadas

- **Kotlin** 1.9.20
- **Jetpack Compose** BOM 2023.10.01
- **Supabase** (gotrue, postgrest, realtime, storage)
- **Hilt** 2.48
- **Navigation Compose** 2.7.5
- **Coil** 2.5.0 (carga de imágenes)
- **ExoPlayer** 1.2.0 (reproducción de video)
- **WebRTC Native** 120.6099.04
- **Socket.io Client** 2.1.0

## 📝 Estado Actual del Proyecto

### ✅ Completado

- [x] Configuración de Gradle y dependencias
- [x] Integración inicial con Supabase

**Cambios recientes (migración completa a Supabase)**

- **Eliminación total de Firebase**: Removidos `FirebaseModule.kt`, `MyFirebaseMessagingService.kt` y referencias a Firebase. El proyecto ahora es 100% Supabase.
- Reemplazado uso directo de `FirebaseAuth` en `RoomDetailViewModel` por `AuthRepository`.
- `AgoraConfig.kt` creado con App ID de desarrollo.
- `google-services.json` retirado del seguimiento git (ejecutar localmente: `git rm --cached google-services.json`).
- [x] Modelos de datos
- [x] Repositorios (Auth, User, Message, Video)
- [x] Módulos de Hilt
- [x] Tema de Compose con colores rojos
- [x] Sistema de navegación
- [x] ViewModel de autenticación
- [x] Pantalla de login básica
- [ ] Servicio de notificaciones push (removido: no implementado en Supabase)
- [x] Recursos XML (strings, colores, temas)

### 🚧 Pendiente de Implementación

- [ ] Pantallas completas de registro
- [ ] Pantallas de mensajería (lista y chat)
- [ ] Pantallas de  videos (feed, player, upload)
- [ ] Pantallas de perfil (vista, edición)
- [ ] Implementación de salas de voz con Agora
- [ ] Componentes UI reutilizables
- [ ] Gestión de permisos
- [ ] Compresión de imágenes y videos
- [ ] Tests unitarios y de integración

## 🎨 Diseño

El tema utiliza colores rojos como color primario:
- **Primary**: #E53935
- **Primary Dark**: #B71C1C
- **Primary Light**: #FF6F60

## 📧 Contacto

Para preguntas o soporte técnico, contacta al equipo de desarrollo.

## 📄 Licencia

Este proyecto es privado y confidencial.
