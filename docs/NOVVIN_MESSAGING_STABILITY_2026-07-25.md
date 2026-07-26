# Novvin 3.0.7 — estabilidad de mensajes

Fecha de validación: 2026-07-25

## Alcance

Este bloque corrige respuestas que desaparecían, bloqueos al finalizar audios
largos, reproducción repetida de videos de chat, eliminación administrativa y
el orden entre persistencia local, tiempo real y notificaciones.

## Causas y correcciones

- **Respuestas:** FCM podía insertar una copia parcial del mensaje y reemplazar
  los campos `reply_to_id` y `reply_to` recibidos por tiempo real. Los
  repositorios ahora fusionan las variantes por identificador, preservan la
  respuesta y escriben la versión completa en Room.
- **Audio:** `AudioRecorder.stop()` convertía PCM a AAC desde el hilo de la
  interfaz. La finalización ahora corre en `Dispatchers.IO`, espera al escritor
  PCM y protege contra finalizaciones simultáneas. La vista previa usa
  `prepareAsync()`.
- **Videos de chat:** el visor y la burbuja solicitaban nuevamente el archivo.
  Ambos usan ahora `VideoCache`, una clave estable y precarga parcial. La
  miniatura ya no intenta decodificar el video completo con Coil.
- **Panel administrativo:** la interfaz exigía escribir una confirmación larga
  y el motivo antes de enviar la solicitud. Ahora muestra una confirmación
  simple; la aplicación genera internamente el motivo y la confirmación
  técnica. Las protecciones del servidor continúan vigentes.
- **Notificaciones:** el mensaje se guarda localmente antes de publicar la
  notificación. El servidor espera la publicación en tiempo real antes de
  enviar FCM y los chats inicializan inmediatamente su estado visible.

## Verificación ejecutada

- Compilación Kotlin Debug correcta.
- 31 suites Android, 128 pruebas unitarias, 0 fallos.
- API: comprobación sintáctica y pruebas de rutas de datos y moderación
  aprobadas.
- Grabación física de 65 segundos en un moto g04: archivo AAC generado, sin
  crash ni ANR; finalización aproximada de 10,8 segundos fuera del hilo de UI.
- APK Debug compilado.
- APK Release compilado con R8 y reducción de recursos.
- Novvin `3.0.7` (`versionCode 47`) Release instalada en el moto g04; proceso
  activo y sin bandera `DEBUGGABLE`.
- API y proxy de producción saludables después del despliegue dirigido de
  `data-routes.js`; Firebase Cloud Messaging configurado.
- `git diff --check` sin errores.

## Límites y prueba manual pendiente

- No se eliminó ninguna cuenta real. La prueba debe hacerse únicamente con una
  cuenta ficticia suspendida o marcada como prueba.
- Las cuentas importadas de Supabase requieren configurar
  `SUPABASE_SERVICE_ROLE_KEY` en el servidor para borrar también la identidad
  externa. Sin esa credencial, el servidor rechaza la operación y revierte los
  cambios para evitar una eliminación incompleta.
- El APK Release del dispositivo está firmado localmente con la clave de prueba
  ya usada por ese teléfono. El artefacto para Google Play todavía necesita la
  clave oficial de publicación o un Android App Bundle firmado.
- La suite `androidTest` heredada completa contiene pruebas antiguas que no
  compilan por APIs de prueba desactualizadas. La prueba física de audio se
  ejecutó mediante el source set aislado `audioValidationTest` para no ocultar
  ni modificar esos fallos preexistentes.

## Matriz manual mínima

1. Responder un mensaje en chat directo, grupo y comunidad; esperar 30 segundos
   y volver a abrir cada conversación. La cita debe permanecer.
2. Grabar y enviar un audio de al menos 65 segundos. La interfaz debe seguir
   respondiendo durante la finalización y el archivo debe reproducirse.
3. Abrir un video de chat, cerrarlo y abrirlo otra vez. La segunda apertura debe
   reutilizar la caché y comenzar con mayor rapidez.
4. Suspender una cuenta ficticia, pulsar **Eliminar**, confirmar con
   **Sí, eliminar** y comprobar que desaparezca de la búsqueda y no pueda
   iniciar sesión.
5. Con dos teléfonos, enviar mensajes con la conversación cerrada y abierta.
   Validar llegada inmediata, apertura en el mensaje correcto y ausencia de
   notificación cuando el chat ya está visible.
