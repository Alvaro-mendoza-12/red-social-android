# Limpieza local recomendada (artefactos generados)

Este repositorio genera artefactos pesados que **no son código fuente activo** (builds, caches, dist, node_modules, capturas, etc.).  
Si quieres reducir peso y dejar el repo más “limpio”, puedes borrar estas carpetas localmente (se regeneran al compilar).

## Carpetas seguras para borrar

### Android / Gradle
- `.gradle/`
- `app/build/`
- `build/`

### Web
- `Conectate-web/node_modules/`
- `Conectate-web/dist/`

### Temporales / Capturas / Staging
- `tmp/`
- `.openclaw_staging_*/`
- `.worktrees/` *(duplicados de trabajo)*

## Recomendación
Si tienes dudas, **no borres** `backup/` (contiene snapshots de código + README).  
Lo demás es material generado/temporal y ya está cubierto por `.gitignore`.

