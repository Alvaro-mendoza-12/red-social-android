# Enlaces dentro del chat — 2 de octubre de 2026

## Resultado

El componente compartido por mensajes, grupos y comunidades reconoce direcciones HTTP/HTTPS y dominios sin protocolo, los muestra azules y subrayados y abre su destino. La puntuación de una oración queda fuera del enlace; se conservan los paréntesis que pertenecen a una dirección. Los correos y los protocolos de archivos no se convierten en enlaces web.

Los enlaces de publicaciones solo abren una ruta interna cuando el dominio y el identificador son válidos. Se evita confundir una dirección externa que incluye `conectate.com/post/` con una publicación propia. Si no hay una aplicación capaz de abrir el enlace, se muestra un mensaje al usuario.

## Comprobaciones realizadas

- Cinco pruebas unitarias: múltiples enlaces, puntuación y paréntesis, exclusión de correos/protocolos locales, parámetros/fragmentos y rutas internas.
- Tres pruebas instrumentadas de Compose: enlace enviado azul, enlace recibido y publicación interna; se comprueba el destino al tocar el texto.
- Prueba nativa en `emulator-5554`, Android 15: se escribió y envió un enlace en una conversación entre cuentas de auditoría. Quedó confirmado como enviado, se tocó el enlace y el navegador abrió `https://example.com/`. La página cargó.
- APK de depuración y APK de pruebas compiladas e instaladas conservando datos. Versión de publicación sin cambios.

Capturas: [chat con enlace](../evidencias-enlaces-chat-2026-10-02/chat-enlace.png) y [destino abierto](../evidencias-enlaces-chat-2026-10-02/destino-abierto.png).

## Alcance

La prueba nativa cubre un chat directo y una dirección externa. El componente es común a los tres tipos de conversación; no se ha probado de forma nativa cada destino posible ni cada aplicación externa. Las pruebas de Compose interceptan la apertura y complementan la prueba real del navegador.

Las comprobaciones de llamadas, salas, LIVE, encuestas, música, momentos, avisos, visitas y arranque siguen en curso. Este resultado no certifica toda la aplicación para producción.

## Documentación de referencia

[Android: enlaces e interacción con texto en Compose](https://developer.android.com/develop/ui/compose/text/user-interactions).
