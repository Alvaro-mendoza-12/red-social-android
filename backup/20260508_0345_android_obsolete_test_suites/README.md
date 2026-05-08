# 2026-05-08 03:45 - Android obsolete test suites quarantine

## Motivo

Se movieron fuera de `app/src/test` varias suites antiguas que ya no representaban el APK actual:

- `RealTestingSuite.kt`
- `auth/twoFactor/TwoFactorAuthManagerTest.kt`
- `auth/twoFactor/TwoFactorAuthIntegrationTest.kt`

## Por qué se respaldaron

- dependían de clases eliminadas o reemplazadas (`AuthServiceFixed`, `MessageRepositoryRefactored`)
- asumían una implementación previa del módulo 2FA que ya no existe
- rompían la compilación de tests y falseaban la señal de calidad del proyecto

## Estado honesto

No se eliminaron: quedaron archivadas aquí para futura reescritura.  
La intención ahora es reconstruir pruebas reales y mantenibles alrededor de la implementación vigente.
