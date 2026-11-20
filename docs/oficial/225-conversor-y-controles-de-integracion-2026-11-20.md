# 225. Conversión de documentos y controles de integración

**Veredicto del 20 de noviembre de 2026 — NO APTA PARA PRODUCCIÓN.** La candidata corrige cinco avisos de curl aplicables a la imagen anterior. Sigue abierta la corrección de libxml2 en Debian 13, además de las puertas de capacidad, recuperación y dispositivos físicos del informe 224. Los cambios se publican en las ramas estables con omisión del despliegue. [Resultados por ejecución](evidencias/225-conversor-y-controles-de-integracion.json) · [Certificación anterior](224-certificacion-independiente-2026-11-20.md)

## Qué se comprobó en el conversor

La imagen para Linux amd64 se construyó con el Dockerfile del repositorio y la imagen inmutable de Gotenberg 9.9.1 con LibreOffice. El índice firmado de Debian y cuatro huellas SHA-256 identifican las fuentes. La compilación usó las bibliotecas de Debian 13 y sus backports. Instalar directamente los binarios de Debian testing exigía glibc 2.43, y la imagen base contiene glibc 2.41. El resultado conserva el usuario, el comando de arranque y los 344 paquetes del contenedor base. Cambiaron cuatro versiones de paquete. El manifiesto OCI de prueba es `sha256:34ae9b119349e9741a06105478b6253edfcf7985e94d25309d7f8ba51c3b1f1e`. [Fuentes de Debian](https://snapshot.debian.org/package/curl/8.23.0-2/) · [Dockerfile](../../infrastructure/local-fallback/office-preview/Dockerfile)

| Recorrido | Resultado |
|---|---|
| API autenticada con PostgreSQL desechable | DOC, DOCX, XLS, XLSX, PPT y PPTX devolvieron HTTP 200 y `no-store`. Se abrió cada PDF y se comprobó la página y el texto previsto. La petición multipart de la API se transfirió al conversor en un entorno Linux aislado. |
| Docker Engine 29.1.3 y Compose 2.40.3 en Ubuntu bajo WSL2 | Se enviaron los seis archivos al servicio real. Tras reiniciar el mismo contenedor volvió a estar sano y convirtió otro DOCX. Los siete PDF conservaron su texto. |
| Escrituras temporales | El núcleo confirmó la raíz de solo lectura. Los dos montajes usaron `nosuid,nodev,noexec`. El límite de `/tmp` fue de 1 GiB y el del directorio privado del conversor, de 128 MiB. Dos marcadores presentes antes del reinicio desaparecieron. |
| Entrada de despliegue | Cuatro ensayos del script con comandos simulados verificaron la diferencia con la versión anterior, la compilación habitual, la opción de omitir compilación y el fallo de inicio de Office antes de sustituir la API. |
| Otra máquina Ubuntu de laboratorio | La descarga de la imagen excedió 720 segundos en VirtualBox. La máquina se conservó mediante un estado guardado. La evidencia de Docker de este informe corresponde al motor bajo WSL2. |

El código de curl pasó de 8.21.0 a 8.23.0. La compilación Debian se hizo con el perfil `nocheck`. Las pruebas de producto, integración y reinicio anteriores se ejecutaron por separado. El ensayo cubre amd64. [Avisos de curl 8.21.0](https://curl.se/docs/vuln-8.21.0.html) · [Avisos de curl 8.23.0](https://curl.se/docs/vuln-8.23.0.html)

## Interpretación de los trece avisos críticos

La consulta de las 205 coordenadas de paquetes fuente instalados volvió a identificar los mismos 328 avisos de OSV. Conservando la puntuación del informe 224, hay 13 críticos y 119 altos. OSV devuelve los mismos 39 registros de curl al consultar las versiones de Debian estable, backports y la compilación local. Las siguientes conclusiones utilizan también los rangos del proveedor y los archivos presentes. [Seguimiento de curl en Debian](https://security-tracker.debian.org/tracker/source-package/curl)

| Componente | Avisos críticos y conclusión |
|---|---|
| curl, cinco aplicables a la base | CVE-2026-18924, 19931, 86414, 87866 y 92388. Los cinco rangos publicados sí incluían 8.21.0. La versión 8.23.0 instalada en la candidata queda fuera de los cinco. |
| curl, cuatro anteriores | CVE-2026-10536, 11856, 8926 y 9079. El proveedor sitúa el extremo superior en 8.20.0. La imagen de referencia ya usaba 8.21.0. |
| Cyrus SASL y TIFF | Para CVE-2026-129483, Debian deshabilita SRP y el inventario confirmó que el complemento está ausente. Para CVE-2026-52490, el programa afectado es `tiffcrop` y el contenedor instala la biblioteca sin ese ejecutable. |
| glibc | CVE-2019-1010022 sigue en el inventario. Debian clasifica su urgencia como no importante y el proveedor discute su consideración como fallo de seguridad. Se conserva la discrepancia. |
| libxml2 | CVE-2026-6653 sigue abierta para la versión instalada. La prueba privada descrita a continuación conserva una duda de compatibilidad binaria. |

Los avisos de los componentes concretos y la cobertura de cada fabricante pueden consultarse en sus fichas. [Cyrus SASL](https://security-tracker.debian.org/tracker/CVE-2026-129483) · [TIFF](https://security-tracker.debian.org/tracker/CVE-2026-52490) · [glibc](https://security-tracker.debian.org/tracker/CVE-2019-1010022) · [libxml2 en Debian](https://security-tracker.debian.org/tracker/CVE-2026-6653)

La comparación de las 39 fichas de curl con las listas oficiales de versiones encontró 25 avisos del escáner que el proveedor también enumeraba para 8.21.0. Cinco tenían puntuación crítica y veinte, alta. Ninguna de las 39 fichas incluye 8.23.0 en la lista de versiones afectadas. Para el conjunto completo de 132 avisos altos y críticos, el rastreador de Debian clasificó 129 como abiertos, dos como indeterminados y uno como resuelto en Trixie. Esos estados describen la distribución. El JSON mantiene la versión instalada, el componente y la fuente de cada cruce. La investigación de los demás avisos altos sigue abierta. [Lista de curl 8.21.0](https://curl.se/docs/vuln-8.21.0.json) · [Lista de curl 8.23.0](https://curl.se/docs/vuln-8.23.0.json) · [Rastreador Debian](https://security-tracker.debian.org/tracker/)

### Prueba privada de libxml2

Se obtuvo el parche oficial de Ubuntu para la misma base 2.9.14 y se aplicó a las fuentes de Debian 13. La imagen privada compiló, convirtió seis formatos por API y produjo siete PDF verificados en Docker, incluido uno después de reiniciar. Un lector compilado con las cabeceras antiguas leyó XML ordinario con la biblioteca nueva. Coincidieron los 1.786 símbolos exportados y el nombre de la biblioteca. [Estado de Ubuntu](https://ubuntu.com/security/CVE-2026-6653)

La medición en C detectó que `xmlEntity` pasó de 136 a 152 bytes y `xmlParserInput`, de 104 a 120. Un consumidor que reserve espacio con las cabeceras antiguas podría seguir reservando el tamaño anterior. Por esa razón la candidata compartida conserva la biblioteca de Debian. La compatibilidad de esos consumidores queda pendiente de una actualización de distribución, una reconstrucción conjunta o evidencia equivalente. El parche y la imagen de esta prueba permanecen en el laboratorio.

## Conversión de extremo a extremo con la API real

Una red privada de Docker conectó la API con PostgreSQL 16.16, Redis y el conversor mediante los nombres de servicio de Compose. La API arrancó con su punto de entrada de producción, UID 10001 y credenciales sintéticas del rol restringido. Se aplicaron las 37 migraciones, el alta por HTTP devolvió 201 y la API anunció 25 capacidades. Se enviaron seis formatos Office por la ruta autenticada de la aplicación y se repitió el DOCX después de reiniciar el conversor. Se hizo el mismo recorrido con cada imagen. Los 14 PDF devolvieron HTTP 200 con `no-store`, una página y el texto esperado. Las huellas de las entradas y las salidas se conservan por formato. Estas muestras cubren texto sencillo. La fidelidad de documentos complejos queda pendiente.

Se evaluó también una imagen privada que combina Gotenberg 9.9.1 con Ubuntu 26.04 LTS. Canonical clasifica su libxml2 como libre de CVE-2026-6653. La consulta de sus 189 paquetes fuente encontró 175 avisos, uno crítico, 57 altos, 87 medios, 13 bajos y 17 sin puntuación. La verificación positiva detectó USN-8927-1 al consultar la versión anterior de curl. El único aviso crítico corresponde al mecanismo SRP de Cyrus SASL. El archivo final contiene únicamente el complemento SASL de base de datos. [libxml2 en Ubuntu](https://ubuntu.com/security/CVE-2026-6653) · [Cyrus SASL en Ubuntu](https://ubuntu.com/security/CVE-2026-129483)

La imagen de Ubuntu usa LibreOffice 26.2.6.3 y la compartida, 26.8.2.2. Por ejemplo, el proveedor corrigió CVE-2026-101351, relacionado con la importación de imágenes, en 26.2.7 y 26.8.2. Canonical todavía estaba evaluando ese aviso para 26.04. El archivo de Ubuntu ya ofrecía 26.2.7.3 en el canal de propuestas y seguía ofreciendo 26.2.6.3 en actualizaciones y seguridad. Se conservó la variante en el laboratorio. [Aviso de LibreOffice](https://www.libreoffice.org/security/#cve-2026-101351) · [Seguimiento de Canonical](https://ubuntu.com/security/CVE-2026-101351) · [Versiones del paquete](https://launchpad.net/ubuntu/resolute/+source/libreoffice)

De los 31 avisos de LibreOffice devueltos para Ubuntu, 22 tienen una corrección del proveedor en la serie 26.2 que comienza en 26.2.7. Los nueve restantes ya tienen corrección del proveedor en 26.2.6 o anterior. Se conserva por aviso la versión declarada por LibreOffice. [Avisos de LibreOffice](https://www.libreoffice.org/security/)

## Integración continua

El primer commit de este ciclo, `3b89ec2d`, hizo visibles fallos en las dos ramas. Se corrigieron la configuración sintética de Firebase de los jobs Android, la instalación de Signaling que usa la prueba de arranque de la API y los fixtures de las imágenes, restauración y pertenencia a encuestas. Los controles de perfil y precarga de Reels pasaron el lint y siete pruebas focalizadas. En Signaling, el modo de vigilancia de Node reinició el proceso al editar un módulo importado y la auditoría de npm pasó de dos avisos moderados a cero.

En un árbol extraído de Git para Linux, la suite de la API aprobó 1.166 casos, con 35 omitidos. La Web aprobó 1.346 casos de Jest en 208 suites. El registro JSON conserva los resultados de cada fase y la auditoría de dependencias por separado.

## Entrega de la Web

El nuevo árbol de instalación contiene 903 entradas de paquetes frente a 1.083. Se retiró el generador de PWA que Vite no utilizaba, se actualizó la cadena de análisis de TypeScript y se declaró Terser como dependencia directa de compilación. El primer ensayo reveló que la configuración de Vite dependía indirectamente de Terser. El cambio final conserva todas las versiones de dependencias de ejecución.

Con Node 22.23.5 en una extracción completa de Git sobre Ubuntu, pasaron `npm ci`, lint, las 1.346 pruebas Jest y las 129 de Node. El build comprobó 22 páginas públicas y la copia exacta del worker multimedia de 10.199 bytes en la salida. La auditoría de npm, incluidas las dependencias de desarrollo, pasó de nueve avisos moderados a cero. El flujo de Web y servicios de GitHub aprobó los seis trabajos en cada rama para `b7168c8e`. [Ejecución en main](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44741261695) · [Ejecución en feature/live-gifts](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44741261514)

## Integridad de las migraciones

Una prueba con PostgreSQL 16.15 reprodujo una interrupción entre la escritura de los datos y la anotación del archivo en el historial. Al rechazar la base la escritura de la huella, el ejecutor anterior devolvió error y dejó una fila de aplicación confirmada, con cero filas en el historial. El ejecutor nuevo devolvió el mismo error y dejó ambas tablas intactas. Cada migración y su SHA-256 se confirman en una sola transacción. El historial serializa los ejecutores que usan diferentes bloqueos locales de Compose.

Las 37 migraciones tomadas de Git se aplicaron sobre el esquema inicial y conservaron la huella de cada archivo original. Una segunda ejecución omitió las 37. Dos ejecutores simultáneos aplicaron una sola vez el mismo cambio. Se verificaron también la interrupción de una conexión activa, el fallo de una sentencia posterior, el rechazo de un cambio de huella y el de una confirmación intermedia. Se probaron nombres con comillas y datos con finales de línea CRLF. La prueba ejecutó el script de despliegue con un sustituto local de Compose que enviaba las órdenes SQL a PostgreSQL real. La nueva prueba de Node abrió su propia base temporal y la retiró al terminar. [Ejecutor SQL](../../infrastructure/local-fallback/scripts/apply-schema-migration.sql) · [Regresión PostgreSQL](../../infrastructure/local-fallback/api/tests/schema-migrations.postgres.test.js)

La preparación del rol de ejecución transfería a `novvin_api` la propiedad del historial. El despliegue y la preparación del rol ahora conservan el historial bajo el administrador. PostgreSQL confirmó que el rol de aplicación conserva la propiedad de `fallback_users` y carece de privilegios de lectura y escritura sobre el historial en las pruebas de instalación, reparación y repetición. La fila histórica conservó su huella.

## Configuración de los servicios

Una configuración sintética en Docker Compose 2.40.3 confirmó que la API, Signaling y Realtime recibían la contraseña administrativa de PostgreSQL a través del archivo de entorno compartido. La corrección mantiene esa contraseña en PostgreSQL y entrega a la API la conexión del rol restringido. Signaling y Realtime reciben únicamente sus ajustes declarados y admiten un archivo privado propio para extensiones. Los 51 ajustes identificados de Signaling y los 17 de Realtime conservaron sus valores en la comparación. La credencial de administración, la conexión del usuario de base de datos y una clave de control ficticia quedaron fuera de ambos servicios de sockets. Las 90 pruebas de herramientas pasaron en Linux. También pasaron la suite de API (1.166 aprobadas, 36 omitidas), Signaling y Realtime. La regresión PostgreSQL que se omite sin una base dedicada se ejecutó además contra una base desechable.

Un segundo control comprobó la ubicación de los archivos de ajustes. El montaje de Google Play de la API abarcaba los dos archivos privados de sockets. Las ramas estables los ubican junto al Compose como `.env.signaling.local` y `.env.realtime.local`. Con archivos sintéticos concretos, Compose confirmó que cada servicio leyó su extensión, la API conservó sus ajustes y ninguno de los dos archivos quedó bajo los montajes de la API. Git ignora ambas rutas.

## Capacidad con las migraciones aplicadas

En Ubuntu bajo WSL2 se ejecutaron dos API, dos gateways Realtime, Redis, PostgreSQL 16.15 y el generador de llegadas. Se aplicaron las 37 migraciones y las API se conectaron con `novvin_api` sin privilegio de superusuario. Hubo diez cuentas sintéticas, cinco receptores y dos procesadores lógicos compartidos por los servicios y el generador.

La prueba prevista para 45 minutos a 16 solicitudes por segundo activó el corte por fallos funcionales a los 489 s. Las 7.572 solicitudes despachadas devolvieron 2xx. Los receptores observaron 1.881 de 1.896 mensajes dentro del plazo. El generador contabilizó 80 llegadas descartadas al alcanzar el límite de ocho operaciones en vuelo y 35.548 posteriores al corte. La mediana del p95 por ventana fue de 41,71 ms en HTTP y 69,05 ms en WebSocket. En la última ventana, el p95 HTTP alcanzó 11.633 ms. La muestra coincidió con compilaciones y exportaciones de imágenes en el mismo anfitrión. La evidencia no permite atribuir el retraso a un componente concreto.

Se repitió el arranque de la prueba con las mismas cuotas y dos CPU mientras seguía la importación de imágenes. Los 180 HTTP aprobaron durante 45 s a cuatro solicitudes por segundo. Dos de los 45 mensajes excedieron el límite de tres segundos. El generador detuvo la prueba antes de iniciar el tramo de 16 solicitudes por segundo.

El control ejecutado después de terminar las compilaciones e importaciones de imágenes falló a 16 solicitudes por segundo durante 720 s. Se despacharon 11.515 de 11.520 llegadas, con 11.515 HTTP 2xx y 2.879 de 2.879 mensajes recibidos en tres segundos. Hubo 5 llegadas descartadas por concurrencia y 0 por retraso del generador. La mediana de los p95 por ventana fue 28,00 ms en HTTP y 43,58 ms en WebSocket. Las dos API conservaron el rol restringido y los dos gateways usaron Redis. El ensayo acota el resultado a ese intervalo y a este laboratorio. Durante los últimos 675,60 s muestreados, la mediana de ocupación de las dos CPU fue 11,95 % y la mediana de espera de E/S fue 2,90 %.

El generador ahora conserva por ventana las latencias HTTP de feed, perfil, envío de mensajes y notificaciones. Registra también la latencia HTTP de los envíos aceptados para los que al menos un receptor excedió el plazo. Estas medidas permiten distinguir un ACK HTTP lento de un retraso posterior en nuevos ensayos. Se verificaron los 16 casos del generador, incluida una respuesta 201 con receptor silencioso y el recuento de todas las operaciones. El desglose se incorporó después del control de 12 minutos.

## Servidor y alcance del cierre

El 20 de noviembre a las 07:10 y a las 08:45 de Bogotá, los accesos HTTPS público y privado del Ubuntu activo devolvieron HTTP 200 en `/ready`, TLS 1.3, el mismo commit de backend `cc6a022e` y 25 capacidades. Esa lectura comprueba la disponibilidad anunciada. La capacidad sostenible del servidor objetivo y los recorridos entre teléfonos físicos siguen abiertos. Los tres alias de Vercel comprobados después del envío de `b7168c8e` continuaban en `4517f8d1`. La APK histórica 9.1.43 (183) y los resultados de dispositivo conservan el alcance documentado en 224.

## Controles Android de GitHub

En el commit `b7168c8e` pasaron en ambas ramas los contratos de backend, los de clientes y Realtime, el control Gradle de depuración y la verificación de las clases de arranque en el paquete. El trabajo de release también aprobó en las dos ramas. Construyó APK y AAB con una clave temporal de CI, comprobó las clases tras R8, la firma del bundle, la compatibilidad de páginas de 16 KiB y los presupuestos de rendimiento. [Android en main](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44741261564) · [Android en feature/live-gifts](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/44741261448)
