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

## Comandos de verificacion local

```bash
npm run vercel:build
```

## Notas honestas

- En este entorno no estaba instalado el CLI global `vercel`, asi que la verificacion fuerte se hizo ejecutando el mismo build command que ahora consume Vercel desde la raiz del repo.
- El CLI efimero `npx vercel` si llego a ejecutarse y confirmo un bloqueo extra fuera del codigo: el token local de Vercel es invalido, por eso `vercel build --yes` no pudo completar el flujo de proyecto enlazado.
- Si el deploy remoto sigue fallando despues de esto, lo mas probable ya no sera la deteccion del proyecto web, sino variables de entorno faltantes en Vercel:
  - `VITE_SUPABASE_URL`
  - `VITE_SUPABASE_ANON_KEY`
- El fallback SPA ya evita el error tipico de 404 al refrescar rutas como `/app/messages`, `/app/profile` o `/login?mode=qr`.
