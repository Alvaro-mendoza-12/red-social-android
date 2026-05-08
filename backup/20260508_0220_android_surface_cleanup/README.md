# 2026-05-08 02:20 - Android surface cleanup

## Motivo

Se sacaron del build varias superficies administrativas y de "producción enterprise" que no estaban cerradas funcionalmente y complicaban la estabilidad del APK.

## Contenido principal movido aquí

- `app/src/main/java/conectate/com/presentation/admin/**`
- `app/src/main/java/conectate/com/core/production/LowEndDeviceOptimizer.kt`
- `app/src/main/java/conectate/com/core/production/ProductionBuildOptimizer.kt`
- `app/src/main/java/conectate/com/core/production/ProductionFinalChecklist.kt`
- `app/src/main/java/conectate/com/core/production/ProductionValidationReport.kt`
- `app/src/main/java/conectate/com/monitoring/enterprise/ProductionMonitoringSystem.kt`
- `app/src/main/java/conectate/com/notifications/RobustNotificationSystem.kt`
- `app/src/main/java/conectate/com/performance/profiling/PerformanceProfilingSystem.kt`

## Resultado

La navegación y el build quedaron enfocados en la superficie real del producto en vez de pantallas/admin tooling incompleto.
