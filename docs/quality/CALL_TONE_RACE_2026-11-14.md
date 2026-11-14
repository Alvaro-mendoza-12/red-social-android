# Android: cancelación de tonos de llamada — 14 de noviembre de 2026

Se corrigió una carrera reproducida en el reproductor de tonos. Una tarea podía comprobar su actividad, esperar el bloqueo de audio y reproducir un tono después de que `stopRingback()` o `stopAll()` hubiera cancelado la tarea y detenido el sonido.

## Reproducción y corrección

Sobre el blob original `f1d35a37f5cd8265d184d346907f470cb1510cb7` se ejecutaron dos pruebas con Robolectric API 34 y un ejecutor real de un hilo. El ensayo retiene el monitor, espera que la tarea de reproducción quede bloqueada y la cancela antes de liberar el monitor. Ambas fallaron en la aserción de ausencia de tono tardío; no fallaron por tiempo de espera. Es una reproducción local, no evidencia de un incidente en teléfonos de producción.

El inicio, la sustitución y la parada de las tareas ahora comparten el bloqueo de audio. La tarea vuelve a comprobar la cancelación dentro de ese bloqueo, justo antes de crear o reproducir audio. `stopAll()` detiene ambos tipos bajo la misma exclusión. Se mantienen el constructor inyectado, las firmas públicas, las cadencias y la política de silencio/vibración.

Se modificó únicamente [CallTonePlayer.kt](../../app/src/main/java/conectate/com/util/CallTonePlayer.kt) en el código de producción. No se modificaron `CallRepository`, LIVE ni los servicios. Las [ocho pruebas](../../app/src/test/java/conectate/com/util/CallTonePlayerTest.kt) cubren las dos carreras originales, cancelación antes del primer sonido, inicios repetidos, parada del timbre entrante, sustitución entrante/saliente, una llamada nueva y silencio/vibración.

## Validación

Con JDK 21 y el Gradle wrapper del proyecto:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests 'conectate.com.util.CallTonePlayerTest' --console=plain
```

La ejecución previa terminó con dos fallos de aserción en dos pruebas. La ejecución posterior aprobó **ocho pruebas, cero fallos, errores u omisiones**, en 8,745 segundos de ejecución de la suite. Compilaron las clases principales y de pruebas. La [evidencia estructurada](evidence/2026-11-14-call-tone-race.json) registra los blobs y las huellas de los informes locales. Los logs y el informe previo se conservaron en `app/build/call-tone-evidence`, sin versionar datos de la máquina ni los respaldos.

No se repitió la batería JVM completa, Lint, instrumentación o empaquetado firmado. El timbre de las pruebas es un shadow de Robolectric y no acredita el archivo de tono del sistema, volumen, altavoz, Bluetooth o audio físico. Tampoco se probaron aquí los recorridos reales de aceptación, rechazo, cambio de cuenta o avisos en segundo plano de `CallRepository`. No se declara resuelta toda ausencia de tono, no se entrega una APK nueva y no se cierra la revisión móvil ni la preparación integral para producción.

La coordinación con el asistente de VS Code se realizó por mensajes de su chat; este documento registra evidencia técnica y no funciona como buzón de coordinación. La publicación y las huellas de cobertura se integran de forma coordinada, conservando los cambios documentales y web ajenos.
