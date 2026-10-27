# 76 — Servidor: limpieza, actualización y verificación

Fecha: **26/10/2026, America/Bogota**. Ámbito: servicios de Novvin en la mini PC Ubuntu de producción. Complementa la [auditoría móvil 75](75-cierre-movil-167-2026-10-25.md); no sustituye sus verificaciones pendientes de APK, dispositivos y rendimiento.

## Resultado observado

Los servicios actualizados están operativos y el ingreso público `https://conectate.tail9b267c.ts.net/ready` responde HTTP 200. Se comprobaron **14 contenedores persistentes en ejecución**, ninguno con estado unhealthy. Las instancias de PostgreSQL, Redis, edge, TURN, vista previa de documentos y Ferrenorte conservaron sus identidades durante las actualizaciones de Novvin. Edge recibió una recarga gradual de Nginx tras validar su configuración.

El host usa Ubuntu 24.04.3 LTS, kernel `6.8.0-147-generic` y Docker 29.6.0. El reloj informa NTP sincronizado; su zona America/Lima tiene el mismo desplazamiento UTC−5 de Bogotá en esta fecha. No se observaron unidades systemd fallidas, reinicios repetidos ni OOM en la instantánea revisada.

**Pendiente administrativo:** el índice local de APT, actualizado ese día, ofrece **93 paquetes**. Incluye Docker, containerd, Tailscale y componentes de Ubuntu. `sudo -n` exige contraseña; esta sesión no tiene acceso administrativo para instalarlos. Los contenedores de Novvin sí quedaron actualizados. No se presenta el sistema operativo como totalmente actualizado.

## Cambios aplicados

1. Se retiraron **7,213 GB de caché de compilación** sin uso durante al menos siete días, reservando 20 GB de caché. Se conservaron volúmenes, bases, medios, respaldos e imágenes de recuperación. Se eliminaron únicamente las dos etiquetas de imágenes QA creadas para esta revisión cuando terminaron sus pruebas.
2. Se contrastaron Dockerfiles y runtimes activos. IA seguía usando Node 20.20.2 aunque el repositorio ya indicaba Node 22; otros servicios ejecutaban Node 22.23.2. Se reconstruyeron seis imágenes y se actualizaron siete contenedores, incluido el actualizador de firmas, a **Node 22.23.4**.
3. La auditoría npm de producción encontró `body-parser` afectado en API y gateway, Multer afectado en API y Nodemailer afectado en API. Se aplicaron actualizaciones dirigidas dentro de sus versiones mayores: **body-parser 2.4.0**, **Multer 2.5.0** y **Nodemailer 10.1.4**. Se conservaron los metadatos de plataforma de los paquetes nativos que no cambiaron.
4. Se reconstruyeron y sustituyeron solamente API y gateway en una segunda actualización. `npm audit --omit=dev --json` informó **cero vulnerabilidades conocidas** en ambas imágenes finales, cuyos identificadores coinciden con los contenedores en ejecución. Este resultado corresponde al alcance y la base de avisos de npm consultados; no cubre las actualizaciones pendientes del host.
5. Se incorporó una regresión de destinatario de correo: la versión anterior de Nodemailer dirigía el sobre al destinatario incluido dentro de un comentario anidado. La versión corregida conserva el destinatario previsto. La prueba usa transporte en memoria, sin SMTP ni mensajes enviados a personas.

La corrección de `body-parser` evita un cierre por datos posteriores a un cuerpo comprimido. La de Multer corrige el cierre asociado a filtros asíncronos pendientes tras terminar una petición. Son avisos de las bibliotecas; no se afirma que se haya observado explotación en producción.

## Imágenes finales

Los identificadores abreviados siguientes proceden de Docker; las evidencias privadas conservan los identificadores completos.

| Servicio | Imagen local | ID abreviado | Runtime |
| --- | --- | --- | --- |
| API | `novvin-api:maintenance20261026-deps` | `4f97240f0229` | Node 22.23.4 |
| Gateway | `novvin-realtime:maintenance20261026-deps` | `be7705ac6218` | Node 22.23.4 |
| IA | `novvin-ai:maintenance20261026` | `19bb7ad1af48` | Node 22.23.4 |
| Signaling | `novvin-signaling:maintenance20261026` | `aca9fa412065` | Node 22.23.4 |
| Antivirus de archivos y actualizador | `novvin-storage-av:maintenance20261026` | `cdfcfc101e24` | Node 22.23.4 |
| Antivirus de encuestas | `novvin-survey-av:maintenance20261026` | `242804190423` | Node 22.23.4 |

La segunda actualización terminó el 26/10 a las **19:08:57 de Bogotá**. Las comparaciones de configuración canónica de Compose admitieron únicamente cambios de referencias de imagen: siete en la primera actualización y dos en la segunda. Se mantuvieron puertos, entorno, recursos, capacidades, restricciones, redes y volúmenes. Cada servicio pasó su comprobación de salud antes de continuar. Se conservaron archivos e imágenes anteriores para revertir.

## Evidencia de pruebas

| Comprobación | Resultado |
| --- | --- |
| API con las dependencias finales y Node 22.23.4 | **938 casos: 929 aprobados, 9 omitidos, cero fallos**. Incluye la regresión nueva de destinatario. Las omisiones requieren entornos de integración específicos y no se cuentan como aprobadas. |
| IA | Seis archivos de pruebas aprobados: contexto, smoke, conocimiento, búsqueda semántica, resumen y servidor. |
| Gateway | Autenticación y autorización aprobadas en la imagen final. |
| Signaling | **59 casos: 58 aprobados, 1 omitido, cero fallos**. |
| Restauración de respaldo | Dos restauraciones aisladas aprobadas, **256 tablas** en cada una. Los contenedores temporales se eliminaron después de comprobar su etiqueta de propiedad. Sin conexión a la base pública, puertos publicados ni escrituras de restauración en producción. |
| Escaneo antivirus | Ambos servicios detectaron EICAR, prueba inocua de 68 bytes enviada en memoria al protocolo de escaneo. Sin archivos de usuario modificados. |
| Validación multimedia en la API activa | Seis casos aprobados: Excel OOXML renombrado `.xls` en chat/grupo/comunidad, AAC, Opus y rechazo de Excel falsificado. No se crearon objetos almacenados ni publicaciones. |
| Endpoint público tras la última actualización | Cinco respuestas HTTP 200: **49,981; 33,163; 27,703; 33,651; 32,569 ms**, medidas desde el servidor. Son sondas de salud, no latencia de usuarios, propagación de mensajes ni capacidad bajo carga. |

Las primeras ejecuciones del contenedor de pruebas no contenían todos los SQL, scripts, fuentes de clientes y dependencias que verifica la batería. Se completó la estructura aislada del repositorio, las herramientas de shell y los permisos de su directorio temporal. Los controles de producción permanecieron activos. Un fallo de red de npm durante la construcción se reintentó antes del despliegue; no se sustituyó un servicio por una imagen fallida.

## Recursos y exposición

En la instantánea posterior se observaron 3.581 MiB de RAM usada y 11.333 MiB disponibles, con 59 MiB de swap. El sistema de archivos raíz tiene 108 GB disponibles; el de Docker, 385 GB. Los puertos publicados de API, gateway, edge, PostgreSQL y la app Ferrenorte están ligados a `127.0.0.1`.

La base de Novvin ocupa aproximadamente 113 MB. La instantánea inicial mostró 11 conexiones y ninguna transacción de más de cinco minutos. Signaling declaró Redis conectado y cero salas de voz, llamadas o directos activos antes de actualizarse. Eso permitió su sustitución sin interrumpir una sesión multimedia activa observada; no certifica una prueba de llamadas entre redes.

Las muestras acotadas de logs y los estados de salud se conservan como evidencia. No se borraron logs para aparentar una limpieza ni se ejecutó una prueba de estrés sobre usuarios reales. Estos recursos y sondas no acreditan una capacidad de 500.000 usuarios.

## Respaldos, reversión y actualización del host pendiente

Los respaldos privados están en el servidor, dentro de:

- `/home/alvaro/novvin-candidates/security-20261026/state/`
- `/home/alvaro/novvin-candidates/security-20261026-deps/state/`

Cada directorio de estado usa modo 0700. Se guardaron configuración Compose, fuentes previas, referencias de imágenes y respaldo PostgreSQL en formato custom con modo 0600. Los archivos contenían 1.305 entradas de archivo y fueron restaurados realmente en el entorno aislado. La segunda copia ocupa 6.288.496 bytes. Los volcados y secretos permanecen fuera de Git y de los informes públicos.

La actualización de contenedores tiene comprobaciones de imagen esperada, configuración, salud, endpoint público y contenedores protegidos. Ante un fallo, su mecanismo restaura configuración e imágenes previas. Una reversión de contenedores no exige restaurar la base ni perder mensajes recientes; no hubo migraciones de datos en estas actualizaciones.

Se preparó y verificó con `bash -n` el script [upgrade-host-reviewed.sh](../../infrastructure/local-fallback/scripts/upgrade-host-reviewed.sh). Comprueba host y Ubuntu 24.04, genera plan, conserva configuraciones, impide eliminaciones de paquetes y requiere despliegue y restauración aprobados. No reinicia automáticamente la máquina. Actualizar Docker o Tailscale puede interrumpir conexiones y requiere una ventana de mantenimiento.

Cuando un administrador disponga de la consola autorizada, el comando preparado es:

```bash
sudo systemd-run --unit=novvin-host-maintenance-20261026 --collect \
  /home/alvaro/novvin-candidates/security-20261026/upgrade-host-reviewed.sh --apply
```

El trabajo continúa aunque se corte SSH y deja diagnóstico en `/var/log/novvin-host-maintenance/`. `--plan` permite revisar el plan sin cambiar paquetes instalados. Hasta que se ejecute y verifique ese paso, los 93 paquetes del host siguen pendientes. No se solicita publicar contraseñas en el chat ni se usa Docker para eludir la restricción de sudo.

## Referencias

- [Versiones y soporte de Node.js](https://nodejs.org/en/about/previous-releases), [parche 22.23.4](https://nodejs.org/en/blog/release/v22.23.4).
- [Aviso oficial de body-parser](https://github.com/expressjs/body-parser/security/advisories/GHSA-27r3-jxmh-p828), [Multer](https://github.com/expressjs/multer/security/advisories/GHSA-q7qp-rjg7-q26m) y [Nodemailer](https://github.com/nodemailer/nodemailer/security/advisories/GHSA-8c8m-p3m8-cjg8).
- [Limpieza acotada de caché Docker](https://docs.docker.com/reference/cli/docker/buildx/prune/).
- [Gestión de paquetes Ubuntu](https://ubuntu.com/server/docs/how-to/software/package-management/).
