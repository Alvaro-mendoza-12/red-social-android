# LIVE: controles, solicitudes y prueba entre dos emuladores

Fecha: 2026-10-03. Android mantiene versionCode 117 / versionName 9.0.20.

## Cambios comprobados

- Los controles del espectador se colocan sobre la altura medida del campo de comentarios, incluidos los márgenes del sistema. El teclado desplaza el conjunto y los controles admiten desplazamiento en pantallas bajas.
- Si el creador cierra las solicitudes, se muestra «Solicitudes cerradas» y se oculta «Solicitar unirme». Una solicitud pendiente todavía se puede cancelar.
- Los errores de acceso como invitado tienen mensajes comprensibles; la cancelación de una corrutina no se convierte en un error de red.
- Los interruptores de cámara y micrófono incluyen etiquetas accesibles.

La colocación sigue el consumo de márgenes explicado en la [documentación oficial de Compose](https://developer.android.com/develop/ui/compose/system/insets-ui).

## Verificación

- Compilación de APK y APK de instrumentación: correcta.
- 120 pruebas unitarias relacionadas con LIVE: sin fallos.
- 5 pruebas Compose en Android: separación de controles y comentarios, variación de altura, orientación horizontal y fuente grande, solicitudes cerradas y cancelación pendiente.
- Instalación con conservación de datos en dos emuladores API 35. LIVE de cuentas QA propias: cámara visible desde la otra cuenta, like reflejado en ambos extremos, comentario recibido, solicitud aceptada y dos cámaras publicadas. Salidas y fin del LIVE sin cierre de Novvin.
- Segunda prueba con esta APK: captura confirma «Más» separado del campo de comentarios y el estado cerrado sin botón de solicitud. El directo QA se finalizó.

## Rendimiento y pendientes reales

Medición anterior a este cambio visual, mediante marcas del mismo reloj del emulador receptor:

| Evento desde tocar el directo | Tiempo |
|---|---:|
| Participante detectado | 2.976 s |
| ICE conectado | 5.751 s |
| Primera muestra registrada con imágenes recibidas | 6.488 s como límite superior |

El receptor mantuvo aproximadamente 28–30 FPS del anfitrión. La cámara del invitado comenzó cerca de 30 FPS y después bajó a 7–15 FPS con las dos cámaras abiertas. Las estadísticas locales muestran caída de la captura, con pocos fotogramas descartados por el renderizador. Esto necesita investigación; los 60 FPS solicitados no están cumplidos.

La conexión de señalización y la conexión de medios son etapas distintas, conforme a [WebRTC](https://webrtc.org/getting-started/peer-connections). Estos resultados no certifican audio percibido, dispositivos físicos ni redes móviles: los emuladores usan cámara sintética y no reproducen sonido.

Siguen abiertos: rendimiento de cámara del invitado, etiquetas de la cuadrícula próximas a la navegación del sistema, y cancelación de una solicitud mientras su petición HTTP sigue en curso. El arranque frío de esta APK debug midió 3.986 s en el emulador principal y 5.284 s en el secundario; el objetivo de 3 s tampoco se da por cumplido.

Capturas y registros privados se conservan en build/physical-qa-2026-10-01, fuera de Git. No se usaron compras, regalos ni avisos globales.
