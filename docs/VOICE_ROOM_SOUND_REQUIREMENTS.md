# Análisis previo: avisos sonoros de salas de voz

Fecha: 17 de octubre de 2026. Alcance: Android y web de Novvin.

## Integración y decisiones

Las salas actuales usan Socket.IO y WebRTC. Ya existen `room-joined`, `current-participants`, `user-joined`, `user-left` y `mute-state`. No hace falta otro canal, enviar archivos de sonido por la red ni mezclar avisos con la pista del micrófono. La reproducción se realiza en cada cliente conectado, con tonos originales incluidos en la aplicación.

Discord documenta controles separados de sonidos de micrófono y entrada/salida. Se adopta esa separación de preferencias, no sus grabaciones ni la afirmación de que todos sus clientes anuncien el mute de terceros. En Novvin el requisito expreso es que los presentes oigan los cambios de micrófono de participantes conocidos.

## Requisitos funcionales y reglas de negocio

| ID | Criterio de aceptación |
| --- | --- |
| VS01 | Una entrada confirmada produce un tono ascendente; salir produce uno descendente. |
| VS02 | Silenciar y activar el micrófono producen dos tonos diferenciados. Solo cambios reales de estado; el estado inicial no produce tono. |
| VS03 | Los participantes presentes reciben avisos de terceros mediante señalización autenticada existente. Una sala a la que no se ha entrado permanece silenciosa. |
| VS04 | La lista inicial y las actualizaciones de participantes son una referencia silenciosa; no anuncian a cada persona como una entrada nueva. |
| VS05 | Eventos duplicados, cambios de socket de la misma identidad y reconexiones locales no generan entradas repetidas. Salidas se esperan brevemente para absorber una reconexión rápida. |
| VS06 | Hay preferencias de entrada/salida, micrófonos y volumen, activadas por defecto, persistidas por cuenta y dispositivo. Los indicadores visuales siguen funcionando con sonido desactivado. |
| VS07 | Silenciar el audio de la sala en web también suprime estos avisos. Silenciar el micrófono permite seguir oyéndolos. En Android se respeta silencio/No molestar y no se cambia el foco ni la ruta de audio de la conversación. |
| VS08 | Al salir, cambiar de cuenta/sala o desconectar, se cancelan avisos pendientes y se liberan reproductores; un aviso antiguo no se reproduce después. |

## Requisitos no funcionales

- Avisos cortos (menos de 350 ms), volumen moderado, máximo un sonido simultáneo y limitación de ráfagas. No se acumula una cola de eventos pasados.
- Web: iniciar el contexto de audio desde la interacción de entrar o activar sonidos; si autoplay lo bloquea, conservar indicadores y permitir habilitarlo con una pulsación. No modificar las restricciones del navegador.
- Android: muestras PCM locales con SoundPool; reproducción ligera en la ruta de comunicación existente y sin solicitar otro foco. La falta de un decodificador no afecta a la conexión.
- Probar determinísticamente repetición, entrada inicial, reconexión, participantes desconocidos, salida rápida/reentrada, preferencias y aislamiento de cuenta. Verificar dos clientes reales además de las pruebas unitarias.

## Referencias consultadas

- [Discord: sonidos configurables por evento](https://discord.com/blog/how-to-manage-your-discord-desktop-notifications), sección «Can I Turn Off Certain Discord Sounds?».
- [MDN: autoplay y Web Audio](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Autoplay).
- [Android: SoundPool](https://developer.android.com/reference/android/media/SoundPool).

Este documento define la aceptación antes de implementar; sus criterios no se consideran aprobados hasta registrar las pruebas correspondientes.
