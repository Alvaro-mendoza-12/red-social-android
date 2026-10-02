# SSD, llamadas y respuestas multimedia

Fecha: 2026-10-01, zona America/Bogota. Proyecto `D:\red social - live-gifts`, rama `feature/live-gifts`.
Android conserva `versionCode 117` y `versionName 9.0.20`.

## Almacenamiento del servidor

El usuario autorizó expresamente borrar el SSD secundario WDC PC SN530 de 512 GB, serial `22021H800152`, con respaldo externo de sus antiguos datos de Windows. Se verificaron modelo, serial, capacidad, ausencia de montajes y que no fuera el disco del sistema antes del borrado.

- Nuevo formato: ext4, partición `/dev/nvme0n1p1`, etiqueta `novvin-fast`.
- UUID: `073803f2-f845-4d96-82b1-7c9f3692f428`; montaje `/mnt/conectate-fast`.
- Docker y su almacén de imágenes containerd usan el nuevo SSD mediante montajes de sus rutas originales `/var/lib/docker` y `/var/lib/containerd`.
- También se trasladaron los volúmenes de PostgreSQL y almacenamiento de la aplicación. Los discos de medios y respaldo existentes conservaron sus datos.
- Capacidad observada después del cambio: 469 GiB de sistema de archivos, 39 GiB usados y 426 GiB disponibles, según `df -h`.
- `fstab` y dependencias `RequiresMountsFor` de Docker/containerd vinculan el arranque al SSD correcto. TRIM periódico estaba habilitado y activo. No se ha realizado todavía un reinicio completo de la mini PC para validar el arranque físico.
- Respaldo anterior verificado: `20261002T014548Z`, con PostgreSQL, storage y datos de IA; checksums correctos. Se conservan las copias originales en el SSD del sistema para reversión, sin eliminarlas.
- Respaldo posterior creado y verificado: `20261002T021449Z`, PostgreSQL 3.616.557 bytes, storage 2.209.026.967 bytes y datos de IA 4.695.842 bytes, con checksums correctos.
- Copia inicial con los servicios activos y sincronización final con servicios detenidos. La operación final duró 230 segundos, incluyendo parada, copia, arranque y comprobaciones. Nueve contenedores volvieron a estado saludable.

El almacén de imágenes de Docker usa containerd: cambiar solamente el directorio de Docker habría dejado parte importante de los datos fuera del NVMe. Véase la [documentación oficial de Docker](https://docs.docker.com/engine/daemon/). El SSD aporta capacidad y rendimiento de almacenamiento; la latencia de red y los FPS del teléfono deben medirse aparte.

## Hallazgos y correcciones

| Hallazgo | Corrección | Comprobación |
|---|---|---|
| Invitación/cancelación de llamada omitía realtime al tratarse como aviso efímero | Se entrega al canal autenticado del destinatario además del push. Se conserva fuera del historial social | La prueba real falló antes del cambio por ausencia del evento y pasó después: 12 eventos en seis ciclos, 21–53 ms en esta conexión |
| Cancelación tardía podía volver a iniciar el timbre | Guardia compartida y persistente por ID entre FCM, servicio y repositorio; se descartan invitaciones canceladas | Pruebas de guardia y caso nativo de instancia nueva |
| Colgar podía perder destinatarios antes de enviar `end` | Se captura la lista antes de limpiar el estado e incluye al interlocutor/iniciador aunque todavía no llegue el evento de participante | Tres pruebas unitarias del cálculo de destinatarios |
| Consultas decorativas de perfiles bloqueaban el comienzo de llamadas | Invitación y conexión empiezan con datos de sesión; invitaciones múltiples se despachan concurrentemente | Compilación y regresión; tiempos físicos de timbre y conexión aún pendientes |
| Reposición de una vista leía el ID de un `VideoTrack` ya destruido | La vista se identifica por el objeto de pista, evitando la lectura nativa después de `dispose` | Prueba real de Compose/WebRTC con pista destruida, sin cierre de proceso |
| Tareas de medios o callbacks llegaban después del cierre del ejecutor | Ejecutor serial que completa callbacks de indisponibilidad; cierre idempotente; destrucción diferida de recursos usados por las vistas | Tres pruebas de ejecutor y pruebas nativas de reinicio/cierre repetido |
| Envío inicial y trabajador podían competir por el mismo mensaje de grupo/comunidad | Coordinación por ID, relectura del ACK dentro de la exclusión y validación de generación de sesión después de esperas | Pruebas unitarias existentes, compilación. Falta prueba física concurrente de todas las opciones |
| Error de sincronización dejaba un reloj sin reflejar el fallo | Se actualizan estado persistente y flujo visible; reintentar conserva ID/archivo y devuelve error si sigue fallando | Regresión de código y compilación; controles nativos validados anteriormente |
| Stickers, GIF, archivos y otros envíos perdían la respuesta seleccionada | Se pasa la respuesta en chats, grupos y comunidades; se limpia únicamente la referencia que quedó guardada localmente | Pruebas de borrador/revisión, envío y lectura reales por API |
| Registro devolvía el usuario sin su identificador público | Auth devuelve el username permanente de perfil también tras sincronización, sin derivarlo del correo | Tres pruebas del helper y registro desde frontend local contra API real: HTTP 201, username conservado y entrada en Mensajes; cuenta temporal eliminada |

La [implementación oficial de WebRTC](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/MediaStreamTrack.java) muestra que `id()` accede a la pista nativa. La captura “Novvin se ha detenido” no contiene un stacktrace: estas correcciones eliminan riesgos reproducibles, pero no identifican por sí solas la causa exacta del cierre informado en el Motorola.

## Evidencia de esta revisión

- Android: APK de aplicación y APK de instrumentación construidas; **1.043 pruebas unitarias, cero fallos/errores**.
- Android nativo en emulador: **3 pruebas aprobadas**, 9,176 segundos: recomposición con pista destruida, cierres repetidos y guardia persistente de cancelación.
- API: **350 pruebas, 348 aprobadas, 2 omitidas por plataforma, cero fallos**.
- Transporte real después de migrar: **24 mensajes únicos**, foto, audio WAV, sticker y vídeo MP4; respuestas conservadas; cuatro reintentos con el mismo ID sin duplicados; ambos destinatarios verificaron todos los IDs. **40 peticiones, máximo 731 ms** en esta muestra.
- Canal real de llamadas: seis ciclos, uno con cancelación anterior a la invitación, **12 eventos recibidos**, máximo 53 ms. Este ensayo usa WebSocket y HTTP desde el PC; no mide FCM con el teléfono cerrado ni audio/vídeo físico.
- Las cuentas ficticias propias usadas por los ensayos de transporte y llamadas se borraron al finalizar.
- Pruebas web locales: 21 suites y 93 pruebas aprobadas. Su despliegue y comprobación externa se documentan por separado.

Evidencias sin claves ni tokens: [directorio de resultados](../evidencias-ssd-llamadas-2026-10-01/).

## Validaciones pendientes, por prioridad

1. **Cierre inesperado físico:** capturar stacktrace del Motorola/tablet y repetir colgar llamada, salir de sala y terminar LIVE en dos redes, con la APK nueva.
2. **Llamadas fuera de la app:** timbre, aceptar/rechazar y cancelación con pantalla bloqueada; distinguir realtime de entrega FCM y restricciones del fabricante.
3. **Mensajería nativa:** probar todas las opciones primero en conversación individual, después grupos y comunidades; carga pesada, pérdida de red, reintento, cambio de cuenta, audio y respuestas multimedia. El transporte API comprobado no certifica cada interacción visual.
4. **LIVE/salas:** cámara del anfitrión e invitado, micrófono, compartir pantalla, avatar sin cámara y reconexión; medir FPS y retardo en datos móviles frente a Wi-Fi. Los 60 FPS siguen sin estar certificados.
5. **SSD:** comprobar arranque completo y hacer una restauración aislada del nuevo respaldo. Se verificó servicio tras el cambio, sin borrar la copia anterior.
6. **Web:** autenticación y avisos publicados en Vercel y comprobados externamente; véase la [auditoría 38](38-web-autenticacion-avisos-2026-10-01.md). Continúa la paridad de todos los módulos. No se declara cerrada la preparación completa para producción.

Para la prueba física, conectar Motorola y tablet desbloqueados, con dos cuentas diferentes y datos móviles/Wi-Fi respectivamente. El usuario inicia sus sesiones; no se necesitan sus contraseñas para operar ADB ni estar logueado localmente en la mini PC para acceder por SSH.
