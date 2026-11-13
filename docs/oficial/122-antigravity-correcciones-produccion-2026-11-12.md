# Correcciones integrales y cierre del dominio oficial en rama antigravity — 12 de noviembre de 2026

## Alcance y gobierno de ramas

Esta entrega consolida en la rama aislada `antigravity` (`D:\red social - antigravity`, creada desde `19735c17` más la configuración CORP en Vercel) las correcciones de los hallazgos `F-01` a `F-10` reportados en la auditoría independiente, el endurecimiento integral del dominio `novvinapp.com` y la limpieza documental de la rama.

Las ramas `main` (`D:\red social - copia`), `feature/live-gifts` (`D:\red social - live-gifts`), `correciones`, `correcciones-dot` y `codex/experiencias-con-proposito` permanecen intactas.

## Correcciones implementadas

### Android (Kotlin / Compose / Billing / Deep Links)
- **Aislamiento de sesión y cola Play Billing (`F-01`, `F-02`)**: Se incorporó `PlayBillingVerificationQueue.kt`, verificación de sesión activa con `SessionWriteGuard` y limpieza al cambiar o cerrar sesión (`clearData()`) en `WalletRepository`, `WalletViewModel`, `AlgorithmRepository`, `LinkPreviewRepository` y `AuthRepository` (incluyendo su prueba de arranque `AuthRepositoryStartupAuditTest.kt`).
- **Configuración integral del dominio `novvinapp.com` (`F-04`)**:
  - `AndroidManifest.xml` aísla `https://novvinapp.com` en su propio `<intent-filter android:autoVerify="true">` (evitando fallos de App Links por redirecciones 308 de `www.novvinapp.com` y `red-social-android.vercel.app`) y cubre `/profile/`, `/video/`, `/survey/` y `/web-login`.
  - `IntentRouter.kt` (`resolveHttpsAppRoute`), `VideoClipPolicy.kt`, `SharedContentLinks.kt`, `ProfileScreen.kt`, `LiveViewerScreen.kt` y `PlayMatchScreens.kt` unifican la lectura y generación de enlaces oficiales en `https://novvinapp.com` (rechazando puertos explícitos y credenciales en URL; manteniendo `RuntimeBackendEndpoint` en `/p/:identifier?preview=4` para metadatos Open Graph del SSR Ubuntu).
- **Retiro de código muerto (`F-09`)**: Se eliminó `directPasswordReset` sin consumidor activo en `LocalFallbackApiClient.kt`, `AuthRepository.kt`, `AuthService.kt` y `AuthViewModel.kt`.
- **Compilación, pruebas JVM y verificación de release**:
  - En `app/build.gradle.kts`, se configuró `testOptions.unitTests.all { maxHeapSize = "1536m"; forkEvery = 80 }` para resolver la caída del trabajador Gradle (`Process 'Gradle Test Executor 1' finished with non-zero exit value 1`) al ejecutar las 523 suites JVM.
  - En `tools/verify_android_release.py`, se añadió `encoding="utf-8", errors="replace"` en `run_command()` para evitar `UnicodeDecodeError` en Windows al leer salidas de `keytool` en español.

### Web (React / TypeScript / Vite / Vercel)
- **Monetización Ubuntu y LIVE con retiros deshabilitados (`F-03`)**: Se añadió `NoWing-web/src/core/ubuntuMonetization.ts` y se conectaron `MonetizationCenterPage.tsx` (billetera, historial de regalos, catálogo, referencia de cobro y estado explícito `Retiro pendiente de habilitación` por decisión del propietario) y `LiveSessionPage.tsx` (panel y envío de regalos en directo).
- **Seguridad de cuenta y rutas faltantes (`F-06`, `F-08`)**: Se implementaron endpoints y controles UI en `fallbackAuth.ts` y `AccountSettingsPage.tsx` para estado/configuración/activación/desactivación 2FA TOTP, listado y revocación de sesiones web y eliminación de cuenta propia; se corrigieron enlaces rotos y se registraron `/funciones`, `/ayuda/stickers-animados` y `/ayuda/privacidad-y-seguridad` en `App.tsx`.
- **Transporte en tiempo real (`F-07`)**: `LiveSessionPage.tsx` y `WebCallManager.ts` utilizan `voiceTransportOptions` (`['polling', 'websocket']`).
- **Registro con verificación de edad y antispam (`F-09`)**: `RegisterPage.tsx` exige `birthDate` (>= 13 años), campo trampa (`website`) oculto fuera de pantalla y soporte opcional para Cloudflare Turnstile (`VITE_TURNSTILE_SITE_KEY`). Se eliminó la vista obsoleta `PasswordResetPage.tsx`.
- **Coherencia de iconos, accesibilidad táctil y dominio (`F-04`, `F-05`, `F-10`)**:
  - `MessagesPage.tsx` reemplaza la brújula errónea (`Explorar Novvin`) por `MoreVertical` (`Más funciones de Novvin`).
  - Se elevaron a mínimo `44px` de alto táctil en móvil `.icon-btn`, `.icon-btn-sm`, `.chat-composer`, `.reels-toolbar-actions` y `.message-reaction-bar` a través de `foundation.css`, `shell.css`, `screens.css`, `webParity.css`, `searchPage.css`, `voice.css`, `live.css` y `media-viewer.css`.
  - Se sincronizaron `vercel.json` raíz y `NoWing-web/vercel.json` (reescritura `/p/:path*` al API Ubuntu para vista previa SSR sin indexación `X-Robots-Tag: noindex` y cabecera `Cross-Origin-Resource-Policy: cross-origin` en `/novvin-logo.png` y `/novvin-share.png`) y se añadió `novvinapp.com` / `www.novvinapp.com` a la lista permitida de cookies en `novvin-media-worker.js` y `sharedVideoReference.ts`.

### Backend (Ubuntu local-fallback API)
- **Registro y dominio por defecto (`F-04`, `F-09`)**: Se incorporó `infrastructure/local-fallback/api/src/registration-security.js` (`MINIMUM_REGISTRATION_AGE = 13`, campo trampa `website`/`company` y validación opcional con `TURNSTILE_SECRET_KEY`), integrado en `server.js` (`/v1/auth/register`) y cubierto por `tests/registration-security.test.js` (registrado también en `package.json`). Se actualizaron las URL públicas por defecto de `red-social-android.vercel.app` a `https://novvinapp.com` en `public-profile-share.js`, `social-promotions.js` y `config.example`.

### Limpieza documental en rama `antigravity`
- Se retiraron mediante `git rm` únicamente en `antigravity` los borradores históricos sueltos de `docs/` raíz y el archivo vacío residual `.FullName`, conservando intacta la documentación canónica en `docs/oficial/`.

## Verificación posterior a los últimos cambios

- **Android (`tools\quality_gate.ps1` + comprobaciones de artefactos)**:
  - Pruebas unitarias JVM (`:app:testDebugUnitTest`): **523 suites**, **2.769 pruebas**, **2.769 aprobadas**, **0 fallos**, **0 errores**, **0 omitidas** (`100 %` tras ajustar `maxHeapSize = "1536m"` y `forkEvery = 80`).
  - Lint debug (`:app:lintDebug` -> `lint-results-debug.xml`): **0 errores/fatales**, **358 avisos** preexistentes.
  - Compilación de tests instrumentados (`:app:compileDebugAndroidTestKotlin`): **BUILD SUCCESSFUL** (exit `0`, sin ejecución en dispositivo físico).
  - Tooling Python (`tools/tests/test_*.py`): **72 pruebas OK** (`26.646s`).
  - Auditorías estáticas: `Runtime security audit passed` (`Findings=0`, `New violations=0`) y `SQL security audit passed` (`Findings=0`, `New violations=0`).
  - Compilación release (`:app:assembleRelease :app:bundleRelease`): **BUILD SUCCESSFUL** (`versionName='9.1.41'`, `versionCode='181'`, `minSdkVersion='24'`, `targetSdkVersion='36'`).
  - R8 (`tools/verify_r8_release.py`): **PASS** (`45.560` clases en mapping, `17` componentes de manifiesto comprobados, `0` faltantes/renombrados).
  - Firmas (`tools/verify_android_release.py` con `--expected-cert-sha256` del almacén local `CN=Alvaro`):
    - `app\build\outputs\apk\release\app-release.apk` (`221.521.957` bytes) — SHA-256 `B40D0D944B16F279A92DA784F0AA4934CA3E54BE700CD1E4AA4997E6703D411A`, certificado `623CDB12BAD08FCCA8A4A2F62EB6B5C91583D2CD14CED4AE63864066E036D84D`.
    - `app\build\outputs\bundle\release\app-release.aab` (`132.679.069` bytes) — SHA-256 `6E4FA650FC08A6DFC1FC44AC2E397922A313EB00AF9FA9BAD08B55157E41EA99`, certificado `623CDB12BAD08FCCA8A4A2F62EB6B5C91583D2CD14CED4AE63864066E036D84D`.
  - Alineación 16 KiB (`verify_android_page_size.py`): **PASS** en AAB (`PAGE_ALIGNMENT_16K`, `14` librerías nativas de 64 bits verificadas).
  - Presupuestos de tamaño (`performance_budget_check.py`): **PASS**.
- **Web (`NoWing-web`)**:
  - `npm run lint`: **0 errores**, **7 avisos** preexistentes (`react-hooks/exhaustive-deps`).
  - `npm run build`: **PASS** (`{"passed":true,"publicPages":21,"publicJavaScriptBytes":0}`).
  - `npm run test:node`: **122 aprobadas**, **0 fallos**, **0 omitidas**.
  - `npx tsc -b --noEmit`: exit `0`.
  - `npm test -- --watchAll=false`: **191 suites aprobadas de 191**, **1.193 pruebas aprobadas de 1.193** (conserva avisos preexistentes de `React act(...)` en consola de `ChatDetailPage.tsx`).
  - Playwright Chromium móvil sin cabeza: `pageErrors: []`, `overflow: 0`, `smallIconControls: []`, secciones 2FA/sesiones/eliminación presentes, `Explorar Novvin` sustituido por `Más funciones de Novvin`.
- **Backend (`infrastructure/local-fallback/*` y `signaling-server`)**:
  - `api` (`npm run check && npm test`): **1.103 pruebas**, **1.082 aprobadas**, **0 fallos**, **21 omitidas** (`18` pruebas condicionales dependientes de PostgreSQL vivo/entorno Linux más `1` de ffmpeg, `1` fixture bash Linux y `1` monitor validado en producción Linux).
  - `realtime-gateway` (`npm run check && npm test`): **PASS**.
  - `ai-chatbot` (`npm run check && npm test`): **PASS** (`6` suites).
  - `signaling-server` (`npm run check && npm test`): **60 pruebas**, **59 aprobadas**, **0 fallos**, **1 omitida** (contención atómica Redis Live).

## Pendientes operativos y alcance no cubierto localmente

1. **Despliegue de la web en Vercel**: Se ejecuta en producción (`https://novvinapp.com`) inmediatamente después de confirmar el commit y push de `antigravity`, seguido de `check:production-seo` y comprobaciones HTTP en vivo.
2. **Despliegue de contenedores en servidor Ubuntu**: Los cambios del directorio `infrastructure/local-fallback/api` (`server.js`, `registration-security.js`, `public-profile-share.js`, `social-promotions.js`, `package.json`) requieren reconstrucción y despliegue limpio en el servidor Ubuntu (retirando el montaje manual heredado de la nota `116`), junto con configuración de `TURNSTILE_SECRET_KEY`/`VITE_TURNSTILE_SITE_KEY` si se habilita CAPTCHA y verificación TLS/CA del SMTP corporativo.
3. **Play App Signing vs `assetlinks.json`**: La firma local (`62:3C:DB:...`) coincide con `NoWing-web/public/.well-known/assetlinks.json`; si la distribución final pasa por Google Play App Signing, el propietario debe contrastar e incluir la huella de distribución de Play Console.
4. **Ensayo físico en dispositivo y recorridos en vivo**: Quedan fuera de entorno local la instalación de la nueva APK `9.1.41` (`181`) en teléfono Android real, la validación de Play Integrity / facturación real, directo multiusuario LIVE / llamada WebRTC real, los datos societarios finales de Aviso Legal y el seguimiento de mejoras menores (`IMP-01` a `IMP-03`).
