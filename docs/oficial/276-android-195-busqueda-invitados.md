# Novvin Android 9.2.2 con código 195

Los paquetes corresponden a `8b71f52f3451d017629dfa6b9705086da2a4df45`, con Android 16 como destino y el mismo certificado de 194. La búsqueda de invitados tiene sus propios estados de carga, resultados y error. Android espera una pausa de 350 ms antes de enviar, evita repetir el mismo texto y cancela al salir de la sección. El reintento conserva el texto y una respuesta antigua no reemplaza el resultado actual. Las coincidencias con un mismo identificador se muestran una vez.

Pasaron 50 pruebas JVM de release en 9 clases, incluidas cinco nuevas de búsqueda. Se verificaron el código y la versión en los paquetes, la alineación de 16 KiB, los componentes de R8 y el tamaño del AAB.

El emulador instaló 195 sobre 194 y comprobó el arranque, la entrada de invitado, Juegos, Perfil y la salida, sin cierres de Novvin. La búsqueda de personas mostró el error sin red y el mismo texto se consultó al pulsar Reintentar después de restablecer la conexión. El diálogo de comentarios también recuperó la lectura al restablecer la red. Se eligió esperar en el aviso de System UI del emulador para que no cubriera la pantalla de Novvin.

| Paquete | Bytes | SHA-256 |
| --- | ---: | --- |
| APK ARM64 | 87678609 | `287565a23376252188a7e17def1f69207f8e61ec34f48dc61f06a93396abcba1` |
| APK universal | 214008462 | `d7e3b1affacbe9219fc9310a7275646a72c46905d5cd7d8dd8cd6243f9714187` |
| AAB Google Play | 125434649 | `8f5a609f7dc67a8277db1ca20c6532f7adb8d85fd10c2e65e2886b26c3e67f3f` |

La publicación en Google Play y la prueba en un teléfono físico siguen pendientes.
