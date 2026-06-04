# Migraciùn 100% Ubuntu ù guùa rùpida

## 1. Enciende la VM VirtualBox y conùctate por SSH

Desde CMD/PowerShell en Windows:

```powershell
ssh -p 2222 alvaro@127.0.0.1
```

(Ajusta usuario/puerto si tu VM usa otros valores.)

## 2. Sube el cùdigo actualizado desde Windows

En la **raùz del repo** (`d:\red social - copia`):

Requisito: **Git for Windows** (tar/rsync) y **OpenSSH de Windows** (ssh/scp). No uses WSL para el deploy (en WSL `127.0.0.1` no llega a la VM). El script excluye `node_modules` y ejecuta npm en Ubuntu.

Si el deploy se queda colgado en "Sincronizando...": pulsa **Ctrl+C** y vuelve a ejecutar con la version actual del script (ya no usa `tar | ssh`).

Para no escribir la contrasena en cada paso, configura clave SSH una vez (PowerShell):

```powershell
ssh-keygen -t ed25519 -N '""' -f "$env:USERPROFILE\.ssh\id_ed25519"
type "$env:USERPROFILE\.ssh\id_ed25519.pub" | ssh -p 2222 alvaro@127.0.0.1 "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

```powershell
cd "d:\red social - copia"
powershell -ExecutionPolicy Bypass -File scripts\deploy-ubuntu.ps1
```

Con URL pùblica fija (recomendado si ya tienes ngrok estable):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/deploy-ubuntu.ps1 -PublicBaseUrl "https://TU-URL.ngrok-free.dev"
```

Copia manual rùpida (solo backend):

```powershell
# No uses scp -r (copia node_modules). Usa deploy-ubuntu.ps1 con rsync.
```

## 3. En Ubuntu: levantar servicios

```bash
cd ~/conectate-local-fallback
bash scripts/repair-ubuntu-primary.sh
ngrok http 3080
```

**Importante:** ngrok debe apuntar al puerto **3080** (no 3001).

## 4. Actualiza URLs en clientes

Copia la URL HTTPS de ngrok y ponla en:

- `Conectate-web/.env` ? `VITE_FALLBACK_API_URL=...`
- `secrets.properties` ? `FALLBACK_API_URL=...`

Deja vacùos `SUPABASE_URL` y claves Supabase.

## 5. Recompila Android

```powershell
cd app
..\gradlew assembleDebug
```

## 6. Verifica salud del servidor

En Ubuntu:

```bash
curl -sS http://127.0.0.1:3001/health
curl -sS http://127.0.0.1:3080/health
```

Desde Windows (con ngrok activo):

```powershell
curl -H "ngrok-skip-browser-warning: true" https://TU-URL.ngrok-free.dev/health
```

## Quù funciona en Ubuntu ahora

- Auth, registro, migraciùn de contraseùa desde Supabase
- Feed, posts, comentarios, reacciones
- Mensajes directos, grupos, comunidades (bùsico)
- Reels/videos (web + APK vùa `/v1/data/videos` y PostgREST)
- Amistades (solicitudes)
- Notificaciones, perfiles, bùsqueda, seguidores
- Storage de archivos
- Salas de voz y llamadas (signaling Socket.IO)

## Mùdulos con datos legacy (CSV/import)

Moments, retos, monetizaciùn y admin usan tablas que se cargan importando CSV a `imports/` en la VM. Ver `infrastructure/local-fallback/README.md`.
