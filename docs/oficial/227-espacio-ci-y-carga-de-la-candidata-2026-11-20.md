# 227. Espacio del runner Android y capacidad de la candidata

**NO APROBADO para producción.** El ensayo de la API publicada en `c51bdcfb` obtuvo **VERIFICADO** en el laboratorio. El trabajo Android de feature perdió su runner por falta de espacio. El commit `eddaad0c` corrige la preparación de las máquinas temporales de GitHub. El código de la API y la aplicación conserva la revisión usada en el ensayo. Siguen pendientes libxml2 en Debian, las pruebas físicas y las comprobaciones de capacidad y recuperación en el Ubuntu objetivo. [Informe 226](226-capacidad-conmutacion-y-ci-2026-11-20.md) · [Matriz de los 23 puntos](224-certificacion-independiente-2026-11-20.md) · [Datos de las ejecuciones](evidencias/227-espacio-ci-y-carga-de-la-candidata.json)

## Capacidad sobre el código publicado

Dos instancias de la API y dos gateways Realtime compartieron dos CPU lógicas con PostgreSQL 16.15, Redis y el generador. Se aplicaron las 37 migraciones y el usuario de la API carecía de privilegios de superusuario. Diez cuentas sintéticas alternaron feed, perfiles, notificaciones y chat. Cinco receptores se distribuyeron entre los gateways. El generador admitió hasta 16 operaciones simultáneas.

| Tramo a 16 llegadas por segundo | Resultado |
|---|---:|
| Duración | 2.700 s |
| Planificadas / despachadas / HTTP 2xx | 43.200 / 43.200 / 43.200 |
| HTTP 429 / 503 de saturación / otros 5xx / errores de red | 0 / 0 / 0 / 0 |
| Descartes por concurrencia / retraso / posteriores al corte | 0 / 0 / 0 |
| Mensajes entregados dentro de tres segundos / esperados | 10.800 / 10.800 |
| Fallos funcionales | 0 |

| Operación | Peticiones | Mediana del p95 por ventana | Mayor p99 de una ventana |
|---|---:|---:|---:|
| feed | 10.800 | 9,55 ms | 43,42 ms |
| profile | 10.800 | 11,94 ms | 45,25 ms |
| message | 10.800 | 31,98 ms | 1026,12 ms |
| notifications | 10.800 | 9,67 ms | 43,95 ms |

El informe 226 recoge una medición anterior de 45 minutos a 12 llegadas/s y dos controles de 12 minutos a 16 llegadas/s con resultados distintos. El JSON contiene las ventanas agrupadas por operación, los contadores de carga, la memoria, PostgreSQL, Redis y los percentiles de CPU de los procesos. El Ubuntu activo recibió sondas de disponibilidad.

## Integración continua y disco

- main, Android Quality Gate. 4/4 trabajos aprobados. [Ejecución 44760746856](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44760746856)
- feature/live-gifts, Android Quality Gate. 2/4 trabajos aprobados. [Ejecución 44760746848](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44760746848)
- main, Web and service quality. 6/6 trabajos aprobados. [Ejecución 44760746736](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44760746736)
- feature/live-gifts, Web and service quality. 6/6 trabajos aprobados. [Ejecución 44760746841](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44760746841)

En main se abrieron 592 archivos XML del ZIP publicado por GitHub. Contienen 3.553 casos, cero omitidos, cero fallos y cero errores. El ZIP lleva la marca del último caso iniciado. En feature, el runner registró `No space left on device` al escribir su propio diagnóstico. La ejecución terminó durante Gradle y el paso de subida de informes quedó pendiente. Este diagnóstico corresponde a `c51bdcfb`. Para el fallo anterior en `77b507f0`, la causa disponible se documenta en 226.

**CORREGIDO en el workflow.** Antes de compilar Android se registran bloques e inodos disponibles y se liberan los directorios preinstalados del SDK de .NET y del paquete CodeQL. El paso se limita a Linux alojado por GitHub y se comparte entre los trabajos de calidad y release. Las herramientas que usa el proyecto siguen instaladas. Los pasos de compilación, auditoría, firma y verificación permanecen en el workflow. [Preparación de .NET en la imagen de GitHub](https://github.com/actions/runner-images/blob/ubuntu26/20261111.196/images/ubuntu/scripts/build/install-dotnetcore-sdk.sh) · [Instalación de CodeQL en la imagen](https://github.com/actions/runner-images/blob/ubuntu26/20261111.196/images/ubuntu/scripts/build/install-codeql-bundle.sh)

El mismo Bash se ejecutó dos veces en un sistema de archivos tmpfs montado en un espacio de nombres privado. La primera ejecución liberó 1.974.272 bytes y 244 inodos de prueba. La segunda conservó ambas cifras. Se comprobaron también los archivos vecinos del JDK, Android SDK, runtime de .NET y checkout. Se repitió en dos volúmenes artificiales agotados por separado. Una escritura de informe falló con ENOSPC tanto con 64 KiB libres como con cero inodos. Tras la preparación, las dos escrituras tuvieron éxito. Estos tamaños corresponden a los datos artificiales de la prueba.

## Dependencias pendientes

En la alternativa privada de Ubuntu para Office, Canonical vinculaba 16 declaraciones de afectación a nueve paquetes fuente instalados. Se consultaron los cuatro canales públicos de Launchpad por cada fuente. Siete carecían de una edición en propuestas. Dos tenían una edición propuesta, para GStreamer y util-linux. Los archivos de cambios de esas ediciones no mencionaban los cuatro avisos de esos dos paquetes que se estaban comprobando. La publicación de un paquete en propuestas por sí sola deja pendiente la verificación de la corrección. El JSON conserva los enlaces y las huellas de los registros consultados. [Datos VEX de Ubuntu](https://documentation.ubuntu.com/security/security-updates/vex/) · [Archivo principal de Ubuntu](https://launchpad.net/ubuntu/+archive/primary)

## Servidor y artefacto

A las 10:53 de Bogotá, los accesos público y privado a Ubuntu devolvieron HTTP 200 con TLS 1.3, 25 capacidades y backend `cc6a022e`. La inspección SSH interna requiere reautenticación del proveedor. La APK de uso interno 9.1.43 (183) conserva sus fuentes Android y la firma comprobada en la entrega previa. La revisión `c51bdcfb` se publicó en ambas ramas, recibió dos cancelaciones de Vercel y dejó los tres alias públicos en `4517f8d1`. [Corrección del runner](https://github.com/Alvaro-mendoza-12/red-social-android/commit/eddaad0cc189732daae03d6fb5b4639033718ccb)
