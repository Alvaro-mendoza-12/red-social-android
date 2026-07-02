# Acceso publico gratis desde Ubuntu en casa

Fecha de creacion: 2026-06-18
Fecha de ultima revision: 2026-06-18
Estado: guia oficial para probar primero en VM Ubuntu y migrar despues a mini PC Ubuntu Server.

## Objetivo

Dejar Ubuntu como backend principal de Conectate con una URL publica estable y gratuita.
La APK no debe apuntar a una IP cambiante; debe apuntar siempre a un dominio HTTPS fijo.

Ejemplo:

```text
https://conectate-demo.duckdns.org
```

Si la IP publica cambia, DuckDNS actualiza el dominio. La APK no necesita actualizarse
mientras el dominio siga siendo el mismo.

## Lectura de la VM actual

Resultado recibido el 2026-06-18:

```text
38.250.131.49 10.0.2.15 172.17.0.1 172.18.0.1 fd00::...
```

Interpretacion:

| Valor | Significado |
| --- | --- |
| `38.250.131.49` | IP publica de salida a Internet |
| `10.0.2.15` | IP interna de VirtualBox en modo NAT |
| `172.17.0.1`, `172.18.0.1` | redes internas de Docker |

El modo NAT de VirtualBox sirve para desarrollo, pero no imita bien una mini PC real.
Para probar como servidor de casa, usar red puente o reenviar puertos desde Windows a la VM.

## Ruta recomendada

1. VM Ubuntu en VirtualBox como servidor de ensayo.
2. DuckDNS para dominio gratuito.
3. Caddy en Ubuntu para HTTPS automatico.
4. Docker Compose actual para API, Postgres, storage, realtime, signaling y edge.
5. APK apuntando a `https://TU_DOMINIO.duckdns.org`.
6. Migracion posterior a mini PC copiando base de datos, storage y `.env`.

## Paso 1: poner la VM en modo servidor

Opcion recomendada en VirtualBox:

1. Apaga la VM.
2. VirtualBox > selecciona la VM > Configuracion > Red.
3. Adaptador 1 > Conectado a: `Adaptador puente`.
4. Nombre: selecciona tu Wi-Fi o Ethernet real.
5. Inicia la VM.

En Ubuntu:

```bash
hostname -I
```

Resultado esperado: una IP de tu red local, por ejemplo `192.168.1.50`.
Si sigue saliendo solo `10.0.2.15`, la VM continua en NAT.

### Si Ubuntu se queda esperando `systemd-networkd-wait-online`

Sintoma:

```text
Job systemd-networkd-wait-online.service/start running
```

Esto suele pasar cuando VirtualBox esta en `Adaptador puente`, pero Ubuntu no logra
obtener DHCP o el arranque espera la red indefinidamente.

Ruta segura:

1. Apaga la VM.
2. En VirtualBox deja `Adaptador 1` como `NAT` para poder entrar siempre.
3. Habilita `Adaptador 2` como `Adaptador puente`.
4. Marca `Cable conectado`.
5. Arranca Ubuntu.

Luego, dentro de Ubuntu:

```bash
sudo systemctl disable --now systemd-networkd-wait-online.service || true
sudo systemctl mask systemd-networkd-wait-online.service || true
ip -br addr
networkctl list || true
ls -l /etc/netplan
sudo cat /etc/netplan/*.yaml
```

Si el adaptador puente aparece, normalmente sera `enp0s8`. La configuracion de
netplan debe tener `dhcp4: true` y `optional: true` en los adaptadores de red para
que Ubuntu no se quede bloqueado si una interfaz tarda en responder.

Ejemplo, ajustar los nombres reales de interfaz segun `ip -br addr`:

```yaml
network:
  version: 2
  renderer: networkd
  ethernets:
    enp0s3:
      dhcp4: true
      optional: true
    enp0s8:
      dhcp4: true
      optional: true
```

Aplicar:

```bash
sudo netplan generate
sudo netplan apply
ip -br addr
```

Resultado esperado:

- La VM arranca sin esperar indefinidamente la red.
- `enp0s3` conserva NAT/SSH de emergencia.
- `enp0s8` recibe una IP LAN tipo `192.168.1.x` para exponer el servidor.

Si `enp0s8` sube pero no recibe IPv4, por ejemplo queda solo con `fe80::...`,
usar la ruta NAT de VirtualBox para la VM:

```text
Internet/router -> Windows host -> VirtualBox NAT -> Ubuntu -> Caddy -> Docker edge
```

Reenvios recomendados en VirtualBox para la VM:

| Regla | Host | Invitado |
| --- | --- | --- |
| SSH | `127.0.0.1:2222` | `22` |
| Edge local | `127.0.0.1:3080` | `3080` |
| HTTP publico | `0.0.0.0:80` | `80` |
| HTTPS publico | `0.0.0.0:443` | `443` |

En este modo, el router debe reenviar `80` y `443` hacia la IP de Windows,
no hacia la IP de la VM. En la mini PC final esto ya no sera necesario: el router
reenviara directamente a la IP LAN de la mini PC.

## Paso 2: comprobar si tienes IP publica util

En Ubuntu:

```bash
curl -4 https://ifconfig.me
hostname -I
```

Entra al router y busca `WAN IP`, `Internet IP` o `Direccion IP de Internet`.

Si la WAN IP del router coincide con `curl -4 https://ifconfig.me`, puedes abrir puertos.
Si la WAN IP empieza con `10.`, `100.64.`, `172.16` a `172.31` o `192.168`, probablemente hay CGNAT.

Con CGNAT, pedir al proveedor:

```text
Necesito una IP publica dinamica sin CGNAT para abrir puertos 80 y 443 hacia mi servidor.
```

No hace falta IP fija pagada si usas DuckDNS.

## Paso 3: crear dominio DuckDNS

1. Crear cuenta gratis en DuckDNS.
2. Crear un subdominio, por ejemplo `conectate-demo`.
3. Guardar el token.

El dominio final sera:

```text
conectate-demo.duckdns.org
```

## Paso 4: abrir puertos en el router

Con la VM en modo puente, reenvia estos puertos hacia la IP local de la VM:

| Puerto externo | Puerto interno | IP destino | Uso |
| --- | --- | --- | --- |
| `80` | `80` | IP de la VM | desafio HTTP de certificados |
| `443` | `443` | IP de la VM | API publica HTTPS |

No expongas Postgres, `5433`, `3001`, `3002`, `3003` ni `3080` directamente a Internet.

## Paso 5: instalar Caddy + DuckDNS en Ubuntu

En la VM Ubuntu:

```bash
cd ~/conectate-local-fallback
PUBLIC_DOMAIN="conectate-demo.duckdns.org" \
DUCKDNS_TOKEN="PEGA_AQUI_TU_TOKEN" \
bash scripts/setup-public-duckdns-caddy.sh
```

Desde Windows tambien se puede lanzar con:

```powershell
cd "D:\red social - copia"
powershell -ExecutionPolicy Bypass -File ".\scripts\setup-vm-public-access.ps1"
```

Cambia `conectate-demo.duckdns.org` y `PEGA_AQUI_TU_TOKEN` por tus datos reales.

Resultado esperado:

- `PUBLIC_BASE_URL` queda guardado en `.env`.
- Caddy queda escuchando `80` y `443`.
- Caddy reenvia a `127.0.0.1:3080`.
- DuckDNS actualiza tu IP cada 5 minutos.
- `https://TU_DOMINIO.duckdns.org/ready` responde.

## Paso 6: reparar y verificar Ubuntu

```bash
cd ~/conectate-local-fallback
PUBLIC_BASE_URL="https://conectate-demo.duckdns.org" bash scripts/repair-ubuntu-primary.sh
API_URL="http://127.0.0.1:3080" bash scripts/verify-ubuntu-modules.sh
PUBLIC_BASE_URL="https://conectate-demo.duckdns.org" bash scripts/verify-ubuntu-storage.sh
docker compose ps
docker compose logs --tail=120 api edge signaling realtime-gateway
```

## Paso 7: apuntar Android a la URL definitiva

En Windows, archivo `secrets.properties`:

```properties
FALLBACK_API_URL=https://conectate-demo.duckdns.org
SUPABASE_URL=
SUPABASE_ANON_KEY=
```

Compilar:

```powershell
cd "D:\red social - copia"
.\gradlew.bat --no-daemon --console=plain :app:compileDebugKotlin
```

Para Play Store, subir una version nueva con esa URL. Los usuarios instalados no cambiaran
de backend hasta recibir una APK/AAB actualizada, salvo que ya tengan una configuracion remota
implementada en la app.

## Paso 8: migrar a mini PC

En la VM actual:

```bash
cd ~/conectate-local-fallback
docker compose exec -T db pg_dump -U conectate conectate_fallback > conectate_fallback.sql
docker run --rm -v conectate-fallback-storage:/data/storage -v "$PWD":/backup alpine \
  tar -czf /backup/conectate-storage.tar.gz -C /data/storage .
```

Copiar a la mini PC y restaurar:

```bash
cd ~/conectate-local-fallback
docker compose up -d db
cat ~/conectate_fallback.sql | docker compose exec -T db psql -U conectate -d conectate_fallback
docker run --rm -v conectate-fallback-storage:/data/storage -v "$HOME":/backup alpine \
  sh -c "cd /data/storage && tar -xzf /backup/conectate-storage.tar.gz"
docker compose up -d --build
```

Despues ejecutar de nuevo:

```bash
PUBLIC_DOMAIN="conectate-demo.duckdns.org" \
DUCKDNS_TOKEN="PEGA_AQUI_TU_TOKEN" \
bash scripts/setup-public-duckdns-caddy.sh
```

## Comprobacion final

```bash
curl -i https://conectate-demo.duckdns.org/ready
curl -i https://conectate-demo.duckdns.org/health
curl -i https://conectate-demo.duckdns.org/v1/data/feed
docker compose ps
sudo systemctl status caddy --no-pager
```

Resultado esperado:

- HTTPS responde con certificado valido.
- Docker muestra servicios healthy.
- Storage devuelve URLs bajo `https://TU_DOMINIO.duckdns.org`.
- Android registra, inicia sesion, sube archivos y reproduce multimedia contra Ubuntu.
