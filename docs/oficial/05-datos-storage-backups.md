# 05 — Datos, storage y backups

La estrategia de datos debe proteger la base de datos, los archivos de usuario y la continuidad del servicio.

## Principio principal

Servicios críticos en SSD. Archivos pesados y backups en discos externos.

## PostgreSQL

PostgreSQL debe mantenerse en SSD:

- más rápido;
- menos latencia;
- menos riesgo de desconexión USB;
- mejor recuperación.

No mover PostgreSQL principal a HDD/USB salvo decisión técnica explícita y backup probado.

## Storage de usuario

Tipos de archivos:

- fotos de perfil;
- portadas;
- imágenes de publicaciones;
- videos;
- thumbnails;
- stickers;
- documentos;
- adjuntos de chats;
- media de comunidades;
- exports temporales.

Estructura recomendada para disco de media:

```text
/mnt/almacenamiento/
├── uploads/
│   ├── profiles/
│   ├── covers/
│   ├── posts/
│   ├── videos/
│   ├── stories/
│   ├── documents/
│   ├── chats/
│   ├── groups/
│   ├── communities/
│   └── stickers/
├── cache/
│   ├── thumbnails/
│   └── optimized/
├── temp/
├── exports/
└── backups_media/
```

Reglas:

- rutas configurables por env;
- no hardcodear `/mnt/...` dentro del código;
- guardar metadata en PostgreSQL;
- guardar archivo físico en storage;
- generar thumbnails/optimizados;
- limpiar temporales;
- limitar tamaño y duración de videos.

## Disco de backup

El disco externo de backup esperado:

```text
/mnt/conectate-backup
```

Formato recomendado:

```text
ext4
```

Motivo:

- permisos Linux correctos;
- mejor confiabilidad en servidor Ubuntu;
- evitar errores NTFS/MFT;
- menos dependencia de drivers NTFS.

Estructura esperada:

```text
/mnt/conectate-backup/
└── conectate-backups/
    ├── 20260624T202151Z/
    ├── 20260627T203408Z/
    └── latest -> 20260627T203408Z
```

## Backups

Scripts relevantes:

```text
infrastructure/local-fallback/scripts/backup-ubuntu-primary.sh
infrastructure/local-fallback/scripts/verify-backup.sh
infrastructure/local-fallback/scripts/restore-drill.sh
```

Timers:

```bash
systemctl status conectate-backup.timer --no-pager
systemctl status conectate-restore-drill.timer --no-pager
```

Un backup válido debe incluir:

- `postgres.dump`;
- `storage.tar.gz`;
- `docker-compose.yml`;
- metadata;
- checksums;
- verificación OK.

## Backup no es backup hasta probar restore

Se debe ejecutar periódicamente un restore drill:

- crear base aislada;
- restaurar dump;
- contar tablas;
- validar archivos;
- no tocar producción.

La documentación o logs deben indicar:

```text
RESTORE_DRILL_OK=true
RESTORED_TABLES=...
```

## Limpieza de backups

La retención debe considerar:

- espacio disponible;
- backups diarios;
- backups antes de releases;
- backups antes de migraciones;
- backups antes de tocar auth/mensajes/storage.

No borrar el último backup funcional.

## Performance de archivos

Si hay millones de archivos:

- no meter todo en una sola carpeta;
- particionar por fecha/userId/hash;
- ejemplo:

```text
uploads/videos/2026/07/user_123/abcd1234.mp4
uploads/profiles/user_123/avatar.webp
cache/thumbnails/ab/cd/abcd1234.webp
```

## Cache backend

El backend actual contiene cache TTL en memoria en `data-routes.js`.

Esto sirve para bajo volumen, pero:

- se pierde al reiniciar;
- no comparte estado entre procesos;
- no es ideal para crecer.

Redis puede agregarse después para:

- perfiles;
- conteos no leídos;
- presencia;
- listas de salas;
- feed de videos;
- rate limits;
- sesiones temporales.

Si se agrega Redis:

- dejar PostgreSQL como fuente de verdad;
- Redis solo cache/ephemeral;
- invalidar por eventos;
- documentar TTL por entidad.

