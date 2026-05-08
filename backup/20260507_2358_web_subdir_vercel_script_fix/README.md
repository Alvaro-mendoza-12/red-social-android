# Backup 20260507_2358 Web Subdir Vercel Script Fix

Fecha: 2026-05-07 23:58 America/Bogota.

Motivo:
- Vercel fallo en un deploy remoto real con `Missing script: vercel:install`.
- El proyecto estaba creado con `Root Directory = Conectate-web`, pero ese subproyecto no declaraba todavia `vercel:install` ni `vercel:build`.

Archivos respaldados:
- Conectate-web/package.json
- docs/WEB_VERCEL_DEPLOY_2026.md
- docs/BACKUP_AND_ARCHIVE_STATUS_2026.md

Estado real al crear este backup:
- `main` ya tenia endurecido el install limpio desde la raiz del repo.
- Seguía existiendo un hueco de compatibilidad cuando Vercel ejecutaba los comandos dentro de `Conectate-web`.
- Esta pasada cierra ese hueco para que la configuracion actual del proyecto en Vercel no requiera rearmar todo desde cero.
