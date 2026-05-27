# 🔴 Conéctate - Red Social Móvil

Una plataforma de red social móvil construida con **Kotlin**, **Jetpack Compose** y **Supabase** como backend.

## 📱 Características

- ✅ **Autenticación** con email y número de teléfono
- 💬 **Mensajería en tiempo real** (texto, imágenes, videos, audio)
- 🎥 **Feed de videos/lives** con likes, comentarios, gifts y reportes
- 👤 **Perfiles de usuario** personalizables estilo Discord
- 🎤 **Llamadas y salas de voz** de alto rendimiento (WebRTC + Socket.io)
- 🔔 **Notificaciones push** en tiempo real

## 🏗️ Arquitectura

- **Patrón**: MVVM (Model-View-ViewModel)
- **UI**: Jetpack Compose
- **Navegación**: Jetpack Navigation Compose
- **Inyección de Dependencias**: Hilt
- **Backend**: Supabase (Auth, PostgREST, Storage, Realtime)
- **Comunicación de Voz**: WebRTC Native SDK + Custom Node.js Signaling

## Mapa operativo del repo

- `app/`: app Android activa.
- `Conectate-web/`: cliente web Vite/React.
- `signaling-server/`: backend Node para señalización WebRTC.
- `sql/`: scripts clasificados; despliegues automatizados solo desde `sql/release-manifest.txt`.
- `tools/`: quality gates, auditorías y utilidades.
- `docs/`: documentación viva; historial y snapshots versionados viven en `docs/archive/`.

Chequeo rápido de higiene:

```powershell
npm run repo:hygiene
```

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

3. Ejecuta la ruta activa de despliegue desde `sql/release-manifest.txt`. La migración final de Sprint 1-5 es `sql/migrations/supabase_sprint_1_to_5_final_contract_2026.sql`; el hardening operativo queda en `sql/migrations/20260506_wallet_gifts_rpc_security_hardening.sql`, `sql/migrations/20260506_monetization_premium_ads_security_hardening.sql`, `sql/migrations/20260506_chat_archive_preferences_2026.sql`, `sql/migrations/20260506_chat_favorites_preferences_2026.sql` y `sql/migrations/add_audio_to_post_comments.sql`.

4. Mantén RLS habilitado y usa el release manifest como lista de scripts permitidos para staging/producción. `sql/deploy_complete_unified.sql` queda como referencia histórica/legacy, no como fuente principal de deploy automatizado.

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
- `quality-gate`: corre auditoría SQL de seguridad sobre `sql/release-manifest.txt` y publica reportes
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
python tools/security_sql_audit.py --root . --manifest sql/release-manifest.txt --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH
```

Inventario histórico opcional del árbol SQL completo:

```bash
python tools/security_sql_audit.py --root . --report build/reports/security/sql_security_inventory.md --json build/reports/security/sql_security_inventory.json --fail-threshold HIGH
```

### Performance budgets release (local)

```bash
./gradlew :app:assembleRelease
python tools/performance_budget_check.py --apk app/build/outputs/apk/release --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json
```

Documentación adicional:
- `docs/SECURITY_AUDIT_CHECKLIST_2026.md`
- `docs/PERFORMANCE_BUDGETS_2026.md`
- `docs/PROJECT_HEALTH_2026-05-14.md`
- `docs/SPRINT_1_TO_5_IMPLEMENTATION_STATUS_2026.md`
- `docs/BACKUP_AND_ARCHIVE_STATUS_2026.md`

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

- **Kotlin** 1.9.22
- **Jetpack Compose** BOM 2023.10.01
- **Supabase** (gotrue, postgrest, realtime, storage)
- **Hilt** 2.51
- **Navigation Compose** 2.7.5
- **Coil** 2.5.0 (carga de imágenes)
- **Media3 / ExoPlayer** 1.5.1 (reproducción de video)
- **WebRTC Native** 137.7151.05
- **Socket.io Client** 2.1.0

## 📝 Estado Actual del Proyecto

El proyecto ya no está en estado inicial. Hoy cuenta con:

- autenticación y perfil sobre Supabase;
- mensajería, grupos, comunidades y salas de voz con Android nativo;
- feed de video, lives, gifts, premium/ads, perfil, social y comentarios con audio en producción de desarrollo;
- Hilt, Compose, navegación y repositorios ya integrados;
- release/R8 configurado con `org.gradle.jvmargs=-Xmx4096m` para evitar caídas del daemon al minificar APKs por ABI;
- validación local con `compileDebugKotlin`, `testDebugUnitTest`, `assembleRelease`, auditoría SQL y performance budgets.

Puntos importantes sobre el estado real:

- La app usa **Supabase como backend principal**, pero todavía existen piezas Android ligadas a **Firebase Cloud Messaging** para push/notificaciones.
- El contrato activo de push quedó centralizado en `conectate.com.data.service.MyFirebaseMessagingService`; la implementación legacy ya no forma parte de la superficie principal.
- Las notificaciones directas respetan el chat visible con `MessageSessionStateStore` compartido, evitando notificar mientras el usuario ya está dentro de esa conversación.
- Silenciar, fijar, archivar y marcar chats como favoritos queda persistido como preferencia por usuario; archivar/favoritos ya no son solo UI local y se sincronizan con `chat_participants`.
- La pantalla de personalizar app guarda el orden de navegación inferior y `HomeScreen` lo aplica al volver al shell principal.
- `wallet`, gifts, premium, ads y programa de creadores ya tienen ruta activa de app: `WalletRepository`, `GiftSelectionDialog`, `LiveViewerScreen`, `MonetizationRepository`, `MonetizationCenterScreen` y `CreatorProgramScreen` consumen Supabase/RPCs con RLS y hardening en el release manifest.
- La base ya es funcional, pero todavía sigue en fase de **saneamiento estructural**: repositorios grandes, cobertura de tests mejorable y deuda SQL histórica fuera del release manifest.
- A nivel Android, la base ya quedó bastante más consistente para seguir con nuevas funcionalidades sin cargar otra vez tanta duplicación entre `mensajes`, `grupos` y `comunidades`.
- La referencia técnica viva para esa base está en `docs/ANDROID_CODEBASE_BASELINE_2026.md`.
- La guía de orden del repositorio y clasificación de carpetas está en `docs/REPO_STRUCTURE_GUIDE_2026.md`.
- El chequeo local recomendado combina `compileDebugKotlin`, `testDebugUnitTest`, `assembleRelease`, auditoría SQL y performance budgets.

Validación local cerrada el `2026-05-06`:

- `:app:compileDebugKotlin`
- `:app:compileDebugKotlin` despues de favoritos de chat persistentes
- `:app:testDebugUnitTest` con 66 tests en verde
- `:app:assembleRelease`, generando los cuatro APKs release unsigned por ABI en `app/build/outputs/apk/release/`
- `:app:assembleRelease --max-workers=1` despues de favoritos de chat persistentes, regenerando los cuatro APKs el `2026-05-07` local
- `:app:qualityGateDebug :app:assembleDebug :app:assembleRelease --max-workers=1` antes de abrir en Android Studio, en verde
- `python tools/security_sql_audit.py --root . --manifest sql/release-manifest.txt --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH`, sin HIGH/CRITICAL ni violaciones nuevas
- `python tools/performance_budget_check.py --apk app/build/outputs/apk/release --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json`, con los cuatro APKs dentro de budget

Nota runtime: `adb` existe en el SDK local, pero no habia dispositivo/emulador conectado durante la ultima verificacion. La apertura real de la APK en telefono/emulador y revision de `logcat` queda como comprobacion pendiente fuera de los gates locales ya ejecutados.

### Base técnica ya consolidada

- Modelos de datos y persistencia local
- Repositorios principales de `auth`, `user`, `message`, `video`, `group`, `community`
- Módulos de Hilt
- Navegación principal
- Pantallas principales de `mensajes`, `videos`, `social` y `perfil`
- Ajustes de app y de mensajes
- Infraestructura de realtime, voz y push

### Deuda prioritaria antes de grandes funcionalidades nuevas

- aumentar cobertura de tests unitarios e instrumentados;
- limpiar documentación para que refleje exactamente el código actual;
- mantener el release manifest limpio y reducir deuda SQL/RLS histórica fuera de la ruta deployable.

La limpieza estructural principal del Android ya está bastante avanzada; lo que más condiciona la siguiente etapa ya no es tanto duplicación visual/repositorios de `grupo` y `comunidad`, sino pruebas de regresión y seguridad backend.

## 🎨 Diseño

El tema utiliza colores rojos como color primario:
- **Primary**: #E53935
- **Primary Dark**: #B71C1C
- **Primary Light**: #FF6F60

## 📧 Contacto

Para preguntas o soporte técnico, contacta al equipo de desarrollo.

## 📄 Licencia

Este proyecto es privado y confidencial.
