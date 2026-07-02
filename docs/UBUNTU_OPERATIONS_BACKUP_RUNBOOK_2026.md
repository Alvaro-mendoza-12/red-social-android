# Operacion y copias del backend Ubuntu

Fecha de creacion: 2026-06-19  
Fecha de ultima revision: 2026-06-19  
Estado: implementado y validado en la VM beta

## 1. Alcance

Este documento describe la operacion del backend primario Ubuntu, sus comprobaciones automaticas, las copias de PostgreSQL y storage, la prueba de restauracion y el cambio controlado del endpoint publico. Aplica a la VM actual y sirve como base para la migracion a Ubuntu Server en la mini PC.

La VM es un entorno beta. Un Cloudflare Quick Tunnel permite pruebas externas sin abrir puertos y sin depender de Supabase, pero su URL cambia al reiniciarse y no ofrece un SLA de produccion. La mini PC debe usar posteriormente un Cloudflare Tunnel administrado con dominio estable o una conectividad publica equivalente.

## 2. Servicios protegidos

| Servicio | Contenedor | Puerto local | Persistencia |
|---|---|---:|---|
| PostgreSQL 16 | `conectate-local-fallback-db-main` | 5433 | `conectate-fallback-db` |
| API Ubuntu | `conectate-local-fallback-api-main` | 3001 | PostgreSQL y storage |
| Realtime | `conectate-local-fallback-realtime-main` | 3002 | JWT y API |
| Signaling | `conectate-local-fallback-signaling-main` | 3003 interno | memoria y API |
| Edge Nginx | `conectate-local-fallback-edge-main` | 3080 | configuracion del repositorio |
| Storage | volumen `conectate-fallback-storage` | por API | fotos, audios, videos y archivos |

## 3. Politica de copias

- Frecuencia: todos los dias a las 03:15, con hasta 20 minutos de dispersion.
- Retencion local: siete dias.
- PostgreSQL: `pg_dump` custom comprimido, sin propietarios ni privilegios del origen.
- Storage: `tar.gz` completo de `/data/storage`.
- Integridad: SHA-256, `pg_restore --list` y lectura del indice del tar.
- Atomicidad: la copia sale de `.partial-*` solo despues de superar las verificaciones.
- Ultima copia valida: `~/conectate-backups/latest`.
- Restauracion de prueba: domingos a las 04:30 en una base temporal aislada.

La copia local no protege frente a la perdida del disco anfitrion. En la mini PC, `BACKUP_ROOT` debe apuntar a un segundo disco y debe existir una copia adicional desconectada periodicamente.

## 4. Instalacion inicial

Desde PowerShell de Windows:

```powershell
ssh -t -i "$env:USERPROFILE\.ssh\codex_conectate_vm" -p 2222 alvaro@127.0.0.1 "cd ~/conectate-local-fallback && bash scripts/install-ubuntu-operations.sh"
```

Despues de configurar la red administrativa host-only, usar preferentemente:

```powershell
ssh -t -i "$env:USERPROFILE\.ssh\codex_conectate_vm" alvaro@192.168.56.100 "cd ~/conectate-local-fallback && bash scripts/install-ubuntu-operations.sh"
```

El comando solicita la contrasena `sudo` de `alvaro` e instala:

- `conectate-stack.service`
- `conectate-backup.timer`
- `conectate-health.timer`
- `conectate-restore-drill.timer`

## 5. Comprobacion diaria

```bash
systemctl list-timers --all | grep conectate
systemctl status conectate-stack.service --no-pager
journalctl -u conectate-health.service -n 100 --no-pager
journalctl -u conectate-backup.service -n 100 --no-pager
ls -lah ~/conectate-backups/latest/
```

Resultados esperados: `HEALTH_OK`, `BACKUP_OK`, `BACKUP_VERIFIED` y los cinco servicios Docker saludables.

## 6. Ejecucion manual

```bash
cd ~/conectate-local-fallback
scripts/health-watchdog.sh
scripts/backup-ubuntu-primary.sh
scripts/verify-backup.sh
scripts/restore-drill.sh
```

`restore-drill.sh` nunca modifica `conectate_fallback`: crea `conectate_restore_drill_<fecha>`, restaura, cuenta las tablas y elimina la base mediante una trampa de salida.

## 7. Recuperacion

No restaurar sobre produccion sin conservar primero una copia del estado averiado.

1. Detener escrituras o poner la API en mantenimiento.
2. Crear una copia de emergencia si PostgreSQL responde.
3. Verificar el backup elegido con `verify-backup.sh <ruta>`.
4. Ejecutar `restore-drill.sh <ruta>`.
5. Restaurar en una base permanente separada y validar usuarios, conteos y archivos.
6. Cambiar la API solamente despues de la aceptacion funcional.

El reemplazo destructivo de produccion no se automatiza deliberadamente.

## 8. Endpoint publico dinamico

Android usa `RuntimeBackendEndpoint`; la web usa `runtimeBackend.ts`. Ambos consultan el TXT publico de `conectate-alvaro.duckdns.org` mediante DNS-over-HTTPS, conservan la ultima URL validada y solo cambian cuando la nueva URL HTTPS responde en `/ready`.

`Conectate-web/public/backend-config.json` es el descriptor local de la web. No contiene secretos. El puntero remoto para APK instaladas es el TXT de DuckDNS, por lo que el repositorio puede permanecer privado.

Guardar una sola vez el token DuckDNS cifrado con DPAPI:

```powershell
powershell -ExecutionPolicy Bypass -File ".\scripts\configure-duckdns-discovery.ps1"
```

El token se solicita de forma interactiva y se guarda fuera del repositorio en `%LOCALAPPDATA%\Conectate\duckdns-token.dpapi`.

Iniciar un Quick Tunnel y actualizar Android/web local:

```powershell
cd "D:\red social - copia"
powershell -ExecutionPolicy Bypass -File ".\scripts\start-cloudflare-quick-tunnel.ps1"
```

Publicar el endpoint en el TXT para APK ya instaladas:

```powershell
powershell -ExecutionPolicy Bypass -File ".\scripts\start-cloudflare-quick-tunnel.ps1" -PublishDiscovery
```

`-PublishDiscovery` actualiza el TXT con TTL corto sin exponer el token ni realizar commits automaticos.

## 9. Objetivos beta

- RPO local: hasta 24 horas.
- Salud: cada 2 minutos.
- Recuperacion: `docker compose up -d` si falla `/ready`, PostgreSQL o un servicio.
- Retencion: siete copias diarias.
- Restaurabilidad PostgreSQL: prueba semanal real.

## 10. Migracion a mini PC

1. Instalar Ubuntu Server LTS, Docker Engine y Compose Plugin.
2. Crear usuario operativo y deshabilitar login root remoto.
3. Copiar repositorio, `.env` y secretos por canal cifrado.
4. Montar segundo disco y definir alli `BACKUP_ROOT`.
5. Restaurar primero en una base temporal y validar.
6. Instalar timers con `install-ubuntu-operations.sh`.
7. Configurar endpoint HTTPS estable.
8. Ejecutar `verify-ubuntu-production.sh` y pruebas cerradas.
9. Cambiar el descriptor remoto solo tras la aceptacion.

## 11. Evidencia del 2026-06-19

- Web: build de produccion correcto.
- Android: `:app:compileDebugKotlin` correcto.
- PostgreSQL: backup de 477390 bytes verificado.
- Storage: backup de 1366593560 bytes verificado por SHA-256 y lectura del tar.
- Restauracion aislada: 97 tablas restauradas.
- Watchdog: backend, PostgreSQL y cinco servicios saludables.

## 12. Evidencia del 2026-06-20

- Auditoria funcional Ubuntu: 21 PASS, 0 WARN, 0 FAIL.
- Flujos validados: auth, perfil, storage, publicaciones, comentarios, notificaciones, mensajes y audio, grupos, comunidades, videos, momentos, retos, rankings y salas de voz.
- APK debug reconstruida despues de activar el descubrimiento por DuckDNS TXT.
- Web reconstruida con el endpoint activo de la VM.
- Incidente detectado: el verificador original podia esperar indefinidamente.
- Correccion: timeout por comando, por peticion HTTP y para el bloque completo de modulos.
- Recuperacion verificada: cinco contenedores `healthy`, backup integro y watchdog correcto.
- Administracion estabilizada: adaptador host-only en `192.168.56.100`, separado del NAT usado para salida a Internet.
