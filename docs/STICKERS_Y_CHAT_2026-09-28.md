# Stickers y chat: implementación y verificación del 28/09/2026

Checkout: `D:\red social - live-gifts`. Rama: `feature/live-gifts`.

## Entrega

- Android **3.3.32-debug**, código **94**, `BUILD_COMMIT=8a9c92564daf`.
- APK: `build/deliverables/Novvin-3.3.32-debug-v94-8a9c925.apk`.
- SHA-256: `8D5B2CB079CBE52CF9749E956F9567A3DD71A977F45176AD3FEF6062028FD0C3`.
- Instalada mediante `adb install -r` en Moto G04 y emulador. La actualización conservó los datos y la sesión del teléfono.
- Commits: `708c41f` (servidor), `8775e68` (favoritos inmediatos e identidad), `8a9c925` (recientes, selector integrado y desplazamiento).

## Comportamiento implementado

| Requisito | Implementación |
| --- | --- |
| Recientes contiene únicamente favoritos usados | Intersección con favoritos y orden por frecuencia, con último uso para desempatar. Se persisten los contadores. Recibir un sticker o guardarlo no cuenta como enviarlo. Los elementos antiguos que no son favoritos quedan excluidos. |
| Quitar un favorito es inmediato | Publicación y persistencia local antes de descargar, comparar contenido o solicitar al servidor. El selector observa el cambio; desaparece de Favoritos y Recientes. |
| Añadir desde distintos chats no crea copias | URL normalizada, SHA-256 del archivo y huella visual para imágenes estáticas. Las animaciones diferentes no se fusionan por compartir una miniatura. Las decisiones de quitar conservan prioridad frente a una comparación que termina después. |
| Estado visible en la acción del chat | Añadir cambia a Eliminar de favoritos y el icono se rellena mientras la hoja sigue abierta. No se dibujan estrellas sobre las imágenes del selector. |
| Enviar sin cerrar el selector | Panel integrado debajo del compositor, intercambio con el teclado, botón para volver a escribir y Atrás para cerrar. Seleccionar y enviar conserva el panel. |
| Mensajes visibles encima del teclado | Anclaje compartido para chat directo, grupo y comunidad. Apertura normal al último mensaje; reajuste al cambiar el área disponible y al enviar. Se conserva la navegación explícita a un mensaje y la lectura manual de mensajes anteriores. |
| Favoritos del servidor coherentes | Añadir y quitar son idempotentes. Un sticker procedente de un mensaje puede guardarse en un paquete privado después de verificar acceso al archivo. El identificador interno incluye al propietario para evitar sobrescribir el paquete privado de otra cuenta. |

El estado visual cambia localmente; la sincronización y entrega remota dependen de la red. Estas pruebas no establecen una latencia de cero milisegundos.

## Pruebas ejecutadas

- Compilación del commit final: `:app:assembleDebug :app:testDebugUnitTest` con filtros de `StickerIdentityTest`, `StickerFormatSupportTest` y `ChatWorkspaceTest`: **26 pruebas, cero fallos y errores**. Log: `build/codex-3.3.32-final-build.log`.
- Servidor desplegado: suite API con **263 pruebas correctas, cero fallos y una omitida**. Seis pruebas de favoritos y doce de acceso a archivos también pasaron. Log: `build/stickers-api-suite.log`.
- `infrastructure/local-fallback/scripts/verify-sticker-favorites-e2e.js`: alta sintética, archivo propio, añadir dos veces, una sola fila favorita, quitar dos veces y nueva consulta sin favorito. Limpieza mediante eliminación de cuenta. Log: `build/stickers-server-e2e.log`.
- Android a servidor: cuatro stickers enviados desde el emulador a otra cuenta sintética, un texto enviado con teclado abierto, consulta del historial y consulta del favorito eliminado. Log: `build/stickers-android-server-final.log`. Las dos cuentas, conversación y archivos sintéticos se eliminaron al terminar.
- Comprobación de infraestructura: ocho contenedores `healthy`, incluido PostgreSQL, API, signaling y coturn. `/ready` público respondió HTTP 200. Esto confirma disponibilidad en esa comprobación; no certifica todos los servicios o condiciones de red futuros.
- El buffer de `AndroidRuntime:E` consultado tras el recorrido no mostró fallos en ninguno de los dos dispositivos.

## Revisión visual

Las capturas y jerarquías quedan en `build/`, fuera de Git, porque algunas contienen un chat personal. No se enviaron mensajes a personas reales durante esta revisión.

| Recorrido | Evidencia |
| --- | --- |
| Abrir un chat con 24 mensajes | `emulator-332-chat-latest.png`: mensaje 24 visible al abrir. `emulator-332-final-open.png`: último sticker visible después de reinstalar. |
| Sustituir teclado por selector | `emulator-332-final-panel.png`: altura estable, campo separado y último mensaje encima. |
| Dos envíos consecutivos en APK final | `emulator-332-final-stickers-sent.png`: dos envíos confirmados y selector abierto. |
| Quitar favorito usado | `emulator-332-final-recents-removed.png`: desaparece también de Recientes. |
| Enviar texto con teclado | `emulator-332-final-text-sent.png`: mensaje confirmado y visible encima del teclado. |
| Horizontal | `emulator-332-landscape-panel.png`: mensaje, compositor y selector separados; acciones accesibles. |
| Motorola: duplicado recibido por URLs distintas | Ambas hojas de acción reconocieron el mismo favorito. El selector mostró una sola copia; `motorola-332-final-panel.png`. |
| Motorola: eliminación y reapertura | `motorola-332-final-cat-removed.png` y `motorola-332-final-reopened.png`: quedan los dos favoritos originales y ningún sticker no guardado. Comparación de identificadores antes/después: dos y dos, sin diferencias. |

Se restauró la rotación automática original del Motorola. La revisión visual y los envíos descritos corresponden a chat directo. Grupos y comunidades usan los mismos componentes y compilaron; sus recorridos completos no se probaron visualmente en esta entrega.

## Continuidad de medios

Antes de estos cambios se comprobó recepción de imagen de LIVE del emulador al Motorola en LTE, ICE conectado mediante TURN, reacción visible en anfitrión/espectador y cierre del directo. Los commits `90c3594` y `cc79100` añaden contador de reacciones, audiencia observada y distribución de controles en horizontal.

Quedan pendientes la medición de latencia y fotogramas en cámaras físicas, audio acústico bidireccional con dos dispositivos, invitados de LIVE, llamadas entrantes con app en segundo plano y compartir cámara/pantalla en salas de voz. No se presenta la prueba de chat ni la disponibilidad del contenedor de signaling como validación de esos recorridos. La continuidad de requisitos anteriores se encuentra en `VERIFICACION_REQUISITOS_MOVIL_2026-09-28.md`.
