# Backup 20260508_0055 Cross Platform Connectivity Hardening

Fecha: 2026-05-08 00:55 America/Bogota.

Motivo:
- Antes de esta pasada, APK y web ya compartían backend, QR y recuperación, pero todavía podían colarse mensajes demasiado técnicos en algunos errores del puente entre ambos.
- Esta pasada endurece el mapeo de errores compartidos, documenta la conectividad real y agrega pruebas unitarias en Android.

Archivos respaldados:
- Conectate-web/src/core/utils.ts
- Conectate-web/src/core/auth/webLoginPairing.ts
- Conectate-web/src/components/QrCodeTile.tsx
- Conectate-web/src/components/AppErrorBoundary.tsx
- Conectate-web/src/components/ErrorBoundary.tsx
- Conectate-web/src/components/ErrorBoundarySimple.tsx
- app/src/main/java/conectate/com/presentation/common/UserFacingErrorMessage.kt
- app/src/test/java/conectate/com/presentation/common/UserFacingErrorMessageTest.kt
- docs/CROSS_PLATFORM_CONNECTIVITY_AUDIT_2026.md
- docs/BACKUP_AND_ARCHIVE_STATUS_2026.md
- Conectate-web/README.md
- Conectate-web/WEB_ENTERPRISE_PARITY_2026.md

Estado real al crear este backup:
- El bloqueo principal seguía siendo la restricción de Supabase por cuota, no una rotura del contrato de integración APK↔web.
- La prioridad de esta pasada fue dejar el proyecto más profesional de cara a producción futura: menos mensajes crudos, mejor documentación del puente y pruebas del mapper de errores.
- Durante la verificación en worktree limpio también apareció una deuda más profunda del APK: el proyecto Android actual no compila en `main` por errores heredados en varios módulos Compose/analytics, aun con credenciales locales restauradas.
