# Backup 20260507_2333 Main Deploy Install Fix

Fecha: 2026-05-07 23:33 America/Bogota.

Motivo:
- Antes de pasar la web verificada a `main`, se detecto que un checkout limpio fallaba en `npm ci` por conflicto de peer dependencies de testing.
- Se endurecio la instalacion para deploy real en Vercel y para validacion limpia de `main`.

Archivos respaldados:
- package.json
- Conectate-web/.npmrc
- docs/BACKUP_AND_ARCHIVE_STATUS_2026.md
- docs/WEB_VERCEL_DEPLOY_2026.md

Estado real al crear este backup:
- El build web ya pasaba en entornos con `node_modules` existentes.
- Un entorno limpio todavia podia romperse al instalar dependencias antes del build.
- El objetivo de esta pasada fue cerrar ese hueco antes de promover la web a `main`.
