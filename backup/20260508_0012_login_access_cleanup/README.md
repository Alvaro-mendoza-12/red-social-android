# Backup 20260508_0012 Login Access Cleanup

Fecha: 2026-05-08 00:12 America/Bogota.

Motivo:
- Antes de simplificar el login web, el acceso mostraba una columna editorial completa que no era necesaria para usuarios finales.
- Tambien se oculto el mensaje tecnico crudo de Supabase y se reemplazo por textos humanos para acceso y conectividad.

Archivos respaldados:
- Conectate-web/src/pages/auth/LoginPage.tsx
- Conectate-web/src/core/utils.ts
- Conectate-web/src/index.css
- Conectate-web/README.md
- Conectate-web/WEB_ENTERPRISE_PARITY_2026.md
- docs/BACKUP_AND_ARCHIVE_STATUS_2026.md

Estado real al crear este backup:
- La web ya desplegaba correctamente en Vercel desde `main`.
- La pantalla de login seguia cargada visualmente y exponia internals de cuota/restriccion del backend cuando fallaba Supabase.
- Esta pasada la dejo enfocada solo en `Acceso`, con feedback mas limpio y apropiado para produccion.
