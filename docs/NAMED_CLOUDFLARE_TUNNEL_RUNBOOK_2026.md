# Runbook: origen público estable con Cloudflare Tunnel

**Propósito:** sustituir los Quick Tunnels temporales por un único origen HTTPS
estable para API, multimedia, Socket.IO y señalización de NOVVIN.

Quick Tunnels son útiles para desarrollo, pero Cloudflare los limita, no les
ofrece garantía de disponibilidad y les asigna un hostname diferente al reiniciar.
No deben usarse en producción.

## Diseño objetivo

```text
Android / Web ──HTTPS / WSS──> api.novvin.example
                                   │ Cloudflare Tunnel nombrado
                                   ▼
                           127.0.0.1:3080 (Nginx edge)
                                   │
               API · Storage Range · Socket.IO · Realtime · Signaling
```

Un solo hostname llega a Nginx. Los prefijos `/socket.io`, `/live/socket.io`,
`/call/socket.io`, `/storage` y `/realtime` se enrutan dentro de la red Docker;
no se deben abrir sus puertos al Internet público.

## Prerrequisitos

1. Un dominio bajo una zona administrada por Cloudflare.
2. Acceso de administrador a **Networking → Tunnels**.
3. El stack responde localmente:

   ```bash
   curl -fsS http://127.0.0.1:3080/ready
   docker compose ps
   ```

4. `PUBLIC_BASE_URL`, `CORS_ORIGIN` y `SIGNALING_ALLOWED_ORIGINS` definidos con
   URLs HTTPS reales, no `trycloudflare.com`, `ngrok`, localhost ni IPs.

## Crear el túnel administrado

En Cloudflare:

1. Crea un túnel de tipo **Cloudflared** con nombre `novvin-production`.
2. Añade el hostname público, por ejemplo `api.novvin.example`.
3. Configura el servicio de origen como `http://127.0.0.1:3080`.
4. Copia el token una sola vez a un gestor de secretos. No lo pegues en Git,
   `.env`, chats, capturas ni comandos que puedan persistir en el historial.

## Instalar el servicio supervisado en Ubuntu

Transfiere el token mediante un archivo temporal protegido. En la VM:

```bash
umask 077
read -r -s -p 'Token de Cloudflare: ' NOVVIN_TUNNEL_TOKEN; echo
printf '%s' "$NOVVIN_TUNNEL_TOKEN" > /tmp/novvin-cloudflared-token
unset NOVVIN_TUNNEL_TOKEN

cd ~/conectate-local-fallback
sudo bash scripts/install-named-cloudflare-tunnel.sh /tmp/novvin-cloudflared-token
shred -u /tmp/novvin-cloudflared-token
sudo systemctl status novvin-cloudflared --no-pager
```

El instalador guarda el token con permisos `0600` en
`/etc/novvin/cloudflared.env`. La unidad usa `DynamicUser`, restricciones de
sistema y `TUNNEL_TOKEN` desde el entorno, no como argumento de proceso.

## Aplicar el origen a NOVVIN

Edita únicamente el `.env` del servidor:

```env
PUBLIC_BASE_URL=https://api.novvin.example
ALLOW_EPHEMERAL_PUBLIC_ORIGIN=false
CORS_ORIGIN=https://web.novvin.example
SIGNALING_ALLOWED_ORIGINS=https://web.novvin.example
```

Si la web o Android usan el descriptor firmado de descubrimiento, actualiza su
origen canónico y conserva los anteriores solo durante una migración medida.
No apuntes la aplicación al hostname de un túnel temporal como fallback.

Después ejecuta el despliegue controlado:

```bash
cd ~/conectate-local-fallback
bash scripts/deploy-stack-safely.sh
curl -fsS https://api.novvin.example/ready
curl -fsSI https://api.novvin.example/storage/v1/object/public/BUCKET/OBJETO
```

`deploy-stack-safely.sh` falla antes de recrear servicios si detecta un origen
temporal. También comprueba la configuración Nginx cargada: Range/X-Accel para
multimedia, ausencia de buffering para uploads y el formato de logs sin tokens
ni IPs de usuario.

Antes de abrir el canario, ejecuta la verificación estricta:

```bash
cd ~/conectate-local-fallback
STRICT_PRODUCTION=true \
PUBLIC_BASE_URL=https://api.novvin.example \
bash scripts/verify-ubuntu-production.sh
```

Además de los módulos funcionales, ahora rechaza un origen temporal, un túnel
nombrado inactivo, Redis de signaling ausente/no autenticado y un `/ready`
público que no responde. Esta prueba crea fixtures controlados y los limpia al
terminar; ejecútala sólo en una ventana de despliegue autorizada.

## Validación y rollback

Comprueba desde una red externa:

```bash
curl -fsS https://api.novvin.example/ready
curl -fsS https://api.novvin.example/health
sudo systemctl is-active novvin-cloudflared
journalctl -u novvin-cloudflared --since '15 minutes ago' --no-pager
```

Luego prueba en dos teléfonos: login, feed de vídeo, reproducción con scroll,
upload, Momentos, chat, Live y cambio Wi-Fi/datos. Vigila 429, 5xx, reconexiones
Socket.IO y errores Media3 durante el canario.

Para rollback de configuración del túnel, restaura el hostname anterior en el
panel Cloudflare; no vuelvas a Quick Tunnel como solución de producción. Para
detener temporalmente el origen: `sudo systemctl stop novvin-cloudflared`.

## Rotación de token

Si el token aparece en un log, historial o captura, revócalo en Cloudflare,
genera otro y repite la instalación. Reinicia el servicio; nunca edites el token
en una unidad systemd ni en Docker Compose:

```bash
sudo bash scripts/install-named-cloudflare-tunnel.sh /ruta/segura/token-nuevo
sudo systemctl restart novvin-cloudflared
```
