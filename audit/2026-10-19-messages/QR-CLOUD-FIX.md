# Corrección posterior a la prueba física — 9.1.15

## Hallazgos reales

El Motorola con 9.1.14 registró dos cierres al pulsar el escáner de Mensajes. La excepción indica que `web_login_scanner` no existe en el grafo interno que empieza en `messages`. La pantalla sí está registrada en el grafo raíz. Home entregaba el controlador de pestañas a la bandeja, aunque sus acciones abren destinos externos a esas pestañas.

El mismo teléfono tiene `animator_duration_scale=0` y `transition_animation_scale=0`. Las nubes consultaban esa configuración para detenerse; su botón únicamente modificaba `paused` y nunca permitía superar la condición del sistema. Por eso «Animar nubes» no podía activarlas en ese dispositivo. También se elimina la llamada directa a `ValueAnimator.areAnimatorsEnabled`, que no corresponde a todos los niveles Android admitidos por la app.

La primera prueba física se ejecutó con la pantalla bloqueada y no encontró jerarquías Compose; ese resultado no se cuenta como aprobado. Después de desbloquear, las cuatro pruebas dirigidas pasaron. Al abrir el lector real también se encontró un cierre anterior de la linterna: `findViewById(android.R.id.content)` podía devolver null. El lector inicia la cámara automáticamente; por eso se verificará tanto esa cámara como la pantalla de vinculación al volver.

## Cambios e impacto

- Home entrega el controlador raíz a la bandeja. QR, ajustes, guardados y entradas de grupos/comunidades usan el mismo grafo donde están sus destinos; no se duplica el escáner ni cambia el protocolo de vinculación.
- El botón del escáner informa de un destino no disponible mediante un aviso recuperable si el estado de navegación queda obsoleto.
- La linterna conserva la referencia al lector devuelta por `initializeContent`, sin recorrer vistas de la ventana. Comprueba el ciclo de vida y comunica fallos de cámara recuperables.
- Las nubes tienen recorridos suaves, desfasados y acotados en el espacio superior e inferior de la pregunta. Se preservan el texto, respuesta completa, reacciones y permisos.
- La elección automática respeta la configuración del sistema. «Animar nubes» es una elección explícita que funciona con escala cero, se guarda localmente por cuenta y se comparte entre sus preguntas. «Pausar nubes» conserva la posición. La animación se detiene al salir de primer plano o abrir las respuestas.
- No se modifica la configuración global del teléfono ni la política de escritura de grupos/comunidades. La web conserva su control de animación explícita, ya comprobado con movimiento reducido.
- Versión 9.1.15 / 155 para identificar claramente el arreglo. Las dos ramas locales se sincronizarán con este cambio.

## Verificaciones previstas y límites

Pruebas unitarias de ruta, error recuperable, elección de movimiento y límites geométricos; pruebas Compose del botón desde un grafo anidado y de píxeles que cambian/pausan con el reloj de cuadros. Después, instalación conservando datos y apertura física del escáner. Los resultados efectivos se registran en `artifacts/release-9.1.15/`; esta lista no supone que ya hayan pasado.

Se agrega una quinta prueba física para encender y apagar la linterna sobre el lector inicializado.

La compilación firmada 9.1.14 se interrumpió tras el reporte: no se presenta como una entrega cerrada. La sincronización anterior sigue documentada y no sustituye estas correcciones físicas.

## Referencias consultadas

- [Android: pruebas y callbacks de navegación Compose](https://developer.android.com/guide/navigation/testing/compose).
- [Android: grafos de navegación anidados](https://developer.android.com/guide/navigation/design/nested-graphs).
- [Android: MotionDurationScale y escala cero](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale).
