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

---

# QA visual — Novvin LIVE, Videos y Social Descubrir

- Fecha: 2026-08-11
- Rama: `feature/live-gifts`
- Dispositivo de prueba: Pixel 6a virtual, 1080 × 2400
- Referencias LIVE: capturas aportadas por el propietario y el documento `docs/oficial/10-live-experience-reference.md`
- Referencia Social: capturas vigentes de la ficha oficial de Facebook para Android en Google Play

## Estados comparados

| Superficie | Fuente | Implementación comprobada | Resultado |
|---|---|---|---|
| Preparación LIVE | Cámara a pantalla completa, acciones compactas, título y CTA inferior | Novvin LIVE conserva la misma jerarquía, respeta insets y adapta el bloque inferior | PASS |
| Mejorar y efectos | Paneles superpuestos con categorías y presets | Sliders modifican estado real; categorías filtran presets; el panel no cubre la navegación | PASS |
| Centro, recompensas, ajustes y Servicio+ | Hojas inferiores oscuras, contenido desplazable y CTA | Apertura, cierre, scroll y marca Novvin comprobados | PASS |
| Modo práctica | Cámara privada, cabecera mínima, mensajes y herramientas inferiores | Entrada y herramientas comprobadas; sin controles inertes detectados | PASS |
| Videos horizontal/pequeño | Medio centrado dentro del lienzo vertical | Video centrado con letterbox negro; no queda pegado arriba | PASS |
| Social Descubrir | Cabecera, acceso de publicación, historias y feed de Facebook móvil | Misma estructura funcional con identidad Novvin y componentes nativos de la app | PASS |
| Social Siguiendo | Experiencia existente | Conservada sin rediseño estructural | PASS |

## Carga y estados vacíos

- Videos muestra carga transitoria y conserva el contenido anterior durante la navegación; no presenta “sé el primero” mientras todavía consulta el feed.
- Social Descubrir presenta inmediatamente el compositor y las historias, y reserva el estado vacío para una respuesta final real.
- Capturas a 150 ms, 800 ms y 3 s confirmaron que los mensajes vacíos falsos ya no parpadean.

## Interacción comprobada

- Mejorar: cambio visible de brillo mediante slider.
- Efectos: cambio de categoría y filtrado de presets.
- Centro LIVE, recompensas, ajustes, Servicio+ y práctica: navegación y cierre correctos.
- Social: compositor real y pestaña Siguiendo navegables.
- No había una emisión pública activa durante la prueba, por lo que la pantalla del espectador requiere la validación física de dos dispositivos descrita en la auditoría de producción.

Resultado de esta iteración visual: **PASS**.

---

# QA visual — Momentos, personalización, PlayMatch, Ajustes y Perfil

- Fecha: 2026-08-12
- Rama: `feature/live-gifts`
- Dispositivo: Pixel 6a virtual, 1080 × 2400
- Capturas de referencia: imágenes aportadas por el propietario para perfil tipo Instagram, ajustes agrupados, personalización de mensajes y PlayMatch.

## Comparación visual y funcional

| Superficie | Referencia esperada | APK verificada | Resultado |
|---|---|---|---|
| Perfil | Contenido sin aglomeración y destacados visibles | Selector `Inicio / Presentación`; Inicio agrupa bienvenida, métricas y Momentos; Presentación agrupa descripción, ubicación, música e intereses | PASS |
| Momentos destacados | Carrusel circular tipo destacados de perfil | `Nuevo` crea Momento y los destacados abren el visor enlazado | PASS |
| Ajustes | Tarjetas agrupadas, texto separado de bordes | Tarjetas con 14 dp horizontales y 12 dp verticales; botones conservan objetivos táctiles y ya no duplican margen | PASS |
| Personalizar mensajes | Chips compactos para color y fondo | Rojo, Blanco, Azul y Grafito; Negro, Textura, Noche y Limpio; selección persistente y restablecimiento real | PASS |
| Conversación | La selección debe modificar la conversación | Validado en APK: Azul cambia las burbujas enviadas y Noche aplica el fondo azul oscuro; Blanco mantiene texto oscuro legible | PASS |
| PlayMatch buscando | Texto contrastado; cancelar y volver responden | Paleta propia de PlayMatch; cancelar detiene polling, actualiza estado, solicita cancelación al servidor y vuelve inmediatamente | PASS |
| Galería gamer | Foto tocable y ampliable | Carrusel abre el visor nativo a pantalla completa con zoom, paneo y navegación | PASS |
| Temas sólidos | El tema elegido reemplaza el negro | El fondo adopta el color seleccionado; negro queda reservado al tema oscuro predeterminado | PASS |

## Pruebas y evidencia

- `:app:compileDebugKotlin`: PASS.
- `:app:testDebugUnitTest`: 328 pruebas, 0 fallos, 0 errores, 0 omitidas.
- `:app:assembleDebug`: PASS; APK instalada mediante `adb install -r`.
- `:app:lintDebug`: PASS; 0 errores, 42 advertencias no bloqueantes.
- Pruebas Node: moderación 2/2, PlayMatch rutas PASS, catálogo PlayMatch PASS.
- `node --check`: servidor y rutas PlayMatch PASS.
- `git diff --check`: PASS; solo avisos informativos LF/CRLF.
- Arranque e interacción real: sin `FATAL EXCEPTION` de `conectate.com` en el registro inspeccionado.
- Capturas reales: `.codex-tmp/profile-redesign.png`, `.codex-tmp/profile-presentation.png`, `.codex-tmp/settings-padding.png`, `.codex-tmp/chat-settings-bottom.png`, `.codex-tmp/chat-blue-night.png`.
- Vista compacta de 360 dp: `.codex-tmp/profile-small-360dp.png`; sin solapamientos ni texto recortado en la nueva cabecera y selector de secciones.

## Servidor y escala

- PlayMatch usa índices para catálogo, perfiles descubribles, juegos, conexiones, sesiones y matches; matchmaking mantiene bloqueo transaccional por usuario.
- API conserva timeouts de consulta, bloqueo y transacción, rate limits en entradas sensibles y pool PostgreSQL parametrizable con límites seguros.
- La capacidad máxima no puede certificarse con pruebas locales funcionales: requiere carga distribuida contra la topología de producción, métricas de PostgreSQL, TURN/LiveKit y dos o más dispositivos reales.

Resultado de esta iteración visual y funcional: **PASS**.

---

# QA visual — Estadísticas del creador

- Fecha: 2026-08-12
- Rama: `feature/live-gifts`
- Fuente visual: `C:/Users/gamer/AppData/Local/Temp/codex-clipboard-ea32c842-92e3-4349-9b43-0ec990a5da7a.png`
- Implementación capturada: `.codex-tmp/creator-analytics-fixed.png`
- Comparación normalizada lado a lado: `.codex-tmp/creator-analytics-comparison.png`.
- Viewport: Pixel 6a virtual, 1080 × 2400 px, densidad Android 420 dpi.
- Estado comparado: entrada desde Ajustes a Estadísticas del creador, con sesión autenticada.

## Comparación e iteraciones

| Iteración | Hallazgo | Corrección | Evidencia posterior |
|---|---|---|---|
| 1 | P0: el servidor desplegado devolvía `CREATOR_ANALYTICS_DISABLED`, por lo que la pantalla nunca podía mostrar métricas. | Se comprobó la migración `008`, se desplegó la ruta vigente y se reconstruyó únicamente la API; `/ready` y el contenedor quedaron saludables. | La pantalla carga dos directos y su informe real. |
| 1 | P1: mensaje de error suelto, alineado a la izquierda y con demasiado vacío vertical. | Estado de error convertido en tarjeta centrada, con icono, jerarquía, texto centrado y CTA compacto de 44 dp. | La variante queda preparada para fallos de red futuros sin dejar un CTA aislado. |
| 1 | P2: mensajes técnicos y ambiguos. | Copy breve, accionable y diferenciado para servidor, sesión, propiedad y conectividad. | Suite `CreatorAnalyticsPresentationTest` actualizada y aprobada. |

## Superficies de fidelidad

- Tipografía: jerarquía clara entre encabezado, privacidad, filtro, contenido y métricas; sin truncamiento en el estado cargado.
- Espaciado: márgenes de 16 dp, tarjetas separadas y cuadrícula de dos columnas; no queda el gran vacío de la referencia fallida.
- Color: usa los tokens activos del tema y mantiene contraste en cabecera, selección, tarjetas y barra inferior.
- Imágenes/recursos: la pantalla no requiere imágenes externas; utiliza iconos Material nativos existentes.
- Contenido: se muestran los directos reales del propietario y métricas protegidas cuando no alcanzan el umbral de privacidad.

## Interacción y técnica

- Navegación Ajustes → Estadísticas: PASS.
- Carga autenticada desde la API pública: PASS.
- `:app:compileDebugKotlin`: PASS.
- `:app:testDebugUnitTest`: PASS.
- `:app:assembleDebug`: PASS.
- `npm run test:creator-analytics`: PASS.
- API desplegada: contenedor saludable; configuración en ejecución `enabled=true`.
- Registro Android inspeccionado: sin `FATAL EXCEPTION` ni ANR de `conectate.com`.

No fue necesario un recorte focal adicional: en 1080 × 2400 se leen claramente encabezado, texto de privacidad, selector, tarjetas de contenido y primeras seis métricas.

final result: passed

---

# QA visual — Momentos, audiencias, reacciones, temas y widgets

- Fecha: 2026-08-12
- Rama: `feature/live-gifts`; `main` no fue modificada.
- Dispositivo principal: Pixel 6a virtual, 1080 × 2400 px.

## Hallazgos y correcciones

| Prioridad | Hallazgo reproducido | Corrección y comprobación | Resultado |
|---|---|---|---|
| P0 | Abrir/publicar un Momento podía cerrar la aplicación con `VerifyError` en `MomentViewerScreenKt`. | El visor monolítico fue dividido en composables verificables; publicación protegida contra doble toque y excepciones; APK instalada y visor abierto sin `FATAL EXCEPTION`. | PASS |
| P0 | El creador no podía controlar con precisión quién veía un Momento. | Audiencias reales: todos, seguidores, seguidos, chats, seleccionados, todos excepto seleccionados y solo yo; backend, modelo, UI y SQL sincronizados. | PASS |
| P1 | Momentos no exponía reacción/respuesta completa y el resumen propio podía decir siempre “Visible para todos”. | Reacción persistente, contador, respuesta condicionada por permisos y etiqueta de audiencia derivada del modelo. | PASS |
| P1 | El tema oscuro heredaba rojo/crema y los temas personalizados se mezclaban con negro. | `Sin tema` es neutro y oscuro por defecto; cada tema personalizado usa un lienzo opaco propio. El color del chat queda limitado a la conversación. | PASS |
| P2 | Crear Momento se veía ovalado y desproporcionado. | Tarjeta vertical compacta, radio de 10 dp, borde y recorte de imagen estables. | PASS |
| P2 | Solo había un widget genérico. | Receivers instalables para Mensajes, Videos, Momentos y Accesos rápidos, todos con deep link propio. | PASS |

## Evidencia

- Privacidad: `.codex-tmp/audit-20260812-final/20-moment-privacy-dialog.png`.
- Visor estable con respuesta y reacción: `.codex-tmp/audit-20260812-final/19-moment-viewer-fixed.png`.
- Tema Jade opaco, sin negro/rojo mezclado: `.codex-tmp/audit-20260812-final/25-theme-jade-opaque.png`.
- Tema `Sin tema` oscuro y neutro en APK final: `.codex-tmp/audit-20260812-final/27-final-apk-neutral.png`.
- Instalación limpia muestra fondo negro/neutro antes de cualquier personalización: `.codex-tmp/audit-20260812-final/28-fresh-install-neutral-dark.png`.
- Estadísticas enriquecidas: `.codex-tmp/audit-20260812-final/15-creator-analytics-fixed.png`.

## Verificación final

- Android: 84 suites, 332 pruebas, 0 fallos, 0 errores y 0 omitidas.
- Lint: 0 errores; 44 advertencias no bloqueantes.
- APK debug: ensamblada e instalada; arranque frío y visor de Momentos sin excepción fatal.
- Backend: `npm run check`, Momentos, analítica, PlayMatch, Social, colaboración LIVE y moderación LIVE: PASS.
- Publicación privada real: el servidor almacenó `only_me/private`; el Momento temporal de QA fue eliminado al terminar.
- Widgets detectados por Package Manager: 4/4.

Resultado de esta iteración: **PASS**, con las pruebas físicas multidispositivo enumeradas en la auditoría de producción todavía pendientes.

---

# QA visual — Contraste oscuro, navegación y destacados persistentes

- Fecha: 2026-08-12
- Rama: `feature/live-gifts`; `main` no fue modificada.
- Viewport: Android virtual 1080 × 2400 px.
- Evidencia: `.codex-tmp/audit-20260812-dark/`.

## Recorrido comprobado

| Pantalla/flujo | Evidencia | Resultado |
|---|---|---|
| Bandeja de entrada | `01-launch.png` y `12-reinstall.png` | Pestañas de texto compactas, contador visible y contraste oscuro correcto. |
| Social Descubrir | `08-social-final.png` | Sin falso estado vacío; Momentos más compactos y perfil `Novvin` actualizado en lugar de `Conectate`. |
| Perfil propio y ajeno | `03-profile.png` y `16-profile-timeout.png` | Cabecera, flecha, título e iconos visibles; perfil ajeno carga con datos actuales. |
| Editor de Momento | `05-create-editor.png` y `06-text-dialog.png` | Audiencia y publicar visibles; zoom, texto movible, tipografía, color, tamaño y música disponibles. |
| Destacado existente | `18-highlight-fixed.png` | Abre el contenido usando la portada aunque el Momento original ya haya expirado; no muestra el estado vacío incorrecto. |
| PlayMatch | `11-games.png` | Logo Novvin aplicado y etiqueta `GRATIS` retirada. |

## Validación técnica final

- 332 pruebas unitarias, 0 fallos, 0 errores y 0 omitidas.
- APK ensamblada e instalada correctamente; marca de tiempo `2026-08-12 20:21:21`.
- API local-fallback reconstruida y saludable; actualización de perfil en el carrusel comprobada en el cliente.
- Lint de la iteración: 0 errores y 44 advertencias no bloqueantes.
- El perfil ya no puede quedar cargando indefinidamente: timeout de 15 s, mensaje recuperable y CTA `Reintentar`.

Resultado: **PASS para QA visual y prueba cerrada**, manteniendo las puertas físicas multidispositivo de LIVE, llamadas, retiros y distribución descritas en la auditoría de producción.

---

# QA visual — Release 3.3.0 (63), música compartida, LIVE y perfil

- Fecha: 2026-08-13.
- Rama: `feature/live-gifts`; `main` no fue modificada.
- APK auditada: release minificada, alineada y firmada para QA.
- Dispositivo: `Novvin_QA_2`, Android virtual 1080 × 2400 px.
- Evidencia: `.codex-tmp/audit-20260813-release/`.

## Recorrido actual

1. **Arranque release y Mensajes — saludable.** La app instalada informa `3.3.0 (63)`, conserva sesión y datos, muestra pestañas compactas y avatares sin aro decorativo por defecto. Evidencia: `01-release-launch.png`.
2. **Social Descubrir — saludable.** El compositor y Momentos aparecen inmediatamente; no parpadea un estado vacío falso. El texto largo queda limitado a tres líneas con `Ver más`. Evidencia: `02-social.png`.
3. **Perfil propio — saludable.** Se muestran insignias, `@usuario`, Compartir y Mi QR; el avatar no hereda un borde genérico. El historial de visitas queda oculto hasta que el usuario lo habilita. Evidencia: `03-profile.png`.
4. **Entrada de creación — saludable.** El diálogo de publicación conserva contraste oscuro y controles de contenido sensible. Evidencia: `04-create-entry.png`.
5. **Preparación LIVE — saludable con límite de emulador.** Controles, título, CTA, Centro LIVE y Recompensas respetan insets; la cámara virtual produce el patrón de prueba del emulador. Evidencia: `05-live-setup.png`.
6. **Modo práctica y cámara apagada — saludable.** `Apagar` detiene la pista, cambia a `Activar` y muestra el avatar con `CÁMARA DESACTIVADA`, sin cierre ni pantalla fantasma. Evidencia: `06-live-practice-camera-off.png`.

## Fortalezas observadas

- Contraste oscuro coherente en navegación, texto, contadores y acciones.
- Controles táctiles visibles sin solaparse con barras del sistema.
- La identidad pública evita exponer el correo; compartir y QR están disponibles en el perfil propio.
- Momentos usa aro únicamente para contenido no visto; los estados funcionales explícitos conservan sus bordes.
- El selector musical común de Mensajes/Momentos/LIVE usa Media3, búsqueda, catálogo, archivo local, preescucha y recorte antes de confirmar.

## Riesgos y accesibilidad

- La preferencia de historial de visitas es opt-in y requiere consentimiento mutuo; al desactivarla se purgan visitas entrantes y salientes.
- Los iconos relevantes conservan descripciones de accesibilidad (`Apagar`, `Activar`, `Mi QR`, `Visitas al perfil`).
- La lista de visitantes no se puede validar con una sola sesión: necesita dos cuentas con la preferencia activada y la migración aplicada en producción.
- El color de la cámara en la captura es el patrón del emulador, no un defecto de composición.

## Validación técnica

- `:app:compileDebugKotlin`: PASS.
- `:app:testDebugUnitTest`: PASS.
- `npm run check`: PASS.
- `npm run test:security`: PASS.
- `:app:assembleRelease :app:bundleRelease`: PASS.
- APK v2/v3: firma QA verificada; SHA-256 `077D201B8AF4EFB1A44A43E04899E90E84D18A51024A4C0CF4FF0EBAC843CE47`.
- AAB SHA-256 `0678F43262376F30AC0C6C67425A65F036AA98872A237B194444B97CB68B953F`; requiere la firma privada de distribución en Android Studio/Play App Signing.
- Registro inspeccionado durante el recorrido: sin `FATAL EXCEPTION` ni ANR de `conectate.com`.

## Límites de esta pasada

- El Motorola no estaba visible por ADB; la instalación física solicitada no pudo completarse.
- Falta aplicar `sql/migrations/20260813_profile_view_history.sql` al backend desplegado antes de probar visitantes reales.
- Música audible, LIVE con dos participantes, llamadas en segundo plano y widgets dinámicos requieren pruebas físicas multidispositivo.

final result: blocked
