# Voice VPS Runbook 2026

Guia practica para mantener el sistema de voz/WebRTC de Conectate en produccion.

Esta guia documenta:

- que cambios se hicieron en la app y en el VPS
- como entrar al VPS
- como revisar el estado del signaling server y coturn
- como actualizar `index.js` en el VPS
- como validar si el problema es de TURN, FCM o WebRTC

## 1. Estado actual confirmado

Produccion actual:

- Dominio de voz: `voice.srv1495648.hstgr.cloud`
- VPS publica: `76.13.160.177`
- Carpeta real del signaling server: `/srv/signaling-server`
- Servicio systemd del signaling: `signaling-server.service`
- Puerto interno del signaling: `127.0.0.1:3001`
- Servicio TURN: `coturn.service`
- Puerto TURN activo: `3478/tcp` y `3478/udp`
- Puerto TLS TURN reservado: `5349/tcp`
- Rango relay TURN: `49160-49200/udp`

Notas importantes:

- En este momento `TURN_ENABLE_TLS=false`, por eso `5349` puede estar abierto en firewall pero no necesariamente escuchando.
- Para mantenimiento rapido, la forma mas segura de entrar es la consola web de Hostinger.
- El acceso SSH externo no quedo estable para copiar archivos desde laptop. Se detecto trafico en `18789`, pero no sirve como SSH normal para `scp`.

## 2. Cambios que se hicieron

### Android app

Archivos principales tocados:

- `app/src/main/java/conectate/com/data/webrtc/WebRTCManager.kt`
- `app/src/main/java/conectate/com/data/repository/CallRepository.kt`
- `app/src/main/java/conectate/com/data/service/MyFirebaseMessagingService.kt`

Resumen:

- se estabilizo el refresh de TURN antes de crear `PeerConnection`
- se redujeron bucles de renegociacion y `glare`
- se agrego limpieza de estados pegados (`ignoreOffer`, glare recovery)
- se mejoro modo emulador para audio
- se agregaron logs para `call_invite` y errores de push

### Signaling server

Archivo principal:

- `signaling-server/index.js`

Resumen:

- `GET /turn-credentials` ya devuelve TURN autohospedado
- `GET /live-active-ids` expone los lives realmente vivos en memoria del signaling para filtrar directos fantasma
- el orden de prioridad de relays quedo:
  1. `turns:...tcp`
  2. `turn:...tcp`
  3. `turn:...udp`
- cada URL TURN se entrega como entrada separada en `iceServers`

### Supabase Edge Function

Archivo:

- `supabase/functions/push-notification/index.ts`

Resumen:

- `call_invite` ya no usa TTL `0s`
- ahora usa TTL `45s`
- se agregaron logs de envio y respuesta FCM

### VPS / coturn

Se confirmo:

- firewall Hostinger abierto para `22`, `80`, `443`, `3478/tcp`, `3478/udp`, `49160:49200/udp`, `5349/tcp`
- `ufw` abierto con esas mismas reglas
- `coturn` activo y escuchando en `3478`
- `turnserver.conf` con:
  - `use-auth-secret`
  - `fingerprint`
  - `lt-cred-mech`
  - `external-ip=76.13.160.177`
  - `realm=voice.srv1495648.hstgr.cloud`
  - `min-port=49160`
  - `max-port=49200`

## 3. Como entrar al VPS

### Opcion recomendada

Usar la consola web de Hostinger.

Ventaja:

- no depende de SSH externo
- permite editar, reiniciar y revisar servicios

### Opcion SSH externa

Todavia no esta documentada como estable para este servidor.

Lo observado fue:

- `22` desde internet daba timeout
- `18789` respondia, pero cerraba la sesion al intentar `ssh`

Por ahora:

- usar Hostinger para mantenimiento
- si luego se quiere dejar SSH normal, revisar aparte `sshd`, `openclaw-gateway` y forwarding externo

## 4. Comandos base de diagnostico

Desde la consola del VPS:

```bash
cd /srv/signaling-server
pwd
systemctl status signaling-server.service --no-pager
systemctl status coturn --no-pager
ss -lntup | grep -E ':3001|:3478|:5349'
```

Ver variables clave del signaling:

```bash
cd /srv/signaling-server
grep -E '^(PORT|TURN_HOST|TURN_PORT|TURN_TLS_PORT|TURN_SECRET|TURN_REALM|TURN_ENABLE_UDP|TURN_ENABLE_TCP|TURN_ENABLE_TLS)=' .env
```

Comprobar endpoints HTTP publicados por el proxy:

```bash
curl -I https://voice.srv1495648.hstgr.cloud/health
curl "https://voice.srv1495648.hstgr.cloud/live-active-ids"
```

Ver configuracion de coturn:

```bash
grep -nE 'listening-port|tls-listening-port|min-port|max-port|static-auth-secret|realm|fingerprint|lt-cred-mech|listening-ip|external-ip' /etc/turnserver.conf
```

## 5. Como actualizar `index.js` en el VPS

### Ruta correcta

Siempre trabajar en:

```bash
/srv/signaling-server
```

No editar por error una carpeta `backup`.

### Antes de editar

```bash
cd /srv/signaling-server
cp index.js "index.js.bak-$(date +%F-%H%M%S)"
```

### Editar rapidamente en servidor

Si solo se necesita un cambio pequeno y no funciona `scp`, usar `nano`:

```bash
cd /srv/signaling-server
nano index.js
```

Guardar:

- `Ctrl + O`
- `Enter`
- `Ctrl + X`

### Reiniciar despues de cambiar

```bash
systemctl restart signaling-server.service
systemctl status signaling-server.service --no-pager
```

### Ver logs del signaling

```bash
journalctl -u signaling-server.service -n 100 --no-pager
```

Seguir logs en vivo:

```bash
journalctl -u signaling-server.service -f
```

## 6. Como actualizar `turnserver.conf`

Hacer backup:

```bash
cp /etc/turnserver.conf "/etc/turnserver.conf.bak-$(date +%F-%H%M%S)"
```

Editar:

```bash
nano /etc/turnserver.conf
```

Reiniciar:

```bash
systemctl restart coturn
systemctl status coturn --no-pager
```

Ver logs:

```bash
journalctl -u coturn -n 100 --no-pager
```

## 7. Checklist rapido si falla entre redes

Si funciona en la misma Wi-Fi pero falla entre Wi-Fi y datos moviles, revisar esto:

1. `signaling-server.service` activo
2. `coturn.service` activo
3. `3478/tcp` y `3478/udp` escuchando
4. `TURN_SECRET` en `/srv/signaling-server/.env` igual a `static-auth-secret` en `/etc/turnserver.conf`
5. `external-ip` correcto
6. rango `49160-49200/udp` abierto en Hostinger y en `ufw`
7. la app muestra candidatos `RELAY (TURN)` en Logcat

Filtro recomendado en Android Studio Logcat:

```text
package:conectate.com & (tag:WebRTC | tag:WebRTCManager | tag:CallRepository | tag:VoiceRoomRepository | tag:LiveRepository)
```

Senales buenas:

- `Managed TURN configuration loaded`
- `Added ICE Node: turn:...transport=tcp`
- `Generated Candidate: RELAY (TURN)`

Senal mala tipica:

- solo aparecen `HOST` y `SRFLX`
- termina en `ICE FAILED`

## 8. Checklist rapido si la llamada no llega con app cerrada

Revisar estas piezas:

1. Edge Function desplegada:
   - `supabase/functions/push-notification/index.ts`
2. tipo `call_invite` con TTL `45s`
3. FCM token del usuario destino existe en tabla `fcm_tokens`
4. Logcat del receptor muestra:
   - `Received call_invite via FCM`

Si no llega:

- revisar logs de Edge Function en Supabase
- revisar respuesta FCM
- revisar que Android no haya revocado notificaciones o background restrictions

## 9. Activar TLS TURN mas adelante

Si con `3478` todavia no basta para redes moviles mas duras, el siguiente paso es habilitar TLS real:

1. poner certificados en `turnserver.conf`
2. habilitar `tls-listening-port=5349`
3. confirmar que `5349` escucha con:

```bash
ss -lntup | grep 5349
```

4. cambiar en `/srv/signaling-server/.env`:

```env
TURN_ENABLE_TLS=true
```

5. reiniciar:

```bash
systemctl restart coturn
systemctl restart signaling-server.service
```

## 10. Comandos utiles de bolsillo

Estado general:

```bash
systemctl status signaling-server.service --no-pager
systemctl status coturn --no-pager
ss -lntup | grep -E ':3001|:3478|:5349'
```

Validar carpeta correcta:

```bash
systemctl show signaling-server.service -p WorkingDirectory -p ExecStart --no-pager
```

Ver archivo actual del signaling:

```bash
cd /srv/signaling-server
grep -nE 'turns:|transport=tcp|transport=udp|relayUrls.map|turnEnableTcp|turnEnableTls' index.js
```

Verificar `health` local:

```bash
curl http://127.0.0.1:3001/health
```

## 11. Regla practica para la proxima

Si hay que tocar voz/llamadas/live:

1. entrar a Hostinger console
2. `cd /srv/signaling-server`
3. backup del archivo a tocar
4. editar
5. `systemctl restart signaling-server.service`
6. si tocaste TURN, tambien `systemctl restart coturn`
7. validar con `status`, `ss`, y pruebas en Logcat
