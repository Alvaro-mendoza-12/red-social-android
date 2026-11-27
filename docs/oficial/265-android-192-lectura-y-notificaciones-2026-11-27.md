# Novvin Android 9.2.1 con código 192

27 de noviembre de 2026 · La APK universal y el AAB firmados corresponden a `12b93cc677926d90464e85b7a751cb2b14e74cd2`. Ambos manifiestos identifican el paquete `conectate.com`, código 192 y Android 16, API 36. El nombre visible de la versión es 9.2.1. Los dos artefactos conservan el certificado de la entrega 191.

La versión incorpora las [acciones de notificaciones](263-acciones-de-notificaciones-android-2026-11-27.md) y la [consulta de lectura visible](264-lectura-visible-android-y-diagnostico-ubuntu-2026-11-27.md). Android comprueba la confirmación antes de retirar un aviso de lectura y conserva las respuestas que ya llegaron a la cola local al vencer su plazo. Los chats, grupos y comunidades buscan los mensajes visibles por identificador. Room actualiza los lotes y el contador de la conversación en una transacción.

Pasaron 3624 pruebas JVM en 603 clases. Se comprobaron la firma de ambos artefactos, la preservación de los componentes requeridos por R8 y la alineación de 16 KiB del APK, del AAB y de las 14 bibliotecas nativas de 64 bits.

El AAB pasó los cuatro límites de tamaño de CI. Bundletool estimó una descarga máxima de 56.7 MiB por dispositivo. La APK universal pesa 204.1 MiB e incluye las cuatro arquitecturas admitidas.

En el emulador de Android 16 se instaló la APK 192 sobre la 191. El primer arranque sin conexión mostró la pantalla inicial sin aviso de actualización. Al restablecer la conexión se verificaron la entrada de invitado, Juegos, Perfil y la salida. El registro de fallos del emulador no registró un cierre de Novvin durante el ensayo.

| Artefacto | SHA-256 |
| --- | --- |
| APK universal | `939534b44cdaafc0e31a0f6b5a0403bf4541f5a77b8cdd36f287553837d58477` |
| AAB | `989c0fdc3398d6f9062ee2fb404b0e6f177ccdbe296b33ef657e47adf9ec8eea` |

La ficha pública de Google Play muestra categorías de datos recopilados y enlaza la política vigente. La política y la solicitud de eliminación de cuenta devolvieron HTTP 200 en ambos orígenes públicos. Una copia aislada de la imagen activa de Ubuntu superó 46 peticiones del recorrido de Rondas, incluida la reapertura de una ronda iniciada hacía 36 horas con plazos vencidos. [Seguridad de los datos en Google Play](https://play.google.com/store/apps/datasafety?id=conectate.com) · [Política de privacidad](https://novvinapp.com/privacidad).

El [ensayo del panel](267-estadisticas-del-panel-y-rondas-2026-11-27.md) cubrió los permisos y avisos con cuentas sintéticas. La entrega se verificó fuera de Google Play. Los recorridos físicos de notificaciones y conversaciones y la revisión integral de producción siguen abiertos.
