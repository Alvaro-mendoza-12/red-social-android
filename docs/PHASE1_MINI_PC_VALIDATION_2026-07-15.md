# Fase 1 - Despliegue y estabilidad de la mini PC

Fecha: 2026-07-15
Host: `conectate` (`100.91.84.2` por Tailscale)
Release desplegado: `conectate-mini-pc-20260715T073651Z`
Estado de Novvin: **actualizacion desplegada y verificada de extremo a extremo**

Estado de infraestructura: **no cerrada; requiere intervencion fisica/administrativa en discos**

## Resultado operativo

El backend principal permanece disponible y los contenedores `db`, `api`,
`ai-chatbot`, `realtime-gateway`, `signaling` y `edge` estan saludables. La URL
publica responde por Cloudflare Quick Tunnel y el descubrimiento dinamico se
publica en el TXT de DuckDNS. PostgreSQL y el volumen Docker de storage no se
recrearon durante el despliegue.

Novvin ejecuta el modelo propio `conectate-first-party-hybrid-v5`, dataset 5.
Su persistencia fue migrada desde el disco externo defectuoso a
`/home/alvaro/conectate-ai-data-primary` en el SSD interno. El bind activo fue
verificado en Docker.

## Cambios de estabilidad aplicados

- Despliegue por etapas con `deploy-stack-safely.sh`; la compilacion ocurre antes
  de reemplazar contenedores y cada servicio debe quedar saludable para avanzar.
- El despliegue reconstruye tambien `ai-chatbot` y excluye sus datos persistentes
  del paquete de fuentes.
- El watchdog supervisa Novvin ademas de API, base, realtime, signaling y edge.
- Las copias incluyen `ai-chatbot-data.tar.gz`, SHA-256 y validacion del indice.
- La API maneja la perdida de conexiones ociosas de PostgreSQL sin terminar el
  proceso y registra solo codigo, severidad y mensaje resumido.
- API, realtime y signaling usan locks reproducibles. Las imagenes finales
  reportaron cero vulnerabilidades con `npm audit --omit=dev`.
- Se actualizaron Firebase Admin 14.1, Axios 1.18.1 y dependencias parcheadas de
  Socket.IO dentro de sus lineas compatibles.

## Pruebas completadas

- QA estricto de produccion: 23 PASS, 0 WARN, 0 FAIL.
- Flujos cubiertos: auth, cambio de clave, perfil, storage de audio/video/imagen,
  publicaciones, comentarios, reacciones, notificaciones/FCM, mensajes directos,
  grupos, comunidades, videos, momentos, retos, rankings, salas de voz y borrado
  de objetos.
- Novvin por API autenticada: health, ayuda detallada de videos y conocimiento
  general, todos con modelo v5/dataset 5.
- Copia final interna `~/conectate-backups/20260715T073656Z`: PostgreSQL, storage,
  Novvin y compose con hashes correctos.
- Restauracion aislada: 107 tablas recuperadas y base temporal eliminada.
- Autorrecuperacion: Novvin fue detenido de forma controlada; el watchdog lo
  recupero en 120 segundos mientras `/ready` publico siguio en HTTP 200.
- Reinicio de PostgreSQL: `/ready` recupero en 3 segundos; la API mantuvo el mismo
  contenedor, la misma hora de inicio y cero reinicios.
- Realtime HTTP y handshake Socket.IO validos despues de reinicios.
- Carga publica moderada: 1,150/1,150 respuestas, p50 48.4 ms, p95 239.8 ms,
  p99 811.7 ms.
- Carga Novvin: 1,000/1,000 respuestas, p50 84.58 ms, p95 314.11 ms,
  p99 417.22 ms.

## Recursos observados

- CPU: 8 nucleos; load promedio final aproximado 1.00 / 1.11 / 1.30.
- RAM: 14 GiB totales, aproximadamente 1.8 GiB usados y 12 GiB disponibles.
- SSD principal: 218 GiB, 33% usado, 139 GiB disponibles.
- Novvin: limite Docker de 512 MiB y 0.75 CPU; memoria observada muy por debajo
  del limite.
- Ninguna carga se acerco al 65% de capacidad total de la mini PC.

## Incidente de disco y mitigacion

`/dev/sdd` (dispositivo USB identificado como `high speed`, particion ext4 montada
en `/mnt/conectate-backup`) devolvio errores de hardware y errores de lectura en
los sectores 203651160-203651416. No se ejecuto `fsck`, no se forzo un desmontaje
y no se borraron datos.

Mitigacion aplicada:

- Novvin fue restaurado desde una copia verificada al SSD interno.
- `conectate-sync-external-backups.timer` quedo `disabled/inactive` de forma
  reversible para no seguir leyendo o escribiendo `/dev/sdd`.
- `conectate-backup.timer` interno permanece `enabled/active`.
- Despues del aislamiento no aparecieron nuevos errores de E/S ni procesos en
  estado D durante las pruebas finales.

## Bloqueos para cerrar la fase

1. Apagar o intervenir fisicamente la mini PC para revisar cable, adaptador y
   alimentacion de `/dev/sdd`; reemplazar el dispositivo si corresponde.
2. Con privilegios administrativos y el disco desmontado, obtener SMART y revisar
   el filesystem. No ejecutar reparaciones sobre la particion montada.
3. Resolver el montaje heredado `/mnt/conectate-archive` (`/dev/sdc1`, NTFS), que
   sigue como la unica unidad fallida en `systemctl --failed`. Requiere `sudo` y
   diagnostico no destructivo antes de cambiar `/etc/fstab`.
4. Considerar `/dev/sdb1` (ext4, 1 TB, actualmente sin montar) como destino de
   copia alternativo despues de identificar y montar por UUID con permisos
   administrativos.
5. Rehabilitar la sincronizacion externa solo despues de verificar el disco:
   `systemctl --user enable --now conectate-sync-external-backups.timer`.

Hasta completar esos puntos, las copias internas son validas pero no existe una
segunda copia automatica confiable. La actualizacion de IA ya esta verificada,
pero esta deuda sigue registrada como riesgo operativo y no se inicia la Fase 2.

## Actualizacion acotada de Novvin v5

El 2026-07-15 se desplego `novvin-v5-20260715T182949Z` sin ejecutar el
despliegue general del stack. Solo se actualizaron el servidor de Novvin, las
rutas autenticadas de IA, sus pruebas, Compose/logging y la documentacion de
configuracion. Base de datos, storage, realtime, signaling y edge no fueron
recreados.

Antes de compilar se verifico la copia
`/home/alvaro/conectate-backups/20260715T182748Z`. Las imagenes anteriores se
etiquetaron como rollback y se guardo un tar de las fuentes reemplazadas. Una
validacion secundaria fallo por una plantilla incorrecta de `docker inspect`;
el rollback automatico restauro correctamente la fuente y la imagen anterior.
El corte se repitio con la comprobacion corregida y termino sin errores.

Evidencia final:

- paquete SHA-256
  `eb4de941887501b86d600069bcaeb803a6ed723c37b08b294d425a9b9785c25d`;
- fingerprint del dataset `4b55fd76b7dcd20e`, igual antes y despues;
- 35 intenciones, 430 muestras, 31 guias y 16 tarjetas conservadas;
- imagen IA
  `sha256:923f19098aaf3e68b246aceb8e332152fa815dc15ad54fa290b4370c9f97b601`;
- imagen API
  `sha256:f108b75757954b258f5ad51f5915abb5776ac2187acae69fe43d2d93e910c27a`;
- QA estricto posterior: 23 PASS, 0 WARN y 0 FAIL;
- 34 consultas observadas, 0 errores de Novvin y 0 reinicios;
- la consulta Android real quedo trazada a las `2026-07-15T19:15:00.120Z`
  como `perfil_editar`, modo `guided`, fuente v5 y 26 ms; la revision posterior
  encontro 0 errores y 0 reinicios en IA y API;
- logs JSON sin contenido de mensajes y rotacion 10 MiB x 3;
- limite de chat verificado: 30 respuestas 200 y la siguiente 429 con
  `Retry-After: 60`;
- copia posterior verificada:
  `/home/alvaro/conectate-backups/20260715T185242Z`.

El release recuperable se conserva en
`/home/alvaro/conectate-releases/novvin/20260715T182949Z`, junto con manifiesto,
paquete y referencias de rollback. `verification.json` agrega la evidencia web,
Android y de runtime; `MANIFEST_SHA256SUMS` valida correctamente el paquete, los
metadatos y esa evidencia.

## Clientes

- Android compila con `NovvinChatScreen`, `NovvinChatViewModel` y
  `LocalFallbackApiClient`; el contrato exige la fuente v5 y el QA de produccion
  valida sus endpoints. Se instalo exactamente
  `D:\red social - live-gifts\app\build\outputs\apk\debug\app-debug.apk`
  (205.427.450 bytes, SHA-256
  `A71A375201E463DEAADC1B00B51C99CED2AFFF70E8AB5CF481ED98DA732DAD5F`)
  en el AVD local `Medium_Phone_API_36.1`, Android 16, `emulator-5554`; no se uso
  ningun dispositivo fisico.
- En el E2E Android de produccion se inicio sesion desde la UI real, se abrio
  Novvin y se comprobo el aviso honesto de que usa conocimientos y reglas
  revisadas y no aprende automaticamente. La sugerencia `Editar mi perfil`
  devolvio una respuesta fundamentada con la ruta Perfil > Editar perfil,
  pasos para modificar y guardar los datos, y sugerencias relacionadas. La
  captura de evidencia quedo en
  `D:\red social - live-gifts\tmp\novvin-android-production.png`.
- Esta prueba valida el APK debug instalado contra produccion; no equivale a
  publicar una nueva APK en la tienda. Tras forzar el cierre y relanzar la app,
  la consulta y la respuesta completas reaparecieron al abrir Novvin. No hubo
  excepciones fatales ni ANR de `conectate.com`.
- Web incorpora `/app/messages/novvin`, historial por cuenta, detalle, reintento
  y UI responsive. Se publico el deployment
  `dpl_Aidp6J684PqLzpjapS43vmnVGgmq` y se verifico una consulta real en desktop y
  390 x 844, sin errores de consola ni overflow.
- El proyecto Vercel mantiene Vercel Authentication incluso en el alias de
  produccion. El E2E se realizo con el bypass de automatizacion. Si la web debe
  ser publica para usuarios finales, esa politica debe revisarse expresamente;
  no se desactivo durante este trabajo.

La cuenta desechable del E2E web, las dos cuentas del QA estricto y la cuenta
Android `codex.novvin.android.20260715.1411@example.local` fueron eliminadas de
forma dirigida; los conteos posteriores por correo e ID fueron cero. El APK de
prueba se desinstalo y el emulador se apago. No se inicio la Fase 2.
