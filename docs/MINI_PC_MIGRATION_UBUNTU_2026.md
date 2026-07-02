# Migración de Conéctate a una mini PC Ubuntu nueva

Estado actualizado: 24 de junio de 2026.

Esta es la ruta oficial para mover el backend Ubuntu completo sin perder base
de datos, uploads, tokens FCM, secretos, API, realtime ni signaling.

## Estado real antes del corte

- API, PostgreSQL, storage, realtime y signaling: operativos.
- Verificación funcional: auth, perfil, publicaciones, mensajes privados,
  grupos, comunidades, notificaciones, videos, momentos, retos, rankings y
  salas de voz aprobados.
- Firebase Admin: configurado; prueba real FCM con `sent=1`, `failed=0`.
- Respaldo: checksum aprobado y restauración aislada de 97 tablas aprobada.
- TURN: preparado para Coturn gratuito en la mini PC, pero requiere abrir en el
  router TCP/UDP 3478 y UDP 49160-49200 hacia la IP local de la mini PC.
- La URL `trycloudflare.com` es temporal. Después del corte hay que crear un
  túnel nuevo y publicar su URL por el descubrimiento DuckDNS.

No borres ni formatees el Ubuntu anterior durante al menos siete días después
del cambio.

## Qué contiene el paquete cifrado

`export-mini-pc-migration.sh` produce un único `.tar.gz.enc` con:

- dump PostgreSQL en formato custom;
- todo el volumen de storage;
- `.env` real;
- cuenta Firebase Admin;
- código activo de API, proxy, realtime y signaling;
- checksums SHA-256 y metadata de conteos;
- importador para Ubuntu limpio.

No contiene backups históricos, logs ni `node_modules`.

## 1. Preparar el paquete desde Windows

Con la VM Ubuntu actual encendida, abre PowerShell en la raíz del repositorio:

```powershell
.\scripts\prepare-mini-pc-migration.ps1
```

El script pide una contraseña de al menos 12 caracteres, crea un respaldo
nuevo, lo verifica, cifra el paquete y lo descarga a `migration-output`.
Guarda esa contraseña fuera del PC; no queda escrita en el paquete.

Para otro usuario o IP:

```powershell
.\scripts\prepare-mini-pc-migration.ps1 `
  -UbuntuHost 192.168.56.100 `
  -UbuntuUser alvaro `
  -SshPort 22
```

## 2. Instalar la mini PC desde cero

Instala Ubuntu Server 24.04 LTS, crea un usuario con `sudo`, activa OpenSSH y
conecta la mini PC por cable Ethernet. En el router reserva una IP DHCP fija
para esa máquina.

Desde Windows autoriza la llave existente:

```powershell
.\scripts\authorize-vm-ssh.ps1 -User TU_USUARIO -HostName IP_MINI_PC -Port 22
```

## 3. Copiar el paquete

```powershell
scp -r .\migration-output\* TU_USUARIO@IP_MINI_PC:~/
```

En la mini PC:

```bash
chmod 700 ~/import-mini-pc-migration.sh ~/verify-mini-pc-migration.sh
~/verify-mini-pc-migration.sh ~/conectate-mini-pc-*.tar.gz.enc
~/import-mini-pc-migration.sh ~/conectate-mini-pc-*.tar.gz.enc
```

El importador instala Docker desde el repositorio oficial, conserva cualquier
directorio previo con sufijo `.pre-migration-*`, restaura base y storage,
construye los cinco servicios, comprueba Firebase y configura:

- inicio automático del stack;
- watchdog cada dos minutos;
- backup diario verificado;
- simulacro semanal de restauración.

El importador se niega a sobrescribir un volumen PostgreSQL existente. Está
diseñado para una mini PC nueva.

Al terminar, cierra y vuelve a abrir la sesión SSH para que el grupo `docker`
quede aplicado al usuario (`newgrp docker` también sirve).

## 4. Activar TURN gratuito

Usa un dominio que apunte a la IP pública de tu casa. Puede ser DuckDNS:

```bash
cd ~/conectate-local-fallback
bash scripts/configure-mini-pc-turn.sh conectate-alvaro.duckdns.org
```

El script genera el secreto TURN, configura Coturn y recrea signaling. Luego,
en el router reenvía hacia la IP local mostrada por el script:

- TCP 3478;
- UDP 3478;
- UDP 49160-49200.

No abras PostgreSQL 5433, API 3001 ni realtime 3002 a Internet.

## 5. Publicar el backend sin pagar

Desde Windows inicia el Quick Tunnel en la mini PC y actualiza el TXT de
descubrimiento DuckDNS:

```powershell
.\scripts\start-conectate-server.ps1 `
  -User TU_USUARIO `
  -HostName IP_MINI_PC `
  -Port 22
```

Este comando actualiza también el endpoint Android/web local. El Quick Tunnel
es gratuito, pero su URL cambia cuando se recrea. El watchdog de Windows debe
seguir activo mientras se use este modo temporal.

## 6. Compuerta final antes del cambio

En la mini PC:

```bash
cd ~/conectate-local-fallback
STRICT_PRODUCTION=true bash scripts/verify-ubuntu-production.sh
```

El resultado obligatorio es `FAIL: 0`. Comprueba además:

```bash
docker compose ps
curl -fsS http://127.0.0.1:3080/ready
systemctl list-timers --all | grep conectate
```

Pruebas manuales con dos teléfonos y la app cerrada:

1. mensaje privado y de grupo;
2. llamada de voz y videollamada entrante;
3. cámara y pantalla desde redes diferentes;
4. reacción y comentario;
5. notificación de momento/video;
6. abrir cada notificación en su destino correcto.

## 7. Corte definitivo con mínima pérdida

1. Avisa una ventana corta de mantenimiento.
2. Evita publicaciones/mensajes durante el corte.
3. Ejecuta otra vez `prepare-mini-pc-migration.ps1` para obtener el respaldo
   final, no reutilices el paquete de ensayo.
4. Importa ese último paquete en la mini PC.
5. Configura TURN, túnel y descubrimiento.
6. Ejecuta la compuerta estricta y las pruebas de dos teléfonos.
7. Apaga el stack Ubuntu viejo, pero conserva su disco y backups siete días.

## Rollback

Si el verificador o una prueba crítica falla:

1. no borres nada en la mini PC;
2. vuelve a encender el Ubuntu anterior;
3. ejecuta `scripts/start-conectate-server.ps1` apuntando al servidor anterior;
4. confirma `/ready` y notificaciones;
5. investiga la mini PC fuera de producción.
