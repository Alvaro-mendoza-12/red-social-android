# Backup 20260507_2358 Web Subdir Vercel Config Hardening

Fecha: 2026-05-07 23:58 America/Bogota.

Motivo:
- Antes del redeploy final en Vercel se detecto un hueco de configuracion: el proyecto web se importara con `Root Directory = Conectate-web`, pero el fallback SPA y los headers solo estaban definidos en `vercel.json` del repo raiz.
- La documentacion oficial de Vercel para Vite SPA pide `vercel.json` en la raiz del proyecto desplegado.
- Tambien se fijo el runtime esperado en `Conectate-web/package.json` para no depender del Node por defecto de la plataforma.

Archivos respaldados:
- Conectate-web/vercel.json
- Conectate-web/package.json
- docs/WEB_VERCEL_DEPLOY_2026.md
- docs/BACKUP_AND_ARCHIVE_STATUS_2026.md

Estado real al crear este backup:
- El build ya pasaba y los scripts `vercel:install` / `vercel:build` ya existian dentro de `Conectate-web`.
- Sin este ajuste, un deploy nuevo podia compilar pero aun quedar expuesto a 404 al refrescar rutas internas o a perder headers definidos solo en la raiz del monorepo.
- Fijar `engines.node = 22.x` deja alineado el deploy con el runtime ya validado localmente durante esta pasada.
