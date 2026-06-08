# Migracion de multimedia Supabase Storage a Ubuntu

Este procedimiento copia todos los buckets de Supabase Storage al storage local de Ubuntu y luego cambia las URLs antiguas de Supabase en Postgres para que apunten a `PUBLIC_BASE_URL`.

No ejecutes el paso `--apply` hasta que el importador haya terminado sin errores.

## 1. Entrar al servidor Ubuntu

Desde Windows PowerShell o CMD:

```powershell
ssh TU_USUARIO@IP_DE_TU_UBUNTU
```

Si tu Ubuntu esta dentro de VirtualBox y entras por un puerto reenviado:

```powershell
ssh -p PUERTO TU_USUARIO@127.0.0.1
```

Todo lo siguiente se ejecuta dentro del SSH de Ubuntu, no en Android Studio.

## 2. Preparar variables

Entra a la carpeta del backend local:

```bash
cd ~/conectate-local-fallback
bash scripts/sync-env-from-example.sh
```

Edita `.env`:

```bash
nano .env
```

Verifica o completa estas variables:

```bash
PUBLIC_BASE_URL=https://TU_DOMINIO_O_NGROK
SUPABASE_URL=https://TU_PROYECTO.supabase.co
SUPABASE_SERVICE_ROLE_KEY=TU_SERVICE_ROLE_KEY
SUPABASE_STORAGE_BUCKETS=
SUPABASE_STORAGE_DRY_RUN=false
SUPABASE_STORAGE_OVERWRITE=false
SUPABASE_STORAGE_PAGE_SIZE=100
SUPABASE_STORAGE_REWRITE_APPLY=false
```

Notas:

- Usa la `service_role key`, no la anon key.
- `PUBLIC_BASE_URL` debe ser la URL publica que usan la web y la APK para llegar a Ubuntu.
- Si quieres importar solo algunos buckets, ponlos separados por coma en `SUPABASE_STORAGE_BUCKETS`, por ejemplo `avatars,post-media,videos`.
- Si dejas `SUPABASE_STORAGE_BUCKETS` vacio, el script intenta importar todos los buckets visibles.

## 3. Levantar servicios

```bash
docker compose up -d --build
```

Comprueba que el backend local responda:

```bash
curl -f http://127.0.0.1:3080/ready
curl -f http://127.0.0.1:3080/health
```

## 4. Simular importacion

Este paso solo lista lo que va a copiar:

```bash
docker compose run --rm api npm run import:supabase-storage -- --dry-run
```

Si hay errores de permisos, revisa `SUPABASE_SERVICE_ROLE_KEY`.

## 5. Copiar multimedia a Ubuntu

```bash
docker compose run --rm api npm run import:supabase-storage
```

El importador guarda archivos en el volumen `conectate-fallback-storage` y registra cada archivo en `fallback_storage_objects`.

Si necesitas volver a descargar archivos que ya existen:

```bash
docker compose run --rm api npm run import:supabase-storage -- --overwrite
```

## 6. Verificar conteos

```bash
docker compose exec db psql -U conectate -d conectate_fallback -c "SELECT bucket_id, COUNT(*) FROM fallback_storage_objects GROUP BY bucket_id ORDER BY bucket_id;"
```

Tambien puedes probar una URL copiada:

```bash
curl -I "https://TU_DOMINIO_O_NGROK/storage/v1/object/public/avatars/RUTA_DE_UN_ARCHIVO"
```

Debe responder `200` o `304`.

## 7. Simular reescritura de URLs

Despues de copiar los archivos, revisa cuantas URLs de Supabase quedan en la base local:

```bash
docker compose run --rm api npm run rewrite:supabase-storage-urls -- --dry-run
```

## 8. Aplicar reescritura de URLs

Cuando el `dry-run` se vea correcto:

```bash
docker compose run --rm api npm run rewrite:supabase-storage-urls -- --apply
```

Esto reemplaza URLs como:

```text
https://TU_PROYECTO.supabase.co/storage/v1/object/public/avatars/archivo.png
```

por:

```text
https://TU_DOMINIO_O_NGROK/storage/v1/object/public/avatars/archivo.png
```

## 9. Reiniciar y probar

```bash
docker compose restart api edge realtime-gateway signaling
```

Verifica en la web y APK:

- Fotos de perfil antiguas.
- Imagenes de publicaciones.
- Videos.
- Fotos enviadas por chat.
- Subida de archivos nuevos.
- Reproduccion de videos desde Ubuntu.

## 10. Despues de verificar

Cuando todo cargue desde Ubuntu:

```bash
docker compose run --rm api npm run rewrite:supabase-storage-urls -- --dry-run
```

El resultado debe quedar vacio o sin coincidencias importantes. En ese punto ya se puede eliminar el ultimo uso operativo de Supabase Storage.

## Mini PC

Para mover esto a la mini PC mas adelante, migra estos tres elementos:

- El archivo `.env` sin publicar claves.
- El volumen o backup de Postgres `conectate-fallback-db`.
- El volumen o backup de archivos `conectate-fallback-storage`.

Comandos base para backup en el Ubuntu actual:

```bash
docker compose exec db pg_dump -U conectate conectate_fallback > conectate_fallback.sql
docker run --rm -v conectate-fallback-storage:/data -v "$PWD":/backup alpine tar czf /backup/conectate-storage.tar.gz -C /data .
```
