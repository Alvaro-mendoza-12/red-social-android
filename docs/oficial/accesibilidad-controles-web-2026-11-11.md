# Web: contornos interactivos y foco de acceso

Corrección acotada para `correcciones-dot`, sin despliegue ni cambios funcionales.

## Defectos reproducidos

Los campos compartidos y botones secundarios usaban el mismo contorno tenue que
los separadores decorativos. El acceso siempre tiene fondo claro, pero su foco de
teclado heredaba el cian del tema oscuro. Las razones calculadas con luminancia
sRGB de los tokens reales eran:

| Control / fondo | Antes | Después |
| --- | ---: | ---: |
| Contorno oscuro / `#151519` | 1,80:1 | 6,21:1 |
| Contorno claro / `#f3f5f9` | 1,89:1 | 4,06:1 |
| Campo de acceso / `#fafbff` | 1,31:1 | 3,92:1 |
| Foco de acceso / `#fafbff` | 1,71:1 | 6,62:1 |

Se separa `--control-outline` de `--outline`: campos, selectores, áreas de texto y
botones secundarios ganan bordes reconocibles sin endurecer todas las tarjetas.
`--focus-ring` conserva los acentos existentes y permite que el acceso use su
violeta oscuro independientemente del tema guardado. No cambian tamaños, orden
de tabulación, etiquetas, navegación, formularios ni contratos de red.

## Verificación

- Siete regresiones nuevas, observadas en rojo antes de aplicar la corrección y
  verdes después. Comprueban 3:1 para contornos en superficies predeterminadas,
  tonos de acento del catálogo y extremos blanco/negro del acento personalizado.
- Jest completo: 185 suites, 1.090 pruebas aprobadas.
- Suite Node completa: 113 pruebas aprobadas después de generar `dist`.
- Lint: cero errores, siete advertencias previas de dependencias de efectos React.
- Compilación TypeScript/Vite, prerender y control SEO aprobados con un origen
  HTTPS de prueba. Ese resultado no verifica disponibilidad del servidor ni es
  una compilación destinada a despliegue. Vite mantiene el aviso de chunk grande.
- El primer intento de Node anterior a la compilación falló en pruebas que
  requieren `dist`; se repitió después del build. No es un fallo de contraste.

## Límites

Las comprobaciones de CSS y luminancia no son un motor de cascada ni certifican
WCAG para todas las pantallas. Chromium local no pudo arrancar: `socket() failed:
Operation not permitted`, incluso con la ejecución elevada permitida. No hubo
capturas ni comprobación visual de teclado, zoom o reflujo en navegador.

Quedan por validar visualmente acceso/recuperación, campos y botones secundarios
con tema claro, oscuro y sistema; foco con Tab; ancho 390 px y escritorio; y los
estilos específicos que sobrescriben bordes compartidos. No se declara cerrada
la auditoría web ni móvil.
