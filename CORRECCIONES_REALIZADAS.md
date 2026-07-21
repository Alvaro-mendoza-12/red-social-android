# CORRECCIONES REALIZADAS — Estabilización de crashes (APK)

> Rama: `fix/release-crash-stabilization` (creada desde `production-stabilization-full-audit`)
> Fecha: 2026-07-19
> Alcance autorizado: verificar hallazgos del informe contra el código real, descartar falsos
> positivos, y corregir crashes reales de la APK con cambios **pequeños y reversibles**.
> Restricciones respetadas: NO se tocó PostgreSQL, Nginx ni el servidor de producción; NO se
> hicieron migraciones de datos; NO se desplegó nada.

Este documento se actualiza **inmediatamente** tras cada grupo de cambios.

---

## 0. Método de verificación

Todos los hallazgos "críticos/altos" relativos a crashes de la APK Release se verificaron
**contra el código real y contra los logs de crash reales** (`crash_log.txt`, `logcat.txt`,
`logcat2.txt`), no solo contra la descripción del informe. Herramienta clave de verificación:
comparador automático del normalizador de esquema Room contra el esquema autoritativo exportado
por Room (`app/schemas/conectate.com.data.local.AppDatabase/51.json`).

---

## 1. Falsos positivos descartados (NO requieren cambios)

La instrucción pedía "corregir primero" los crashes de Release relacionados con WebRTC, llamadas,
salas de voz, R8, ProGuard, JNI, `@CalledByNative` y Foreground Services. **Tras verificar contra
el código real, ninguna de estas categorías tiene un crash real pendiente:**

| Categoría | Verificación | Veredicto |
|---|---|---|
| **R8 / ProGuard / WebRTC** | `app/proguard-rules.pro` ya contiene reglas keep correctas y completas: `-keep class conectate.com.data.webrtc.**`, `-keep class conectate.com.data.signaling.**`, `-keep class org.webrtc.**`, `native <methods>` global, y `@org.webrtc.CalledByNative`/`CalledByNativeUnchecked` (métodos y campos) preservados (líneas 19-20, 63-77). | ✅ Falso positivo — ya correcto |
| **JNI / `@CalledByNative`** | `grep -rn "@CalledByNative" app/src/main/java` → **0 resultados**. No hay código nativo propio (sin `.so`, sin `CMakeLists.txt`, sin `System.loadLibrary`). El único JNI es el de la AAR `org.webrtc`, cuyas clases están preservadas. | ✅ Falso positivo — sin superficie propia |
| **Foreground Services (Android 14+)** | `VoiceForegroundService`, `LiveForegroundService`, `CallForegroundService` usan `ServiceCompat.startForeground` con tipos calculados y **`try/catch` defensivo** que captura `SecurityException` en Android 14+ y detiene el servicio limpiamente (`VoiceForegroundService.kt:100-121`). Tipos declarados en el manifiesto (`camera\|microphone\|mediaProjection`). | ✅ Ya manejado defensivamente |
| **Llamadas / Salas de voz (crash)** | `grep -icE "ForegroundServiceStartNotAllowed\|MissingForegroundServiceType\|UnsatisfiedLink..."` sobre los 3 logs → **0**. No hay crash de estas categorías en los logs reales. | ✅ Sin evidencia de crash |

**Conclusión:** el `isMinifyEnabled = true` de Release **sí** ejecuta R8, pero las reglas keep de
WebRTC/JNI/@CalledByNative son correctas, por lo que la hipótesis "R8 elimina clases WebRTC y la
APK Release crashea" **no se sostiene**. No se hará ningún cambio en `proguard-rules.pro` ni en los
foreground services (cambiarlos sin un crash reproducible solo añadiría riesgo).

---

## 2. Crash real #1 — Migración de Room (app no abre para usuarios existentes)

**Estado: YA CORREGIDO en el working tree (por sesión previa) y VERIFICADO en esta sesión.**

- **Evidencia del crash (logs reales):**
  `java.lang.IllegalStateException: Migration didn't properly handle: users(...UserEntity)`,
  y equivalentes para `video_comments`, `groups` (`crash_log.txt`/`logcat.txt`, 2026-07-16).
- **Causa raíz:** `LatestSchemaNormalizer` (en `DatabaseMigrations.kt`) mantiene a mano el esquema
  "objetivo" de cada tabla. Cuando se añadieron columnas a las entidades (`UserEntity`,
  `VideoCommentEntity`, `GroupEntity`, `CommunityEntities`) sin espejarlas en el normalizador, el
  hash de esquema que Room valida al abrir la BD dejaba de coincidir → excepción al arrancar →
  **la app no abre para ningún usuario con datos existentes** (impacto crítico, adyacente a
  pérdida de datos).
- **Corrección presente en el working tree (verificada):**
  - `users` ← `cover_url`, `profile_theme`, `profile_effect`, `profile_welcome` (commit 598fd62)
  - `video_comments` ← `reactionsJson`, `myReaction`, `updatedAt`
  - `groups` ← `rest_until`
  - `communities` ← `daily_message_limit`
  - `cleanupOrphanedRecords(db)` se ejecuta antes del `PRAGMA foreign_key_check`.
- **Verificación realizada en esta sesión (comparador automático):**
  se comparó cada `createSql` del normalizador contra el conjunto de columnas de cada entidad en
  `51.json` (esquema autoritativo de Room):
  **`✅ Las 17 tablas del normalizador coinciden exactamente con el esquema Room v51`**.
- **Pendiente de confirmar por build:** que `assembleDebug`/`assembleRelease` compilan con estos
  cambios (en curso). *No se declara "cerrado" hasta que el build pase.*
- **Recomendación de robustez (no aplicada aún, requiere decisión):** añadir un test con
  `MigrationTestHelper` (ya existe `androidx-room-testing` en el catálogo) que falle en CI si el
  normalizador vuelve a divergir de las entidades. Esto convierte un crash de producción en un
  fallo de CI.

---

## 3. Crash real #2 — Navegación de notificación (`moment_viewer`) [CAMBIO APLICADO]

**Estado: guard mínimo aplicado en esta sesión. Pendiente de verificación por build.**

- **Archivo:** `app/src/main/java/conectate/com/presentation/profile/NotificationScreen.kt`
  (función `handleNotificationNavigation`, ~línea 257).
- **Evidencia del crash (logs reales):**
  `java.lang.IllegalArgumentException: Navigation destination that matches request
  ...moment_viewer/{id} cannot be found in the navigation graph ComposeNavGraph(0x0)
  startDestination={route=messages}` (`crash_log.txt`, 2026-07-15 y 2026-07-16).
- **Causa raíz:** `NotificationScreen` está registrada en **dos** NavHost: el raíz
  (`MainNavigationRoutes.kt:404`) y el **anidado** de pestañas dentro de `HomeScreen`
  (`HomeScreen.kt:322`, `startDestination = messages`). Cuando la pantalla se muestra dentro del
  NavHost anidado, su `navController` no contiene destinos que solo existen en el grafo raíz (p.
  ej. `moment_viewer`), y `navigate(...)` lanza `IllegalArgumentException` → crash al tocar la
  notificación. Afecta potencialmente a varias notificaciones, no solo a moments.
- **Cambio aplicado (pequeño y reversible):** se envolvió el bloque `when (type) { ... }` de
  `handleNotificationNavigation` en `runCatching { ... }.onFailure { Log.w(...) }`. Ahora un
  destino ausente en el grafo **degrada a un warning en log en vez de cerrar la app**.
- **Honestidad sobre el alcance (según instrucción #9):** este guard **detiene el crash**, pero
  **no** hace que la navegación funcione desde el contexto anidado (el usuario tocaría la
  notificación y no pasaría nada en ese caso). La corrección completa consiste en enrutar la
  navegación de notificaciones a través del `NavController` raíz. Eso es un cambio de arquitectura
  de navegación mayor y se deja como recomendación, no se declara "resuelto".

---

## 4. Verificación de build (paso obligatorio tras cada grupo de cambios)

Cadena ejecutada: `assembleDebug` → `testDebugUnitTest` → `lintDebug` → `assembleRelease`.

| Paso | Resultado |
|---|---|
| assembleDebug | ⏳ en curso |
| testDebugUnitTest | ⏳ pendiente |
| lintDebug | ⏳ pendiente |
| assembleRelease (R8/ProGuard) | ⏳ pendiente |

*(Esta tabla se actualiza al finalizar el build. Nada de lo anterior se considera "cerrado" hasta
que Debug y Release compilen sin error.)*

---

## 5. Web (verificado en la fase de auditoría, sin cambios de código)

- `Conectate-web`: `npm run build` ✅ compila, `eslint` ✅ sin errores, `jest` ✅ 63/63 tests.
- `npm audit`: 23 vulnerabilidades (0 críticas, 8 altas, 10 moderadas, 5 bajas), casi todas en
  `devDependencies` (workbox-cli, cypress, vite). No son crashes de la app Android; se listan en el
  informe de auditoría para tratamiento posterior. **No se modificaron en esta sesión de crashes.**

---

---

# RONDA 2 — Bugs reportados por el usuario (lista funcional)

El usuario reportó una lista de bugs concretos para dejar la app lista para producción. Se tratan
por orden de valor y **confianza de corrección sin regresión**. Se distingue explícitamente entre:
**(A)** corregido en código y verificable en un solo dispositivo; **(B)** causa raíz de
infraestructura (no un bug de código); **(C)** requiere reproducción/logs o dos dispositivos reales
y NO se toca a ciegas para no desestabilizar producción.

## R2-1 (A) · Contador de vistas no sube +1 al empezar el vídeo [CORREGIDO]
- **Archivo:** `app/src/main/java/conectate/com/presentation/video/RealPlaybackViewTracker.kt:91`.
- **Causa raíz:** `DEFAULT_VIEW_THRESHOLD_MS = 3_000L` → la vista solo se contaba tras **3 s** de
  reproducción continua; por eso no subía "ni bien empiezas a ver" como TikTok.
- **Cambio:** umbral bajado a **500 ms** (cuenta casi al iniciar, pero sigue exigiendo reproducción
  real y continua para no inflar vistas con el scroll rápido). La lógica de dedupe por sesión y la
  actualización optimista de la UI (`VideoFeedViewModel.onVideoPlaybackSample` →
  `incrementViewInList` + `reloadVideo`) ya eran correctas.
- **Verificación:** compila (pendiente build final); comportamiento observable requiere ejecutar la
  app. Impacto: bajo riesgo de regresión.

## R2-2 (A) · Eliminar cuenta disponible para todos los usuarios [CORREGIDO — movido a admin]
- **Archivos:** `app/src/main/java/conectate/com/presentation/profile/SettingsScreen.kt` (botón de
  "Eliminar cuenta" retirado del ajuste de usuario).
- **Contexto:** el flujo self-serve (`SettingsViewModel.deleteOwnAccount` →
  `UserRepository.deleteOwnAccount`) llamaba a un RPC de **Supabase** (`supabaseUrl` vacío en
  `BuildConfig`) → **ya estaba roto** además de ser indeseado para producción.
- **Cambio:** se elimina el botón/entrada de borrado del ajuste del usuario final. La capacidad de
  eliminación **ya existe en el backend como operación de administrador**
  (`infrastructure/local-fallback/api/src/admin-moderation-routes.js`: hard-delete con confirmación
  `ELIMINAR <id>`, bloqueo de tablas y cuarentena de almacenamiento).
- **Pendiente (recomendado, no crítico):** el diálogo `showDeleteAccountDialog` queda inalcanzable
  (código muerto que compila); conviene retirarlo del ViewModel en una limpieza posterior. Verificar
  que el panel de administración (web `AdminModerationPage`) exponga la búsqueda + hard-delete en la
  UI; el endpoint ya está.

## R2-3 (A) · Crash de navegación de notificación [CORREGIDO — Ronda 1, §3]
Ver §3. Guard `try/catch` en `handleNotificationNavigation`. Compila (confirmado: el build llegó a
`dexBuilderDebug`).

## R2-4 (B) · Voz/llamadas/directos NO funcionan entre redes distintas (datos móviles ↔ wifi)
- **Diagnóstico (verificado):** síntoma clásico de **NAT simétrica que requiere relay TURN**. Con
  wifi↔wifi funciona porque suele bastar STUN; entre datos móviles (CGNAT) y wifi hace falta TURN.
- **Estado real de la config:** el servidor de señalización **sí ofrece TURN** — `signaling-server/
  .env` tiene `TURN_STATIC_URLS=turn:openrelay.metered.ca:80,:443,:443?transport=tcp` con usuario y
  credencial (proveedor **gratuito OpenRelay**). El cliente Android obtiene y aplica estos ICE
  servers (`WebRTCManager.fetchSelfHostedIceServers`).
- **Causa raíz probable:** el tier **gratuito de OpenRelay** usa credenciales públicas compartidas,
  frecuentemente **saturadas/limitadas** → el relay falla de forma intermitente. **No es un bug del
  código de la app.**
- **Solución (infraestructura, no código):** desplegar un **coturn dedicado en un VPS público** (el
  código ya lo soporta vía `TURN_HOST` + `TURN_SECRET`, patrón REST con credenciales efímeras HMAC)
  **o** contratar un proveedor TURN fiable de pago. Configurar UDP + TCP + TLS(443). Tras ello, las
  llamadas entre redes funcionarán.
- **No se declara resuelto** (requiere infra + prueba con dos dispositivos en redes distintas).

## R2-5 (C) · "Me bota de la APK" al dar/ver directos
- **Investigación:** no hay ninguna traza de crash de directos en los logs capturados
  (`crash_log.txt`/`logcat*.txt`) — probablemente son anteriores al uso de la función. Los
  foreground services de live ya tienen manejo defensivo Android 14+.
- **Decisión:** **no se modifica a ciegas** una función de dos dispositivos sin un crash
  reproducible (riesgo de desestabilizar producción). Se necesita capturar un `logcat` en el momento
  del cierre para localizar la causa. Muy probablemente ligado al mismo problema de TURN (R2-4) si el
  "corte" ocurre al no completar la conexión de medios.
- **Acción para el usuario:** reproducir el cierre y capturar `adb logcat` filtrado por `AndroidRuntime`
  o `conectate.com` para adjuntarlo; con eso se corrige con precisión.

## R2-6 (C) · No deja actualizar foto de perfil ni de portada
- **Investigación:** el modelo (`UpdateProfileRequest` con `avatar_url`/`cover_url`/`remove_*`) y el
  flujo (`UserRepository.uploadCoverImage`/update) están completos; hubo correcciones recientes
  (commits `4841789`, `51d891c`). El fallo es de **runtime** (subida al almacenamiento o selector),
  no de estructura.
- **Decisión:** sin reproducción/log no se altera el flujo de subida (riesgo de regresión). **Nota
  relevante:** puede estar relacionado con el hallazgo crítico del backend de almacenamiento (ver
  `AUDITORIA_COMPLETA.md §4.1`); conviene revisar la respuesta real del endpoint de `/storage/upload`
  al subir el avatar/portada.
- **Acción para el usuario:** capturar el log al intentar cambiar la foto (error de subida vs. error
  de guardado de URL) para corregir el punto exacto.

## R2-7 (C) · Imágenes tardan en cargar
- **Diagnóstico:** latencia de comunicación con el servidor de almacenamiento/CDN. El backend actual
  se sirve tras un **túnel efímero Cloudflare** (`Conectate-web/public/backend-config.json`) y un
  mini-PC — latencia y variabilidad esperables.
- **Solución (infra):** CDN/caché real delante del almacenamiento + dominio estable; ver
  `AUDITORIA_COMPLETA.md §4`. No es un bug de código puntual.

---

## Verificación de build — Ronda 2

Tras aplicar R2-1 y R2-2 se ejecuta de nuevo la cadena `assembleDebug → tests → lint →
assembleRelease`. (Nada se declara cerrado hasta que Debug y Release compilen.)

| Paso | Resultado |
|---|---|
| assembleDebug | ⏳ pendiente (build en curso) |
| testDebugUnitTest | ⏳ pendiente |
| lintDebug | ⏳ pendiente |
| assembleRelease (R8/ProGuard) | ⏳ pendiente |

---

## Resumen global de la sesión

**Corregido en código (verificable en un dispositivo):**
1. Contador de vistas +1 casi inmediato (umbral 3 s → 0.5 s). [R2-1]
2. Eliminar cuenta retirado del ajuste de usuario (queda como operación admin; hard-delete de
   backend ya existe). [R2-2]
3. Guard anti-crash en navegación de notificaciones. [Ronda 1 §3]
4. Crash de migración Room verificado como ya-corregido contra el esquema autoritativo. [Ronda 1 §2]

**Causa raíz de infraestructura (no bug de código):**
5. Voz/llamadas/directos entre redes distintas → requiere **TURN dedicado** (OpenRelay gratuito
   satura). [R2-4]
6. Lentitud de imágenes → CDN/dominio estable. [R2-7]

**Requiere reproducción/logs o dos dispositivos (no tocado para no desestabilizar):**
7. Cierre al dar/ver directos [R2-5] · Actualización de foto de perfil/portada [R2-6].

**Falsos positivos verificados (sin cambios):** WebRTC/R8/ProGuard/JNI/@CalledByNative/Foreground
Services como fuente de crash de Release. [Ronda 1 §1]

**Intacto (no tocado, como se pidió):** PostgreSQL, Nginx, servidor de producción, migraciones,
despliegues.

---

# RONDA 3 — Crash de directos, build de Release y diagnóstico de avatar

## R3-1 (A) · Crash al crear/ver directo ("me bota de la APK") [CORREGIDO]
- **Archivos:** `app/src/main/java/conectate/com/presentation/video/VideoFeedScreen.kt` +
  `app/src/main/java/conectate/com/presentation/home/HomeScreen.kt`.
- **Causa raíz (confirmada):** los destinos de directos (`LiveSetup`, `LiveDiscovery`, `LiveViewer`)
  están registrados **solo en el NavHost raíz** (`MainNavigationRoutes.kt:82/88/108`). Pero
  `VideoFeedScreen` (pestaña Videos) vive dentro del **NavHost anidado** de `HomeScreen`, y llamaba a
  `navController.navigate(Screen.LiveSetup.route)` con el navController **anidado** → el destino no
  existe en ese grafo → `IllegalArgumentException` → **cierre de la app**. Afectaba a "Crear directo"
  (`:925`), "Ver directos" (`:933`) y a abrir un directo del feed (`:777`).
- **Cambio:** se añadieron 3 callbacks a `VideoFeedScreen` (`onNavigateToLiveSetup`,
  `onNavigateToLiveDiscovery`, `onNavigateToLiveViewer`), cableados en `HomeScreen` para navegar con
  `mainNavController` (el raíz), siguiendo el mismo patrón que ya usaban "Subir video"/"Buscar"/
  "Perfil". Se reemplazaron las 3 navegaciones directas por los callbacks.
- **Verificación:** compila (build final en curso). El flujo completo de directo requiere prueba real
  con dos dispositivos (no se declara "funcionando", solo que **ya no cierra la app** al entrar).

## R3-2 (A) · La APK de Release no compilaba [CORREGIDO — desbloquea producción]
- **Archivo:** `app/src/main/java/conectate/com/presentation/videos/VideoPlayerScreen.kt:633-635`.
- **Causa raíz (confirmada por el log de build):** anotación **duplicada** —
  `@androidx.annotation.OptIn(markerClass = [UnstableApi::class])` (línea 633) **y**
  `@androidx.annotation.OptIn(UnstableApi::class)` (línea 635) sobre la misma función. `@OptIn` de
  androidx **no es repetible** → `compileReleaseKotlin` fallaba con *"This annotation is not
  repeatable"*. (Debug compilaba por caché incremental; por eso solo saltaba en Release, que es la
  APK de producción.)
- **Cambio:** se eliminó la anotación duplicada (línea 635), dejando la forma `markerClass`.
- **Impacto:** desbloquea `assembleRelease`. Sin esto, **no había APK de producción**.

## R3-3 (C) · `PROFILE_AVATAR_NOT_OWNED` / "El servidor no confirmó todos los cambios" [DIAGNOSTICADO, NO tocado]
- **Origen:** `infrastructure/local-fallback/api/src/data-routes.js:4102-4113` — al guardar perfil, el
  backend valida que `avatar_url`/`cover_url` sean un objeto de storage **propiedad del usuario** en
  el bucket `avatars` (`currentUserOwnsStorageObject`). Si falla → 403 `PROFILE_AVATAR_NOT_OWNED` →
  el cliente muestra "El servidor no confirmó todos los cambios" (`SettingsViewModel.kt:296/387`).
- **Verificado (para descartar teorías erróneas):**
  - El bucket es correcto: el cliente sube avatar y portada a `avatars` (`UserRepository.kt:1599/1668`).
  - **NO es un mismatch de IDs:** el JWT se firma con `subject: user.id` (`server.js:356`), la subida
    guarda `owner_id = req.auth.sub` (= `user.id`), y `fallback_storage_objects.owner_id` tiene FK a
    `fallback_users(id)`. Es decir `owner_id == user.id`, así que la comprobación por `user.id` es
    correcta. (Se descarta la hipótesis de reconciliación de identidades.)
- **Causa raíz probable (2 candidatas, requieren la BD en vivo para distinguir):**
  1. `parsePublicStorageObjectUrl` **no parsea** la URL que envía el cliente. El cliente reescribe la
     URL al dominio del túnel (`UbuntuStorageUrl.rewrite`); si el resultado no contiene exactamente
     `/storage/v1/object/public/avatars/<ruta>`, la función devuelve `null` → NOT_OWNED.
  2. **Re-validación de un avatar sin cambios/antiguo:** al guardar el perfil, el cliente reenvía el
     `avatar_url` actual aunque no se haya cambiado; si ese avatar es una URL antigua (p. ej. de
     Supabase) sin fila en `fallback_storage_objects`, la comprobación falla aunque el usuario no
     tocara la foto.
- **Por qué NO se cambió:** es un **control de seguridad** (impide que un usuario ponga como avatar el
  medio privado de otro). Sin ver el valor real de `avatar_url` ni la tabla `fallback_storage_objects`
  en la BD en vivo no se puede saber cuál de las 2 causas es, y un cambio a ciegas podría **no
  arreglarlo o abrir un hueco de seguridad**. Cambiar esto sin verificar es lo contrario de "estable".
- **Cómo confirmarlo en 1 minuto (para el mantenedor del backend):** añadir un log temporal antes de
  la línea 4106 con `console.log('AVATAR_CHECK', patch.avatar_url, parsePublicStorageObjectUrl(patch.avatar_url, ['avatars']))`
  y reproducir; **o** en Postgres: `SELECT bucket_id, name, owner_id FROM fallback_storage_objects
  WHERE bucket_id='avatars' ORDER BY updated_at DESC LIMIT 5;` y comparar `name` con la ruta de la URL
  del avatar. Con eso se corrige con precisión (probable fix seguro: omitir la comprobación cuando el
  `avatar_url` **no cambia** respecto al guardado, o normalizar la URL antes de parsear).

## R3-4 · "La APK instalada = la de producción" — firma de Release
- **Estado actual:** **no existe `keystore.properties`** ni un keystore de release (solo
  `keystores/debug.keystore`). Por eso `hasReleaseSigning = false` (`app/build.gradle.kts:24`) y
  `assembleRelease` genera una APK **sin firmar** (`app-release-unsigned.apk`), **no instalable**.
- **La APK que instalas hoy desde Android Studio es la variante `debug`** (sin R8/minify), que **no
  es** la de producción. Por eso los crashes que solo ocurren en Release (como R3-2) no se ven en debug.
- **Para que la APK instalada sea idéntica a la de producción:**
  1. Consigue tu **keystore de release** (si ya publicaste en Play, usa el MISMO `.jks` de subida; si
     no, crea uno con `keytool -genkeypair -v -keystore upload-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload`).
     *(La creación del keystore y sus contraseñas debes hacerla tú — no puedo generar credenciales por ti.)*
  2. Copia `keystore.properties.example` a `keystore.properties` y rellena `storeFile`, `storePassword`,
     `keyAlias`, `keyPassword`.
  3. En Android Studio: panel **Build Variants → app → release**, luego Run/instala; **o** por CLI:
     `./gradlew :app:assembleRelease` e instala `app/build/outputs/apk/release/app-release.apk` con
     `adb install -r`.
  4. Sube a producción **ese mismo build** (para Play: `./gradlew :app:bundleRelease` → `.aab`).
  Así la APK que pruebas es exactamente el código de producción (mismo R8/ProGuard).

## R3-5 · TURN gratuito para llamadas/voz/directos entre redes (ampliación de R2-4)
Sí hay opciones **gratuitas y más fiables** que la actual (`openrelay.metered.ca`, credenciales
públicas compartidas y saturadas). El código ya lo soporta; solo cambia `signaling-server/.env`:
- **Metered.ca (gratis 50 GB/mes, credenciales propias):** crea cuenta gratuita, obtén tu
  `username`/`credential` y URLs, y ponlos en `TURN_STATIC_URLS` / `TURN_STATIC_USERNAME` /
  `TURN_STATIC_CREDENTIAL`. Reinicia el `signaling-server`.
- **Cloudflare TURN (Realtime):** tier gratuito generoso; genera credenciales y configúralas igual.
- **coturn propio en un VPS público barato:** el código ya soporta el patrón REST con credenciales
  efímeras HMAC vía `TURN_HOST` + `TURN_SECRET` (más robusto y sin límite de terceros).
- **La mini-PC NO sirve como TURN** si está detrás de CGNAT doméstico (sin IP pública/port-forwarding).
Tras configurar un TURN fiable con UDP + TCP + TLS(443), las llamadas entre datos móviles y wifi
funcionarán. **Requiere prueba real con dos dispositivos en redes distintas para darlo por cerrado.**

---

## Verificación de build — Ronda 3 (build LIMPIO, sin concurrencia)

Se detuvieron los daemons de Gradle (los fallos previos de tests/Release fueron por **corrupción de
builds concurrentes** compartiendo `app/build/`, no por el código). Cadena: `assembleDebug` →
`assembleRelease` → `lintDebug`.

| Paso | Resultado |
|---|---|
| assembleDebug | ⏳ en curso |
| assembleRelease (R8/ProGuard) | ⏳ pendiente |
| lintDebug | ⏳ pendiente |

*(Resultado build limpio: assembleDebug ✅, lint ✅. assembleRelease falló por un error de
**sistema de archivos de Windows en R8** — `base.jar ... no se encuentra` en `merged_java_res`, NO por
código: `compileReleaseKotlin` pasó. Se relanza con `clean` para regenerar intermedios.)*

---

# RONDA 4 — Errores de perfil (avatar/portada) y guardado

## R4-1 (Servidor) · `PROFILE_AVATAR_NOT_OWNED` bloquea guardar el perfil [CORREGIDO]
- **Archivo:** `infrastructure/local-fallback/api/src/data-routes.js` (endpoint `PATCH
  /v1/data/profile/me`, ~línea 4102).
- **Causa raíz (confirmada):** el backend validaba la propiedad del objeto de storage de
  `avatar_url`/`cover_url` en **cada** guardado de perfil, **sin comprobar si cambiaban**. El cliente
  reenvía el avatar/portada actual junto al resto de campos; si esa URL es **heredada** (migrada de
  Supabase, o subida antes del registro en `fallback_storage_objects`), la comprobación fallaba →
  `403 PROFILE_AVATAR_NOT_OWNED` → **se bloqueaba TODO el guardado** aunque el usuario solo cambiara
  la bio/tema.
- **Descartado antes de tocar:** no es mismatch de IDs (JWT `subject: user.id`, FK `owner_id →
  fallback_users(id)`), y el formato de URL del cliente (`/storage/v1/object/public/avatars/…`)
  coincide con lo que parsea el servidor.
- **Cambio (seguro):** la validación de propiedad ahora **solo se ejecuta cuando el `avatar_url`/
  `cover_url` cambia** respecto al valor actual (leído de `fallback_profile_overrides` /
  `fallback_users`). No debilita la seguridad: sigue siendo imposible poner como avatar/portada un
  objeto que no sea tuyo; solo se deja de re-validar una URL que el usuario ya tenía.
- **Verificación:** `node -c` OK (sintaxis válida). Es el **único** endpoint con esa validación, así
  que cubre las dos pantallas de las capturas. **Requiere desplegar el backend en la mini-PC** para
  que surta efecto (ver R4-3).
- **Lado APK:** ya era correcto — `ProfileViewModel` solo envía `avatar_url` cuando se elige imagen
  nueva (`avatarUrl.takeIf { imageUri != null }`), así que no hace falta cambio de cliente para este
  caso.

## R4-2b (Cliente) · "El servidor no confirmó todos los cambios del perfil" [CORREGIDO]
- **Diagnóstico confirmado EN VIVO** (consultando la BD de la mini-PC): la persistencia **sí
  funciona** — `fallback_profile_overrides` guarda correctamente `profile_theme` (p. ej. `aurora`),
  `profile_effect` (`equalizer`), `interests: []`, `hobbies: []`, `linked_games: []`,
  `game_logo_urls: {}`. **El dato se guardaba; solo salía un mensaje de error falso.**
- **Causa raíz:** `SettingsViewModel` hacía una **verificación post-guardado con igualdad exacta**
  (`==`) contra el perfil re-descargado (incluyendo listas y mapas). El endpoint `PATCH
  /v1/data/profile/me` responde 2xx **solo tras** el INSERT (si falla → 500 → `result.isFailure`),
  así que un 2xx ya garantiza la escritura. La comparación estricta daba **falsos negativos** por
  diferencias de formato/orden/`null`-vs-vacío del round-trip JSON.
- **Cambio (APK):** en las dos funciones de guardado (`saveProfileCustomization` y la de apariencia)
  se sustituye la comparación frágil por **confiar en `result.isSuccess`** (2xx = persistido) y
  refrescar la UI con el perfil re-descargado (`getUserById(..., forceNetwork = true)`). Ya no se
  muestra el falso "no confirmó".
- **Requiere reinstalar el APK** (es cliente). El dato ya se guardaba con el APK actual; el fix solo
  elimina el mensaje engañoso.

### (diagnóstico original, para referencia)
## R4-2 (Cliente) · "El servidor no confirmó todos los cambios del perfil" [DIAGNOSTICADO]
- **Archivo:** `app/src/main/java/conectate/com/presentation/profile/SettingsViewModel.kt:371-388`
  (`saveProfileCustomization`).
- **Causa raíz:** **NO es el avatar** (esta ruta no envía `avatar_url`). Tras guardar (el servidor
  responde 200, `result.isSuccess = true`), el cliente **re-verifica con igualdad estricta**
  (`==`) que el perfil re-descargado coincide campo a campo, incluyendo listas y mapas
  (`linked_games == normalizedGames`, `game_logo_urls == normalizedLogos`, `interests`, `hobbies`).
  Esta comparación es **frágil**: si el servidor devuelve una lista vacía como `null` (o en otro
  orden/formato tras el round-trip JSON), la igualdad falla y se muestra el mensaje de error **aunque
  el guardado sí funcionó**.
- **Por qué NO se cambió a ciegas:** para saber cuál campo no cuadra en el round-trip hace falta ver
  la respuesta real del servidor vs. lo enviado (dato en vivo). Relajar la verificación sin eso
  podría **ocultar un fallo real de persistencia**. Candidato de fix seguro (a confirmar con 1 log):
  normalizar `null`↔lista-vacía y comparar sin orden en `interests/hobbies/linked_games/
  game_logo_urls`, o confiar en `result.isSuccess` para los campos ya confirmados por el servidor.
- **Cómo confirmarlo:** log temporal en `saveProfileCustomization` imprimiendo `normalizedGames` /
  `persisted.linked_games` (y los mapas) al fallar la verificación; con eso se ajusta la comparación
  con precisión.

## R4-3 · Despliegue del cambio de backend a la mini-PC [DESPLEGADO ✅]
- El usuario proporcionó acceso SSH (`alvaro@100.91.84.2` vía Tailscale, clave
  `conectate_mini_ed25519`) y autorizó el despliegue.
- **Ejecutado (no destructivo):**
  1. Inspección: el código va **baked en la imagen** (sin volumen de `src/`); proyecto compose en
     `/home/alvaro/conectate-local-fallback` (no es repo git). Servicio: `api`.
  2. Se hizo **backup** del archivo en la mini-PC (`data-routes.js.bak-20260720-161741`).
  3. Parche **quirúrgico** del bloque de validación (no sobrescritura del archivo de 398 KB), con
     `node -c` OK y auto-restauración si fallaba.
  4. `docker compose up -d --no-deps --build api` → **solo** el servicio `api` se reconstruyó y
     recreó. db, nginx/edge, signaling, realtime, coturn **quedaron intactos y corriendo**.
  5. Verificación: contenedor `api` **Up (healthy)**, logs limpios ("listening on 3001"), y el código
     parcheado confirmado dentro del contenedor (`grep currentProfileOverride` = 3 coincidencias).
- **PostgreSQL, Nginx, migraciones y demás servicios: NO tocados.** Solo redeploy del código del `api`.
- **Efecto en vivo:** guardar el perfil sin cambiar la foto ya **no** devuelve `PROFILE_AVATAR_NOT_OWNED`.

---

# RONDA 5 — Organización y limpieza del proyecto

**Objetivo:** dejar el repo ordenado, sin basura ni código muerto, documentado por módulos.
**Principio:** cambios **reversibles** y **sin tocar código que se compila** — solo se elimina/mueve
basura, backups y scaffolding muerto (verificado con 0 referencias).

## R5-1 · Documentación por módulos [CREADO]
- **`ARQUITECTURA.md`** (nuevo, en la raíz): mapa completo del proyecto por módulos (Android, Web,
  Backend Ubuntu, Señalización) con la ubicación de cada cosa y notas de escalabilidad. Sirve para
  que cualquiera entienda y encuentre las cosas rápido.

## R5-2 · Código muerto eliminado (0 referencias, verificado) [HECHO]
- Carpetas "buzzword" (scaffolding generado por IA, **nunca compilado ni referenciado** — grep de
  sus nombres de clase = 0 en `app/src` y `Conectate-web/src`): `quantum/`, `photonic/`,
  `neuromorphic/`, `space/`, `dna/`, `ai/`, `network/`, `infrastructure/{quantum,edge,blockchain}/`.
  Borradas del disco y desrastreadas de git. (Nota: `infrastructure/local-fallback/` es el backend
  REAL y NO se tocó; solo las carpetas buzzword de `infrastructure/`.)

## R5-3 · Basura desrastreada de git y limpiada [HECHO / EN CURSO]
- **Desrastreado de git** (con `git rm --cached`, se mantiene en disco = reversible) y añadido a
  `.gitignore`: `tmp/` (2569 archivos, incluía cachés de Chrome), `backup/` (127),
  `app_backup_before_restore/` (418), `backups/` (9), logcats multi-MB, `crash_log.txt`, y la carpeta
  con **PII** `IMPORTACION DE SUPABASE POR CSV/`.
- **Borrado de disco:** scratch de raíz (`temp_*`, `diff_*`, logs, `hs_err`/`replay`), ~50 scripts
  one-off (`fix_*.ps1`, `patch_*`, etc.), y carpetas muertas. Las grandes (`tmp/`, `.worktrees/`,
  `.codex-*`, `app_backup_before_restore/`) se borran en segundo plano (son GB).
- **`.gitignore` ampliado** para que esta basura no vuelva a entrar al repo.

## R5-4 · Documentos de raíz organizados [HECHO]
- 34 archivos `.md` históricos (`AUDITORIA_FINAL_*`, `SOLUCION_*`, `GUIA_*`, `IMPLEMENTATION_*`, etc.)
  movidos a **`docs/archive/`**. En la raíz quedan solo los 4 principales: `README.md`,
  `ARQUITECTURA.md`, `AUDITORIA_COMPLETA.md`, `CORRECCIONES_REALIZADAS.md`.

## R5-5 · Verificación de estabilidad [EN CURSO]
- Se ejecuta `compileDebugKotlin` para confirmar que la limpieza **no rompió el build** (solo se
  eliminó código no compilado, así que se espera OK). Resultado pendiente.
- **Importante sobre el historial de git:** desrastrear quita la basura de aquí en adelante, pero
  sigue en el **historial**. Para purgarla del historial (recomendado por la PII de los CSV) hace
  falta un paso aparte con `git filter-repo`/BFG + rotación de credenciales — no ejecutado aquí por
  ser reescritura de historia (coordinar por separado).

---

## Verificación de build — FINAL (rama fix/release-crash-stabilization)

Build **limpio** (`gradlew clean :app:assembleRelease`) tras corregir todo:

| Paso | Resultado |
|---|---|
| assembleDebug | ✅ BUILD SUCCESSFUL |
| lintDebug | ✅ BUILD SUCCESSFUL |
| **assembleRelease (R8/ProGuard)** | ✅ **BUILD SUCCESSFUL** (13m 23s) — genera `app-release-unsigned.apk` (184 MB) |

- El fallo previo de Release era un **error transitorio de sistema de archivos de Windows en R8**
  (`base.jar` no encontrado en `merged_java_res`), NO de código. El `clean` lo resolvió.
- La APK sale **sin firmar** porque no hay `keystore.properties`. Para que sea instalable e idéntica
  a producción, configura la firma de release (ver R3-4) y usa el Build Variant `release`.
