# Antivirus privado de adjuntos de Encuestas

El daemon carga las firmas una vez y recibe bytes por INSTREAM. No publicar el
puerto 3310 en el host ni en el ingreso HTTPS. El cliente falla con 503 si no hay
un resultado final válido en diez segundos; nunca omite el antivirus ni vuelve
al proceso `clamscan` por archivo.

Integración prevista en Compose (el responsable del módulo integra este bloque):

```yaml
survey-antivirus:
  build: ./survey-antivirus
  restart: unless-stopped
  mem_limit: 2g
  cpus: 1
  pids_limit: 64
  read_only: true
  cap_drop: [ALL]
  security_opt: [no-new-privileges:true]
  tmpfs:
    - /tmp:rw,nosuid,noexec,size=64m,uid=10001,gid=10001,mode=0700
  volumes:
    - novvin-clamav-db:/var/lib/clamav:ro
```

La API utiliza `SURVEY_CLAMD_HOST=survey-antivirus`, `SURVEY_CLAMD_PORT=3310`.
Conservar el `singleJob` existente para rechazar simultáneos sin acumular cuerpos
en memoria. El límite del protocolo es 4 MiB; el servidor tiene un hilo y cola
de dos. `ConcurrentDatabaseReload no` evita mantener dos bases en memoria;
`SelfCheck 60` observa cambios de firmas. Durante recarga o indisponibilidad se
rechaza el adjunto y se permite reintentar. El volumen lo actualiza el mecanismo
`freshclam` existente: no iniciar un segundo actualizador sobre el mismo volumen.

Antes de habilitar: construir la imagen, verificar PING y probar PDF limpio,
EICAR, timeout, límite y memoria usando el UID 10001 y firmas reales. Las pruebas
de protocolo con un servidor local no certifican velocidad del daemon real.

Referencias: [INSTREAM](https://docs.clamav.net/manual/Usage/ClamdProtocol.html),
[escaneo con daemon](https://docs.clamav.net/manual/Usage/Scanning.html).
