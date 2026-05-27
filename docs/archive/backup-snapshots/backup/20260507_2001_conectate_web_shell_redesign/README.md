# Backup 20260507_2001 Conectate Web Shell Redesign

Fecha: 2026-05-07 20:01 America/Bogota.

Motivo:
- Iniciar una pasada fuerte de integracion visual y estructural en `Conectate-web`.
- Corregir el desorden del shell web, mejorar responsive del login y conectar mejor modulos ya existentes como llamadas.

Archivos respaldados:
- Conectate-web/src/App.tsx
- Conectate-web/src/layout/AppLayout.tsx
- Conectate-web/src/pages/auth/LoginPage.tsx
- Conectate-web/src/features/calls/CallsPage.tsx
- Conectate-web/src/index.css
- Conectate-web/README.md
- Conectate-web/WEB_ENTERPRISE_PARITY_2026.md

Estado real al crear este backup:
- `Conectate-web` compila, pero la UX visual esta mezclada entre varios estilos.
- Verificacion local en navegador headless del login confirmo desborde/corte en mobile.
- `agent-browser` no esta disponible como comando en este entorno; la inspeccion visual se hace con capturas headless del navegador instalado.
