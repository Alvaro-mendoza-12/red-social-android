# WebRTC/TURN de Conéctate — estado operativo 2026

Actualizado el 22 de julio de 2026. Este documento reemplaza la referencia antigua a Hostinger.

## Arquitectura activa

- API y signaling: mini-PC Ubuntu, publicado por Cloudflare Tunnel en
  `https://recognition-milk-overall-western.trycloudflare.com`.
- Namespaces Socket.IO: `/` para salas de voz, `/call` para llamadas y `/live`
  para emitir/ver directos.
- TURN público: VPS IONOS Ubuntu `198.251.72.174`.
- Host TURN: `turn.198-251-72-174.sslip.io`.
- Transporte preferido: `turns` TCP/TLS en `443`; alternativas TCP y UDP en
  `3478`.
- Rango de relay: UDP `49160-49200`.
- Credenciales: TURN REST temporales, emitidas por `GET /turn-credentials` solo
  para usuarios autenticados; el secreto nunca se incluye en el APK.
- Certificado: Let's Encrypt, con renovación de Certbot y hook que actualiza las
  copias legibles por Coturn y reinicia el servicio.

El túnel Cloudflare transporta API y signaling. El audio/video WebRTC atraviesa
directamente Internet o el VPS TURN; no viaja por el túnel HTTP.

## Configuración del VPS

La instalación reproducible está en `scripts/provision-turn-vps.sh`. La
configuración efectiva usa:

- `use-auth-secret` y `static-auth-secret`;
- `realm=turn.198-251-72-174.sslip.io`;
- `external-ip=198.251.72.174`;
- `listening-port=3478` y `tls-listening-port=443`;
- `cert=/etc/turnserver/tls-fullchain.pem`;
- `pkey=/etc/turnserver/tls-privkey.pem`;
- bloqueos de destinos privados y multicast;
- usuario de servicio sin shell y capacidad limitada para enlazar el puerto 443.

Puertos requeridos tanto en IONOS como en UFW:

- TCP `22` para administración;
- TCP/UDP `3478` para STUN/TURN;
- TCP/UDP `443` para TURN en redes restrictivas;
- UDP `49160-49200` para los relays.

TCP `80` se usa para la validación/renovación ACME del certificado.

## Diagnóstico rápido

En el VPS:

```bash
systemctl is-active coturn
systemctl is-active certbot.timer
ss -lntup | grep -E ':3478|:443'
sudo turnserver -c /etc/turnserver.conf --check-config
sudo journalctl -u coturn -n 100 --no-pager
sudo certbot certificates
```

En el mini-PC:

```bash
cd /home/alvaro/conectate-local-fallback
docker compose ps api signaling edge
docker inspect --format='{{.State.Health.Status}}' conectate-local-fallback-signaling-main
curl -fsS http://127.0.0.1:3003/health
```

`GET /turn-credentials` sin token debe responder `401`. Con un token válido debe
responder `provider: coturn-rest`, vencimiento futuro y estas tres rutas:

```text
turns:turn.198-251-72-174.sslip.io:443?transport=tcp
turn:turn.198-251-72-174.sslip.io:3478?transport=tcp
turn:turn.198-251-72-174.sslip.io:3478?transport=udp
```

## Cliente Android

`WebRTCManager` renueva las credenciales antes de que expiren y fuerza una
renovación/reinicio ICE si la conexión falla. Llamadas, salas y directos usan la
misma fuente administrada de servidores ICE. Los repositorios no deben declarar
el medio como conectado solo porque Socket.IO haya unido a los usuarios.

En Logcat, confirmar:

```text
Managed TURN configuration loaded
Generated Candidate: RELAY (TURN)
ICE state: CONNECTED o COMPLETED
```

Si solo aparecen candidatos `HOST`/`SRFLX`, revisar credenciales, firewall y
rango relay. Si signaling conecta pero ICE termina en `FAILED`, verificar que el
APK instalado sea el nuevo y revisar Coturn, no el túnel Cloudflare.

## Límites actuales

Llamadas, salas y directos usan una malla WebRTC. Funciona para llamadas 1 a 1 y
grupos pequeños, pero el anfitrión de un directo envía una copia por espectador.
Para directos masivos se necesita un SFU (por ejemplo LiveKit/mediasoup/Janus),
no un plan TURN más grande. El rango actual tiene 41 puertos relay y está pensado
para la etapa de prueba/beta; se debe ampliar antes de concurrencia alta.
