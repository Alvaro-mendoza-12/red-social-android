# Confirmación de fragmentos en las subidas de Android

27 de noviembre de 2026. Continúa la revisión de las [subidas reanudables](260-subidas-reanudables-durante-incidentes-2026-11-27.md).

Android aceptaba cualquier avance positivo dentro del tamaño total después de enviar un fragmento. En una subida de 20 MiB, por ejemplo, enviaba 6 MiB a partir del sexto MiB y aceptaba un desplazamiento que confirmara un byte menos o uno más. El cliente podía seguir desde una posición distinta a la del último fragmento enviado.

Ahora el desplazamiento confirmado debe coincidir con la posición inicial más los bytes del fragmento enviado. Ante una respuesta incongruente, Android consulta el progreso de la misma sesión antes de transmitir más bytes. Esa consulta debe devolver un desplazamiento dentro de la longitud declarada. Al crear una sesión, también se comprueba que la longitud devuelta corresponda al archivo elegido antes de enviar su primer fragmento. [Protocolo TUS](https://tus.io/protocols/resumable-upload).

Pasaron 65 pruebas JVM de release en 6 clases. Las pruebas del protocolo incluyen confirmaciones que faltan, se quedan cortas o se adelantan, y desplazamientos de reanudación que exceden el tamaño total. Las demás cubren errores HTTP, pertenencia de la sesión, límites y validación de archivos. La revisión de `StorageService.kt` se limitó al recorrido de subida.

La suite HTTP de almacenamiento también pasó en una extracción de la API. Comprobó la creación, el envío, la consulta del progreso y la conservación de la sesión durante una indisponibilidad simulada.

Los paquetes Android 192 se generaron antes del cambio. El ensayo con un archivo privado de prueba en un dispositivo sigue pendiente. Ubuntu mantiene `/health` en 200 y `/ready` en 503. FreshClam todavía recibe la versión oficial 28194, que supera el máximo de 48 horas de la política vigente.
