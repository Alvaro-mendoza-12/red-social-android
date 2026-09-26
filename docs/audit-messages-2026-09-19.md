# Auditoría y mejoras de mensajes — 19 septiembre 2026

Trabajo realizado en `D:\red social - live-gifts`, rama `feature/live-gifts`. Los cambios anteriores del checkout se conservaron. Los cuatro archivos nuevos que inicialmente se prepararon en la copia equivocada se retiraron sin tocar archivos existentes y se trasladaron a staging dentro del checkout correcto.

## Corrección prioritaria: portada visible, video enviado no reproducible

Se identificó una incompatibilidad concreta en la ruta del reproductor:

1. En el caso descrito por el usuario, el envío finaliza y la miniatura aparece; la inspección encontró un fallo posterior en la lectura de la copia local.
2. `MessageMediaResolver` prefiere la copia persistente por cuenta de `ChatMediaOfflineCache`, que devuelve `file:/.../<hash>.media`. Un envío aún pendiente también usa una URI local.
3. La portada se carga con Coil, que admite archivos locales.
4. `VideoCache.getCacheDataSourceFactory`, cuando su caché Media3 estaba activa, usaba exclusivamente `OkHttpDataSource` como upstream. Una caché vacía de esa URI intentaba abrir un archivo local con una fuente HTTP y fallaba. El fallback sin caché sí admitía archivos, por lo que el comportamiento dependía de qué factory se utilizaba.

Se sustituyó el upstream por `MediaPlaybackDataSources.streamingFactory(context)`, que envuelve el cliente HTTP existente en `DefaultDataSource` y admite HTTP, archivos y `content://`. Se conservan encabezados de almacenamiento, timeouts y caché de red. Se aplica al visor de mensajes y a las demás superficies que consumen esa factory.

`VideoCacheLocalPlaybackTest` abre bytes reales de un archivo `.media` con la caché activa, vuelve a leer un rango y abre un MP4 local pendiente. Exige que la fuente sea `CacheDataSource`, para que un fallo al inicializar la caché no esconda la regresión. Esta prueba valida lectura de bytes y seeks, no decodificación de codecs en hardware.

El visor ahora registra eventos antes de `prepare`, permite que Media3 reconozca el contenedor de los archivos `.media`, pausa al pasar la app a segundo plano y recrea el reproductor al reintentar tras retirar únicamente la entrada Media3 del recurso fallido. `FullScreenVideoScreen` también recrea el player cuando cambia la URL y libera/pausa por ciclo de vida. Los logs del error de reproducción ya no incluyen excepciones que puedan contener URLs firmadas.

## Tres funciones visibles y persistentes

Se añadió **Mi espacio del chat**, accesible en las opciones del chat:

- **Pendientes:** convertir un mensaje de texto en una acción privada, completar/reabrir y regresar a su origen.
- **Contexto:** conservar notas de decisiones e información, con enlace opcional al mensaje que les dio origen.
- **Borradores:** mantener varias respuestas preparadas, recuperar cualquiera y guardar el texto actual antes de reemplazarlo.

También se pueden crear elementos directamente desde el espacio. Mantener pulsado un mensaje de texto muestra Crear pendiente y Guardar contexto. No se hacen afirmaciones de exclusividad frente a otras aplicaciones.

La persistencia usa `EncryptedSharedPreferences`, claves derivadas de cuenta y conversación y escrituras serializadas fuera del hilo principal. La identidad de un chat directo se conserva entre placeholder y chat creado. No se recurre a almacenamiento sin cifrar si falla la clave. El archivo privado no está incluido en las reglas de backup existentes. Los errores de carga/escritura se muestran sin sustituir contenido corrupto por una lista vacía. Hay un máximo de 50 elementos de cada tipo y 4000 caracteres por elemento, sin eliminar silenciosamente elementos anteriores al alcanzar el límite. Los mensajes de visualización única y eliminados no pueden convertirse en copias de contexto.

Son funciones personales **solo de este dispositivo**, anunciado en la pantalla. No son acuerdos compartidos, recordatorios push, tareas del interlocutor ni sincronización entre dispositivos. Al desinstalar o borrar los datos de la app se pierden. El enlace de origen funciona si el mensaje sigue presente en el historial cargado.

Ampliación del 20 de septiembre: el mismo espacio se integró en grupos y comunidades, con separación por canal. Los pendientes admiten fecha/hora, cambio y retirada de fecha y una vista Cronograma que ordena tareas sin completar por vencimiento. La pantalla aclara que las fechas no crean alarmas ni envían mensajes. Se mantiene compatibilidad con el JSON anterior sin fecha. El selector y visor de documentos también se comparten entre los tres tipos de conversación; detalles en `audit-documents-2026-09-20.md`.

## Fiabilidad adicional

- Los mensajes entrantes dejan de desplazar al final a quien está leyendo historial. El salto automático se conserva al abrir, estar al final o enviar un mensaje propio. Abrir el teclado tampoco desplaza el historial si se estaba leyendo arriba.
- El texto escrito se limpia únicamente cuando el repositorio acepta el envío y el contenido del editor sigue siendo el enviado. Fallar una solicitud no sustituye ediciones más recientes. Se evita el doble envío simultáneo del mismo texto.
- Se retiraron los cambios globales a leído ejecutados al abrir, cerrar y destruir el chat. Los IDs visibles se resuelven por claves y los mosaicos de medios incluyen sus elementos visibles. La llegada de un mensaje fuera de la vista no limpia el contador.
- La ruta Ubuntu utiliza el nuevo endpoint `/v1/data/messages/chats/:chatId/read-visible`, creado con el agente de backend. Solo persiste los IDs confirmados por el servidor; no llama al antiguo `/read` como fallback ni a Supabase. El endpoint separado evita que un servidor anterior ignore los IDs y marque toda la conversación. Se verifican cuenta y generación de sesión antes/después de la operación.
- Al cambiar de cuenta se limpia el estado sensible del chat y se cancelan sus cargas/listeners para que una respuesta tardía no republique datos anteriores. El espacio privado también invalida sus operaciones y exige reabrir el chat.
- El refresco periódico de la bandeja solo se ejecuta mientras la pantalla está activa; se conserva el bloqueo existente contra refrescos simultáneos y se sincroniza la sección elegida al cambiar pestaña.

## Verificación y límites

- `ChatWorkspaceTest`: 14 casos aprobados, incluidos JSON anterior sin fecha, persistencia/orden de cronogramas y separación de grupos/comunidades/canales, además de persistencia, límites, aislamiento, política de scroll y edición durante envío.
- Añadidos 2 casos Robolectric en `VideoCacheLocalPlaybackTest`, centrados en la causa de fallo del video.
- `git diff --check` de los archivos tocados: sin errores de whitespace (solo advertencias de normalización LF/CRLF del checkout).
- Compilación Kotlin, empaquetado y 789 pruebas unitarias de Android aprobados el 20 de septiembre; los reportes XML incluyen `ChatWorkspaceTest` y `VideoCacheLocalPlaybackTest`. El informe general registra la verificación instrumental posterior.
- Backend informó cuatro pruebas pasando para el módulo nuevo de lectura visible. Requiere desplegar el endpoint antes de publicar Android. Servidores antiguos devuelven error y no se degradan a lectura global.
- La nueva ruta no confirma mensajes históricos ausentes de `fallback_messages`; no se fingen recibos. No se añadió una cola offline de recibos de lectura.
- Falta validar en dispositivo físico la decodificación del video concreto del usuario y probar emisor/receptor, red móvil, modo avión después de descargar, seek y vuelta desde segundo plano. El parche corrige una causa verificable de fallo local; no convierte por sí mismo codecs incompatibles ni recupera archivos corruptos remotos.

## Archivos principales

`ChatWorkspaceModels.kt`, `ChatWorkspaceStore.kt`, `ChatWorkspaceViewModel.kt`, `ChatWorkspaceSheet.kt`, `ChatScreen.kt`, `ChatViewModel.kt`, `MessagingViewModel.kt`, `ConversationsScreen.kt`, `MessageRepository.kt`, `LocalFallbackApiClient.kt`, `VideoCache.kt`, `FullScreenImageDialog.kt`, `FullScreenVideoScreen.kt`, `ChatWorkspaceTest.kt`, `VideoCacheLocalPlaybackTest.kt`.
