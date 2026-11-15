# 154 · Plan de host vigente después de API153

El procedimiento administrativo de 149 fijaba la imagen API147. API153 se activó
después de entregar ese comando al propietario. La comprobación de imagen del
plan anterior ahora lo detiene **antes de instalar paquetes**. Su código,
artefactos y evidencia se conservan como referencia histórica.

Esta continuación renueva la candidata para la API vigente. No instala los nueve
paquetes ni cambia daemon, red o datos de producción. Sigue pendiente la
autenticación administrativa de Ubuntu solicitada al propietario.

## Comprobación actual

La lectura independiente de las 16:40 UTC del 15 de noviembre confirma:

- API `novvin-api:bottle153-1a09dbda`, imagen
  `sha256:449f2a78d302bf39e1dc6acf529d97f87468ca25f3a3b2f83c0ba21b9ebaab6a`.
- Contenedor `08f3b71d5b33226877969be72b77c4a9974b5686efe6142130a07a65082378c7`,
  iniciado a las `2026-11-15T16:30:29.709045214Z`, saludable, cero reinicios y sin OOM.
- Sin unidades fallidas; unos 11 GiB de memoria disponible. Docker mantiene
  `live-restore=false` y `sudo -n true` solicita contraseña.
- Los nueve objetivos de paquetes son los mismos de la tabla de 149. APT sigue
  difiriendo `distro-info`, `python3-distro-info` y `snapd`.

La herramienta versionada `host-maintenance149.py` y sus pruebas conservan sus
huellas. Se repitieron las dieciocho pruebas Linux, sin errores u omisiones, y
el preflight completo contra la nueva API. El dump restaurado en 149 y el
manifiesto de sus cinco archivos coinciden todavía. El alcance de aquel ensayo
continúa siendo esquema/datos de PostgreSQL, sin propietarios/ACL, restauración
de storage ni arranque de la aplicación.

El plan nuevo conserva las versiones anteriores/objetivo y cambia su timestamp,
la identidad API admitida y su huella. La aplicación captura otra vez el estado
actual bajo bloqueo antes de actuar y exige conservar contenedores, imágenes,
arranques y salud. Los planes o respaldos de más de 24 horas se rechazan.

## Comando vigente

Desde una consola Ubuntu de la mini PC, autenticar la tarea ya autorizada con:

```bash
sudo systemd-run --unit=novvin-host149-20261115 --collect --service-type=exec \
  /usr/bin/python3 \
  /home/alvaro/novvin-host149-20261115T164023Z/infrastructure/local-fallback/scripts/host-maintenance149.py \
  --plan /home/alvaro/novvin-host149-20261115T164023Z/plan149.json \
  --approved-sha256 7785d47187dbb25f1f9e110e111c84a84a4f54fc198e33e323b4e8519ec957cf \
  --apply
```

La contraseña se introduce solo en `sudo` en esa consola. La tarea continúa
independiente de la conexión SSH y conserva el resultado en
`/var/backups/novvin-host149-<fecha>/result.json`. Una instalación parcial necesita
revisar su recibo y los paquetes retenidos. El procedimiento conserva la
configuración previa, obtiene paquetes anteriores/nuevos y valida la recarga de
Docker antes de instalar. Los reinicios automáticos del host y la eliminación
de paquetes/respaldos quedan fuera del procedimiento.

Tras ejecutar se comprobarán independientemente versiones instaladas, salud,
arranques, `/ready`, `/health`, servicios y conectividad pública. **El preflight
aprobado todavía no es una actualización aplicada.**

## Trazabilidad

La evidencia está en `docs/quality/evidence/154-host-plan-refresh.json`. Los
recibos nuevos se conservan separados en `app/build/audit154/`; los de 149 no se
sobrescriben. La revisión de acceso Android152 conserva su propio estado y sus
fuentes sin publicar. No se modifica la decisión de salida para producción ni
se cierra la revisión móvil integral.
