# 149 · Host, respaldo y mantenimiento acotado

Estado del 15 de noviembre de 2026: mantenimiento preparado, **paquetes del host
todavía sin actualizar**. La revisión Android continúa primero. Este bloque no
cierra los módulos móviles ni acredita capacidad de marketing masivo.

## Lectura actual de Ubuntu

La consulta de las 14:25 UTC conserva la API de 147: imagen
`novvin-api:media147-deps-20261115`, identificador de imagen
`sha256:19354f261e64d364ec8e99ba82c914fa9b43e9b74a7ba4384ab59723b840e0cf`,
inicio `2026-11-15T05:56:19.448340674Z`, saludable, cero reinicios y sin OOM.
El host tiene unos 11 GiB de memoria disponible, 102 GiB libres en la raíz y
ninguna unidad fallida. Es una instantánea; no demuestra capacidad máxima ni
ausencia de latencia en otros momentos. No se actualizó el índice APT durante
esta consulta.

APT propone nueve actualizaciones instalables y aplaza por *phasing*
`distro-info`, `python3-distro-info` y `snapd`. Esto explica la diferencia entre
los doce paquetes actualizables de 147 y los nueve de su simulación de upgrade.

| Paquete | Antes | Objetivo preparado |
| --- | --- | --- |
| docker-ce, docker-ce-cli, docker-ce-rootless-extras | 5:29.9.2-1~ubuntu.24.04~noble | 5:29.9.3-1~ubuntu.24.04~noble |
| containerd.io | 2.4.2-3~ubuntu.24.04~noble | 2.4.2-4~ubuntu.24.04~noble |
| docker-buildx-plugin | 0.38.1-1~ubuntu.24.04~noble | 0.39.0-1~ubuntu.24.04~noble |
| docker-compose-plugin | 5.6.1-1~ubuntu.24.04~noble | 5.7.1-1~ubuntu.24.04~noble |
| libsqlite3-0 | 3.45.1-1ubuntu2.8 | 3.45.1-1ubuntu2.9 |
| nodejs | 22.23.4-1nodesource1 | 22.23.5-1nodesource1 |
| tailscale | 1.104.6 | 1.104.7 |

`sudo -n true` devuelve `sudo: a password is required`. La autorización del
propietario para actualizar existe; falta autenticación administrativa real del
sistema. El asistente no ha ejecutado instalación, cambios del daemon, reinicios
ni cambios de red en este bloque.

## Respaldo actual y ensayo

Se verificó el manifiesto completo del respaldo
`/home/alvaro/conectate-backups/20261115T082639Z`, cuya metadata termina a las
08:33:51 UTC: dump PostgreSQL, roles sin contraseñas, storage, datos de IA y
Compose. Las cinco huellas coinciden.

Entre 14:35:34 y 14:36:07 UTC, `restore-drill-isolated.sh` restauró **265 tablas**
en un PostgreSQL temporal propio, sin red, puertos publicados ni volúmenes
duraderos. El script retiró únicamente su contenedor etiquetado. La API conservó
identificador, imagen, hora de inicio, cero reinicios y estado saludable.

El ensayo usa `--no-owner --no-privileges`: comprueba restauración de esquema y
datos. La restauración de propietarios/ACL, de storage y el arranque de la
aplicación siguen pendientes. Verificar las huellas del archivo de storage no
equivale a extraerlo o probar una aplicación restaurada.

## Procedimiento preparado

`infrastructure/local-fallback/scripts/host-maintenance149.py` admite una revisión
sin privilegios por defecto y una aplicación explícita con root. El plan fija los
nueve paquetes y versiones anteriores/objetivo, la imagen de API y el dump
restaurado. Caduca a las 24 horas. Su comprobación se ejecutó en Ubuntu con éxito:
versiones instaladas, simulación exacta, disponibilidad de dieciocho versiones
anteriores/objetivo en APT, respaldo y `/ready`.

La aplicación vuelve a comprobar el plan bajo un bloqueo privado de root,
actualiza índices y repite la revisión. Conserva las versiones anteriores y
nuevas de los paquetes antes de modificar el sistema; si no puede obtenerlas,
se detiene. Guarda la configuración Docker previa y valida la nueva antes de
activar `live-restore` con una recarga. Preserva las demás opciones del daemon.
Solo entonces instala los nueve objetivos fijados. Los cambios de versión mayor
o menor de Docker, paquetes adicionales y eliminaciones se rechazan.

La guía de [Docker sobre live restore](https://docs.docker.com/engine/daemon/live-restore/)
explica su alcance para actualizaciones de parche y la recarga del daemon. Su uso
reduce interrupciones; la continuidad de los servicios necesita comprobarse
después. El procedimiento contrasta los contenedores previos y posteriores,
sus imágenes, horas de inicio, reinicios, salud, `dpkg --audit`, unidades fallidas
y HTTPS público. Conserva diagnósticos y paquetes para una reversión dirigida.
No reinicia automáticamente el host ni elimina paquetes o respaldos.

Dieciocho pruebas del procedimiento aprobaron en Linux, sin omisiones. Incluyen
cambios de alcance/versión, planes vencidos, gate de root, opciones conservadas,
fallo del validador Docker, configuración modificada en paralelo, JSON inválido
y un enlace simbólico real. En Windows aprobaron diecisiete casos y se omitió el de
enlace POSIX. Estas pruebas y el preflight **no son una ejecución de upgrade**.

La candidata revisada está en
`/home/alvaro/novvin-host149-20261115T144815Z`.
La huella del plan es
`b750de69eeb74a0ff56a5f3cdd16a1f6340d2d9c5505dccfda58e133d9c93d08`.
Desde una consola Ubuntu del servidor, el propietario puede autenticar la tarea
ya autorizada con:

```bash
sudo systemd-run --unit=novvin-host149-20261115 --collect --service-type=exec \
  /usr/bin/python3 \
  /home/alvaro/novvin-host149-20261115T144815Z/infrastructure/local-fallback/scripts/host-maintenance149.py \
  --plan /home/alvaro/novvin-host149-20261115T144815Z/plan149.json \
  --approved-sha256 b750de69eeb74a0ff56a5f3cdd16a1f6340d2d9c5505dccfda58e133d9c93d08 \
  --apply
```

La contraseña se introduce únicamente en `sudo` en esa consola. La tarea conserva
su ejecución aunque se cierre la conexión SSH. Su recibo queda bajo
`/var/backups/novvin-host149-<fecha>/result.json`. La instalación puede quedar
parcial si APT falla; el recibo distingue inicio de instalación, resultado
confirmado y estado desconocido. La verificación independiente posterior sigue
pendiente de esta ejecución.

## Continuación móvil y trazabilidad

La conexión del teléfono, el primer cuadro de vídeo, el desplazamiento, los
reintentos y la caché autenticada se validan aparte en QA145. El enlace Ethernet
y la descarga disponible no prueban la subida útil hacia clientes, carga
inmediata ni equivalencia con TikTok. Se conserva la comparación Ubuntu/R2 de
147 y ambas rutas mientras se completa esa comprobación.

Los requisitos `REQ-295`, `REQ-327` y `REQ-329` conservan sus pendientes de
inventario de conexiones, infraestructura y SLI/SLO/capacidad en la matriz de
trazabilidad. La evidencia técnica de este bloque se conservará en
`docs/quality/evidence/149-host-maintenance.json`; la revisión integral sigue
abierta.
