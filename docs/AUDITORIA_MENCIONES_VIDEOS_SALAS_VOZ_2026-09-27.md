# Menciones, enlaces de video y salas de voz — 27/09/2026

Rama de trabajo: `feature/live-gifts` en `D:\red social - live-gifts`.

## Hallazgos corregidos

- El selector de menciones insertaba `username`, que en cuentas antiguas podía derivarse del correo. Ahora inserta el identificador público basado en el nombre visible. No usa la dirección de correo para formar la mención.
- La búsqueda de menciones admite nombres con tildes: por ejemplo, `@keiber_joya` puede resolver “Keibér Joya”. También consulta perfiles importados además de cuentas locales.
- Los comentarios de video y Social mostraban las menciones como texto normal; en Social el toque llevaba al autor del comentario. Ahora se resaltan en azul y se resuelve el perfil mencionado. Las descripciones de videos usan el mismo formato público y admiten caracteres Unicode.
- La lista de sugerencias de menciones podía ocupar demasiado espacio. Ahora su altura está limitada y permite desplazamiento, dejando espacio para seguir escribiendo el mensaje.
- El enlace compartido de video usaba la ruta `/app/reels/{id}` de Vercel, mientras el chat solo reconocía `/video/{id}`. Ahora la app reconoce ambas rutas y abre el reproductor interno cuando se toca el enlace en el chat.
- Los comentarios y descripciones de videos en el backend local no notificaban de forma confiable a los perfiles mencionados. El servidor ahora crea `mention_post`, `mention_video` y `mention_video_description` al guardar el contenido. No se envía una notificación a uno mismo ni a un perfil bloqueado.
- La reconexión y las transiciones de cámara/pantalla de una sala de voz podían preparar el micrófono como activo después de silenciarlo. Ahora conservan el estado de silencio y el permiso de hablar. Al pasar de una sala a otra se cierra primero la sala anterior y luego se fija el ID de la nueva.

## Verificación realizada

- Compilación Android `assembleDebug` y pruebas unitarias de identidad pública, enlaces y menciones de descripciones.
- APK 3.3.16 (código 78) instalada y abierta en emulador Android. El Motorola no apareció en ADB durante este cierre, por lo que no se instaló ni verificó allí.
- Pruebas Node de extracción/resolución de menciones, presencia de voz y señalización.
- API, señalización, coturn, base de datos, pasarela realtime, edge, Redis y chatbot saludables en la mini PC. La API local respondió HTTP 200.
- La URL pública configurada en la APK respondió HTTP 200 tanto en `/health` como en el handshake de `/socket.io/`.
- Se ejecutó una búsqueda real de un identificador de prueba contra PostgreSQL desde el contenedor API; la consulta de perfiles locales e importados terminó sin error.

## Límites de esta verificación

- Una prueba real de audio bidireccional requiere dos dispositivos y dos cuentas conectadas simultáneamente. La salud de los contenedores y las pruebas de señalización no demuestran por sí solas que ambos escuchan audio.
- Si dos cuentas comparten exactamente el mismo identificador público, la app y el servidor no adivinan cuál se quiso mencionar; el toque muestra un error y no se entrega la notificación a una cuenta equivocada. Hace falta asignar identificadores públicos únicos para resolver ese caso de forma definitiva.
- El enlace se abre dentro de la app desde los chats. La apertura universal desde otras aplicaciones requiere configurar y verificar un dominio propio para Android App Links.
- El cambio de contraseña y la recuperación asistida estaban implementados en una entrega anterior. Esta auditoría no ejecutó un restablecimiento real de una cuenta personal ni la aprobación administrativa completa.
