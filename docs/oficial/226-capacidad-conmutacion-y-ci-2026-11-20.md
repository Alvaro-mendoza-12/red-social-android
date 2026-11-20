# 226. Capacidad sostenida, recuperación de PostgreSQL y Android CI

**NO APROBADO para producción.** Se midió la candidata con llegadas abiertas, se promovió una réplica física durante una sesión de chat y se corrigió el manejo de desconexiones en las subidas. La imagen de Office definida en las ramas estables mantiene pendiente CVE-2026-6653 en Debian 13. Siguen abiertas las pruebas físicas de llamadas, Bluetooth, Doze y FCM, la recuperación integral y la capacidad del Ubuntu objetivo. [Matriz de los 23 puntos](224-certificacion-independiente-2026-11-20.md) · [Correcciones anteriores](225-conversor-y-controles-de-integracion-2026-11-20.md) · [Evidencia del ciclo](evidencias/226-capacidad-conmutacion-y-ci.json) · [Estado de libxml2 en Debian](https://security-tracker.debian.org/tracker/CVE-2026-6653)

## Capacidad

**VERIFICADO** para el escenario de 12 llegadas por segundo y 2.700 segundos. Se alternaron feed, perfil, envío de chat y notificaciones, con diez cuentas sintéticas y cinco receptores WebSocket. Dos API y dos gateways trabajaron contra PostgreSQL 16.15 con 37 migraciones y Redis. El generador y los servicios compartieron dos CPU lógicas de WSL2. El JSON conserva el calentamiento por separado.

| Medida del tramo sostenido | Resultado |
|---|---:|
| Llegadas planificadas / despachadas | 32.400 / 32.400 |
| HTTP 2xx / 429 / 503 de saturación / otros 5xx | 32.400 / 0 / 0 / 0 |
| Fallos de red / funcionales | 0 / 0 |
| Descartes por concurrencia / por retraso / tras corte | 0 / 0 / 0 |
| Entregas dentro de tres segundos / esperadas | 8.100 / 8.100 |

| Ruta | Operaciones | Mediana del p95 por ventana | Mayor p99 de una ventana |
|---|---:|---:|---:|
| feed | 8.100 | 9,55 ms | 39,55 ms |
| profile | 8.100 | 12,44 ms | 54,84 ms |
| message | 8.100 | 31,80 ms | 108,79 ms |
| notifications | 8.100 | 9,42 ms | 37,91 ms |

El control anterior a 16 llegadas por segundo en 12 minutos perdió 5 de 11.520 llegadas por el límite de ocho operaciones en vuelo. A la misma tasa, durante las compilaciones de imágenes, la ejecución se detuvo a los 489 s por recepciones tardías. El JSON conserva los descartes, las ventanas con espera del pool y los contadores de Redis. La CPU se muestreó en intervalos de cinco segundos. Un proceso al 100 % equivale a una CPU lógica. En las 180 ventanas se observaron como máximo 0 solicitantes esperando conexión de PostgreSQL. Los dos gateways registraron en total 0 descartes de consumidor lento. Estos límites corresponden a la mezcla y al hardware indicados.

Con un límite de 16 operaciones en vuelo, el segundo control de 720 s a 16 llegadas por segundo obtuvo **VERIFICADO**. Se despacharon 11.520 de 11.520, con 11.520 HTTP 2xx, 0 descartes por concurrencia y 0 llegadas tardías. Se recibieron 2.880 de 2.880 mensajes dentro del plazo. El máximo observado fue 2 operaciones simultáneas. Los controles ocurrieron en intervalos distintos, lo que impide atribuir por sí sola la diferencia al límite del generador.

## PostgreSQL y mensajería

**VERIFICADO en laboratorio.** Sobre un primario nuevo se aplicaron las 37 migraciones y se creó una réplica física con `pg_basebackup`. Se exigieron `fsync=on`, `synchronous_commit=on` y que PostgreSQL declarara la réplica síncrona antes de aceptar los mensajes del ensayo. Dos cuentas abrieron y aceptaron un chat mediante la API. Se detuvo el primario privado, se comprobó su parada, se promovió la réplica y se dirigieron las nuevas conexiones al servidor promovido. El proceso de la API conservó su pool y el destinatario mantuvo su sesión. Este primer ensayo usó la revisión `77b507f0`.

De los 13 mensajes confirmados antes del corte se perdieron 0. Una escritura coincidió con la parada. Durante el intervalo de indisponibilidad, el intento posterior devolvió 500 y la sonda devolvió 500. Se repitieron las escrituras inciertas con sus identificadores originales. El destinatario terminó leyendo 29 identificadores distintos, con 0 duplicados. El resumen ordenado del historial de migraciones coincidió antes y después.

| Hito desde el comienzo de la parada | Tiempo |
|---|---:|
| Primario detenido | 106 ms |
| Réplica promovida | 327 ms |
| API disponible | 391 ms |
| Chat leído con el token existente | 422 ms |
| Última escritura comprobada | 950 ms |

El cambio de destino fue dirigido por el ensayo. La conmutación automática entre máquinas, una partición de red real y la restauración conjunta de PostgreSQL y objetos en la infraestructura objetivo permanecen pendientes.

**CORREGIDO el contrato de disponibilidad.** En un ensayo adicional se dirigió la conexión de la API a una réplica física legible antes de promoverla. El código anterior devolvió HTTP 200 y una hora de base de datos mientras PostgreSQL confirmaba que la transacción era de solo lectura. La candidata comprueba en la misma consulta la hora de la base y si PostgreSQL marcó la transacción como de solo lectura. Devuelve HTTP 503 con el código público `DATABASE_UNAVAILABLE` tanto ante la desconexión como en la réplica de solo lectura. Después de promoverla volvieron el HTTP 200 y la escritura, con 29 mensajes únicos, sin reiniciar la API. La misma sesión leyó el chat a los 475 ms desde la parada. Las escrituras de chat durante el corte conservaron su respuesta de error y se reintentaron únicamente con el mismo identificador. [Modo de espera de PostgreSQL 16](https://www.postgresql.org/docs/16/hot-standby.html)

## Subidas durante una desconexión

**CORREGIDO.** Las conexiones privadas para reservar y finalizar subidas solo atendían errores del pool mientras estaban libres. Al comprobar el espacio disponible entre dos consultas podía cerrarse una conexión prestada antes de instalar el manejador de la reserva. Los dos pools ahora usan el monitor que protege a la API desde que entrega la conexión hasta que se devuelve. El diagnóstico registra el código y una huella del mensaje. La ruta mantiene sus límites de conexiones y el rechazo de una operación interrumpida.

Dos regresiones fallaron antes de la corrección. Después pasaron cuatro casos de manejo de conexiones. En Linux pasaron 17 casos en los dos archivos de pruebas, incluidos los cuatro casos de los pools y el conjunto de admisión con PostgreSQL real. En una base dedicada se interrumpió la conexión de reserva durante la comprobación de disco. La transacción dejó cero filas, el mismo identificador pudo reservarse una vez y al finalizar volvió a quedar libre.

## Android y GitHub

El commit `77b507f0` aprobó los seis trabajos de Web y servicios en cada rama. Android aprobó calidad y release en main. En feature pasaron los contratos de servicios y clientes, el ejecutor de Gradle terminó con código 1 y se omitió el release. El archivo de informes de 72.322 bytes contenía el lint y salida parcial sin XML JUnit. El registro disponible no identificó el caso ni una causa del cierre. [Android en main](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44749402635) · [Android en feature](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44749403168)

**CORREGIDO el diagnóstico de CI.** El control de depuración ejecuta un proceso de pruebas a la vez, con 768 MiB de heap y 512 MiB de metaspace, y renueva el proceso cada diez clases. Gradle escribe el último caso iniciado en un archivo que GitHub recoge incluso si el proceso termina sin XML. En la muestra de Gradle, tanto el control anterior como el nuevo rechazaron el cierre deliberado del proceso. El nuevo también identificó la clase y el caso. En la prueba ordinaria se ejecutaron 23 clases en grupos de 10, 10, 3. La suite histórica con este perfil de recursos aprobó 3.553 pruebas en 592 suites. La APK privada 9.1.43 (183) lleva la revisión `1018190b`. Las rutas de compilación de la aplicación conservan sus huellas en este ciclo.

## Conversión de documentos

**VERIFICADO en dos imágenes de laboratorio.** Con la API de producto, PostgreSQL 16.16 y Docker Compose, se convirtieron DOCX, XLSX y PPTX con imágenes, texto y dos páginas por archivo. En seis comparaciones se conservaron el contenido previsto, la geometría y cada píxel RGB a 72 ppp. La alternativa de Ubuntu 26.04 reemplazó 23 paquetes binarios de LibreOffice con una sola diferencia de paquete fuente. Launchpad situaba 26.2.7.3 en propuestas y 26.2.6.3 en actualizaciones y seguridad al consultar los cuatro canales. [Historial de LibreOffice en Ubuntu](https://launchpad.net/ubuntu/+source/libreoffice/+publishinghistory)

El inventario de la variante alternativa devolvió 175 avisos, entre ellos 57 altos y uno crítico asociado a un complemento SRP ausente. Para las coordenadas estables exactas, las declaraciones oficiales de Canonical identificaban 16 afectadas y 42 bajo investigación. Los rangos de corrección de los 31 avisos de LibreOffice quedan registrados en la evidencia. El índice firmado de Debian para trixie-proposed-updates del 20 de noviembre tampoco contenía una actualización de libxml2. La variante continúa en laboratorio. [Avisos del fabricante](https://www.libreoffice.org/security/) · [Declaraciones VEX de Canonical](https://documentation.ubuntu.com/security/security-updates/vex/)

## Servidor y entrega

A las 09:38 de Bogotá, las dos sondas de lectura al Ubuntu activo devolvieron HTTP 200, TLS 1.3, 25 capacidades y backend `cc6a022e`. La inspección administrativa del host sigue condicionada a la reautenticación del proveedor de identidad. Los ensayos de fallo y carga usaron infraestructura desechable. Las dos ramas se publican con omisión de despliegue. La revisión previa recibió dos cancelaciones de Vercel y sus tres alias seguían en `4517f8d1`.

| Mejora | Commit |
|---|---|
| Bound Android CI test workers and retain the last started test | [`7508e437`](https://github.com/Alvaro-mendoza-12/red-social-android/commit/7508e4371d933d936a4b2cba13b9e1f2be2ce034) |
| Handle PostgreSQL disconnects throughout storage pool checkouts | [`a85ac235`](https://github.com/Alvaro-mendoza-12/red-social-android/commit/a85ac235ebbca766b087de40b0e05724decec4b5) |
| Reject database outages and read-only replicas in API readiness | [`4dee378b`](https://github.com/Alvaro-mendoza-12/red-social-android/commit/4dee378b4adf7af14c0f049880990319a1d6ec54) |
