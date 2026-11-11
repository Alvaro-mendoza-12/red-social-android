# Web: legibilidad de marcas de tiempo y texto secundario

Segunda corrección acotada de accesibilidad en `correcciones-dot`. Continúa la
revisión de [contornos y foco](accesibilidad-controles-web-2026-11-11.md), sin
modificar lógica, tamaños, distribución ni preferencias guardadas.

## Evidencia

El texto pequeño usa el umbral de 4,5:1, no el de 3:1 de bordes e iconos.
`--muted` alimenta las marcas de tiempo y `--ink-500`; `--secondary-text` se usa
en las vistas previas de conversaciones, ayudas de ajustes y formularios.

Cinco regresiones nuevas reprodujeron contraste insuficiente en estos tokens:

| Texto / fondo | Antes | Después |
| --- | ---: | ---: |
| Tenue oscuro / contenedor `#343439` | 4,19:1 | 6,05:1 |
| Tenue claro / contenedor `#e3e3e8` | 4,39:1 | 5,18:1 |
| Ayuda de acceso / panel `#eef0fb` | 4,35:1 | 5,30:1 |
| Tenue de acceso / contenedor `#e9e6ff` | 3,46:1 | 4,58:1 |

La paleta compartida conserva la jerarquía entre texto principal, secundario y
tenue. Los tokens claros también se actualizan para el tema sistema. Las pruebas
comprueban las superficies originales, los acentos del catálogo y extremos
blanco/negro del acento personalizado; los dos tokens llegan a 4,5:1 incluso en
los contenedores tintados calculados al 15 %.

## Validación y límites

Las cinco regresiones se observaron fallando antes de cambiar CSS y pasan
tras la corrección. Las siete regresiones de contornos siguen pasando.
Suite completa Jest: 185 suites y 1.090 pruebas aprobadas. Suite Node: 118
pruebas aprobadas. Lint: cero errores y siete advertencias previas de efectos
React. Compilación TypeScript/Vite, prerender y control SEO aprobados con el
mismo origen HTTPS de prueba de la tanda anterior; no valida el backend activo.
Vite conserva el aviso de chunk grande. Estas pruebas usan los valores de fuente y mezclas sRGB; no sustituyen
cascada calculada, capturas ni pruebas con lectores de pantalla.

No se vuelve a certificar el resto de textos, colores fijos o estilos que
sobrescriben estos tokens. Continúa pendiente la validación visual de navegador
documentada en la revisión anterior; no se ha publicado ni desplegado la web.

## Reconstrucción tras reinicio del entorno

Se reconstruyó esta tanda sobre `853836e7`, conservando los mismos cambios CSS y
las cinco regresiones del commit local perdido `d2637281`. Se repitieron los
cinco fallos antes de corregir, las 1.090 pruebas Jest, 118 Node, lint y build/SEO,
con los mismos resultados indicados arriba. Un nuevo intento de Chromium en el
entorno restaurado volvió a fallar con `socket() failed: Operation not permitted`;
no produjo capturas ni validación visual.
