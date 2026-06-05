# Backup 2026-05-07 17:50

## Motivo

Respaldo previo a una pasada de cierre del APK enfocada en sincronizar la apariencia real de runtime con las preferencias guardadas del usuario y en reemplazar el stub del design system por una base usable.

## Archivos incluidos

- `app/src/main/java/conectate/com/MainActivity.kt`
- `app/src/main/java/conectate/com/presentation/calls/IncomingCallActivity.kt`
- `app/src/main/java/conectate/com/presentation/theme/Theme.kt`
- `app/src/main/java/conectate/com/design/system/ConectateDesignSystem.kt`
- `docs/BACKUP_AND_ARCHIVE_STATUS_2026.md`
- `docs/SPRINT_1_TO_5_IMPLEMENTATION_STATUS_2026.md`

## Estado capturado

- Antes de esta pasada, la app guardaba `fontOption` y `textScale` en preferencias, pero `RedSocialTheme` no los aplicaba al runtime principal.
- `IncomingCallActivity` leía `fontOption` y `textScale`, pero no los usaba.
- `ConectateDesignSystem.kt` seguía siendo un stub vacío.

## Nota operativa

Este backup es un snapshot puntual de trabajo y no reemplaza los backups históricos congelados ni los manifiestos de release.
