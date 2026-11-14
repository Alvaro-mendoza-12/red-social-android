# Continuación 143: velocidad del servidor y entorno Android

Fecha: 14 de noviembre de 2026. Atiende la medición solicitada y la preparación del entorno de desarrollo. La revisión móvil integral continúa abierta. Los valores son muestras de laboratorio con sus condiciones, no capacidad certificada para marketing masivo.

## Medición desde Ubuntu

Muestra iniciada a las **14:47 de Colombia**, durante quince segundos y antes de las transferencias de velocidad:

| Medida | Resultado |
|---|---:|
| Enlace Ethernet negociado | 100 Mbps |
| Tráfico de entrada observado | 0,0746 Mbps |
| Tráfico de salida observado | 0,0802 Mbps |
| CPU ocupada en el intervalo | 29,05 % de ocho procesadores lógicos |
| Memoria disponible | 11.431 MiB de 14.915 MiB |
| CPU puntual de API al final | 0,21 % según Docker |
| CPU puntual de PostgreSQL al final | 0,39 % según Docker |

Una transferencia HTTPS IPv4 a Cloudflare de 20 MB dio **19,218 Mbps de descarga**; una de 4 MB dio **16,579 Mbps de subida**. Se utilizó el endpoint de prueba publicado por el [proyecto oficial de Cloudflare](https://github.com/cloudflare/speedtest). No se enviaron datos del proyecto: la subida contenía bytes nulos. La prueba IPv6 de descarga agotó veinticinco segundos sin recibir cuerpo; la de subida dio 15,053 Mbps. Una muestra por familia y dirección no demuestra la velocidad máxima contratada ni la capacidad de usuarios simultáneos.

100 Mbps equivalen a un máximo teórico de 12,5 MB/s antes de sobrecargas; no son 100 MB/s. En este intervalo el enlace no estaba saturado. Después de sustituir cable o switch debe repetirse la negociación y la medición: la categoría del cable por sí sola no prueba una velocidad superior ni elimina la latencia de otros componentes.

La lectura de `ethtool` previa a la muestra Gigabit confirmó que la tarjeta del servidor admite y anuncia **1000baseT/Full**, pero su interlocutor solo anunciaba 10/100; el enlace en esa muestra era **100 Mbps, dúplex completo**. Esto es compatible con el switch de 100 Mbps descrito por el propietario. No se cambiaron rutas, DNS ni ajustes del router. La comprobación posterior se describe a continuación.

## Nueva muestra con enlace Gigabit

A las **17:42 de Colombia** el servidor ya negociaba **1000 Mbps**. En quince segundos se observaron RX **0,0037 Mbps**, TX **0,0061 Mbps**, CPU **7,93 %** y **11.612 MiB disponibles de 14.915 MiB**. La transferencia HTTPS **IPv6** de 20 MB completos dio **169,692 Mbps de descarga**; la subida de 4 MB completos dio **16,148 Mbps**. Son transferencias individuales, sin datos del proyecto. No se presenta una mejora multiplicativa frente a la muestra IPv4 anterior: cambiaron familia de red, momento y estado de conexión, y la primera descarga IPv6 había fallado. La negociación Gigabit y la transferencia efectiva son medidas distintas.

La descarga IPv4 hacia Cloudflare no conectó en cinco segundos y las tres peticiones IPv4 al dominio no conectaron en tres segundos cada una. No se hizo la subida IPv4 después de ese fallo. El gateway local respondió con mediana **6,68 ms**. Windows también agotó su conexión IPv4 a GitHub. Estos ensayos no demuestran saturación de CPU, memoria o enlace de Ubuntu ni miden la capacidad de usuarios simultáneos. El origen concreto del fallo de salida pública sigue sin establecerse.

Se corrige una distinción de ruta: la URL denominada `directFunnelReady` resolvió y conectó en la comprobación posterior a **100.91.84.2**, la dirección privada del propio servidor en Tailscale. Su éxito **no demuestra Internet IPv4 ni mide el acceso público de Funnel**. Las primeras muestras no conservaron `remote_ip`; su tabla identifica la URL utilizada sin atribuirle ahora un recorrido WAN comprobado. Los resultados privados completos se conservan; no se reemplaza un timeout por una latencia cero.

## Contraste solicitado entre mini PC y laptop

El propietario confirmó dos cables directos del router, de unos 27 metros, y comunicó una prueba de unos 65 en la laptop frente a 170–180 junto al router. La lectura de Windows a las **17:56–17:59** mostró la tarjeta Realtek PCIe GbE enlazada a **100 Mbps**, dúplex completo, con **negociación automática**. La mini PC ya enlazaba a **1000 Mbps**. No se forzó la velocidad ni se reinició el adaptador. El nombre comercial del cable no acredita por sí solo el enlace negociado.

Se ejecutaron por separado, sin transferencias de velocidad simultáneas, dos descargas HTTPS de **50 MB** y una subida de **10 MB de bytes nulos** por familia IP y equipo, contra el mismo servicio Cloudflare:

| Equipo | Enlace LAN | Descargas IPv4 (Mbps) | Descargas IPv6 (Mbps) | Subida IPv4 / IPv6 (Mbps) |
|---|---:|---:|---:|---:|
| Mini PC, 17:58 | 1000 | 142,044 / 138,326 | 107,479 / 118,931 | 121,152 / 35,658 |
| Laptop, 17:59 | 100 | 75,485 / 19,624 | 69,113 / 70,272 | 29,622 / 37,685 |

La laptop seguía ejecutando Lint de Novvin durante su muestra; se registra esa condición y la segunda descarga IPv4 lenta no se omite. Son transferencias individuales y variables, no máximos contratados ni una prueba idéntica a la del celular. El enlace de 100 Mbps es un límite comprobado en la laptop; todavía no distingue puerto del router, cable/conectores o comportamiento del adaptador. Se solicitó al propietario intercambiar los puertos solo después de terminar estas medidas; esa comprobación física no se presume realizada. Las conexiones IPv4 y HTTPS volvieron a responder antes de estas transferencias: el fallo anterior era intermitente y no se presenta como desconexión IPv4 permanente.

## Resultado posterior al cambio de puerto

A las **18:16** Windows pasó a **1 Gbps**, conservando `SpeedDuplex=0` (negociación automática), y la mini PC seguía a **1000 Mbps**. El asistente no cambió opciones del adaptador. El propietario confirmó después mover la conexión de la laptop de LAN 4 a LAN 2, con la mini PC en LAN 1, y comunicó 150–170 Mbps en su prueba de laptop y 130–140 en la del servidor. Esa intervención y la renegociación están registradas; no demuestran por sí solas un defecto permanente del puerto LAN 4.

Con el mismo ensayo de transferencias individuales de 50 MB, la laptop ya a Gigabit dio descargas IPv4 de **114,197 / 60,636 Mbps** e IPv6 de **101,429 / 85,369 Mbps**. Una prueba adicional de **cuatro descargas IPv4 concurrentes de 25 MB cada una**, con cada equipo ensayado por separado, dio **151,458 Mbps agregados en laptop** y **80,201 Mbps en mini PC**. En esta última, tres flujos acabaron en 3,3–3,8 s y uno tardó 9,96 s. No se omite ese flujo lento ni se transforma la muestra de 80,201 en un límite fijo: las otras muestras del servidor habían dado 138–142 y 169,692, bajo métodos/familias/momentos distintos. No se controló todo el tráfico del resto de la casa.

Se retiró el límite LAN de 100 Mbps observado en la laptop y hay transferencias verificadas que lo superan. La capacidad de todos los servicios y la fluidez del feed tipo TikTok siguen sin establecerse con estas pruebas. Cuando los medios salen de la mini PC, importa su subida; si se sirven desde almacenamiento/CDN, debe medirse esa ruta. La precarga, el peso de los archivos, el primer cuadro y las pausas al cambiar de video requieren ensayos propios de Android. Referencias primarias: [precarga Media3](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager) y [entrega de medios por caché](https://developers.cloudflare.com/use-cases/media-streaming/cache-delivery/).

## Latencia e imágenes

Tres peticiones HTTPS nuevas por ruta, desde el propio Ubuntu, produjeron estas medianas de tiempo hasta el primer byte:

| Ruta | Mediana |
|---|---:|
| `/ready` por gateway local | 7,37 ms |
| `/ready` por URL de Tailscale (destino no guardado entonces) | 45,04 ms |
| `/ready` por novvinapp.com | 618,99 ms |
| Logo público por novvinapp.com | 314,95 ms |

Se contrastaron dos avatares **públicos** mediante credencial de invitado, sin iniciar sesión personal, imprimir nombres, URLs de objetos o credenciales ni conservar sus imágenes. Una fotografía de 30.375 bytes produjo una miniatura WebP de 1.998 bytes. La miniatura respondió localmente en 64,90 ms y por el dominio en 1.321,43 ms. El otro objeto era un video de 853.976 bytes; una petición con solo `width` y `animated` conserva ese video y no equivale a solicitar un póster con `poster=1`. No se presenta esa petición como una miniatura válida de video ni como defecto del servidor.

Un navegador Chromium aislado de invitado, viewport 375 × 812, CPU ralentizada cuatro veces y conexión local sin limitar, tardó **7.408, 7.921 y 4.520 ms** en mostrar el avatar desde tres arranques fríos de la web real. Son observaciones de laboratorio; no son CrUX ni datos agregados de usuarios. En dos recorridos se registraron dos verificaciones `/ready` seriales, de aproximadamente 398–499 ms cada una; en otro, una lectura del avatar tardó unos cinco segundos. No hubo errores JavaScript. No se puede atribuir toda esa variación al servidor, al tamaño del archivo o al cable.

La [evidencia 143](../quality/evidence/143-ide-server-performance.json) distingue tráfico actual, velocidad de una transferencia, demora del recorrido público, tamaño de imágenes y carga de proceso. Las trazas privadas y los recibos con huellas permanecen en `app/build/audit143`.

## Optimización comprobada y límite de la comparación

`appEntry` inicia la comprobación de disponibilidad en segundo plano. Una entrada de invitado posterior volvía a comprobar el mismo servidor aunque acabara de verificarse. Se reprodujo con una prueba válida: el resultado anterior producía dos peticiones donde se necesitaba una; los otros tres controles aprobaron. El primer borrador del arnés tenía una expectativa incorrecta de su mock para el control negativo; se corrigió antes de conservar la reproducción válida y no se cuenta como fallo del producto.

`initializeRuntimeBackend` reutiliza durante **quince segundos** únicamente una comprobación compatible satisfactoria del origen todavía vigente. El plazo no extiende una firma vencida, no cachea fallos ni impide el sondeo de recuperación explícita. Se conservan la verificación de confianza, capacidades y renovación posterior. Las nueve pruebas de disponibilidad/confianza aprobadas comprueban reutilización, vencimiento de la ventana, reintento de fallos, recuperación y contratos anteriores.

Una comparación alternada de tres recorridos por versión, con frontend servido localmente y API pública real, dio medianas de **2.526 ms antes y 2.730 ms después**. Ambas versiones solaparon su inicialización y emitieron una sola petición `/ready` en esos recorridos. Esa comparación **no demuestra una mejora general de tiempo**. Conservó la caché de avatares: actualizar el mismo perfil no realizó otra transferencia de imagen. Una variante del arnés que bloqueaba el módulo hasta recibir `/ready` no completó un recorrido; otra con demora fija se interrumpió por timeout de conexión IPv4 antes de empezar. Se registran ambas como inconclusas y no como regresiones del producto o pruebas aprobadas.

La suite completa web superó **1.268 pruebas en 199 suites**. TypeScript, Vite y las comprobaciones de 21 páginas públicas aprobaron. Las demoras restantes requieren nuevas mediciones con una red estable y los recorridos autenticados correspondientes.

## Android Studio, herramientas y compilación

Los perfiles de 2025 y 2026, las preferencias, los Android SDK, los NDK, las herramientas y los emuladores anteriores existen y se conservaron. El perfil 2026 contenía Flutter pero carecía de Dart, dependencia necesaria. Se instaló **Dart 511.0.0**, compatible con el build actual, desde el Marketplace oficial; el registro confirma la carga de Dart y Flutter 98.0.0, junto con los otros plugins compatibles ya presentes. No se copiaron plugins antiguos incompatibles para forzar su carga. Véanse las guías oficiales de [Dart en Android Studio](https://dart.dev/tools/jetbrains-plugin) y [configuración Android de Flutter](https://docs.flutter.dev/platform-integration/android/setup).

La entrada conservada de SDK llamada `jbr-21` seguía apuntando al `jbr` de la instalación del IDE; ese directorio contiene ahora Java 25. Se mantuvo el nombre utilizado por los proyectos y se corrigieron su directorio y sus raíces al **JDK Temurin 21 real**, con respaldo. Las entradas de otros JDK y Android SDK se preservaron. Flutter 3.44.0 queda con las rutas explícitas de SDK, Android Studio y JDK 21.

Android Studio completó la sincronización de Novvin con JDK 21 en **85,517 segundos**. Reconoció la configuración de ejecución `app`, de tipo Android, con su módulo importado. La advertencia de licencia pendiente corresponde a `microxr-sysimage-license`, una imagen de vista previa opcional; no se aceptó a ciegas para ocultar el aviso y no impidió compilar Novvin. No se afirma que todos los frameworks o cualquier proyecto histórico estén validados.

El IDE propio ocupaba aproximadamente 3.988 MiB y Windows tenía unos 247 MiB libres durante una observación. Se cerró ordenadamente para compilar. En `gradle.properties` se sustituyó el compilador Kotlin separado, que podía reservar otros 5 GiB, por compilación dentro del JVM de Gradle de 4 GiB, con un trabajador. La configuración anterior ya había provocado fallos de recursos; el esquema de JVM único había superado las selecciones reales 141. Se conserva la compilación incremental y no se cambia el JDK del IDE, que necesita su propio runtime. Esta validación no repite la optimización R8 de release.

Con esa configuración, **`:app:assembleDebug` terminó BUILD SUCCESSFUL en 7 min 55 s**. Se creó un AVD nuevo, **NovvinIDE143**, con Android 15/API 35 y aceleración WHPX disponible, sin sobrescribir dispositivos anteriores. La APK se instaló, la actividad principal arrancó y la pantalla de bienvenida se mostró; el buffer de fallos estaba vacío. Después se apagó ese emulador para recuperar memoria.

APK exclusivamente de laboratorio: **9.1.40-debug, código 180**, paquete `conectate.com`, 270.435.298 bytes, SHA-256 `56e3adb0048488add8aea1eaf56b677357b6351aa5c5ebf92a1a585dc8820e21`. Contiene el trabajo móvil 141 todavía sin publicar; **no es una APK final entregada ni un cierre funcional de Android**. No se inició sesión personal ni se ensayaron llamadas físicas con esa instalación.

Se creó además una **muestra Flutter separada de los proyectos del propietario**, con Flutter 3.44.0/Dart 3.12.0, JDK 21 y Gradle 9.1.0. Compiló en **538,7 s**, se instaló y mostró `Hello World!` en primer plano. La APK de muestra tiene SHA-256 `5b45f657230f56c4c117fa1eb4ad045434aed4998a941a01b64a933c5385d5c0`; no es Novvin. El intento inicial se canceló tras conservar pilas de conexión y conexiones IPv4 pendientes. El reintento cambió solo los ajustes de la muestra a 4 GiB, un trabajador y preferencia IPv6, sin cambiar versiones ni configuraciones globales. No se presenta el intento cancelado como una compilación aprobada.

El buffer del emulador de ese ensayo conserva **dos abortos de `com.android.bluetooth` al iniciar su pila**. La primera verificación, que exigía buffer vacío, falló; la inspección posterior distinguió ese servicio del proceso Flutter, que seguía vivo y en primer plano, sin fallo observado en su registro. Se conserva el fallo inicial: el arranque de la muestra está comprobado, **Bluetooth no está validado**. Se apagó el AVD antes de continuar las compilaciones Android. No se garantiza que cualquier proyecto móvil sea compatible sin sus propios SDK y dependencias.

Una comprobación posterior de la terminal encontró `JAVA_HOME` y `ANDROID_HOME` sin registrar: `java` resolvía Oracle 24 y el proyecto rechaza ese runtime para Gradle. Con respaldo de las variables de usuario, se registraron JDK 21 y el SDK conservado, se añadieron `platform-tools` y `cmdline-tools/latest/bin` al Path del usuario y se fijó `STUDIO_JDK` al JBR 25 incluido para mantener separado el runtime del IDE. No se desinstaló Oracle ni se alteraron variables de máquina. Un proceso cargado con los valores registrados ejecutó el wrapper: **Gradle 8.13, Launcher JVM 21.0.6 y Daemon JVM Temurin 21**. Las terminales ya abiertas conservan su entorno anterior. Se sigue la documentación oficial de [variables Android](https://developer.android.com/tools/variables) y [JDK para Gradle](https://developer.android.com/build/jdks). Esta comprobación no reabre el IDE ni sustituye su sincronización ya ensayada.

La reapertura final automática del IDE fue rechazada por la revisión de aprobación de herramientas con el motivo genérico `blocked by policy`, sin explicación adicional. No se repitió la acción bloqueada ni se afirma que el IDE haya quedado abierto. La sincronización, la compilación, la instalación y el arranque anteriores sí están comprobados. Los respaldos del entorno siguen en `D:/Novvin-IDE-Repair-20261114` y el acceso directo apunta a `D:/AndroidSTUDIO/bin/studio64.exe`.

## Publicación y continuidad

Se verificó la publicación remota atómica de **142** y su despliegue Git `READY` bajo el dominio: main `19b641508b75ba4ed98e8a3dde249a273dcf0e0c`, despliegue `dpl_3SRa1x4H77j4eHoFn1CPc3wHtk1m`. La selección **143** quedó publicada: main `400f183a60b964f3a01ab721fee46c01bbd9880e` y feature/live-gifts `972b727a3bfa2c23398926ba394f42fd150a064a`, con árbol igual `ed74b32f34c78ce8a4d330ea5d4e5b3091e439fa`. El push notificó desconexión al leer la respuesta; una consulta independiente confirmó ambas refs antes de cualquier reintento. A las **17:55 de Colombia**, Vercel confirmó el despliegue Git `dpl_3BnLLwp35sddsvRSNhTqvVwrfmEj`, `READY`, del commit main indicado y con alias `novvinapp.com`. Los cambios móviles 141 no formaban parte de esos commits; su integración posterior requiere evidencia propia.

La tanda 141 de recibos de pantalla terminó con **30 de 32 casos aprobados**. Los cierres retenidos, la entrada encolada, la reapertura del minimizado y la espera de liberación modelada aprobaron; permanecen reproducidos dos fallos de control de micrófono y lectura de estado desde una pantalla antigua. Sus fuentes, XML y log se conservaron antes de continuar. No se incluyen esos cambios móviles incompletos en la selección estable de este bloque.

Permanecen abiertos la auditoría móvil integral, el resto de los veinte controles de seguridad —incluido RLS—, la latencia restante de medios, las pruebas físicas, los recorridos completos con cuentas y la APK final identificada. Se preservan cambios ajenos, `correciones` y la separación de los experimentos 105. No se declara preparación integral para producción o marketing masivo.
