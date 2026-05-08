# Backup 20260507_2257 Web Vercel Settings Hardening

Fecha: 2026-05-07 22:57 America/Bogota.

Motivo:
- Preparar este repo raiz para despliegue web en Vercel sin depender de que el usuario configure manualmente un subdirectorio correcto.
- Endurecer headers y fallback SPA del frontend web.
- Reordenar `AccountSettingsPage` para que la pantalla de ajustes no se vea como un formulario largo sin jerarquia.

Archivos respaldados:
- Conectate-web/src/features/profile/AccountSettingsPage.tsx
- Conectate-web/src/index.css
- Conectate-web/README.md
- Conectate-web/WEB_ENTERPRISE_PARITY_2026.md

Estado real al crear este backup:
- `Conectate-web` compilaba y el login QR ya estaba conectado a la APK.
- El repo raiz todavia no tenia `package.json` ni `vercel.json`, asi que un deploy desde la raiz seguia siendo propenso a fallar o a requerir configuracion manual en Vercel.
- `Ajustes` aun se sentia correcto funcionalmente, pero demasiado denso visualmente para una app social moderna.
