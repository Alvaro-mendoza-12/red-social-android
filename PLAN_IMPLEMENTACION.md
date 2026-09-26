# PLAN DE IMPLEMENTACIÓN — NOVVIN / CONÉCTATE
**Fecha**: 2026-09-23  
**Preparado para**: Agente de programación que ejecutará las correcciones  
**Referencia**: `AUDITORIA_COMPLETA.md` (misma raíz del proyecto)

> **Repriorización tras contrastar el código (2026-09-23).** La estimación y el orden originales no reflejan N-001/N-002/N-003/N-007/N-009 ni la suite real de pruebas. Se conserva el plan para trazabilidad; el orden operativo pasa a: (1) N-001 cierre de recuperación de sesión revocada y prueba de regresión; (2) N-007 2FA obligatorio en el servidor para el backend principal; (3) N-002 preservar outbox por cuenta antes de cualquier borrado local; (4) N-003/N-009 proteger media publicada y autorización de borrado; (5) H-001 solo capturas que ocultan un fallo funcional, con cancelación de coroutines respetada; (6) H-016 después de probar su flujo; (7) N-004/N-005/N-006; (8) refactorización, UX y accesibilidad según impacto medido. H-003 se descarta en su formulación “~0%”, pues hay 819 pruebas Android, 201 API y 87 web tras las adiciones de esta implementación. H-008 se descarta para el ejemplo `LoginScreen.kt:447` porque el icono acompaña texto. No ejecutar en bloque las sugerencias de `runBlocking`, `R8`, librerías o migraciones sin prueba específica de compatibilidad. El control por tarea y las limitaciones figuran en `IMPLEMENTACION_PROGRESO.md`.

> **Actualización de ejecución.** N-011 (render de música para momentos con FFmpeg compatible) se añadió al bloque de media y quedó validado en API/PostgreSQL aislados con imagen y video. N-010 (permiso persistente del selector de documentos) pertenece al bloque funcional Android. N-002 ya archiva por cuenta las operaciones pendientes de chat, grupos y comunidades; N-012 añade revocación del refresh móvil al cerrar sesión. Ambos siguen parciales hasta verificar en Android físico. N-009 continúa parcial: `moment-media`, `videos` y `thumbnails` ya rechazan borrar o sobrescribir archivos publicados; faltan otros buckets y la carrera entre publicación y borrado. La cuenta actual de la suite API es 201 correctas de 202 (1 omitida). La firma release y la prueba en dispositivo permanecen bloqueadas por el entorno; véase `IMPLEMENTACION_PROGRESO.md`.

**N-002, criterio de seguridad antes del cambio:** `MessageDao` almacena `pending_action='send'/'edit'/'delete'` en una única Room DB global; `AuthService.signOut()` y `clearLocalDataForAccountSwitch()` llaman `clearAllTables()`. No quitar ese borrado sin aislar por cuenta: la solución necesita una outbox cifrada y separada por identidad, transferencia confirmada antes del borrado, restauración solo tras autenticar la misma cuenta y pruebas con mensaje offline + cambio/reinicio/regreso. También deben revisarse pendientes de grupos y comunidades, no solo chat directo.

---

## CONTEXTO PARA EL AGENTE EJECUTOR

Este documento está diseñado para que puedas ejecutar las correcciones **sin repetir la auditoría**.

### Arquitectura del Proyecto (Resumen)

- **App Android** (`app/`): Kotlin, Jetpack Compose, MVVM, Hilt, Room, Ktor, WebRTC
- **Backend API** (`infrastructure/local-fallback/api/`): Node.js, Express, PostgreSQL
- **Signaling Server** (`signaling-server/`): Node.js, Socket.IO, Redis
- **Web App** (`Conectate-web/`): React 19, Vite, TypeScript
- **Base de datos**: PostgreSQL 16, Room v55 (local Android)
- **Infraestructura**: Docker Compose con Postgres, Redis, Nginx, Coturn

### Paquete Android Principal
`app/src/main/java/conectate/com/`

### Archivos Clave
- Application: `SocialApp.kt`
- Navegación: `presentation/navigation/AppNavGraph.kt`, `presentation/navigation/Screen.kt`
- DI: `di/CoreModule.kt`, `di/DatabaseModule.kt`, `di/SyncDataSourceModule.kt`
- DB: `data/local/AppDatabase.kt`
- Coroutines: `core/concurrency/SafeScopes.kt`

---

## REGLAS PARA EL AGENTE EJECUTOR

1. **Ejecutar las etapas EN ORDEN**. Las dependencias están indicadas.
2. **No refactorizar sin tests previos** para las etapas 5+.
3. **Preservar la arquitectura existente** — no reescribir módulos completos.
4. **Cada cambio debe ser verificable** de la forma indicada.
5. **Commits atómicos** — un commit por hallazgo corregido.

---

## ETAPA 1 — PROBLEMAS CRÍTICOS DE ESTABILIDAD
**Prioridad: INMEDIATA | Esfuerzo: 2-4 horas | Riesgo: MUY BAJO**

### 1.1 Corregir excepciones silenciadas (H-001)

**Problema**: Bloques `catch (e: Exception) { }` completamente vacíos ocultan errores fatales.

**Archivos a modificar** (buscar `catch.*Exception.*\{\s*\}` con regex):

| Archivo | Líneas aprox. |
|---|---|
| `app/src/main/java/conectate/com/services/VoiceForegroundService.kt` | 118, 221 |
| `app/src/main/java/conectate/com/services/CallForegroundService.kt` | 71, 191 |
| `app/src/main/java/conectate/com/data/repository/VoiceRoomRepository.kt` | 2511, 2608 |
| `app/src/main/java/conectate/com/data/repository/UserRepository.kt` | 975 |
| `app/src/main/java/conectate/com/data/repository/LiveRepository.kt` | 579, 3473, 3568, 3613, 3810 |
| `app/src/main/java/conectate/com/data/repository/MessageRepository.kt` | 2890, 3519 |
| `app/src/main/java/conectate/com/data/repository/MomentRepository.kt` | — |
| `app/src/main/java/conectate/com/data/repository/message/MessageRealtimeSync.kt` | — |
| `app/src/main/java/conectate/com/presentation/messages/GroupDetailViewModel.kt` | — |
| `app/src/main/java/conectate/com/presentation/messages/CommunityDetailViewModel.kt` | — |

**Instrucciones**:
1. Buscar TODOS los bloques `catch` vacíos: `grep -rn "catch.*{.*}" --include="*.kt" app/src/main/` y filtrar los que no tienen cuerpo.
2. Para cada `catch` vacío, agregar como mínimo:
   ```kotlin
   catch (e: Exception) {
       android.util.Log.e(TAG, "Descripción del contexto", e)
   }
   ```
3. Para errores críticos (pagos, autenticación, streaming), agregar también reporte a Crashlytics:
   ```kotlin
   catch (e: Exception) {
       android.util.Log.e(TAG, "Descripción del contexto", e)
       com.google.firebase.crashlytics.ktx.Firebase.crashlytics.recordException(e)
   }
   ```

**Verificación**: Compilar. Verificar en Logcat que los errores se registran.

### 1.2 Corregir comentario engañoso (H-013)

**Archivo**: `app/src/main/java/conectate/com/SocialApp.kt`  
**Línea**: 68

**Cambiar**:
```kotlin
private val appScope = newSafeScope("SocialApp", Dispatchers.Default) // ⚠️ Sin SupervisorJob
```

**Por**:
```kotlin
private val appScope = newSafeScope("SocialApp", Dispatchers.Default) // Usa SupervisorJob (ver SafeScopes.kt)
```

**Verificación**: Lectura del código. La función `newSafeScope()` en `core/concurrency/SafeScopes.kt:20` YA usa `SupervisorJob()`.

---

## ETAPA 2 — SEGURIDAD E INTEGRIDAD DE DATOS
**Prioridad: ALTA | Esfuerzo: 4-8 horas | Riesgo: MEDIO**

### 2.1 Resolver pérdida silenciosa de 2FA (H-004)

**Archivo**: `app/src/main/java/conectate/com/auth/twoFactor/TwoFactorAuthManager.kt`  
**Función**: `createSecurePreferencesWithRecovery()` (aprox. línea 84)

**Problema actual**: Si el KeyStore pierde llaves (restauración de dispositivo), se borran las preferencias encriptadas silenciosamente. El usuario pierde 2FA sin saberlo.

**Solución**:
1. Antes de borrar las prefs, marcar un flag en SharedPreferences NO encriptadas:
   ```kotlin
   // Antes de eliminar las preferencias encriptadas:
   context.getSharedPreferences("app_flags", Context.MODE_PRIVATE)
       .edit()
       .putBoolean("2fa_recovery_needed", true)
       .apply()
   ```
2. En el flujo de login, verificar este flag y mostrar un diálogo informando al usuario que debe re-configurar 2FA.
3. Limpiar el flag después de que el usuario reconfigure o descarte.

**Dependencias**: Ninguna  
**Verificación**: Test manual — hacer backup y restaurar dispositivo, verificar que se muestra notificación.

---

## ETAPA 3 — ERRORES FUNCIONALES
**Prioridad: ALTA | Esfuerzo: 3-5 horas | Riesgo: BAJO**

### 3.1 Eliminar runBlocking en FCM Service (H-009)

**Archivo**: `app/src/main/java/conectate/com/data/service/MyFirebaseMessagingService.kt`  
**Línea**: ~396

**Cambiar**: `runBlocking(Dispatchers.IO)` por un `CoroutineScope` con `SupervisorJob`:

```kotlin
// En la clase, declarar:
private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// En onMessageReceived o donde se use runBlocking:
serviceScope.launch {
    withTimeoutOrNull(2_000L) {
        // lógica de inserción en BD
    }
}

// En onDestroy:
override fun onDestroy() {
    super.onDestroy()
    serviceScope.cancel()
}
```

**Verificación**: Compilar. Enviar ráfaga de push notifications. Verificar que no se pierden mensajes.

### 3.2 Verificar binding de HiltWorkerFactory (H-016)

**Archivo**: `app/src/main/java/conectate/com/SocialApp.kt`  
**Líneas**: 44, 58-66

**Verificar**:
1. Confirmar que `implementation(libs.hilt.work)` está en `build.gradle.kts`
2. Confirmar que `kapt(libs.hilt.compiler)` o `ksp(libs.hilt.compiler)` incluye el procesador de Workers
3. Si está correcto, eliminar el comentario `// ⚠️ No hay @Provides para este tipo` y el fallback silencioso:

```kotlin
override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
        .setWorkerFactory(workerFactory)
        .setMinimumLoggingLevel(android.util.Log.INFO)
        .build()
```

**Verificación**: Compilar. Verificar que Workers con inyección de dependencias funcionan.

---

## ETAPA 4 — FUNDACIÓN DE TESTS
**Prioridad: ALTA | Esfuerzo: 40-60 horas | Riesgo: BAJO**
**⚠️ DEBE COMPLETARSE ANTES DE LA ETAPA 5**

### 4.1 Configurar infraestructura de testing

**Archivos a crear/verificar**:
- `app/src/test/java/conectate/com/` — tests unitarios
- `app/src/androidTest/java/conectate/com/` — tests instrumentados

**Dependencias a verificar** (ya declaradas en `libs.versions.toml`):
```toml
junit = "4.13.2"
testExtJunit = "1.1.5"
testCoreKtx = "1.5.0"
espressoCore = "3.5.1"
robolectric = "4.12.2"
```

**Agregar** (si no existen):
```toml
mockk = "1.13.8"
turbine = "1.0.0"
kotlinx-coroutines-test = "1.7.3"
```

### 4.2 Tests prioritarios a implementar

**Prioridad 1 — ViewModels críticos**:
- `AuthViewModel` — login, registro, manejo de estados de error
- `ChatViewModel` — envío de mensajes, estados de carga
- `WalletViewModel` — transacciones, saldos

**Prioridad 2 — Repositorios core (usar subsets manejables)**:
- `AuthRepository` — login, refresh token, logout
- `WalletRepository` — balance, transactions
- `MessageRepository` (funciones críticas) — sendMessage, deleteMessage

**Prioridad 3 — Migraciones Room**:
```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )
    
    @Test
    fun migrate1To55() {
        // Test all migration paths
    }
}
```

### 4.3 Patrón de test recomendado

```kotlin
@HiltAndroidTest
class AuthViewModelTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)
    
    @Inject
    lateinit var viewModel: AuthViewModel
    
    @Before
    fun setup() {
        hiltRule.inject()
    }
    
    @Test
    fun `login with valid credentials updates state to success`() = runTest {
        viewModel.login("user@test.com", "password123")
        viewModel.authState.test {
            assertThat(awaitItem()).isInstanceOf(AuthState.Success::class.java)
        }
    }
}
```

**Verificación**: `./gradlew test` pasa. Cobertura medida con JaCoCo.

---

## ETAPA 5 — ARQUITECTURA Y MANTENIBILIDAD
**Prioridad: MEDIA | Esfuerzo: 80-120 horas | Riesgo: ALTO**
**⚠️ REQUIERE ETAPA 4 COMPLETADA**

### 5.1 Descomponer God Classes Android (H-002)

**Estrategia**: Extraer responsabilidades en sub-repositorios sin cambiar la API pública inicialmente.

**MessageRepository.kt (255KB)** → Dividir en:
| Nuevo archivo | Responsabilidad |
|---|---|
| `MessageSendRepository.kt` | Envío y creación de mensajes |
| `MessageSyncRepository.kt` | Sincronización local↔remoto |
| `MessageCacheRepository.kt` | Caché y persistencia local |
| `MessageMediaRepository.kt` | Manejo de multimedia en mensajes |
| `MessageReadStateRepository.kt` | Estado de lectura/entrega |

**LiveRepository.kt (188KB)** → Dividir en:
| Nuevo archivo | Responsabilidad |
|---|---|
| `LiveStreamRepository.kt` | Gestión de streams |
| `LiveGiftRepository.kt` | Regalos en vivo |
| `LiveChatRepository.kt` | Chat de live |
| `LiveCoHostRepository.kt` | Co-hosts |
| `LiveViewerRepository.kt` | Viewers y conteos |

Aplicar el mismo patrón a: `VideoRepository`, `CommunityRepository`, `UserRepository`, `GroupRepository`.

**Método**:
1. Crear el nuevo sub-repositorio con las funciones extraídas.
2. En el repositorio original, delegar al sub-repositorio.
3. Correr tests existentes para verificar que no hay regresiones.
4. Gradualmente actualizar los consumidores para usar el sub-repositorio directamente.

### 5.2 Modularizar Backend (H-005)

**data-routes.js (603KB)** → Dividir por dominio:
| Nuevo archivo | Responsabilidad |
|---|---|
| `data-routes-profiles.js` | Rutas de perfiles |
| `data-routes-messages.js` | Rutas de mensajería |
| `data-routes-videos.js` | Rutas de videos |
| `data-routes-social.js` | Rutas sociales (posts, follows) |
| `data-routes-live.js` | Rutas de live streams |
| `data-routes-groups.js` | Rutas de grupos/comunidades |

Usar un `index.js` que registre todos los módulos:
```javascript
const { registerProfileRoutes } = require('./data-routes-profiles')
const { registerMessageRoutes } = require('./data-routes-messages')
// ...
function registerDataRoutes(app, pool) {
    registerProfileRoutes(app, pool)
    registerMessageRoutes(app, pool)
    // ...
}
```

### 5.3 Inyectar DispatcherProvider (H-010)

**Patrón**:
```kotlin
// Ya existe en core/concurrency/DispatcherProvider.kt
// Verificar que se inyecta vía Hilt en CoreModule

// En repositorios, cambiar:
withContext(Dispatchers.IO) { ... }
// Por:
withContext(dispatcherProvider.io) { ... }
```

**Archivos afectados**: 20+ repositorios y ViewModels.

---

## ETAPA 6 — RENDIMIENTO
**Prioridad: MEDIA | Esfuerzo: 16-28 horas | Riesgo: MEDIO**

### 6.1 Migrar threads manuales a coroutines (H-011)

**AudioRecorder.kt:204** — `startWriterThread()`:
- Reemplazar `Thread` manual por `CoroutineScope.launch(Dispatchers.IO)`
- Vincular al lifecycle del componente

**WebRTCManager.kt:2574**:
- Similar migración, pero verificar compatibilidad con WebRTC callbacks

### 6.2 Investigar R8 fullMode (H-012)

1. Buscar la causa original del `ConcurrentModificationException` en los logs JVM (`hs_err_pid*.log` en la raíz)
2. Si fue un bug de R8 ya corregido, habilitar `android.enableR8.fullMode=true`
3. Build de release y smoke test completo

### 6.3 Consolidar hotfixes SQL (H-014)

- Revisar `sql/hotfixes/`
- Incorporar cada hotfix como migración numerada
- Verificar con deploy en entorno limpio

---

## ETAPA 7 — UX/UI (HEURÍSTICAS DE NIELSEN)
**Prioridad: MEDIA | Esfuerzo: 28-42 horas | Riesgo: BAJO**

### 7.1 Internacionalización (H-006)

**Comando para encontrar textos hardcodeados**:
```bash
grep -rn 'Text("' --include="*.kt" app/src/main/java/conectate/com/presentation/
grep -rn 'text = "' --include="*.kt" app/src/main/java/conectate/com/presentation/
```

**Para cada hallazgo**:
1. Crear entry en `app/src/main/res/values/strings.xml`
2. Reemplazar `Text("texto")` por `Text(stringResource(R.string.key))`

### 7.2 Unificar colores al tema (H-007)

**Buscar**:
```bash
grep -rn 'Color(0x' --include="*.kt" app/src/main/java/conectate/com/presentation/
```

**Para cada hallazgo**:
1. Agregar el color al `ColorScheme` en el tema
2. Reemplazar `Color(0xFF...)` por `MaterialTheme.colorScheme.nuevaClave`

---

## ETAPA 8 — ACCESIBILIDAD
**Prioridad: MEDIA-BAJA | Esfuerzo: 8-12 horas | Riesgo: BAJO**

### 8.1 Agregar contentDescription (H-008)

**Buscar**:
```bash
grep -rn 'contentDescription = null' --include="*.kt" app/src/main/java/
```

**Para cada hallazgo**:
1. Determinar si el icono es **decorativo** (acompañado de texto visible) → dejar `null`
2. Si es **funcional** (botón sin texto) → agregar descripción significativa:
   ```kotlin
   Icon(
       imageVector = Icons.Default.ArrowBack,
       contentDescription = stringResource(R.string.cd_go_back)
   )
   ```

---

## ETAPA 9 — MEJORAS SECUNDARIAS
**Prioridad: BAJA | Esfuerzo: 12-20 horas | Riesgo: BAJO**

### 9.1 Optimizar assets (H-015)
- Convertir PNGs grandes a WebP con `cwebp`
- Evaluar vectorización de iconos simples

### 9.2 Centralizar lógica de permisos
- Accompanist Permissions ya está en dependencias (`0.32.0`)
- Extraer comprobaciones inline a composables reutilizables

---

## ETAPA 10 — TESTS Y VALIDACIÓN FINAL
**Prioridad: CONTINUA**

### Tests a implementar progresivamente:

| Tipo | Herramienta | Módulos |
|---|---|---|
| Unit tests | JUnit 4 + MockK + Turbine | ViewModels, Repositorios |
| Room migration | AndroidX Test + Room Testing | AppDatabase |
| Integration | Hilt Testing | Auth flow, Payment flow |
| UI/E2E | Compose Test + Espresso | Pantallas principales |
| Backend API | Jest/Mocha | Endpoints críticos |
| Signaling | Jest + Socket.IO Client | Conexión, rooms |
| Rendimiento | AndroidX Benchmark | Cold start, scroll |
| Accesibilidad | TalkBack manual | Todas las pantallas |

---

## ESTIMACIÓN TOTAL

| Etapa | Esfuerzo | Riesgo |
|---|---|---|
| 1. Críticos | 2-4h | Muy bajo |
| 2. Seguridad | 4-8h | Medio |
| 3. Funcionales | 3-5h | Bajo |
| 4. Tests base | 40-60h | Bajo |
| 5. Arquitectura | 80-120h | Alto |
| 6. Rendimiento | 16-28h | Medio |
| 7. UX/UI | 28-42h | Bajo |
| 8. Accesibilidad | 8-12h | Bajo |
| 9. Secundarias | 12-20h | Bajo |
| 10. Tests finales | Continuo | Bajo |
| **TOTAL** | **~193-299h** | — |

---

## CHECKLIST DE PROGRESO

- [ ] **ETAPA 1**: Excepciones silenciadas corregidas
- [ ] **ETAPA 1**: Comentario de SupervisorJob corregido
- [ ] **ETAPA 2**: 2FA recovery implementado
- [ ] **ETAPA 3**: runBlocking eliminado de FCM Service
- [ ] **ETAPA 3**: HiltWorkerFactory verificado
- [ ] **ETAPA 4**: Infraestructura de testing configurada
- [ ] **ETAPA 4**: Tests unitarios de ViewModels críticos
- [ ] **ETAPA 4**: Tests unitarios de Repositorios core
- [ ] **ETAPA 4**: Tests de migración Room
- [ ] **ETAPA 5**: MessageRepository descompuesto
- [ ] **ETAPA 5**: LiveRepository descompuesto
- [ ] **ETAPA 5**: Backend modularizado
- [ ] **ETAPA 5**: DispatcherProvider inyectado
- [ ] **ETAPA 6**: Threads manuales migrados
- [ ] **ETAPA 6**: R8 fullMode investigado
- [ ] **ETAPA 6**: Hotfixes SQL consolidados
- [ ] **ETAPA 7**: Textos extraídos a strings.xml
- [ ] **ETAPA 7**: Colores unificados al tema
- [ ] **ETAPA 8**: contentDescription agregados
- [ ] **ETAPA 9**: Assets optimizados
- [ ] **ETAPA 9**: Permisos centralizados
- [ ] **ETAPA 10**: Tests de integración
- [ ] **ETAPA 10**: Tests E2E
- [ ] **ETAPA 10**: Tests de rendimiento
- [ ] **ETAPA 10**: Tests de accesibilidad

---

*Plan generado el 2026-09-23. Basado en la auditoría completa del proyecto Novvin/Conéctate.*
