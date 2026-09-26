# 04 — Accesibilidad (cobertura inicial)

Fecha: 2026-09-26. No se ha ejecutado un barrido sistemático con TalkBack ni un cálculo completo de contrastes; por eso no se declara conformidad WCAG/MASVS.

La pantalla de acceso fue comprobada visualmente a 360 dp con la barra de estado despejada y desplazamiento del registro. Ese resultado no cubre fuente 200 %, idioma largo, teclado externo, lector de pantalla ni 240 dp. Los iconos de regalo, menú, regreso, ver contraseña, cerrar LIVE, reaccionar y navegación inferior necesitan comprobación de nombre accesible y objetivo táctil de al menos 48 dp, conforme a [Android Developers](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views). Para web: foco visible, orden de teclado, formularios, anuncios de error y contraste según [WCAG 2.2](https://www.w3.org/TR/wcag/).

Android Lint detectó inicialmente 2 errores `NewApi` y 220 advertencias. Tras la corrección, el informe tiene **0 errores y 219 advertencias**; la advertencia `ObsoleteSdkInt` desapareció. Las restantes eran mayormente `UseKtx` (estilo/API) y recursos; **no sustituyen** pruebas de accesibilidad. AUD-010 documenta el defecto y su validación.

Matriz pendiente por pantalla: entrada, registro, recuperación, bandeja/chat, video, publicación, LIVE creador/espectador/regalos, social, juegos, perfil y configuración. Registrar captura, nodo semántico, tamaño efectivo, contraste y resultado de TalkBack/teclado para estado normal/error. Corregir primero acciones irreversibles o de dinero inaccesibles.
