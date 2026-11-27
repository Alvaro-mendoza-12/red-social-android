# Novvin Android 9.2.1 con código 193

27 de noviembre de 2026. La APK universal, la APK para ARM64 y el AAB se generaron con el commit `3bbf3c9959b066f6d8b33b601b2804a9571a87d5`. Las tres variantes corresponden a `conectate.com`, código 193, versión 9.2.1 y Android 16 como destino. Todas conservaron el certificado de Android 192.

Esta versión incorpora la [corrección de subidas reanudables](270-offsets-reanudables-android-2026-11-27.md). Android comprueba la longitud de una sesión recién creada antes de transmitir datos. Si el servidor confirma un desplazamiento distinto al esperado al terminar un fragmento, el cliente consulta el progreso de esa sesión antes de continuar.

Pasaron 3.626 pruebas JVM de release en 603 clases. Se verificaron la firma, los componentes preservados por R8 y la alineación de 16 KiB, incluidas las 14 bibliotecas de 64 bits del AAB. El AAB conserva las cuatro arquitecturas. La APK ARM64 contiene los mismos DEX y las mismas siete bibliotecas ARM64 que la universal. El AAB satisface los cuatro límites de tamaño de CI. Bundletool estimó una descarga máxima de 56.7 MiB por dispositivo.

Windows cerró algunos procesos de la primera suite por falta de memoria virtual. Se archivaron en D: seis temporales identificados de un emulador anterior, con comprobación SHA-256, y se repitió la suite con un límite menor para el proceso de Gradle.

La segunda ejecución aprobó 3.610 casos. Los 16 casos de recuperación de contraseña se detuvieron al iniciar el agente de Byte Buddy. Los 16 aprobaron en un proceso aislado con el agente precargado. El recuento incluye las dos ejecuciones y conserva el informe inicial.

Las APK por arquitectura y el AAB se construyeron en ejecuciones separadas de Gradle. La ejecución conjunta de esta versión del plugin produjo archivos de recursos reducidos que el empaquetador del AAB no pudo combinar. Se comprobó que el código de la corrección está en las APK y en el AAB.

En el emulador de Android 16, la APK 193 se instaló sobre la 192. El primer arranque sin red abrió la pantalla inicial. Al recuperar la conexión, el ensayo completó el acceso de invitado, las pantallas de Juegos y Perfil y la salida del modo invitado. El registro de fallos quedó sin cierres de Novvin durante esa prueba.

| Artefacto | Tamaño | SHA-256 |
| --- | --- | --- |
| APK ARM64 | 83,6 MiB | `4889274b615dda4235133dd42ef793c6884baed3c2e8ffaad85cf7dae68b2854` |
| APK universal | 204,1 MiB | `34e0ec44aadb6b680802c3b1c7d0c0ecdbf4356f1e8175581d72e862ed47e329` |
| AAB Google Play | 119,6 MiB | `5e9f7c9ce07496855fec218f949560000039d4c91a0f81733498431bcc23fdc8` |

La ficha pública de Play muestra categorías de datos recopilados. Privacidad y Eliminación de cuenta respondieron por HTTPS en el dominio principal y en www. La aprobación de Google Play, las subidas autenticadas en un dispositivo y la revisión integral de producción quedan pendientes. [Ficha de seguridad](https://play.google.com/store/apps/datasafety?id=conectate.com) · [Política de privacidad](https://novvinapp.com/privacidad).
