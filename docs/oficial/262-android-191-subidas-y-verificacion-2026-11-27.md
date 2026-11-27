# Novvin Android 9.2.1 con código 191

27 de noviembre de 2026 · La APK y el AAB firmados corresponden a `73ab1047ee41e50affd7e7ad3a551aa8e107960f` y apuntan a Android 16, API 36. Conservan el certificado de la entrega 190. Incluyen la [corrección de subidas directas y reanudables](260-subidas-reanudables-durante-incidentes-2026-11-27.md) que muestra un aviso ante una indisponibilidad y conserva el progreso de una sesión reanudable.

Pasaron 3613 pruebas JVM distribuidas en 602 clases. El primer intento de la suite terminó por el cierre del proceso de pruebas de Gradle. La repetición con procesos aislados completó todas las pruebas. La validación de R8, la firma de ambos artefactos y la alineación de 16 KiB de las 14 bibliotecas de 64 bits también pasaron.

En el emulador con Android 16, la APK 191 se instaló encima de la 190. La aplicación abrió el acceso inicial sin conexión y omitió el aviso de actualización. Al recuperar la conexión se probó el recorrido de invitado por Juegos y Perfil, incluida la salida. La comprobación de errores del dispositivo no registró un cierre de Novvin durante ese recorrido.

| Artefacto | SHA-256 |
|---|---|
| APK universal | `1c9ec3b8f0af7eab123026827b2d594738dea2aa1fbf3d3ec9ce77da90e5e70f` |
| AAB | `68d7f512e0658bd63079ffba7ab1d20e50c43ddf1c8065e76e0ff86641972d19` |

Firebase confirmó después que `novvinapp.com` y `www.novvinapp.com` están activos con HTTPS. Se verificaron los documentos públicos de privacidad y eliminación de cuenta en el dominio de Novvin y en el origen enlazado por la ficha pública de Google Play.

La revisión integral sigue abierta. La imagen candidata de la API continúa a la espera de que Ubuntu recupere `/ready` con firmas vigentes. La entrega Android se verificó fuera de Google Play.
