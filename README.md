# Novvin — Red social móvil

Fecha de última revisión documental: 2026-10-29.

> Documentación oficial actual: [docs/oficial/README.md](docs/oficial/README.md).
> Si algún documento antiguo contradice esa carpeta, usa `docs/oficial/` como fuente de verdad y valida el estado real con los comandos del runbook.

Continuación vigente: [web: insignias, chat adaptable y estados comprensibles](docs/oficial/87-web-insignias-chat-adaptable-y-estados-2026-10-29.md), publicada y subida a GitHub en ambas ramas; 852 pruebas web y 90 controles públicos aprobados. Continúa las [salas compartidas y recuperación de voz](docs/oficial/86-salas-compartidas-voz-y-avatares-2026-10-29.md) y la [auditoría móvil 85](docs/oficial/85-conectividad-cancelacion-y-carga-movil-2026-10-29.md). Candidato Android **9.1.30 / código 170**, local: 1.956 unitarias aprobadas. La apertura HTTPS directa requiere la huella de firma de Play; la optimización API aislada no se despliega con la web. Los cierres distinguen producción web, pruebas QA y distribución móvil pendiente.

La [revisión 83](docs/oficial/83-capturas-chat-y-recuperacion-voz-2026-10-28.md) conserva la publicación anterior, el aviso de momentos y el último paquete Android **9.1.29 / 169** firmado. La indexación y sus comprobaciones están documentadas en esa continuidad y la [revisión SEO 80](docs/oficial/80-auditoria-seo-y-candidato-local-2026-10-27.md). La rama `main` está conectada a Vercel: la tanda 86 tiene autorización del propietario para publicar la web y subir los commits.

La [revisión del servidor](docs/oficial/76-auditoria-servidor-2026-10-26.md) y su [continuación del 27/10](docs/oficial/78-capacidad-host-y-acceso-web-2026-10-27.md) registran limpieza, restauración, Ubuntu actualizado y reiniciado, capacidad y transporte web público. La [revisión de indexación](docs/oficial/79-indexacion-publica-2026-10-27.md) recoge 19 páginas, seis imágenes nuevas y verificaciones de Vercel y buscadores. Las [instrucciones de continuidad](docs/oficial/77-prompt-continuidad-produccion-y-escalado.md) conservan los objetivos pendientes.

Una plataforma de red social móvil construida con **Kotlin**, **Jetpack Compose** y backend propio en **Ubuntu**. Supabase queda como compatibilidad legacy y herramienta temporal de migracion/importacion.

## Probar Novvin

- **Android (Google Play):** <https://play.google.com/store/apps/details?id=conectate.com>
- **Web:** <https://red-social-android.vercel.app>

Novvin está en beta pública. Las pruebas y comentarios honestos de usuarios reales ayudan a priorizar mejoras; no se solicitan pagos para empezar.

## 📱 Características

- ✅ **Acceso** con correo, cuentas guardadas, recuperación, 2FA y exploración como invitado
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
- **Backend principal**: Ubuntu local/server (`infrastructure/local-fallback`) con Auth, Postgres, storage, realtime, signaling y proxy edge.
- **Backend legacy**: Supabase solo para migracion/importacion mientras se termina la limpieza controlada.
- **Comunicación de Voz**: WebRTC Native SDK + Custom Node.js Signaling

## 🚀 Configuración Inicial

### Prerequisites

1. **Android Studio** (Hedgehog o superior recomendado)
2. **JDK 17–21** para ejecutar Gradle (el bytecode Java/Kotlin sigue dirigido a 17). JDK 22 o superior falla de forma explícita para evitar builds incompatibles.
3. **VM/servidor Ubuntu** con Docker y Docker Compose para backend principal.

### Paso 1: Clonar el Proyecto

El proyecto ya está en `d:\RED SOCIAL`

### Paso 2: Configurar Ubuntu como backend principal

1. Entra a la VM/servidor Ubuntu.
2. Copia `infrastructure/local-fallback`.
3. Configura `.env` con `POSTGRES_PASSWORD`, `JWT_SECRET`, `ADMIN_SYNC_TOKEN` y `PUBLIC_BASE_URL`.
4. Levanta servicios con Docker Compose.
5. En Android configura `secrets.properties`:

```properties
FALLBACK_API_URL=https://tu-url-ngrok-o-dominio
SUPABASE_URL=
SUPABASE_ANON_KEY=
```

Guia oficial: `docs/CONECTATE_UBUNTU_OFFICIAL_RUNBOOK_2026.md`.

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
- `release-smoke`: genera una clave efímera, valida `assembleRelease`/`bundleRelease` y comprueba criptográficamente la firma en cada push
- `release-smoke`: rechaza AAB sin alineación ZIP de 16 KiB o con bibliotecas ELF de 64 bits incompatibles
- `release-smoke`: calcula con `bundletool` la descarga máxima real por dispositivo y valida budgets de AAB/DEX/native comprimidos
- `android-production-release.yml`: workflow manual protegido que exige la clave original de Play, fija la huella del certificado y publica un candidato verificable
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
./gradlew :app:bundleRelease
python tools/verify_android_release.py app/build/outputs/bundle/release/app-release.aab --expected-cert-sha256 "$NOVVIN_UPLOAD_CERT_SHA256"
python tools/verify_android_page_size.py app/build/outputs/bundle/release/app-release.aab
python tools/performance_budget_check.py --artifacts app/build/outputs/bundle/release/app-release.aab --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json
```

Documentación adicional:
- `docs/TEAM_HANDOFF_UBUNTU_2026.md`
- `docs/CONECTATE_UBUNTU_OFFICIAL_RUNBOOK_2026.md`
- `docs/UBUNTU_PRODUCTION_READINESS.md`
- `docs/SECURITY_AUDIT_CHECKLIST_2026.md`
- `docs/PERFORMANCE_BUDGETS_2026.md`

Para que funcione en tu repositorio remoto con Ubuntu primario, configura estos secretos/variables segun tu pipeline:

```
FALLBACK_API_URL
LOCAL_FALLBACK_API_URL
VITE_FALLBACK_API_URL
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

## 🔐 Seguridad (Ubuntu principal)

El contrato actual de seguridad se apoya en Ubuntu:

- JWT local firmado con `JWT_SECRET`.
- Login web por QR con pairings temporales.
- Sesiones web revocables desde la APK movil.
- Storage servido por Ubuntu y proxy `edge`.
- Supabase solo se habilita con `LEGACY_SUPABASE_IMPORT_ENABLED=true` durante migraciones puntuales.

No comitees `secrets.properties`, `.env` ni llaves de migracion.

### Storage (Ubuntu)

El storage de produccion se sirve desde `conectate-fallback-storage` y las rutas compatibles `/storage/v1/object/...`.

## 🛠️ Tecnologías Utilizadas

- **Kotlin** 2.2.10 / **KSP** 2.2.10-2.0.2
- **Jetpack Compose** BOM 2026.06.00
- **Ubuntu backend** (Node/Express, Postgres, storage local, realtime gateway, nginx edge)
- **Supabase legacy** solo para migracion temporal
- **Hilt** 2.57.2
- **Navigation Compose** 2.7.5
- **Coil** 2.5.0 (carga de imágenes)
- **Media3 / ExoPlayer** 1.11.0 (reproducción, caché y exportación multimedia)
- **WebRTC Native** 137.7151.05
- **Socket.io Client** 2.1.0

## 📝 Estado Actual del Proyecto

El proyecto ya no está en estado inicial. Hoy cuenta con:

- autenticación, perfil y storage sobre Ubuntu;
- mensajería, grupos, comunidades y salas de voz con Android nativo;
- feed de video, perfil y social en producción de desarrollo;
- Hilt, Compose, navegación y repositorios ya integrados;
- validación local con `compileDebugKotlin`, `testDebugUnitTest` y `lintDebug`.

Puntos importantes sobre el estado real:

- La app usa **Ubuntu como backend principal** cuando `FALLBACK_API_URL` esta configurado.
- Todavia existen piezas legacy de Supabase en Android/web que deben limpiarse solo cuando cada modulo tenga equivalente Ubuntu probado.
- El contrato activo de push quedó centralizado en `conectate.com.data.service.MyFirebaseMessagingService`; la implementación legacy ya no forma parte de la superficie principal.
- `wallet` y el descubrimiento público por hashtags quedaron **feature-gateados** para evitar rutas rotas mientras se terminan sus contratos reales.
- La base ya es funcional, pero todavía sigue en fase de **saneamiento estructural**: repositorios grandes, tests insuficientes y deuda SQL/RLS todavía abierta.
- A nivel Android, la base ya quedó bastante más consistente para seguir con nuevas funcionalidades sin cargar otra vez tanta duplicación entre `mensajes`, `grupos` y `comunidades`.
- La referencia técnica viva para esa base está en `docs/ANDROID_CODEBASE_BASELINE_2026.md`.
- La guía de orden del repositorio y clasificación de carpetas está en `docs/REPO_STRUCTURE_GUIDE_2026.md`.
- En Windows, el gate local de debug, herramientas y seguridad se ejecuta con `.\tools\quality_gate.ps1 -DebugOnly`; el release completo requiere la configuración de firma original descrita en `docs/oficial/08-pruebas-release-handoff.md`.

Validación local cerrada el `2026-04-26`:

- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- `:app:testDebugUnitTest`
- `:app:lintDebug`
- `:app:qualityGateDebug`
- `:app:assembleRelease`
- `:app:bundleRelease`
- `python tools/verify_android_release.py app/build/outputs/bundle/release/app-release.aab`
- `python tools/verify_android_page_size.py app/build/outputs/bundle/release/app-release.aab`
- `python tools/performance_budget_check.py --artifacts app/build/outputs/bundle/release/app-release.aab`

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
- endurecer seguridad SQL/RLS.

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
