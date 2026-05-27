# Sprint 1-5 Implementation Status

Fecha de cierre de esta pasada: 2026-05-07.

## Mensajes

- Chats 1:1, grupos, comunidades, reacciones, reply, forward, editar/eliminar, mensajes temporales/view-once, fijados, estados, mute/pin, busqueda en chat, multi-account, typing y presencia quedan integrados en Android.
- Archivar/desarchivar chats queda persistido como preferencia por usuario en Room y Supabase (`chat_participants.is_archived`) con filtro activo `Archivados` en la bandeja de mensajes.
- Favoritos de chat quedan persistidos como preferencia por usuario en Room y Supabase (`chat_participants.is_favorite`) con estrella visible, accion en menu contextual y filtro activo `Favoritos`.
- Reportar conversaciones 1:1 ya usa `MessageRepository.reportContent`/`MessageReportStore`; no queda funcion vacia en `ChatViewModel`.
- Llamadas 1:1 y salas persistentes usan WebRTC y servicios Android existentes para mantener sesiones activas.
- Registro de llamadas y navegacion a historial quedan conectados desde la navegacion principal.
- La entrada de llamadas ya no depende de un flujo "optimista": `IncomingCallActivity` pide permiso de microfono con `ActivityResultContracts`, no sigue adelante a ciegas si `acceptIncomingCall` falla y muestra feedback visible cuando aceptar/rechazar no se completa.
- Vista previa de enlaces ya se renderiza dentro de `ChatScreen` usando `LinkPreviewRepository` y `LinkPreviewCardCompact`.
- Notificaciones directas usan `MessageSessionStateStore` compartido para no duplicar alertas cuando el chat activo ya esta visible, y `MessagePolicyEngine` mantiene anti-spam/deduplicacion reciente en memoria.

## Videos

- El modulo mantiene feed vertical, subida/editor, limite de 30 segundos, deteccion de marcas de agua por ML Kit, duet/stitch, sonidos, filtros, hashtags, comentarios anidados, reportes, guardado/compartido/descarga y ranking/recomendacion por interacciones.
- Lives, chat en vivo y gifts quedan soportados por `LiveRepository`, pantallas de host/viewer, `GiftSelectionDialog`, `WalletRepository` y el RPC atomico `send_virtual_gift`.

## Social

- Feed social, publicaciones, momentos, retos, votaciones, podio/ranking, solicitudes de amistad, notificaciones agrupadas, comentarios anidados, shares y menciones estan cubiertos en las pantallas y repositorios existentes.
- Comentarios de posts soportan audio con `PostRepository.addAudioComment`, bucket `post-comments-media`, path por usuario y columnas `media_url`/`content_type` bajo SQL deployable.
- Stories highlights queda implementado de punta a punta con SQL (`moment_highlights`, `moment_highlight_items`), `MomentHighlightRepository`, `MomentHighlightViewModel`, guardado desde `MomentViewerScreen`, carrusel en `ProfileScreen` y pantalla `MomentHighlightsScreen`.

## Perfil Y Ajustes

- Perfil estilo Discord: banner, avatar con borde de estado, bio/frase, intereses, juegos con logos, musica favorita, libro, deporte, carrera y horario.
- Edicion completa: `EditProfileScreen`, `ProfileViewModel`, `UserRepository`, `User` y `UpdateProfileRequest` guardan y muestran todos los campos nuevos.
- Ajustes avanzados: cuenta, seguridad/2FA, privacidad, notificaciones, chats, almacenamiento/datos, apariencia, idioma, ayuda, acerca de la app, terminos y privacidad.
- Personalizar app ya persiste el layout y orden de la barra inferior en `AppSettingsPreferences`, y `HomeScreen` aplica ese orden al volver a Inicio.
- La apariencia ya no queda solo guardada en preferencias: desde la pasada `2026-05-07`, `MainActivity` e `IncomingCallActivity` aplican realmente `fontOption` y `textScale` en `RedSocialTheme`, y `ConectateDesignSystem` deja de ser un stub vacio para exponer tokens reales de apariencia/motion.
- El ajuste `Reducir movimiento` ya tiene un primer uso real: la transicion entre tabs de `MessagesScreen` se desactiva cuando el usuario lo habilita. Aun falta extender ese criterio al resto de pantallas animadas del APK.
- El login y el cambio rapido de cuenta ahora tratan de forma consistente las restricciones del proyecto en Supabase: el mensaje no se borra si el backend sigue restringido, el acceso rapido se pausa en Login/Ajustes en vez de disparar reintentos inutiles y el acceso por QR tambien queda bloqueado mientras el proyecto siga en ese estado externo.

## Integracion Final

- La navegacion principal conecta llamadas, lives, historial, ajustes avanzados y rutas de perfil/social/video/mensajes.
- Monetizacion base: wallets, gifts, planes premium, ads, programa de creadores, payout accounts y creator earnings estan en el contrato SQL y en la ruta activa Android.
- Wallet/gifts ya no usan saldo local simulado: el saldo se lee con `wallet_get_or_create`, los regalos activos se cargan desde `virtual_gifts` y el envio se ejecuta con `send_virtual_gift`.
- Premium/ads ya no quedan solo como contrato SQL: `MonetizationRepository`, `MonetizationCenterViewModel` y `MonetizationCenterScreen` cargan planes activos, suscripcion actual, balance de wallet, campanas activas y registran impresiones en `ad_impressions`.
- Programa de creadores queda visible en Ajustes con `CreatorProgramRepository`, `CreatorProgramViewModel` y `CreatorProgramScreen` para solicitud, metodo de pago y resumen de ganancias.
- Notificaciones de mensajes ya no contienen `TODO()` ejecutable: `MessageNotificationService` usa `MessageNotificationFactory`, respeta mutes y valida `POST_NOTIFICATIONS` antes de publicar.
- `CallRepository` y `WebRTCManager` ya no esconden varios fallos best-effort del flujo de llamadas: quedan logueados los errores no fatales de historial, respuestas de cancel/end/decline, teardown de socket/WebRTC, actualizacion/parada del foreground service y espera de niveles de audio remoto.
- Release Android queda ajustado para R8 con `org.gradle.jvmargs=-Xmx4096m`, evitando la caida del daemon por heap insuficiente durante `minifyReleaseWithR8`.
- SQL listo para deploy: `sql/migrations/supabase_sprint_1_to_5_final_contract_2026.sql`, `sql/migrations/add_audio_to_post_comments.sql`, `sql/migrations/20260506_wallet_gifts_rpc_security_hardening.sql`, `sql/migrations/20260506_monetization_premium_ads_security_hardening.sql`, `sql/migrations/20260506_chat_archive_preferences_2026.sql` y `sql/migrations/20260506_chat_favorites_preferences_2026.sql`, registrados en `sql/release-manifest.txt`.
- Release Android genera APKs por ABI para evitar un universal inflado por WebRTC/ML Kit nativo y mantener cada artefacto dentro del budget.
- Estado honesto de cierre: el APK compila y las mejoras de esta pasada son reales, pero todavia no puede declararse "cerrado al 100% para produccion" mientras sigan pendientes smoke tests en dispositivo real, reduccion de warnings Kotlin heredados y una pasada final de endurecimiento runtime modulo por modulo.
- Estado honesto actualizado: ya hubo smoke real en dispositivo Android y sirvio para descubrir un bloqueo externo de Supabase por cuota de almacenamiento. Mientras ese backend siga restringido y no se complete una nueva pasada runtime post-fix con `adb` operativo, no es serio marcar el APK como cierre total de produccion.

## Verificacion Recomendada

1. Ejecutar `.\gradlew.bat compileDebugKotlin --no-daemon --console=plain`.
2. Ejecutar `.\gradlew.bat testDebugUnitTest --no-daemon --console=plain`.
3. Ejecutar `.\gradlew.bat assembleRelease --no-daemon --console=plain`.
4. Ejecutar la auditoria SQL contra `sql/release-manifest.txt`.
5. Ejecutar `python tools/performance_budget_check.py --apk app/build/outputs/apk/release --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json`.

## Verificacion Ejecutada En Esta Pasada

- `.\gradlew.bat compileDebugKotlin --no-daemon --console=plain`: PASS.
- `.\gradlew.bat compileDebugKotlin --no-daemon --console=plain` despues de archive/nav/report/chat preferences: PASS.
- `.\gradlew.bat compileDebugKotlin --no-daemon --console=plain` despues de favoritos persistentes de chat y Room v40: PASS.
- `.\gradlew.bat testDebugUnitTest --no-daemon --console=plain`: PASS, 66 tests, 0 failures, 0 errors, 0 skipped.
- `.\gradlew.bat assembleRelease --no-daemon --console=plain`: PASS.
- `.\gradlew.bat assembleRelease --no-daemon --console=plain --max-workers=1` despues de favoritos persistentes de chat: PASS.
- `.\gradlew.bat :app:qualityGateDebug :app:assembleDebug :app:assembleRelease --no-daemon --console=plain --max-workers=1`: PASS.
- `python tools/security_sql_audit.py --root . --manifest sql/release-manifest.txt --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH`: PASS.
- `python tools/performance_budget_check.py --apk app/build/outputs/apk/release --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json`: PASS, 4 APKs dentro de budget.
- APKs generados en `app/build/outputs/apk/release/`: `app-arm64-v8a-release-unsigned.apk`, `app-armeabi-v7a-release-unsigned.apk`, `app-x86-release-unsigned.apk`, `app-x86_64-release-unsigned.apk`.
- APKs debug firmadas generadas en `app/build/outputs/apk/debug/`: `app-arm64-v8a-debug.apk`, `app-armeabi-v7a-debug.apk`, `app-x86-debug.apk`, `app-x86_64-debug.apk`.
- Validacion en dispositivo/emulador: `adb` existe en `C:\Users\gamer\AppData\Local\Android\Sdk\platform-tools\adb.exe`, pero no habia ningun dispositivo/emulador conectado; queda pendiente instalar/abrir la APK y revisar logcat en hardware real.
- `.\gradlew.bat compileDebugKotlin --no-daemon --console=plain` el `2026-05-07` despues de sincronizar tipografia/escala runtime y activar el design system real: PASS.
- `.\gradlew.bat app:testDebugUnitTest --tests "conectate.com.presentation.theme.AppTypographyRuntimeMappingTest" --no-daemon --console=plain` el `2026-05-07`: PASS.
- `.\gradlew.bat compileDebugKotlin --no-daemon --console=plain` el `2026-05-07` despues del hardening de llamadas entrantes y cleanup de WebRTC/call teardown: PASS.
- `.\gradlew.bat app:testDebugUnitTest --tests "conectate.com.presentation.calls.IncomingCallIntentParserTest" --no-daemon --console=plain` el `2026-05-07`: PASS.
- `.\gradlew.bat compileDebugKotlin --no-daemon --console=plain` el `2026-05-07` despues del hardening de restricciones Supabase en login/cambio rapido: PASS.
- `.\gradlew.bat app:testDebugUnitTest --no-daemon --console=plain` el `2026-05-07`: PASS, 77 tests, 0 failures, 0 errors, 0 skipped.
- `.\gradlew.bat assembleDebug --no-daemon --console=plain` el `2026-05-07`: PASS. APK debug `arm64-v8a` regenerada en `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
- `.\gradlew.bat :app:qualityGateDebug --no-daemon --console=plain` el `2026-05-07`: PASS. Lint report debug actualizado en `app/build/reports/lint-results-debug.html`.
- Smoke real previo del `2026-05-07` en `moto_g04` (`ZY22JSH8CF`): Login abre, QR de Conectate Web abre y rechaza QR ajeno, cambio rapido con cuenta guardada llega al backend y revela restriccion real `exceed_storage_size_quota` en Supabase.
- Revalidacion runtime posterior al hardening intentada el `2026-05-07`: BLOCKED. Windows sigue mostrando interfaz ADB/USB del telefono, pero `adb devices -l` ya no enumera un dispositivo utilizable, asi que no hubo instalacion/captura nueva de esta pasada.
