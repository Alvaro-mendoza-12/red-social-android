# Backup And Archive Status 2026

Fecha de revision: 2026-05-06.

## Estado activo

La fuente activa de producto es el arbol principal `app/`, `sql/release-manifest.txt`, `signaling-server/`, `tools/` y la documentacion viva bajo `docs/`.

Los snapshots y cuarentenas locales no forman parte del build, del deploy SQL ni del release Android. Se conservan solo para recuperacion manual o comparacion historica.

## Backups detectados

- `.backup/groups_communities_20260212`: snapshot historico de grupos/comunidades.
- `app_backup_before_restore`: copia previa a restauracion del app Android, con codigo viejo que no debe importarse sin comparar.
- `tmp/app_java_untracked_quarantine_20260429_120405`: cuarentena de Java/Kotlin no trackeado.
- `tmp/app_recovery_snapshot_20260429_120341`: snapshot de recuperacion.
- `tmp/app_tests_untracked_quarantine_20260429_124501`: cuarentena de tests.
- `tmp/current_working_app_backup_20260429_132711`: copia local de trabajo.
- `tmp/recovery_quarantine_20260429_*`: cuarentenas de recuperacion adicionales.

## Regla operativa

- No se deben actualizar masivamente estos backups para "ponerlos al dia"; eso destruye su valor como snapshot.
- Si una pieza de un backup se recupera, debe copiarse al arbol activo con revision manual y luego verificarse con build/tests.
- Si un backup queda obsoleto y ya no se necesita, debe eliminarse en una tarea explicita aprobada por el owner del repo.
- La documentacion y los manifiestos activos deben apuntar al codigo vigente, no a snapshots.

## Validacion relacionada

La pasada del `2026-05-06` actualizo la documentacion activa, wallet/gifts, premium/ads, comentarios con audio y el hardening SQL del manifest. No se modificaron contenidos internos de snapshots para evitar mezclar historial con codigo vigente.

El cierre local del `2026-05-06` confirmo:

- `compileDebugKotlin` sin errores.
- `testDebugUnitTest` con 66 tests, 0 fallos, 0 errores y 0 omitidos.
- `assembleRelease --no-daemon --console=plain --max-workers=1` exitoso con cuatro APKs regenerados.
- Auditoria SQL del manifest sin violaciones nuevas.
- Presupuesto de performance/tamano de APK en verde para los cuatro artefactos.
- La siguiente pasada compilo `compileDebugKotlin`, `testDebugUnitTest` y `assembleRelease --max-workers=1` despues de agregar favoritos persistentes de chat en Room v40 y SQL `20260506_chat_favorites_preferences_2026.sql`.
- La verificacion previa a Android Studio del `2026-05-07` ejecuto `:app:qualityGateDebug`, `:app:assembleDebug`, `:app:assembleRelease`, auditoria SQL y performance budgets en verde.
- No se ejecuto smoke en dispositivo/emulador local porque `adb` existe, pero no habia dispositivo/emulador conectado; los backups no se tocaron y la validacion runtime queda como siguiente comprobacion con Android Studio/ADB.

Los backups historicos siguen congelados a proposito. La referencia vigente queda en `README.md`, `docs/`, `sql/release-manifest.txt` y los reportes generados bajo `build/reports/`.
