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
cloudflare=active
READY LOCAL ok:true
READY PUBLIC ok:true
```

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

Si Cloudflare cambió URL y la app no descubre:

```bash
systemctl --user restart conectate-cloudflare-tunnel.service
sleep 20
~/bin/conectate-status
```

Ver DuckDNS TXT:

```bash
curl -fsS "https://dns.google/resolve?name=conectate-alvaro.duckdns.org&type=TXT"
```

## Revisar contenedores

```bash
cd ~/conectate-local-fallback
docker compose ps
```

Logs API:

```bash
docker logs --tail=150 conectate-local-fallback-api-main
```

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
systemctl --user restart conectate-cloudflare-tunnel.service
~/bin/conectate-status
```

## Despliegue desde Windows a mini PC

Ruta local:

```powershell
Set-Location "D:\red social - copia"
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

