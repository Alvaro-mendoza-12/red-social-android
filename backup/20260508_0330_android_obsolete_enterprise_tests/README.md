# 2026-05-08 03:30 - Android obsolete enterprise tests quarantine

## Motivo

Se movió `EnterpriseTestSuite.kt` fuera de `app/src/test` porque no era una suite ejecutable real:

- mezclaba APIs de `androidTest` y Compose UI dentro de `src/test`
- dependía de módulos ya retirados del árbol principal
- rompía `testDebugUnitTest` con referencias inexistentes

## Impacto

- la compuerta de calidad vuelve a medir las pruebas unitarias reales del APK actual
- no se eliminó el archivo: quedó respaldado aquí para futura reescritura si se decide reconstruir una suite de integración seria
