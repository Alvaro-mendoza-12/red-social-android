# 06 — Operación en producción / runbook

Este runbook está diseñado para recuperar el servicio rápido sin adivinar.

## Conectarse por SSH

Desde Windows en la misma red:

```powershell
ssh -i "$env:USERPROFILE\.ssh\conectate_mini_ed25519" alvaro@192.168.18.22
```

Desde fuera de casa usando Tailscale:

```powershell
ssh -i "$env:USERPROFILE\.ssh\conectate_mini_ed25519" alvaro@100.91.84.2
```

Si la clave falla y pide password, revisar ruta real de la llave. En PowerShell, `$env:USERPROFILE` funciona; dentro de Ubuntu/bash no.

## Estado general

En Ubuntu:

```bash
~/bin/conectate-status
```

Debe mostrar:

```text
docker=active
stack=active
ingreso HTTPS activo (Tailscale Funnel actualmente)
READY LOCAL ok:true
READY PUBLIC ok:true
```

Al 30/09/2026, verificar además `tailscale funnel status` y consultar
`https://conectate.tail9b267c.ts.net/ready` desde una conexión pública.
El texto `cloudflare=active` de los scripts históricos no describe el ingreso
vigente. El documento 33 contiene las evidencias y límites de la auditoría actual.

## Identidad verificable del release

La salud del stack no demuestra por sí sola que esté ejecutando el código esperado.
Después de un despliegue, conserva el `commit` y el nombre de release que imprimió
`deploy-mini-pc-current.ps1` y ejecuta desde `~/conectate-local-fallback`:

```bash
STRICT_PRODUCTION=true \
EXPECTED_COMMIT="COMMIT_VALIDADO" \
EXPECTED_RELEASE="RELEASE_VALIDADO" \
EXPECTED_SOURCE_DIGEST="HUELLA_SHA256_VALIDADA" \
./scripts/verify-ubuntu-production.sh
```

El verificador compara el marcador `.conectate-release` con `/ready`, comprueba
el commit y la huella SHA-256 del artefacto instalado, Redis, el túnel nombrado,
storage y los recorridos funcionales. El despliegue sólo
confirma el marcador después de superar los health checks; si la recreación final
del API falla, restaura el marcador anterior. No sustituir `COMMIT_VALIDADO` ni
`RELEASE_VALIDADO`/`HUELLA_SHA256_VALIDADA` por valores inventados: deben provenir
del artefacto de release revisado y del resultado que imprime el despliegue. La
huella se calcula sobre los tarballs exactos transferidos, por lo que también
identifica cambios locales que todavía no tengan commit de Git.

## Recuperación rápida

Si la app no carga:

```bash
~/bin/conectate-restart
~/bin/conectate-status
```

Si `stack=failed`:

```bash
sudo systemctl reset-failed conectate-stack.service
sudo systemctl restart conectate-stack.service
sleep 20
~/bin/conectate-status
```

Si el origen público o WebSocket no responde:

```bash
sudo systemctl restart novvin-cloudflared.service
sleep 20
sudo systemctl status novvin-cloudflared.service --no-pager
~/bin/conectate-status
```

El servicio requiere que el túnel nombrado haya sido instalado siguiendo el
[runbook específico](../NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md). No sustituir
su hostname estable por un `trycloudflare.com` temporal durante un incidente.

Antes del primer despliegue con descubrimiento firmado, desde la laptop:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\configure-backend-discovery-signing.ps1
```

El comando genera la clave privada P-256 dentro de
`/home/alvaro/.config/novvin/` con modo `0600`, guarda su ruta en el `.env` del
servidor y copia únicamente la clave pública a Android/web. Después se debe
desplegar el backend y recompilar ambos clientes. Nunca copiar la clave privada
al repositorio, Drive, chat o AAB.

Ver DuckDNS TXT:

```bash
curl -fsS "https://dns.google/resolve?name=conectate-alvaro.duckdns.org&type=TXT"
```

El valor TXT vigente debe empezar con `novvin1:`. Una URL
`https://...trycloudflare.com` en texto plano indica configuración antigua y los
clientes endurecidos la rechazarán.

## Revisar contenedores

```bash
cd ~/conectate-local-fallback
docker compose ps
```

Logs API:

```bash
docker logs --tail=150 conectate-local-fallback-api-main
```

Después del primer despliegue de la imagen API endurecida, verificar que Node
ya no quede ejecutándose como root y que finalizó la migración única del
volumen:

```bash
docker compose exec -T api sh -ec '
  grep -E "^(Name|Uid|Gid):" /proc/1/status
  test "$(awk "/^Uid:/{print \$2}" /proc/1/status)" = "10001"
  test "$(awk "/^Gid:/{print \$2}" /proc/1/status)" = "10001"
  storage_root="${STORAGE_ROOT:-/data/storage}"
  test -f "$storage_root/.novvin-runtime-owner-v2"
  test -z "$(find "$storage_root" -mindepth 1 -maxdepth 1 \
    \( ! -user 10001 -o ! -group 10001 \) -print -quit)"
'
```

Si falla, no forzar permisos recursivos a ciegas: conservar el volumen, revisar
el log de arranque, resolver el path real de `STORAGE_ROOT` y validar el
propietario antes de reiniciar. Una prueba funcional debe pedir además una URL
real con `?width=384&quality=84&format=webp`: una imagen válida debe responder
`200 image/webp`; si la conversión puntual falla, debe responder `200` con el
tipo original y nunca `500 EACCES`.

Después de restaurar un volumen, retirar el marcador v2 para obligar a una
reconciliación completa en el siguiente arranque. Nunca crear manualmente un
bucket `root:root`: un directorio legible pero no escribible permite servir
archivos antiguos y, al mismo tiempo, rompe la primera subida de un usuario
nuevo.

La puerta de despliegue ejecuta también `verify:social-read-paths` contra
`PUBLIC_BASE_URL`. Es de solo lectura: crea un JWT diagnóstico de cinco minutos
dentro del contenedor, no registra usuarios ni publica contenido y nunca
imprime el token. Exige `/health`, `/ready`, Social, Videos y búsqueda de
perfiles con datos; prueba hasta tres imágenes optimizadas y dos rangos
consecutivos del primer MP4. Para ejecutarla manualmente:

```bash
cd ~/conectate-local-fallback
public_base_url="$(sed -n 's/^PUBLIC_BASE_URL=//p' .env | tail -1 | tr -d '\r"')"
docker compose exec -T \
  -e SMOKE_API_BASE_URL="$public_base_url" \
  -e SMOKE_REQUIRE_CONTENT=true \
  api npm run verify:social-read-paths
docker compose exec -T \
  -e SMOKE_API_BASE_URL="$public_base_url" \
  -e SMOKE_REQUIRE_CONTENT=true \
  api npm run verify:messaging-read-paths
```

El comando falla ante respuestas no JSON, feeds vacíos, `500`, miniaturas sin
cuerpo, ausencia de `Accept-Ranges`, rangos distintos de `206` o latencia sobre
los umbrales. Para una instalación deliberadamente vacía se permite
`SMOKE_REQUIRE_CONTENT=false`; no usar esa excepción en producción con datos.
La segunda puerta exige además perfil actual, inbox de chats/grupos/comunidades,
historial real, perfil del interlocutor y avatares optimizados. Ninguno de los
dos verificadores imprime JWT, correos, nombres, IDs ni URLs de objetos; las
referencias diagnósticas se reducen a hashes opacos.

### Rotación segura de Quick Tunnel

Quick Tunnel es sólo una contingencia y no tiene SLA. Mientras siga en uso, el
vigilante debe cumplir estas reglas:

1. conservar `conectate-public-url.txt` mientras el proceso cloudflared que
   posee ese hostname continúe activo; una llamada del servidor hacia su propio
   hostname puede fallar por hairpin/CGNAT y no autoriza por sí sola una rotación;
2. evaluar candidatos nuevos con varias sondas IPv4, no con una sola respuesta;
3. usar HTTP/2 sobre IPv4 para el túnel de respaldo en este host, donde los
   logs demostraron pérdida intermitente de QUIC/IPv6;
4. mantener el mismo `flock` durante cambio de `.env`, `docker compose up`,
   reinicio de `edge` y comprobación local;
5. reiniciar `edge` después de recrear API/realtime/signaling: Nginx resuelve
   esos nombres al arrancar y puede conservar la IP del contenedor anterior;
6. si `/ready` local no se recupera, restaurar el `PUBLIC_BASE_URL` previo y
   reconciliar de nuevo antes de publicar el TXT firmado.

Diagnóstico de un 502 tras rotación:

```bash
systemctl --user status conectate-cloudflare-tunnel.service --no-pager -l
cat ~/conectate-public-url.txt
sed -n 's/^PUBLIC_BASE_URL=//p' ~/conectate-local-fallback/.env | tail -1
cd ~/conectate-local-fallback
docker compose ps -a
docker compose logs --tail=120 api edge
curl -fsS --max-time 8 http://127.0.0.1:3080/ready
```

Si API está sana pero `edge` registra `could not be resolved` o `connection
refused` hacia la IP anterior, ejecutar bajo el bloqueo de Compose:

```bash
(
  flock --wait 240 9
  docker compose up -d --remove-orphans
  docker compose restart edge
) 9>~/.cache/conectate-compose.lock
```

Después es obligatorio repetir `verify:social-read-paths`; reiniciar solamente
cloudflared no demuestra que perfiles, imágenes y rangos MP4 atraviesen edge.

### Despliegue de sesión web HttpOnly

El orden es obligatorio para no cerrar sesiones web durante una publicación:

1. confirmar que `.env` contiene el origen Vercel exacto en `CORS_ORIGIN`;
2. desplegar primero API/nginx y esperar `/ready` saludable;
3. comprobar que el esquema añadió `refresh_token_hash` y `expires_at` a
   `fallback_web_sessions`;
4. probar login web y verificar en DevTools que `conectate:fallback-session` no
   contiene `accessToken` ni el JWT;
5. verificar que la respuesta de login crea
   `__Secure-novvin-web-session` con `HttpOnly`, `Secure`, `SameSite=None` y
   `Partitioned`;
6. recargar la página y confirmar que `POST /v1/auth/web-session/refresh`
   devuelve 200 antes de desplegar la web a todos;
7. desplegar la web y probar login por contraseña, QR, logout, cambio de
   contraseña, llamada, Live, realtime y upload.

Valores recomendados:

```dotenv
WEB_JWT_EXPIRES_IN=15m
WEB_SESSION_TTL_DAYS=30
WEB_SESSION_RATE_LIMIT_PER_15_MINUTES=120
CORS_ORIGIN=https://red-social-android.vercel.app
```

Un `403 UNTRUSTED_WEB_SESSION_REQUEST` indica header u origen inválido. Un
`401 WEB_SESSION_REQUIRED/WEB_SESSION_INVALID` indica cookie ausente, expirada
o revocada. No desactivar la comprobación de origen ni cambiar temporalmente
`CORS_ORIGIN` a `*`; revisar primero el hostname real de Vercel y si el Quick
Tunnel rotó.

Logs signaling:

```bash
docker logs --tail=150 conectate-local-fallback-signaling-main
```

Logs realtime:

```bash
docker logs --tail=150 conectate-local-fallback-realtime-main
```

Logs nginx:

```bash
docker logs --tail=150 conectate-local-fallback-edge-main
```

## Health local

```bash
curl -fsS http://127.0.0.1:3080/ready
curl -fsS http://127.0.0.1:3001/health
curl -fsS http://127.0.0.1:3003/health
```

## Health público

Usar la URL que muestre `~/bin/conectate-status`:

```bash
PUBLIC_URL="$(cat ~/conectate-public-url.txt 2>/dev/null || true)"
curl -fsS "$PUBLIC_URL/ready"
```

Si da 502/530:

- túnel recién levantando;
- nginx no disponible;
- Cloudflare aún propagando;
- stack reiniciando.

Esperar 30–60 segundos y reintentar. Si persiste, revisar logs.

## Auditar objetos multimedia huérfanos

Después de desplegar la imagen API que contiene el auditor:

```bash
cd ~/conectate-local-fallback
docker compose exec -T api npm run audit:storage-references -- \
  --grace-hours=168 \
  --report=/tmp/novvin-storage-reference-audit.json
docker cp \
  conectate-local-fallback-api-main:/tmp/novvin-storage-reference-audit.json \
  "$HOME/novvin-storage-reference-audit.json"
jq '.summary' "$HOME/novvin-storage-reference-audit.json"
```

Para monitorización automatizada se puede añadir `--fail-on-critical`; devuelve
código 2 cuando hay rutas inseguras, referencias sin objeto o archivos
referenciados que faltan. `--fail-on-candidates` devuelve código 3 si existe
material que requiere revisión, pero no borra nada.

No eliminar objetos mientras ocurra cualquiera de estas condiciones:

- `summary.critical` es mayor que cero;
- el objeto aparece en `referenced` o `protected-*`;
- solo existe una medición;
- hay uploads/publicaciones en curso;
- no se verificó un backup reciente y restaurable.

El auditor es intencionalmente de solo lectura. Los flags `--apply`, `--delete`,
`--purge` y `--quarantine` fallan para impedir que un error de operación se
convierta en pérdida masiva de multimedia.

## Mantener 24/7

Verificar anti-suspensión:

```bash
sudo systemctl status conectate-inhibit-sleep.service --no-pager
systemctl status sleep.target suspend.target hibernate.target hybrid-sleep.target --no-pager
cat /etc/systemd/logind.conf.d/99-conectate-server.conf
```

Estado esperado:

- `conectate-inhibit-sleep.service` activo;
- sleep/suspend/hibernate/hybrid-sleep masked;
- logind con `IdleAction=ignore`;
- acciones de lid/suspend/hibernate ignoradas.

## BIOS Lenovo

Si la mini PC se sigue suspendiendo aunque Linux esté bloqueado, revisar BIOS.

Opciones útiles:

- `Power > Enhanced Power Saving Mode`: Disabled.
- `Power > After Power Loss`: Power On.
- `Power > Automatic Power On`: Enabled si se desea.
- `Power > Wake on LAN`: Automatic/Primary.
- Desactivar deep sleep/ErP si aparece.
- Revisar Intelligent Cooling/ICE si tiene modo de ahorro extremo.

Guardar cambios:

- normalmente `F10` → Save and Exit.

## Apagado seguro

No cortar corriente si se puede evitar.

```bash
sudo shutdown now
```

Reinicio:

```bash
sudo reboot
```

## Después de reiniciar

Esperar 2–5 minutos y validar:

```bash
ssh alvaro@192.168.18.22
~/bin/conectate-status
```

Si no levanta solo:

```bash
sudo systemctl restart docker
sudo systemctl restart conectate-stack.service
sudo systemctl restart novvin-cloudflared.service
~/bin/conectate-status
```

## Despliegue desde Windows a mini PC

Ruta local:

```powershell
Set-Location "D:\red social - live-gifts"
```

Scripts disponibles:

```powershell
.\scripts\apply-mini-pc-24x7.ps1
.\scripts\deploy-openrelay-turn.ps1
.\scripts\configure-duckdns-discovery.ps1
```

Ejemplo:

```powershell
powershell.exe -ExecutionPolicy Bypass -File ".\scripts\apply-mini-pc-24x7.ps1" -HostName "192.168.18.22" -StaticIp "192.168.18.22"
```

Nunca ejecutar comandos Windows dentro de SSH Ubuntu. Si el prompt dice `alvaro@conectate:~$`, estás en Linux. Si dice `PS D:\...>`, estás en Windows.

## Checklist de incidente

### Revisión de acceso administrativo

No se edita `is_admin` de forma aislada ni se concede `full` para resolver un
problema temporal. Verificar primero el contrato del actor:

```bash
cd /home/alvaro/conectate-local-fallback/infrastructure/local-fallback/api
npm run verify:admin-panel-e2e
```

La respuesta de `/v1/data/admin/access` debe incluir nivel, propietario y lista de
capacidades. Si una operación devuelve `ADMIN_CAPABILITY_REQUIRED`, corregir el rol
o reasignar la tarea; no crear un bypass. `x-admin-token` queda reservado a los
endpoints operativos `/v1/admin/sync/*`.

### Canario de lecturas públicas

El monitor no destructivo ejecuta cada diez minutos las lecturas reales de
Social/Videos y Mensajes por el origen público. Un fallo aislado queda como
`degraded`; tres fallos consecutivos hacen fallar la unidad para que aparezca en
systemd. No reinicia API, edge ni túnel.

Las solicitudes HTTPS salen por la red del host, no por el bridge del contenedor:
el hairpin Docker → Quick Tunnel puede agotar tiempo aunque un cliente externo sí
alcance Cloudflare. El API sólo emite un token diagnóstico de cinco minutos y un
contenedor efímero, sin capacidades y de sólo lectura, ejecuta las sondas. La
última evidencia completa por módulo queda con modo `0600` en
`~/.local/state/conectate/public-social.latest.log` y
`~/.local/state/conectate/public-messaging.latest.log`.

```bash
systemctl --user status conectate-public-read-paths.timer
systemctl --user status conectate-public-read-paths.service
cat ~/.local/state/conectate/public-read-paths.status
cat ~/.local/state/conectate/public-social.latest.log
cat ~/.local/state/conectate/public-messaging.latest.log
journalctl --user -u conectate-public-read-paths.service --since '2 hours ago'
```

Instalación sin privilegios:

```bash
cd ~/conectate-local-fallback
./scripts/install-public-read-path-monitor-user.sh
```

Cuando usuarios reportan que no carga:

1. Ejecutar `~/bin/conectate-status`.
2. Confirmar `READY PUBLIC ok:true`.
3. Confirmar DuckDNS TXT.
4. Confirmar app no apunta a URL vieja.
5. Revisar logs API.
6. Revisar logs edge.
7. Revisar disco:

```bash
df -h
df -h /mnt/conectate-backup
```

8. Revisar memoria/carga:

```bash
free -h
uptime
docker stats --no-stream
```

9. Reiniciar stack solo si hace falta.

### Diagnóstico diferencial de lentitud multimedia

Los contadores de `ip -s link` son acumulativos desde el arranque y no prueban
por sí solos una falla actual. Antes de reiniciar o cambiar servicios, ejecutar
el auditor de solo lectura durante una ventana representativa:

```bash
~/audit-mini-pc-health.sh --sample-seconds 30
```

Para comprobar también el camino HTTPS/Range de un objeto multimedia público e
inmutable, pasar una URL de prueba sin token ni datos privados:

```bash
MEDIA_URL='https://media.example.invalid/storage/v1/object/public/videos/probe.mp4' \
  ~/audit-mini-pc-health.sh --sample-seconds 30
```

El informe queda bajo `~/conectate-health-audits/` y clasifica:

- `CRITICAL`: descartes de interfaz de 1 % o más, filesystem al 95 %, disco al
  95 % de ocupación de I/O, `/ready` caído, Range distinto de HTTP 206 o
  contenedor detenido/no saludable;
- `WARN`: descartes desde 0.1 %, filesystem al 85 %, disco al 80 %, enlace menor
  a 1 Gbps, errores de interfaz, memoria/carga elevadas o reinicios acumulados;
- `OK`: la métrica observada no cruzó el umbral durante la muestra.

No usar `--fail-on-critical` en una primera investigación. Ese flag está
destinado a automatización y devuelve código 1 cuando el informe termina en
estado crítico. El script no cambia red, discos, mounts, contenedores ni
servicios.

Los contadores RX/TX de la interfaz requieren correlación con HTTP/TCP: un
porcentaje alto en una muestra casi inactiva no prueba pérdida en las peticiones
de la aplicación. Si carrier no se puede leer, el auditor marca dato desconocido
y advertencia; sólo carrier=0 confirma enlace caído.

### Línea base observada de conectividad — 2026-09-04

Con el release `conectate-resource-budgets-20260902T020157Z`, las comprobaciones
públicas reales dieron aproximadamente estos tiempos hasta cabeceras: health en frío 1.18–1.65 s, ready
0.27–0.42 s, feed Social 0.48 s, feed Videos 0.50 s, búsqueda de perfiles
0.48–0.56 s, imágenes 0.41–0.73 s y rangos de video HTTP 206 en 0.21–0.44 s.
Una ejecución del monitor completo puede tardar 8–12 s porque inicia y valida
muchos recorridos; no debe confundirse con la latencia de una sola petición.

El origen público continúa siendo un Quick Tunnel sin SLA y el enlace físico
del host fue observado a 100 Mbps. Si `/ready` interno es rápido y el público
se degrada, investigar primero túnel/enlace; un reinicio indiscriminado del
stack no corrige esas dos limitaciones.

La auditoría de referencias de storage del 2026-09-04 encontró 23 referencias
históricas colgantes. No ejecutar limpieza automática: conservar el JSON,
clasificar cada referencia y repetir la medición tras el periodo de gracia.

### Verificación con descarga completa — 2026-09-04, 20:21 -05:00

El monitor ahora separa `ttfbMs` (cabeceras) y `elapsedMs` (cuerpo recibido).
Los presupuestos se comparan con el tiempo total. Una prueba local que envía
cabeceras rápidas y demora el cuerpo valida JSON, imágenes y rangos de video.
Los scripts actualizados están instalados en el host; existen respaldos
`.before-20260904-diagnostics` y `.before-20260904-body-timing` junto a los
archivos originales para rollback, sin reiniciar servicios.

En la medición pública de 01:21 UTC: Social 534 ms/37 posts, Videos 492 ms/12
videos, búsqueda de perfiles 563 ms y avatares 410–715 ms. Los dos rangos de
1 MiB dieron HTTP 206 y tardaron **1313/1197 ms completos**, frente a
455/229 ms hasta cabeceras. Mensajes dio inbox directo 802 ms, grupos 477 ms,
comunidades 411 ms e historial de 184 mensajes en 484 ms. Son muestras del
host por HTTPS público, no una garantía de latencia desde un teléfono móvil.

El auditor del host terminó con WARN: enlace activo 100 Mbps/full duplex,
4 descartes RX entre 2902 paquetes durante 20 s, ningún error de interfaz,
10.3 % de memoria usada y `/ready` local en 3.3 ms. Los ocho contenedores
estaban saludables, sin reinicios. Informe:
`/home/alvaro/conectate-health-audits/novvin-host-health-20260905T012144Z.txt`.
La lectura antigua de carrier y el cálculo awk de CPU estaban defectuosos;
se corrigieron y el self-test pasa en Windows/WSL y en Ubuntu.

Revalidación de la misma noche: temporizador **de usuario** activo y último
estado `ok` a las 02:47:30 UTC del 2026-09-05, sin fallos consecutivos. Los ocho
contenedores permanecían saludables y llevaban tres días activos. El servicio
oneshot aparece `inactive (dead)` después de terminar con `0/SUCCESS`; esto no
indica una caída si el timer sigue activo. Consultar con `systemctl --user`,
no con el administrador de servicios del sistema.

La evidencia pública leída en esa revisión registró Social 469 ms/37 posts,
Videos 454 ms/12 videos, búsqueda de perfiles 506 ms, imágenes 391–699 ms y
rangos de 1 MiB en 1624/1671 ms completos. Inbox directo 709 ms, grupos 581 ms,
comunidades 573 ms e historial de 184 mensajes en 650 ms. Son muestras de
conectividad, no tiempos garantizados de renderizado/reproducción del cliente.

Desde Windows, `/ready` público respondió HTTP 200 en 2619 ms en una conexión
fría (DNS 192 ms, TLS acumulado 1335 ms). Otra ejecución consecutiva dio
560 ms y, reutilizando esa misma conexión, 293 ms con `num_connects=0`.
Esto muestra variación del establecimiento de conexión en esa muestra; no
equivale a medir el throughput del video ni demuestra por sí solo el origen
de la pausa reportada en el teléfono.

### Recuperación DNS del 8–9 de septiembre

Se confirmó que un nombre Quick Tunnel podía desaparecer mientras su proceso
seguía vivo. El supervisor ahora comprueba NXDOMAIN en dos resolutores antes
de invalidarlo. Incidente, scripts desplegados, respaldo y mediciones posteriores:
[recuperación de túnel y feed](16-recuperacion-tunel-y-feed-2026-09-09.md).
Ante un fallo de Tailscale, comprobar la vía LAN documentada antes de concluir
que el mini-PC o la aplicación están desconectados. No reiniciar contenedores
saludables como sustituto de diagnosticar DNS/túnel.


## Comprobar la fuente del API después de actualizarlo

Desde la raíz del repositorio activo, con una clave SSH ya configurada:

```powershell
python infrastructure/local-fallback/scripts/verify-api-source-provenance.py --ssh-target alvaro@192.168.18.22 --identity "$env:USERPROFILE/.ssh/conectate_mini_ed25519" --output build/api-source-provenance.json
```

La comparación es de solo lectura: SHA-256 normalizado de `api/src/*.js` y `/ready`.
Debe indicar `ok:true`, sin archivos diferentes ni adicionales. No lee `.env`, contraseñas
ni filas de usuarios. No certifica esquema, configuración, otros servicios ni el commit
completo del stack. El marcador de release y la comparación de código se informan por separado.
