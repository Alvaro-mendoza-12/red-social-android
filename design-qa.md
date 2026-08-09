# QA visual — PlayMatch y Social

- Fecha: 2026-08-09
- Rama: `feature/live-gifts`
- Dispositivo: emulador Pixel 6a Android

## Comparación

Se compararon en paralelo las capturas aprobadas por el usuario con capturas reales de la APK de este worktree.

| Flujo | 360×640 | 393×873 | 412×915 | Resultado |
|---|---:|---:|---:|---|
| Home PlayMatch | PASS | PASS | PASS | Sin recortes; insets y barra inferior respetados |
| Encabezado PlayMatch | PASS | PASS | PASS | Ya no queda debajo de la barra de estado |
| Acceso tester | PASS | PASS | PASS | Abre Home, sin pantalla de prueba cerrada |
| Visor Social | — | PASS | — | Pantalla completa e información inferior visible |
| Cierre por gesto | — | PASS | — | Deslizar hacia abajo vuelve al feed |

## Pruebas

- `:app:assembleDebug`: PASS.
- `FullScreenMediaViewerTest`: 11 pruebas, 0 fallos, 0 errores.
- `npm run test:playmatch`: rutas y catálogo PASS.
- Instalación y navegación real: PASS.

Las portadas dependen del catálogo dinámico. Si el proveedor no entrega una, se usa el fallback de Novvin; no se inventan imágenes ni se reduce el catálogo.

Resultado de esta corrección: **PASS**.
