# Conectate — Handoff oficial de producción

Fecha: 2026-06-28  
Estado: APK Android en producción y backend Ubuntu en mini PC como fuente primaria.

Este documento existe para que cualquier desarrollador nuevo pueda entender el proyecto sin depender de memoria oral ni de capturas sueltas.

## 1. Resumen ejecutivo

Conectate es una app social Android con:

- mensajes 1 a 1, grupos y comunidades;
- llamadas, videollamadas, historial de llamadas y notificaciones push;
- videos tipo feed vertical;
- social/momentos/publicaciones;
- salas de voz con WebRTC;
- backend Ubuntu local en mini PC, expuesto por Cloudflare Quick Tunnel/DuckDNS;
- administración remota por Tailscale/SSH.

La mini PC es el backend primario. Supabase queda como compatibilidad histórica/migración y no debe ser considerado fuente primaria para los módulos migrados.

## 2. Infraestructura actual

Servidor principal:

- Hostname Ubuntu: `conectate`
- Usuario operativo: `alvaro`
- IP LAN esperada: `192.168.18.22`
- IP Tailscale: `100.91.84.2`
- Proyecto servidor: `~/conectate-local-fallback`
- Disco principal Ubuntu: SSD 240 GB montado en `/`
- Disco externo backup: `/mnt/conectate-backup`

Servicios Docker esperados:

- `api`: backend principal HTTP.
- `db`: PostgreSQL.
- `edge`: nginx local.
- `signaling`: Socket.IO/WebRTC signaling.
- `realtime-gateway`: eventos realtime.
- `coturn`: TURN local configurado, con limitación por CGNAT.

Comando de estado:

```bash
~/bin/conectate-status
```

Debe mostrar:

```txt
docker=active
stack=active
cloudflare=active
READY LOCAL: ok:true
READY PUBLIC: ok:true
```

## 3. Comandos operativos esenciales

Entrar por red local:

```powershell
ssh -i "$env:USERPROFILE\.ssh\conectate_mini_ed25519" alvaro@192.168.18.22
```

Entrar por Tailscale desde fuera de casa:

```powershell
ssh -i "$env:USERPROFILE\.ssh\conectate_mini_ed25519" alvaro@100.91.84.2
```

Reiniciar todo el stack:

```bash
~/bin/conectate-restart
```

Ver estado:

```bash
~/bin/conectate-status
```

Ver URL pública actual:

```bash
cat ~/conectate-public-url.txt
```

Probar salud pública:

```bash
curl -fsS "$(cat ~/conectate-public-url.txt)/ready"
```

Ver contenedores:

```bash
cd ~/conectate-local-fallback
docker compose -p conectate-local-fallback ps
```

Ver logs de API:

```bash
cd ~/conectate-local-fallback
docker compose -p conectate-local-fallback logs --tail=150 api
```

Ver logs del túnel:

```bash
systemctl --user status conectate-cloudflare-tunnel.service --no-pager
journalctl --user -u conectate-cloudflare-tunnel.service -n 120 --no-pager
```

## 4. Arranque 24/7

Ubuntu fue endurecido para evitar suspensión:

- `sleep.target`, `suspend.target`, `hibernate.target`, `hybrid-sleep.target` enmascarados.
- `logind` configurado para ignorar suspensión, botón de power corto, tapa y eventos de energía.
- Docker, stack, health timer, backup timer y túnel Cloudflare habilitados.

Verificación:

```bash
systemctl status sleep.target suspend.target hibernate.target hybrid-sleep.target --no-pager
cat /etc/systemd/logind.conf.d/99-conectate-server.conf
```

Si la mini PC vuelve a apagarse o congelarse, revisar BIOS:

- Power > After Power Loss = Power On
- Power > Wake on LAN = Enabled
- Power/ACPI > Deep Sleep/ErP = Disabled, si existe

## 5. Backups

Ruta local:

```bash
/home/alvaro/conectate-backups
```

Ruta espejo en disco externo:

```bash
/mnt/conectate-backup/conectate-backups
```

Comprobar disco externo:

```bash
df -h /mnt/conectate-backup
ls -lah /mnt/conectate-backup/conectate-backups
```

Timer:

```bash
systemctl list-timers | grep conectate-backup
```

El enlace `latest` debe apuntar al último backup real:

```bash
readlink -f /mnt/conectate-backup/conectate-backups/latest
```

## 6. Release Android

Compilar debug:

```powershell
.\gradlew.bat --no-daemon :app:compileDebugKotlin
```

Generar AAB release:

```powershell
.\gradlew.bat --no-daemon :app:bundleRelease
```

Archivo esperado:

```txt
app/build/outputs/bundle/release/app-release.aab
```

Antes de subir a Play Store:

1. confirmar `~/bin/conectate-status`;
2. confirmar `/ready` público;
3. instalar APK/AAB en un celular real;
4. probar login, mensajes, grupos, comunidades, videos, notificaciones y llamadas;
5. subir AAB a Play Console.

## 7. Tailscale

Tailscale sirve para administración remota, no para el tráfico de usuarios finales.

En laptop:

```cmd
tailscale status
```

La mini PC debe verse:

```txt
100.91.84.2 conectate linux active
```

Si aparece `offline`, no habrá SSH remoto por Tailscale. En ese caso revisar energía/red local de la mini PC.

## 8. Cloudflare Quick Tunnel y CGNAT

El proveedor usa CGNAT. Por eso:

- no se puede depender de abrir puertos normales en el router;
- Cloudflare Quick Tunnel permite HTTP/HTTPS público sin pagar;
- la URL `*.trycloudflare.com` puede cambiar;
- la app debe descubrir la URL actual vía el mecanismo configurado en DuckDNS/TXT o endpoint dinámico.

Si `/ready` público falla pero local funciona, el problema casi siempre está en túnel/DNS, no en PostgreSQL.

## 9. TURN, llamadas y salas de voz

Coturn está configurado, pero con CGNAT tiene una limitación real: TURN necesita que los puertos relay sean alcanzables públicamente. Si no hay IP pública ni túnel UDP adecuado, cámara/pantalla pueden fallar entre usuarios fuera de la misma red.

Investigación y matriz de decisión actualizada: `docs/WEBRTC_CGNAT_MEDIA_OPTIONS_2026.md`.

Estado recomendado:

- audio básico puede funcionar si WebRTC logra ruta STUN/P2P;
- cámara/pantalla confiable para todos los usuarios requiere TURN público real;
- opciones viables:
  1. VPS económico con Coturn;
  2. servicio TURN externo gratuito/limitado para pruebas;
  3. pedir IP pública al ISP;
  4. mantener solo audio estable hasta tener TURN público.

No presentar las salas de voz como “100% videollamadas tipo producción global” hasta resolver TURN público.

## 10. Módulos críticos que deben protegerse

- Autenticación y cambio de contraseña migrada.
- Mensajes y contadores de no leídos.
- Persistencia de eliminar/archivar/favorito/fijado.
- Notificaciones push FCM.
- Storage multimedia.
- Backups diarios y restauración.
- Túnel público.
- WebRTC/Signaling.

## 11. Checklist diario rápido

```bash
~/bin/conectate-status
df -h / /mnt/conectate-backup
systemctl list-timers | grep conectate
```

Checklist visual:

- app abre;
- login funciona;
- lista de mensajes carga;
- nuevo mensaje actualiza preview y contador;
- video reproduce;
- `/ready` público responde;
- backup externo existe.
