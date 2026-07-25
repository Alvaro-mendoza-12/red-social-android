# Auditoría de producción de Conéctate

**Fecha de cierre técnico:** 2026-07-19  
**Rama revisada:** production-stabilization-full-audit  
**Commit base:** 598fd622744abff07f6662ff6685018a16ca8dea  
**Release desplegado en mini-PC:** conectate-mini-pc-20260719T070245Z  
**Servidor:** mini-PC Ubuntu, Tailscale 100.91.84.2  
**Conclusión obligatoria:** **NO APTO PARA PRODUCCIÓN**

Este documento registra únicamente resultados observados mediante compilación, pruebas
automatizadas, llamadas reales al servidor, consultas a PostgreSQL, instalación de APK
release de QA en emulador y comprobaciones visuales. No se considera verificada una
función que solo fue leída en código.

## 1. Resumen ejecutivo

Se corrigió y desplegó la causa principal de las imágenes y videos que se subían pero no
aparecían: URLs históricas no canónicas, persistencia incompleta del perfil, tratamiento
inconsistente de URI/MIME y una llamada Kotlin incompatible que producía
"No static method uploadFileFromUri...$default". También se corrigieron registro con
avatar, portada y avatar de perfil, medios en mensajes, grupos, comunidades, publicaciones,
momentos y retos, video, borrado de archivos y actualización inmediata del perfil.

El canario real del servidor midió avatar 301 ms, portada 205 ms, guardado de perfil
181 ms y video 268 ms. En la APK release de QA, avatar y portada nuevos quedaron visibles
al regresar al perfil en un máximo observado de 2.991 ms, persistieron tras cierre forzado,
arranque en frío y reinstalación, y sus bytes descargados coincidieron por SHA-256.

El panel administrativo fue validado visualmente en Android: aparece para una cuenta
administradora, carga resumen, suspendidos, reportes y videos, y muestra estadísticas
reales. La misma API devuelve 403 a usuarios normales. La cuenta temporal y todos los
datos QA se eliminaron al terminar.

El dominio web público fue reasignado al deployment Vercel validado. Su descriptor de
backend, la raíz y dos rutas SPA profundas responden 200 y el backend descubierto devuelve
ready con FCM configurado.

El backend principal está desplegado y saludable. El cierre detectó además dos
supervisores duplicados del módulo Live que habían acumulado más de 86.000 reinicios y
432 MB de logs. Se conservó PM2, que era el supervisor estable, se deshabilitaron las
unidades duplicadas y se instaló rotación diaria. Live continuó respondiendo 200 sin
interrupción.

El mini-PC fue reiniciado realmente el 19 de julio a las 10:56:25 -05. Docker, PM2,
túneles y timers se recuperaron, pero el arranque reveló una carrera entre el túnel y
Docker Compose. La causa quedó corregida y desplegada; el override de la unidad de sistema
fue aplicado con sudo y `conectate-stack.service` quedó active con estado 0.

No se puede certificar producción porque no se instaló una APK con firma de producción en
dos teléfonos físicos, no se ejecutaron llamadas, cámara, micrófono, notificaciones y
pantalla compartida entre esos dispositivos, falta un reboot de confirmación posterior al
hotfix y el disco físico dedicado a backups presentó errores de E/S.

## 2. Arquitectura validada

| Capa | Implementación observada | Estado |
|---|---|---|
| Android | Kotlin, Jetpack Compose, Hilt, Room esquema 51, Ktor, Coil y WebRTC | Compila y APK release QA ejecutada |
| Web | React y Vite | Lint, 63 pruebas y build aprobados |
| API | Node.js, rutas REST y compatibilidad PostgREST | Desplegada y saludable |
| Base de datos | PostgreSQL 16 | Saludable, respaldo y restauración verificados |
| Archivos | Volumen Docker y tabla fallback_storage_objects | Descarga, rango, SHA y persistencia aprobados |
| Realtime | Gateway Node y publicación interna | Pruebas y health check aprobados |
| Señalización | Node/Socket.IO para llamadas y Live | Health y seguridad aprobados |
| Edge | Nginx en contenedor | Ready público y local en 200 |
| TURN | Coturn 4.6.3-r3 | Healthy y prueba STUN local aprobada |
| IA | Servicio Novvin en contenedor | Saludable |
| Live separado | Backend y frontend Node supervisados por PM2 | Local y público en 200 |
| Acceso público | Cloudflare Quick Tunnel | Operativo, pero URL efímera |

Endpoints observados al cierre:

- API principal: https://recognition-milk-overall-western.trycloudflare.com
- Live: https://dispatch-negotiation-lung-laws.trycloudflare.com
- Web: https://red-social-android.vercel.app
- El TXT de conectate-alvaro.duckdns.org apuntaba al túnel principal vigente.

## 3. Copias de seguridad realizadas

### Código y configuración local

- Ruta: .codex-backups/production-audit-20260717T201306Z
- Contiene bundle Git, árbol fuente, parche del worktree, índice, inventario y seis copias
  de configuración sensible.
- Archivos fuente inventariados: 1.896.
- repository.bundle: 58.880.768 bytes.
- source-tree.tar.gz: 40.247.844 bytes.
- working-tree.patch: 9.549.560 bytes.
- Se restauraron y compararon seis archivos representativos; todos los hashes coincidieron.
- El metadato de verificación registra verification=passed.

### Datos del backend principal

- Ruta principal: /home/alvaro/conectate-backups/20260719T115805Z
- Copia externa: /mnt/conectate-backup/conectate-backups/20260719T115805Z
- PostgreSQL: 1.825.373 bytes.
- Storage: 1.865.811.070 bytes.
- IA: 4.695.842 bytes.
- Incluye docker-compose.yml, metadata.json y SHA256SUMS.
- SHA-256, listado tar y catálogo pg_restore fueron verificados en ambos discos.
- El simulacro de restauración recuperó 117 tablas.
- Tras el reboot, el disco dedicado `/dev/sdd1` no montó por errores físicos de E/S.
- Se creó una copia alterna completa en
  `/mnt/conectate-media/conectate-storage/.conectate-backups`: 77 GB, 55 directorios,
  enlace `latest` relativo y los cuatro SHA-256 del último respaldo aprobados.

### Live y supervisión

- Ruta principal: /home/alvaro/conectate-backups/20260719T130327Z-live-supervisor
- Copia externa: /mnt/conectate-backup/conectate-backups/20260719T130327Z-live-supervisor
- Tamaño: 4,9 MB.
- Incluye código y SQLite de Live, variables de entorno, unidades systemd, dump PM2 y
  estado PM2.
- Todos los hashes se verificaron tanto en origen como en el disco externo.

El temporizador conectate-sync-external-backups.timer quedó habilitado y activo cada seis
horas. El enlace external/latest fue corregido para usar un destino relativo y el script
ahora usa el disco de medios sano como destino alterno cuando los discos dedicados no
montan. Esta copia de emergencia no sustituye el reemplazo del disco físico averiado.

## 4. Errores encontrados por gravedad

### SEC-001 - Suspensión evadible entre canales

**Área:** autenticación, PostgREST, realtime y señalización.  
**Gravedad:** CRÍTICO.  
**Reproducción:** suspender una cuenta y reutilizar su token contra REST, realtime o
señalización. Antes del cambio algunos canales aceptaban la sesión.  
**Causa raíz:** la moderación se comprobaba en rutas administrativas, no en una política
central aplicada a cada canal autenticado.  
**Consecuencia:** una cuenta suspendida podía seguir usando funciones protegidas.  
**Solución aplicada:** guardia central de estado de cuenta y validación compartida en API,
compatibilidad PostgREST, realtime y signaling.  
**Archivos:** infrastructure/local-fallback/api/src/account-status.js,
infrastructure/local-fallback/api/src/server.js,
infrastructure/local-fallback/api/src/postgrest-compat-routes.js,
infrastructure/local-fallback/realtime-gateway/src/server.js y signaling-server/index.js.  
**Prueba y resultado:** canario de producción con administrador, usuario normal y usuario
suspendido; login, token, REST, realtime y signaling fueron bloqueados durante la
suspensión y restaurados al levantarla.  
**Evidencia:** verify-admin-suspension.js y pruebas account-status/postgrest-security,
aprobadas.

### IMG-001 - Medios persistidos con URLs antiguas o no resolubles

**Área:** perfil, publicaciones, mensajes, grupos, comunidades, momentos y videos.  
**Gravedad:** ALTO.  
**Reproducción:** guardar una imagen con un dominio histórico, volver al perfil o abrir un
medio importado; el registro existía pero la interfaz mostraba inicial, espacio vacío o
pantalla negra.  
**Causa raíz:** se persistían URLs absolutas ligadas a endpoints anteriores y cada
repositorio resolvía las rutas de forma distinta.  
**Consecuencia:** contenido válido parecía perdido y portadas/avatares no aparecían.  
**Solución aplicada:** canonicalización central al leer y escribir, reparación masiva de
1.389 filas y resolución uniforme de URL pública.  
**Archivos:** UbuntuStorageUrl.kt, LocalFallbackApiClient.kt,
storage-url-canonicalization.js, canonicalize-storage-urls.js, data-routes.js,
postgrest-compat-routes.js e import-supabase-storage.js.  
**Prueba y resultado:** diagnóstico final encontró cero filas todavía asociadas a los
dominios obsoletos; imágenes históricas y descargas de video por rango respondieron.  
**Evidencia:** canarios verify-media-persistence.js y verify-media-bucket-matrix.js.

### IMG-002 - Fallo Kotlin No static method uploadFileFromUri...$default

**Área:** subida Android de videos e imágenes.  
**Gravedad:** ALTO.  
**Reproducción:** seleccionar un video y pulsar Publicar Video en la APK afectada.  
**Causa raíz:** llamada binaria a un método Kotlin con parámetros por defecto cuya firma
ya no coincidía con la clase empaquetada.  
**Consecuencia:** la subida se detenía antes de enviar el archivo.  
**Solución aplicada:** API de subida con argumentos explícitos, validación uniforme de URI,
MIME y tamaño, y actualización de todos los consumidores.  
**Archivos:** StorageService.kt, MediaUploadGuard.kt, MediaMimeResolver.kt,
VideoRepository.kt, VideoUploadScreen.kt y LocalFallbackApiClient.kt.  
**Prueba y resultado:** R8 release completó; video real subido en 268 ms, descarga completa
y rango de 1.024 bytes correctos, SHA exacto y metadata persistida.  
**Evidencia:** captura del error original aportada por el usuario y canario de medios final.

### PROF-001 - Avatar y portada tardaban y no se reflejaban

**Área:** edición de perfil y registro.  
**Gravedad:** ALTO.  
**Reproducción:** elegir avatar o portada, guardar, esperar entre 7 y 10 segundos y volver
al perfil; se conservaba la inicial o la portada anterior.  
**Causa raíz:** el guardado podía confirmar antes de consolidar URL canónica y estado del
perfil, además de cachés y reconciliación incompleta de identidad.  
**Consecuencia:** falsa confirmación de éxito y percepción de pérdida de archivos.  
**Solución aplicada:** persistencia de las URLs resultantes de cada subida, actualización
optimista, invalidación/recarga del perfil y unión consistente de identidad local/remota.
El registro completa la asociación del avatar después de crear la cuenta.  
**Archivos:** AuthRepository.kt, UserRepository.kt, ProfileViewModel.kt,
EditProfileScreen.kt, RegisterScreen.kt, CircularProfileImage.kt, profile-merge.js y
storage-routes.js.  
**Prueba y resultado:** Android release QA mostró avatar y portada nuevos en hasta
2.991 ms; persistieron después de force-stop, inicio en frío y reinstalación. Los archivos
de 134.434 y 88.081 bytes coincidieron por SHA con los seleccionados.  
**Evidencia:** conectate-after-profile-save.png y conectate-cold-profile.png.

### ACC-001 - PROFILE_NOT_FOUND después del alta o al abrir perfil

**Área:** cuentas y perfiles.  
**Gravedad:** ALTO.  
**Reproducción:** crear o importar una cuenta y navegar a Perfil cuando los identificadores
local y remoto no coincidían.  
**Causa raíz:** búsquedas separadas por identificadores equivalentes sin una política de
fusión determinista.  
**Consecuencia:** una cuenta válida podía quedar sin pantalla de perfil.  
**Solución aplicada:** unión de perfiles por identidad normalizada y fallback controlado,
sin mezclar información entre usuarios.  
**Archivos:** profile-merge.js, AuthRepository.kt, UserRepository.kt,
LocalFallbackApiClient.kt y ProfileScreen.kt.  
**Prueba y resultado:** cuenta nueva abrió su perfil en APK release, conservó identidad
tras reinicio y se eliminó sin residuos; prueba profile-merge aprobada.  
**Evidencia:** conectate-admin-profile.png y pruebas de registro/perfil.

### IMG-003 - Medios negros o inconsistentes fuera del perfil

**Área:** chat, grupos, comunidades, social, momentos y retos.  
**Gravedad:** ALTO.  
**Reproducción:** enviar URI de galería, dibujo o evidencia con MIME incompleto; algunos
consumidores intentaban tratarlo como otro tipo de archivo o no podían resolverlo.  
**Causa raíz:** selección, MIME, bucket, nombre y URL se construían de forma diferente en
cada módulo.  
**Consecuencia:** imágenes negras, previews vacíos o archivos imposibles de abrir.  
**Solución aplicada:** selector y guardia de medios unificados, detección de MIME por
contenido/extensión, rutas de bucket coherentes y renderizado común.  
**Archivos:** UnifiedMediaPicker.kt, MediaPickerViewModel.kt, MediaMimeResolver.kt,
MessageMediaComposer.kt, CollectionMediaStore.kt, MomentRepository.kt,
CreateMomentScreen.kt, moments-routes.js y storage-routes.js.  
**Prueba y resultado:** matriz real de buckets y canarios de mensajería, colecciones,
social/momentos, reportes y videos aprobaron subida, lectura, autorización y borrado.  
**Evidencia:** verify-media-bucket-matrix.js, verify-messaging-collections.js y
verify-social-moments.js.

### ADM-001 - Panel administrativo ausente o con 403

**Área:** permisos y administración.  
**Gravedad:** ALTO.  
**Reproducción:** iniciar sesión con uno de los correos administradores, abrir Ajustes y
buscar el panel; antes podía no aparecer o responder 403.  
**Causa raíz:** desalineación entre correo autorizado, usuario local y fila de moderación.  
**Consecuencia:** administradores legítimos no podían revisar reportes o suspensiones.  
**Solución aplicada:** autorización por identidad normalizada, provisión de los tres
administradores y endpoint único de acceso.  
**Archivos:** admin-moderation-routes.js, postgrest-compat-routes.js,
SettingsViewModel.kt, SettingsScreen.kt, AdminPanelViewModel.kt y AdminPanelScreen.kt.  
**Prueba y resultado:** administrador 200, usuario normal 403; Android mostró el botón y
cargó Resumen, Suspendidos, Reportes y Videos con datos reales.  
**Evidencia:** conectate-admin-settings-bottom4.png y conectate-admin-panel.png.

### STO-001 - Propietario de objetos de almacenamiento incompleto

**Área:** almacenamiento y borrado de cuenta.  
**Gravedad:** MEDIO, parcialmente pendiente.  
**Reproducción:** consultar objetos importados y borrar la cuenta propietaria; una parte
de los objetos históricos no tenía owner_id.  
**Causa raíz:** la importación original no conservó identidad suficiente en todos los
nombres y metadatos.  
**Consecuencia:** esos objetos no pueden asignarse ni limpiarse automáticamente por usuario
sin riesgo de borrar contenido ajeno.  
**Solución aplicada:** reconciliación por identidad verificable y propietario obligatorio
para nuevas subidas. Se resolvieron 362 de 569 filas nulas.  
**Archivos:** reconcile-storage-owners.js, import-supabase-storage.js,
storage-routes.js y postgrest-compat-routes.js.  
**Prueba y resultado:** 720 objetos finales, cero propietarios inválidos y 207 owner_id
nulos históricos todavía no atribuibles.  
**Evidencia:** consulta final directa a PostgreSQL.

### BAK-001 - Respaldo externo deshabilitado y enlace latest inválido

**Área:** continuidad operativa.  
**Gravedad:** ALTO.  
**Reproducción:** revisar el timer y resolver latest en el disco externo. El timer estaba
inactivo y el enlace copiaba una ruta absoluta del disco principal.  
**Causa raíz:** unidad no habilitada y rsync conservando un symlink absoluto.  
**Consecuencia:** el respaldo externo podía quedar desactualizado o apuntar al origen.  
**Solución aplicada:** timer activo cada seis horas, reescritura de latest como enlace
relativo, verificación SHA posterior a cada copia y destino alterno en el disco de medios.  
**Archivos:** scripts/conectate-sync-external-backups.sh y unidades asociadas.  
**Prueba y resultado:** sincronización completa de 77 GB y 55 directorios, hashes válidos,
latest apunta a 20260719T115805Z y el nuevo respaldo Live quedó incluido.  
**Evidencia:** Result=success y ExecMainStatus=0.

### BAK-002 - Disco dedicado de backups con errores físicos

**Área:** continuidad operativa.  
**Gravedad:** ALTO, ABIERTO.  
**Reproducción:** durante el reboot, el kernel registró errores de E/S sobre `/dev/sdd` y
fsck terminó con estado 4; `/mnt/conectate-backup` no montó.  
**Causa raíz:** fallo de lectura del dispositivo ext4 `CONECTATE-BACKUP`, no un problema de
la aplicación.  
**Consecuencia:** la copia dedicada previa no está disponible y ya no existe redundancia
independiente para el disco de medios.  
**Solución aplicada:** no se ejecutó una reparación destructiva remota; se sincronizaron
los 77 GB al disco externo sano de medios y se verificó el último respaldo por SHA-256.  
**Prueba y resultado:** timer exitoso, origen y destino de 77 GB, 55 directorios y hashes
de PostgreSQL, storage, IA y compose correctos.  
**Pendiente:** reemplazar o diagnosticar físicamente `/dev/sdd` y recrear una copia en un
segundo medio independiente.

### LIVE-001 - Dos supervisores competían por los puertos Live

**Área:** Live y servidor Ubuntu.  
**Gravedad:** ALTO.  
**Reproducción:** systemctl --user mostraba backend/frontend en auto-restart; los logs
registraban EADDRINUSE en 4100 y 4174.  
**Causa raíz:** PM2 ya restauraba ambos procesos al arranque y dos unidades systemd de
usuario intentaban iniciar copias adicionales.  
**Consecuencia:** más de 86.000 reinicios, consumo continuo de CPU y 432 MB de logs.  
**Solución aplicada:** respaldo verificable, conservación de PM2, deshabilitación de las
dos unidades duplicadas, compresión de evidencia e instalación de rotación diaria.  
**Archivos:** scripts/conectate-live-logrotate.conf,
scripts/conectate-live-logrotate.service y scripts/conectate-live-logrotate.timer.  
**Prueba y resultado:** PM2 mantiene dos procesos online con cero reinicios; health local
y público 200; logs reducidos de 432 MB a 23 MB; timer habilitado y activo.  
**Evidencia:** respaldo 20260719T130327Z-live-supervisor y estado PM2/systemd.

### DATA-001 - Cuentas sintéticas de auditoría en producción

**Área:** datos de prueba.  
**Gravedad:** MEDIO.  
**Reproducción:** buscar correos module1.*, codex.* y android-admin-smoke.*.  
**Causa raíz:** canarios anteriores finalizaron sin ejecutar limpieza completa.  
**Consecuencia:** estadísticas alteradas y datos QA persistentes.  
**Solución aplicada:** eliminación transaccional de cuatro cuentas y sus relaciones,
además de autoeliminación de la cuenta Android administrativa.  
**Archivos:** infrastructure/local-fallback/scripts/cleanup-test-accounts.sql.  
**Prueba y resultado:** synthetic_users=0, moderación temporal=0 y storage temporal=0.  
**Evidencia:** consulta final PostgreSQL; usuarios finales=69.

### UI-001 - Título superpuesto y aviso de predictive back

**Área:** interfaz Android.  
**Gravedad:** BAJO.  
**Reproducción:** abrir Mensajes en 720x1280; el título podía solaparse con iconos y Android
advertía sobre back invocado.  
**Causa raíz:** encabezado dibujado en overlay sin espacio reservado y manifest sin opt-in.  
**Consecuencia:** texto truncado y warning de plataforma.  
**Solución aplicada:** encabezado en Row con dimensiones estables y
enableOnBackInvokedCallback=true.  
**Archivos:** ConversationsScreen.kt y AndroidManifest.xml.  
**Prueba y resultado:** captura final muestra "Bandeja de entrada" completa; lint release
sin hallazgos y warning de predictive back ausente.  
**Evidencia:** conectate-final-inbox.png.

### REL-001 - No existe firma release real

**Área:** publicación Android.  
**Gravedad:** ALTO, ABIERTO.  
**Reproducción:** revisar configuración de signing y artefactos. No existe
keystore.properties con una llave privada de producción.  
**Causa raíz:** las credenciales de firma de la aplicación no están disponibles en el
workspace.  
**Consecuencia:** no se puede generar una APK/AAB actualizable y atribuible a producción.  
**Solución aplicada:** se generó APK release sin firmar y una copia firmada solo con llave
debug para QA. No se presentó como artefacto de producción.  
**Archivos:** app/build.gradle.kts y keystore.properties.example.  
**Prueba y resultado:** apksigner validó v2/v3 en la copia QA y confirmó certificado
debug. El bloqueo permanece.  
**Evidencia:** hashes registrados en la sección 11.

### RTC-001 - Flujos físicos de llamada y captura no certificados

**Área:** WebRTC, audio, cámara, salas de voz y pantalla compartida.  
**Gravedad:** ALTO, ABIERTO.  
**Reproducción:** requiere dos teléfonos físicos en redes distintas y permisos reales.  
**Causa raíz:** no hubo dos dispositivos físicos disponibles para esta auditoría.  
**Consecuencia:** no existe evidencia suficiente de cámara frontal/trasera, micrófono,
altavoz, foreground service, cambio de red, bloqueo de pantalla, TURN y reconexión real.  
**Solución aplicada:** compilación, análisis, health checks y pruebas de señalización; no
se inventó una certificación física.  
**Archivos:** WebRTCManager.kt, CallRepository.kt, VoiceRoomRepository.kt,
signaling-server/index.js y docker-compose.yml.  
**Prueba y resultado:** pruebas automatizadas aprobadas, pero la prueba física sigue
pendiente.  
**Evidencia:** ausencia explícita de dispositivos físicos en el entorno.

### OPS-001 - Carrera entre arranque del stack y cambio de URL pública

**Área:** recuperación ante reinicio.  
**Gravedad:** ALTO, CORREGIDO; REBOOT DE CONFIRMACIÓN PENDIENTE.  
**Reproducción:** en el reboot real, el túnel obtuvo una URL nueva y recreó la API mientras
`conectate-stack.service` esperaba su health; Compose salió 1 aunque Docker recuperó los
contenedores por su política de restart.  
**Causa raíz:** la unidad de usuario no puede ordenarse directamente contra una unidad de
sistema y ambos procesos mutaban Compose sin exclusión mutua.  
**Consecuencia:** la aplicación volvió, pero systemd quedó en estado failed y el host en
degraded.  
**Solución aplicada:** bloqueo compartido para Compose, espera explícita del estado del
stack, aplicación de `PUBLIC_BASE_URL` a API, realtime y signaling, confirmación `/ready`,
iniciador con tres reintentos y helper root auditable.  
**Archivos:** start-stack.sh, conectate-cloudflare-tunnel.sh,
apply-root-ops-hotfix.sh, deploy-stack-safely.sh y unidades asociadas.  
**Prueba y resultado:** reboot real confirmado con boot ID
`6f99343e-05ca-407a-8730-1dfff7acb626`; siete contenedores sanos y PM2 2/2 online. La
prueba de contención hizo esperar al túnel 25 segundos y finalizó con HTTP 200.  
**Cierre aplicado:** el helper root instaló el drop-in; la unidad quedó
active/exited, ExecMainStatus=0 y completó el primer intento en dos segundos.  
**Pendiente:** repetir un reboot controlado para certificar el arranque completo con el
hotfix ya activo.

### OPS-002 - Disco NTFS opcional no monta

**Área:** almacenamiento de archivo.  
**Gravedad:** MEDIO, ABIERTO.  
**Reproducción:** systemctl --failed muestra mnt-conectate-archive.mount.  
**Causa raíz:** el volumen NTFS `/dev/sdc1` no pudo montarse con ntfs3.  
**Consecuencia:** el disco de archivo opcional no está disponible.  
**Solución aplicada:** no se ejecutó reparación destructiva sin privilegios ni copia del
contenido NTFS.  
**Archivos:** configuración fstab del host, no modificada.  
**Prueba y resultado:** el mount continúa fallido; no bloqueó el boot por `nofail`.  
**Evidencia:** journal del reboot y estado de la unidad de montaje.

### WEB-001 - Descriptor y alias público apuntaban a un deployment antiguo

**Área:** web y descubrimiento del backend.  
**Gravedad:** ALTO, CORREGIDO.  
**Reproducción:** `red-social-android.vercel.app/backend-config.json` devolvía una URL
Quick Tunnel del 15 de julio mientras DuckDNS ya publicaba la URL posterior al reboot.  
**Causa raíz:** el dominio público seguía asociado a un deployment de 49 días y faltaban
los scripts exigidos por la configuración remota de Vercel.  
**Consecuencia:** la web funcionaba mientras DNS-over-HTTPS respondiera, pero su fallback
podía intentar un host vencido.  
**Solución aplicada:** se añadieron `vercel:install` y `vercel:build`, se ejecutaron lint,
63 pruebas y build, se desplegó en Linux y se reasignó el alias público.  
**Archivos:** Conectate-web/package.json, public/backend-config.json y
src/core/runtimeBackend.ts.  
**Prueba y resultado:** deployment `dpl_FEpmKr8uSuPGnxBp7gJM8HzWoYp4` READY; descriptor,
raíz, `/app/messages` y `/app/profile` en HTTP 200; descriptor apunta a la URL vigente y
su `/ready` confirma FCM configurado. npm audit de producción: 0 vulnerabilidades.

## 5. Causas raíz consolidadas

1. Contratos de subida duplicados entre Android y backend, con firmas Kotlin y manejo MIME
   diferentes.
2. Persistencia de URLs absolutas dependientes de infraestructura histórica.
3. Identidades local, importada y remota sin una unión determinista en todos los flujos.
4. Autorización de suspensión y administración aplicada por ruta en lugar de una política
   central.
5. Importación de storage con metadatos de propietario incompletos.
6. Falta de cierre transaccional de algunos canarios QA.
7. Dos mecanismos de supervisión habilitados para el mismo módulo Live.
8. Ausencia de credenciales de firma y de laboratorio físico para completar la
   certificación release.

## 6. Correcciones aplicadas

- Subida única y explícita de URI, MIME, tamaño, bucket y nombre de objeto.
- URLs canónicas en Android, API, importación y compatibilidad PostgREST.
- Guardado inmediato de avatar/portada y refresco coherente del perfil.
- Asociación de avatar durante registro y fusión segura de perfiles.
- Soporte coherente de imágenes/videos en mensajes, grupos, comunidades, social, retos,
  reportes y videos.
- Borrado de cuenta con eliminación de metadata y archivos propios.
- Guardia central para cuentas suspendidas.
- Acceso administrativo normalizado y panel Android validado.
- Reconciliación de propietarios verificables y rechazo de ownership inválido nuevo.
- Saneamiento de cuentas y relaciones QA.
- Respaldo externo periódico y enlace latest relativo.
- Consolidación de Live bajo PM2 y rotación diaria de logs.
- Corrección visual del encabezado de mensajes y predictive back.

## 7. Archivos modificados

El worktree ya contenía cambios del usuario y de rondas anteriores. No se revirtió ni se
limpió ninguno. Git reporta 213 archivos tracked con diferencias, 37.834 inserciones y
3.637 eliminaciones, además de archivos nuevos. Los grupos directamente vinculados a esta
estabilización son:

- Android media/perfil: StorageService.kt, MediaUploadGuard.kt, MediaMimeResolver.kt,
  AuthRepository.kt, UserRepository.kt, ProfileViewModel.kt, RegisterScreen.kt,
  EditProfileScreen.kt, CircularProfileImage.kt y UbuntuStorageUrl.kt.
- Android módulos: repositorios y pantallas de mensajes, grupos, comunidades, social,
  momentos, retos, videos, reportes y perfil.
- Android administración: SettingsScreen.kt, SettingsViewModel.kt,
  AdminPanelScreen.kt y AdminPanelViewModel.kt.
- Backend: account-status.js, profile-merge.js, storage-routes.js,
  storage-url-canonicalization.js, canonicalize-storage-urls.js,
  reconcile-storage-owners.js, data-routes.js, postgrest-compat-routes.js,
  admin-moderation-routes.js, moments-routes.js y server.js.
- Realtime/señalización: realtime-gateway/src/server.js y signaling-server/index.js.
- Operaciones: docker-compose.yml, nginx/default.conf, scripts de backup, despliegue,
  verificación y limpieza.
- Cierre de esta ronda: ConversationsScreen.kt, AndroidManifest.xml,
  conectate-sync-external-backups.sh y los tres archivos conectate-live-logrotate.*.

La amplitud y mezcla del worktree sin commit es un riesgo de trazabilidad; no se hizo
commit porque no fue solicitado y existen cambios previos que no deben atribuirse ni
alterarse automáticamente.

## 8. Migraciones y saneamiento de datos

- Room quedó en esquema 51 y su JSON de esquema fue generado.
- Se canonicalizaron 1.389 referencias de almacenamiento.
- Se reconciliaron 362 propietarios de storage; quedaron 207 históricos sin evidencia
  suficiente para asignación segura.
- Propietarios inválidos finales: 0.
- Se eliminaron cuatro cuentas QA residuales y una cuenta temporal de validación Android.
- Administradores finales: 3.
- Usuarios finales: 69.
- Videos finales: 2.
- Objetos de storage finales: 720.
- No se aplicó una eliminación masiva ni una migración destructiva sin respaldo.

## 9. Pruebas ejecutadas

### Android

- gradlew testDebugUnitTest assembleRelease lintRelease
- Resultado: BUILD SUCCESSFUL en 31 min 33 s.
- Unitarias: 99/99, 0 fallos, 0 errores, 0 omitidas, 25 suites.
- Lint release: No issues found.
- R8/minify/shrink: aprobado.

### Backend API

- npm run check
- test:ai
- test:profile-merge
- test:data-routes
- test:storage
- test:scheduled-messages
- test:admin
- test:proximity
- test:security
- Resultado: todos aprobados.

### Realtime, signaling y web

- Realtime npm run check: aprobado.
- Signaling node --check index.js: aprobado.
- Web lint: aprobado.
- Web: 10 suites y 63 pruebas aprobadas.
- Web build: 1.714 módulos en 17,22 s.
- npm audit --omit=dev: 0 vulnerabilidades en API, realtime, signaling y web.

### Producción

- Canarios de registro, perfil, matriz de buckets, persistencia, mensajería/colecciones,
  videos, social/momentos, reportes, proximidad, Live, administración y suspensión.
- Restart controlado de Docker Compose y recuperación.
- SHA de imágenes/videos, lectura completa, rango HTTP y persistencia.
- Backup, verificación de catálogo y simulacro de restauración.
- Admin visual completo en APK release QA.

## 10. Dispositivos y versiones Android

| Entorno | Versión | Resultado |
|---|---|---|
| Galaxy_Nexus AVD | Android 36, google_apis, x86_64, 720x1280 | APK instalada y flujos perfil/admin aprobados |
| Pixel_6a Play Store AVD | Android 36 | Instalación bloqueada por solo 493 MB libres |
| Teléfono físico 1 | No disponible | Pendiente |
| Teléfono físico 2 | No disponible | Pendiente |

El bloqueo del Pixel_6a fue capacidad del AVD, no un fallo funcional de la APK. El
Galaxy_Nexus limpio tenía 4,9 GB libres.

## 11. Resultados de APK debug y release

### APK release sin firmar

- Ruta: app/build/outputs/apk/release/app-release-unsigned.apk
- Tamaño: 184.171.052 bytes.
- SHA-256: 46760E1537C7ED0B5BAAB1F92A66248CFB83752987A5F7A3C15480C0E7C7527B

### APK release de QA firmada con llave debug

- Ruta: app/build/outputs/apk/release/app-release-test-signed-debug-key.apk
- Tamaño: 184.271.715 bytes.
- SHA-256: 93F168C3CA209EA5297E24302239FB3282329DF7A34E13364F554BE2C84553BF
- Firma v2/v3 válida, certificado debug.

Metadatos: paquete conectate.com, versionCode 45, versionName 3.0.5, minSdk 24,
targetSdk 35 y cuatro ABI. La APK QA se instaló y ejecutó; lanzamiento cálido observado
en 4,85 s y sin Fatal Exception en los flujos realizados.

No existe APK firmada con certificado de producción. No se generó AAB porque el requisito
indica hacerlo solo después de aprobar release.

## 12. Resultados del servidor

- Ready principal local y público: HTTP 200.
- Seis contenedores principales: running/healthy y RestartCount=0.
- Coturn: running/healthy, RestartCount=0 y prueba STUN local aprobada.
- Docker: enabled/active.
- conectate-stack.service: enabled/active, drop-in correctivo cargado y ExecMainStatus=0.
- PM2: backend Live y frontend Live online, cero reinicios.
- Live local y público: HTTP 200.
- Web pública: deployment Vercel READY; raíz, rutas SPA y descriptor en HTTP 200.
- Reinicio físico del host: ejecutado; nuevo boot ID
  `6f99343e-05ca-407a-8730-1dfff7acb626` y runtime recuperado automáticamente.
- RAM: 14 GiB totales y aproximadamente 12 GiB disponibles durante la auditoría.
- Disco raíz: 218 GB, 99 GB usados y 109 GB disponibles antes del saneamiento de logs.
- Logs duplicados Live: reducidos de 432 MB a 23 MB.
- Timer de rotación Live: enabled/active, ejecución exitosa.
- Timer de copia externa: enabled/active, ejecución exitosa; 77 GB verificados en el
  destino alterno sano.
- Unidades fallidas: stack por carrera inicial, montaje NTFS opcional y fsck del disco
  físico de backups averiado.

## 13. Resultados de seguridad

- Usuario normal bloqueado del panel con 403.
- Administrador autorizado aceptado con 200.
- Cuenta suspendida bloqueada en login, token, REST, realtime y signaling.
- Token de cuenta eliminada devuelve USER_NOT_FOUND y login devuelve ACCOUNT_NOT_FOUND.
- Separación de ownership probada en storage y compatibilidad PostgREST.
- Pruebas de IDOR, filtros, mutaciones y rutas administrativas aprobadas en el conjunto
  test:security/postgrest-security.
- Cero vulnerabilidades npm de producción en los cuatro paquetes auditados.
- secrets.properties, llave debug y artefactos build están ignorados; solo los ejemplos
  están versionados.
- No se encontraron URLs runtime activas de Supabase/Hostinger, aparte de compatibilidad
  e historial no ejecutado.

Esto no sustituye un pentest externo ni certifica funciones físicas no ejecutadas.

## 14. Resultados de estrés y casos límite

Ejecutado:

- Solicitudes simultáneas e idempotencia cubiertas por pruebas de API.
- Token inválido, cuenta suspendida, cuenta borrada y acceso cruzado.
- Reinicio controlado de todo Docker Compose durante health checks.
- Descarga de video completa y por rango.
- Persistencia tras force-stop, arranque en frío y reinstalación.
- Limpieza tras borrar cuenta y objetos de almacenamiento.
- Poco almacenamiento reproducido en un AVD.

No ejecutado con evidencia suficiente:

- Carga sostenida con muchos usuarios reales.
- Handoff Wi-Fi/datos móviles en teléfonos físicos.
- Servidor PostgreSQL desconectado durante una escritura real de usuario.
- Cámara, micrófono y pantalla compartida bajo pérdida de red.
- Permisos revocados durante captura física.

## 15. Resultados E2E

### Perfil y medios Android

1. Cuenta temporal creada en servidor real.
2. Inicio de sesión desde APK release QA.
3. Perfil cargado con avatar y portada.
4. Selección de nuevos avatar y portada desde galería.
5. Guardado y regreso al perfil en hasta 2.991 ms.
6. URLs persistidas en backend.
7. Bytes descargados y SHA iguales a los originales.
8. Persistencia después de cierre forzado, arranque en frío y reinstalación.
9. Autoeliminación retiró cuatro medios, sin fallos de cleanup.

### Backend media

- Avatar 301 ms.
- Portada 205 ms.
- Guardado de perfil 181 ms.
- Video 268 ms.
- Rango de video: 1.024 bytes correctos.
- Persistencia y metadata: verdaderas.

### Administración

- Cuenta admin temporal con acceso API 200.
- Login Android, Perfil, Ajustes y botón Panel de Administrador.
- Resumen: 74 usuarios durante el canario, 2 videos y 0 suspendidos/reportes.
- Suspendidos, Reportes y Videos cargaron estados vacíos válidos.
- Sin 403, PROFILE_NOT_FOUND ni crash.
- Cuenta temporal eliminada; administradores finales regresaron a 3.

### Servidor y Live

- Stack principal recuperó tras restart.
- Live permaneció 200 mientras se retiraron supervisores duplicados.
- Backups principal y Live verificados originalmente en dos discos; tras el fallo físico,
  los 77 GB se verificaron también en el destino alterno sano.

## 16. Errores pendientes

1. La firma release y keystore quedan a cargo del responsable del proyecto.
2. No hay prueba en dos dispositivos físicos.
3. Llamadas, salas de voz, cámara, micrófono, altavoz y pantalla compartida no tienen
   certificación física.
4. Falta un reboot de confirmación con el hotfix de arranque ya activo.
5. El disco físico dedicado a backups presenta errores de E/S y debe reemplazarse.
6. El montaje NTFS opcional continúa fallido.
7. Quedan 207 objetos históricos con owner_id nulo no atribuible.
8. Coturn tiene health check y STUN local aprobado, pero falta validar relay desde dos
   redes físicas.
9. El worktree amplio continúa sin commit de release reproducible.

## 17. Riesgos pendientes

- Los dos accesos públicos usan Quick Tunnel y pueden cambiar al reiniciar cloudflared.
- DuckDNS TXT mitiga el descubrimiento del backend principal, pero no equivale a un
  dominio estable con túnel nombrado.
- Los 207 objetos sin propietario requieren investigación manual basada en evidencia; no
  deben asignarse ni borrarse por heurística.
- El reboot real detectó una carrera corregida y el drop-in está activo, pero aún no se
  repitió el boot completo con esa corrección instalada.
- La copia alterna comparte el disco de medios y no reemplaza un segundo disco físico
  independiente.
- Existen warnings Kotlin de deuda técnica aunque lint release no reporta errores.
- El tamaño de la APK universal es alto; conviene distribuir AAB por ABI cuando exista
  firma y se complete la aprobación.

## 18. Evidencias

### Capturas Android

- app/build/conectate-after-profile-save.png
- app/build/conectate-cold-profile.png
- app/build/conectate-final-inbox.png
- app/build/conectate-final-profile.png
- app/build/conectate-admin-settings-bottom4.png
- app/build/conectate-admin-panel.png
- app/build/conectate-admin-panel-reports.png
- app/build/conectate-admin-panel-videos.png

### Artefactos

- app/build/outputs/apk/release/app-release-unsigned.apk
- app/build/outputs/apk/release/app-release-test-signed-debug-key.apk
- app/build/reports/lint-results-release.html
- app/build/reports/tests/testDebugUnitTest/index.html

### Servidor

- /home/alvaro/conectate-backups/20260719T115805Z
- /mnt/conectate-backup/conectate-backups/20260719T115805Z (verificado antes del reboot;
  disco actualmente no montado)
- /home/alvaro/conectate-backups/20260719T130327Z-live-supervisor
- /mnt/conectate-backup/conectate-backups/20260719T130327Z-live-supervisor (verificado antes
  del reboot; disco actualmente no montado)
- /mnt/conectate-media/conectate-storage/.conectate-backups
- Release marker: /home/alvaro/conectate-local-fallback/.conectate-release
- Vercel deployment: dpl_FEpmKr8uSuPGnxBp7gJM8HzWoYp4

## 19. Checklist funcional

| Área | Evidencia real | Estado |
|---|---|---|
| Registro e inicio de sesión | Backend real y APK release QA | APROBADO |
| Cuenta eliminada/token revocado | API real | APROBADO |
| Avatar y portada | APK, backend, SHA, cold start y reinstalación | APROBADO |
| Perfil propio | APK release QA | APROBADO |
| Perfil ajeno/separación | Pruebas backend y repositorio | PARCIAL |
| Mensajes con medios | Canario backend/colecciones | PARCIAL, falta dos teléfonos |
| Grupos y comunidades | CRUD/media automatizado | PARCIAL, falta recorrido físico |
| Social, momentos y retos | Canario backend/media | PARCIAL |
| Subida y lectura de video | Backend real, SHA y rango | APROBADO |
| Editor/reproducción completa | Unitarias y emulador parcial | PARCIAL |
| Live backend/frontend | Health local/público y signaling | PARCIAL, falta emisión física |
| Llamadas WebRTC | Compilación y signaling | PENDIENTE FÍSICO |
| Salas de voz | Compilación y API | PENDIENTE MULTIUSUARIO |
| Pantalla compartida | Compilación | PENDIENTE FÍSICO |
| Notificaciones FCM | Proveedor configurado | PENDIENTE ENTRE DISPOSITIVOS |
| Panel administrativo | Rol admin/normal, API y Android visual | APROBADO |
| Suspensiones | API, REST, realtime y signaling | APROBADO |
| Web | Lint, 63 pruebas, build y deployment público | APROBADO |
| Backups y restore | Restore de 117 tablas y copia alterna SHA | PARCIAL, disco dedicado averiado |
| Arranque tras restart de stack | Producción | APROBADO |
| Arranque tras reboot del host | Boot real, carrera corregida y unidad activa | PARCIAL, falta reboot posterior al hotfix |

## 20. Estado final

**NO APTO PARA PRODUCCIÓN**

El backend desplegado está estable y los defectos de imágenes, videos, perfil, registro,
administración, suspensión y supervisión Live quedaron corregidos y probados en los
alcances descritos. El reboot real también quedó observado y produjo dos hallazgos nuevos.
No se declara aptitud mientras falten dispositivos físicos, pruebas entre dos teléfonos,
un reboot de confirmación y una segunda copia externa independiente.

Para cambiar el veredicto deben completarse, como mínimo:

1. Firmar la release con el certificado que gestionará el responsable del proyecto.
2. Instalar esa APK en dos teléfonos físicos y ejecutar la matriz de llamadas, cámara,
   micrófono, voz, notificaciones, cambio de red y pantalla compartida.
3. Ejecutar un reboot de confirmación y comprobar el arranque automático ya corregido.
4. Reemplazar el disco `/dev/sdd` y restaurar redundancia externa independiente.
5. Reparar o retirar formalmente el montaje NTFS opcional.
6. Crear un commit/tag de release reproducible después de separar los cambios del usuario.
7. Generar y validar el AAB únicamente después de aprobar los puntos anteriores.
