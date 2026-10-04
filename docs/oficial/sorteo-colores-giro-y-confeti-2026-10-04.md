# Sorteo móvil: colores, giro y confeti

Fecha: 4 de octubre de 2026. Rama: `feature/live-gifts`.
Cambios visuales: `2c54b06` y `faf3d34`.

## Comportamiento

- La ruleta usa rojo, azul, verde, naranja, violeta y turquesa, incluso cuando el tema general de la aplicación es blanco y gris. Los nombres o números son blancos; el indicador superior permanece fijo.
- Cada resultado nuevo inicia un giro de cuatro segundos, con al menos cuatro vueltas y desaceleración. Durante ese tiempo aparece «Girando…» y todavía no se muestra la tarjeta del nuevo ganador.
- El giro termina en el participante que devuelve el servidor. Se muestra su nombre, su posición y una celebración de confeti de 1,2 segundos. Después puede presentarse el siguiente resultado pendiente.
- Las actualizaciones durante un giro agregan resultados a la cola sin reiniciarlo. El primer resultado y los resultados de una ronda nueva también tienen animación.
- La presentación continúa aunque la ruleta salga de pantalla al desplazarse al historial. Al volver, el resultado ya completado no vuelve a celebrar. Los botones del siguiente giro y de nueva ronda esperan a que termine la presentación pendiente.
- Cambiar de ronda cancela la presentación anterior y evita mezclar sus ganadores con la nueva ronda. Abrir una ronda histórica completada muestra su resultado sin repetir giros ni confeti.

La selección y el orden de los ganadores siguen viniendo del servidor. Este cambio conserva las reglas y el historial existentes.

## Comprobación realizada

Compilación debug, APK de pruebas normal y pruebas unitarias completadas correctamente. Se verificó el marcador visual `faf3d34` en el BuildConfig generado. La compilación incorpora una captura de las fuentes compartidas con los cambios de música e inbox de la otra IA; no representa un checkout limpio completo de ese commit.

| Comprobación | Resultado |
| --- | --- |
| Estado de presentación: primer giro, apertura de ronda activa, cola, refresco, ronda nueva, historial y último ganador | 7/7 |
| Reglas de estado, cálculo de rotación y compatibilidad existentes del sorteo | 7/7 |
| UI existente de ruleta, creación y tarjeta social | 5/5 |
| UI nueva: paleta bajo tema gris, diferencia real entre fotogramas, revelado/confeti, cola durante refresco, historial, desplazamiento y cambio de ronda | 6/6 |

Las 11 pruebas UI terminaron en 26,232 segundos en el emulador Android API 35. Se instalaron los APK conservando los datos y se comprobó que el SHA-256 del APK instalado coincide con el construido.

APK debug: `ced297a60f127872322d6ef150edcb12f523413656f0edbed78fb424c06bfa4c`.

APK de pruebas: `c88ff4f023083862b8624360c17393a3b705719eef020cc999c5aaf9b9bd17e9`.

La evidencia con hashes de las seis fuentes visuales, captura compartida de fuentes, pruebas y capturas está en [VALIDACION_SORTEO_VISUAL_FAF3D34.json](../../VALIDACION_SORTEO_VISUAL_FAF3D34.json).

Se inspeccionaron estas capturas de participantes sintéticos:

- [Colores de la ruleta](capturas/sorteo-faf3d34/qa-raffle-vivid-wheel.png).
- [Fotograma durante el giro](capturas/sorteo-faf3d34/qa-raffle-spinning.png).
- [Ganador y confeti](capturas/sorteo-faf3d34/qa-raffle-confetti.png).

El alcance de esta comprobación es la presentación móvil en emulador. No se ejecutó un sorteo de producción, no hubo una nueva prueba en teléfono físico y no se comprobó explícitamente la escala de movimiento reducido. La otra IA recibió la misma pareja de APK y la devolución de Gradle/ADB para continuar sus comprobaciones de música y compilación optimizada.
