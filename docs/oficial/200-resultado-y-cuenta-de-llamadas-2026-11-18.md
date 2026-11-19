# 200 · Resultado de llamada y cuenta propietaria en Android

18 de noviembre de 2026.

Al terminar una llamada durante la reconexión, el historial perdía la duración y registraba el estado «finalizada» en lugar de «contestada». Una consulta lenta al chat podía añadir su espera a la duración y a la hora del historial. Además, la persistencia podía asignar la llamada a la cuenta activa al colgar o enviar el resumen desde otra visita de sesión.

El repositorio captura la hora de cierre y conserva el primer instante de conexión aunque cambie el estado de medios. El historial local se guarda para la cuenta que inició o aceptó la llamada. La consulta del chat directo y los envíos a chats directos, grupos y comunidades usan la visita capturada. Si esa visita termina, queda el historial local de su propietario y se omite el envío. Las entradas conservan el identificador estable de la conversación cuando está disponible.

## Evidencia

- La referencia `020fea4f7261` falló en ocho de nueve pruebas. La candidata pasó las nueve y 32 regresiones de llamadas en ocho clases. El primer arnés de tiempo ajustaba el reloj de Android, mientras el repositorio leía el reloj de la JVM. La repetición usa el mismo reloj en prueba y producción.
- El intento Gradle Audit con 2.304 MiB terminó en compilación por falta de heap del compilador. La repetición de Gradle sobre la revisión integrada queda pendiente del turno compartido con los ensayos de emulador.
- Recibos, huellas de las fuentes y XML en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit200-call-outcome/`.

Se probaron el estado de llamada contestada durante reconexión, la espera de consulta, el cierre con otra cuenta activa, dos cambios de visita incluida la vuelta a la misma cuenta, el envío habitual y la llamada sin contestar. Quedan pendientes los ensayos físicos de reconexión, el timbre de invitaciones entrantes, la señalización completa de cierre y la validación multidevice. La APK 9.1.43/183 verificada en [201](201-validacion-android-183-acceso-y-arranque-2026-11-18.md) procede de un commit anterior.

La corrección de la primera respuesta en llamadas colectivas se documenta en [202](202-primera-respuesta-llamadas-colectivas-2026-11-18.md).
