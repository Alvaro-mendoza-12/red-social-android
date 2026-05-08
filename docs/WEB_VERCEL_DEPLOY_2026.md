# Web Vercel Deploy 2026

Fecha: 2026-05-07.

## Objetivo

Hacer que este repositorio raiz pueda desplegar `Conectate-web` en Vercel sin obligar a mover carpetas ni a convertir el repo Android completo en otro proyecto.

## Cambios aplicados

- `package.json` raiz:
  - agrega scripts puente hacia `Conectate-web`
  - `npm run vercel:install`
  - `npm run vercel:build`
- `vercel.json` raiz:
  - usa `Conectate-web/dist` como salida
  - ejecuta install/build dentro de `Conectate-web`
  - agrega fallback SPA a `index.html`
  - agrega headers de seguridad y cache
- `Conectate-web/scripts/ensure-web-build-env.mjs`:
  - valida `VITE_SUPABASE_URL`
  - valida `VITE_SUPABASE_ANON_KEY`
  - corta el build si solo existen placeholders o si faltan por completo
- `Conectate-web/.npmrc`:
  - fija `legacy-peer-deps=true` para evitar que un install limpio se caiga por dependencias de testing incompatibles con React 19
- `Conectate-web/package.json`:
  - ahora tambien expone `npm run vercel:install` y `npm run vercel:build`
  - esto cubre el caso real en Vercel cuando el proyecto se crea con `Root Directory = Conectate-web`
- `Conectate-web/vercel.json`:
  - deja fijados `framework`, `installCommand`, `buildCommand` y `outputDirectory` para el proyecto importado desde el subdirectorio
  - replica el fallback SPA y los headers de seguridad/cache en la raiz real del proyecto que vera Vercel
- `Conectate-web/package.json`:
  - fija `engines.node = 22.x` para que el deploy no dependa del default cambiante de Node en Vercel
- `package.json` raiz:
  - `vercel:install` ahora usa `npm --prefix Conectate-web ci --legacy-peer-deps`

## Comandos de verificacion local

```bash
npm --prefix Conectate-web ci --legacy-peer-deps
npm --prefix Conectate-web run build
npm --prefix Conectate-web run vercel:install
npm --prefix Conectate-web run vercel:build
npm run vercel:build
```

## Notas honestas

- En este entorno no estaba instalado el CLI global `vercel`, asi que la verificacion fuerte se hizo ejecutando el mismo build command que ahora consume Vercel desde la raiz del repo.
- El CLI efimero `npx vercel` si llego a ejecutarse y confirmo un bloqueo extra fuera del codigo: el token local de Vercel es invalido, por eso `vercel build --yes` no pudo completar el flujo de proyecto enlazado.
- Si el deploy remoto sigue fallando despues de esto, lo mas probable ya no sera la deteccion del proyecto web, sino variables de entorno faltantes en Vercel:
  - `VITE_SUPABASE_URL`
  - `VITE_SUPABASE_ANON_KEY`
- Si Vercel usa `Root Directory = Conectate-web`, ya no deberia romperse con `Missing script: vercel:install` porque ese subproyecto ahora declara esos scripts localmente.
- La guia oficial de Vite en Vercel indica que las SPA necesitan un `vercel.json` en la raiz del proyecto desplegado para habilitar deep linking. Por eso esta pasada agrega tambien `Conectate-web/vercel.json`; si el proyecto se importa apuntando a ese subdirectorio, las rutas como `/app/messages` o `/login?mode=qr` seguiran resolviendo al `index.html`.
- El fallback SPA ya evita el error tipico de 404 al refrescar rutas como `/app/messages`, `/app/profile` o `/login?mode=qr`.
