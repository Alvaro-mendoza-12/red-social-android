# Auditoría backend — 19 septiembre 2026

Directorio auditado: `D:\red social - live-gifts`, rama `feature/live-gifts`.
Se preservaron los cambios de trabajo existentes. No se desplegó ni se accedió a servicios de producción.

## Hallazgos corregidos en esta intervención

- El endpoint de actividad «escribiendo» aceptaba un ID de conversación ajena sin comprobar acceso. Ahora requiere acceso al chat o pertenencia vigente/propiedad del grupo o comunidad. Una comunidad pública visible no concede permiso para emitir actividad en nombre de sus miembros. El nombre publicado procede de la cuenta autenticada, no del cuerpo enviado por el cliente.
- Nuevo `POST /v1/data/messages/chats/:chatId/read-visible`: acepta `messageIds` (máximo 200 UUID), comprueba acceso antes de escribir, actualiza exclusivamente esos mensajes entrantes y devuelve los IDs confirmados. No avanza el cursor global de lectura. La ruta separada impide que un servidor anterior ignore los IDs y marque accidentalmente toda la conversación.
- El lockfile del gateway realtime contenía `qs` vulnerable. Se actualizó dentro de los rangos compatibles con `npm audit fix --omit=dev`. API y signaling ya tenían sus dependencias corregidas al comenzar esta auditoría y no se atribuyen como cambios nuevos.

## Portada de canción comunitaria

La interfaz ya extraía el artwork del audio, lo convertía a JPEG y pasaba un `file://` de caché. El repositorio intentaba descubrir nombre y MIME mediante consultas de proveedor; esos metadatos no existen necesariamente para archivos locales. Rechazaba un JPEG válido con «La portada debe ser JPG, PNG o WebP», después de haber enviado el audio.

`prepareCommunityMusicCoverUpload` ahora abre tanto `content://` como `file://`, limita la entrada a 10 MB, verifica la decodificación y genera un JPEG de hasta 1024 píxeles por lado. El repositorio prepara esa copia antes de iniciar la transferencia del audio y usa extensión, MIME y bytes JPEG coherentes. La copia se elimina al terminar o fallar. El nombre del archivo también puede resolverse desde `lastPathSegment`, necesario para audios recortados en caché. No se relajó la validación del servidor.

Regresiones añadidas en `CommunityMusicArtworkTest`: JPEG local sin proveedor; URI de albumart sin MIME/extensión; archivo falso con nombre `.jpg`. Su ejecución Android y el empaquetado APK se coordinan en la tarea principal.

## Verificación local

- Baseline API: 182 pruebas, 181 aprobadas y 1 omitida; ninguna falló.
- API tras integrar cambios de esta tarea y de la tarea principal: 188 pruebas, 187 aprobadas y 1 omitida; ninguna falló (`npm test`).
- Baseline signaling: 27 pruebas, 26 aprobadas y 1 integración Redis omitida; ninguna falló. Comprobación sintáctica aprobada.
- Baseline gateway realtime: suite de autenticación/autorización y comprobación sintáctica aprobadas.
- Baseline IA local: suites smoke, conocimiento, búsqueda semántica, resumen e integración HTTP aprobadas.
- Nuevo módulo de actividad/lectura visible: 4 pruebas aprobadas; comprobación sintáctica de `data-routes.js` y verificación de dependencias locales aprobadas.
- `npm audit --omit=dev`: API 0, signaling 0, realtime 0 después de actualizar `qs`.

Reverificación (20 septiembre): `npm run check` y `npm test` finalizaron con código 0 para API, realtime e IA. API actualizada con historial musical: 194 pruebas, 193 aprobadas y 1 omitida (`backend-api-{check,test}-library.log`). Realtime: suite HTTP/WebSocket aprobada. IA: 6 suites aprobadas, incluida la nueva continuidad contextual. Logs completos en `build/audit-2026-09-19/`.

## Límites pendientes de producción

Estas pruebas no prueban capacidad a escala ni equivalen a certificar producción. No se ejecutaron las integraciones que requieren Redis real ni una prueba de carga con PostgreSQL y varias réplicas; tampoco recuperación de backups, TURN/LiveKit con dispositivos ni un despliegue de staging.

La ruta de lectura visible confirma mensajes materializados en `fallback_messages`; un mensaje sólo presente en la importación histórica no se declara leído sin una escritura verificable. El cliente debe respetar los IDs devueltos y no recurrir a lectura global cuando reciba 404 en un servidor antiguo. Para usarla debe desplegarse primero el backend actualizado.

La IA es un clasificador local con respuestas y conocimiento acotados; las suites no demuestran una capacidad general equivalente a un LLM. Los cambios adicionales de continuidad contextual, UTF-8 y ciclo de vida realtime los integra y verifica la tarea principal.
