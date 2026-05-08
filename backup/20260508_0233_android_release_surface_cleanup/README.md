# 2026-05-08 02:33 - Android release surface cleanup

## Motivo

Se aislaron piezas de diseño/ML/performance que todavía no tenían una integración robusta y metían ruido dentro de la ruta release.

## Contenido principal movido aquí

- `app/src/main/java/conectate/com/design/system/ConectateDesignSystem.kt`
- `app/src/main/java/conectate/com/ml/enterprise/RealMachineLearningSystem.kt`
- `app/src/main/java/conectate/com/performance/MemoryPerformanceOptimizer.kt`
- `app/src/main/java/conectate/com/performance/enterprise/MemoryAndCpuOptimizer.kt`

## Resultado

La ruta release quedó más predecible y sin módulos "enterprise" que no estaban validados en un flujo de producción real.
