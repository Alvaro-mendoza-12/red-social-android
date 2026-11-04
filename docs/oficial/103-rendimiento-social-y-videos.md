# Rendimiento de Social y Videos Android

Estado del 3 de noviembre de 2026. Las correcciones de este bloque están
implementadas y tienen comprobaciones Android descritas abajo. La revisión integral
continúa abierta. La entrega firmada más reciente sigue siendo 9.1.38 / 178.

## Diagnóstico medido antes de los cambios

Se compiló una referencia desde `6001ad95`, con R2 configurado y una prueba
instrumentada optativa de lectura pública. Se usó el AVD dedicado
`codex-doc-qa`, Android x86_64, 1.536 MiB, dos núcleos y renderizado por
software. El perfil de reproducción detectado fue LOW. Se conservaron sus
datos y se restauraron Wi-Fi, datos móviles y límites de red al terminar.
El emulador creado para esta revisión quedó apagado.

La prueba usa la API de invitado, la fábrica real de transporte del feed y
Media3 con una superficie de video. Mide desde la preparación del reproductor
hasta el primer byte y el primer cuadro decodificado. No abre el Social de
miembro ni mide el tiempo hasta su primera publicación visible.

| Referencia con Wi-Fi | Primer byte | Primer cuadro |
| --- | --- | --- |
| Apertura 1 | 11.844 ms | 15.135 ms |
| Apertura 2 | 10.850 ms | 11.609 ms |
| Apertura 3 | 10.916 ms | 11.711 ms |
| Mediana de tres aperturas | 10.916 ms | 11.711 ms |

La traza numérica de OkHttp mostró una resolución del dominio de entrega
estancada durante unos 10 segundos en cada apertura, seguida de la
recuperación por Ubuntu. En esas tres solicitudes, la espera desde el envío
de cabeceras hasta la respuesta de Ubuntu fue de 449, 752 y 830 ms.
La política especial de resolución pública protegía únicamente el dominio
del backend. El dominio del Worker seguía usando la resolución del sistema.

Se repitió la referencia con el mismo objeto, datos móviles como red
predeterminada y límites de 2.500 kbit/s y 100 ms. El primer cuadro no llegó
antes del límite de 25 segundos de la prueba. Ese ensayo fallido se conserva
como resultado y no se convierte en una muestra de latencia completada.
Las condiciones de red se configuraron según la
[consola del emulador](https://developer.android.com/studio/run/emulator-console).

Desde Windows, cinco lecturas de cada API pública dieron medianas de 453 ms
para las 30 publicaciones de invitado y 459 ms para los 13 videos. Los
cuerpos pesaron 22.379 y 10.658 bytes respectivamente. Son lecturas públicas
desde otra plataforma y no se usan como referencia del tiempo de dibujo de
Social ni de las consultas autenticadas.

## Correcciones implementadas

- El dominio de entrega configurado usa también la resolución pública
  acotada que ya usaba el backend. Cada dominio tiene su caché independiente.
  Se reutiliza la carrera entre resolución del sistema y proveedores HTTPS,
  con un límite compartido de cuatro segundos. TLS sigue comprobando el
  dominio original y el transporte conserva la autorización por cuenta.
- Social pide 24 publicaciones en el primer acceso sin contenido visible.
  Las páginas siguientes y las actualizaciones con contenido conservan
  ventanas de 60. Se respeta el cursor que devuelve el servidor.
- Al actualizar Social dentro de la misma cuenta y modo, las publicaciones
  actuales siguen disponibles mientras llega la respuesta. Una respuesta
  válida sustituye la página y un fallo conserva la lectura anterior. El
  refresco explícito acepta el orden nuevo. El ranking local toma una
  instantánea de las señales y se calcula fuera del hilo de interfaz. Antes
  de publicarlo se comprueban generación y filtros vigentes.
- La observación del contenido visible de Social responde a cambios de
  publicaciones y autores visibles. Cambiar contadores deja esa observación
  activa. Se detiene al ocultar la pestaña o salir del primer plano y solo
  solicita momentos de autores realmente visibles.
- Las fotos de publicaciones usan `AsyncImage` con sus estados de carga y
  error. La composición adicional de `SubcomposeAsyncImage` se eliminó de
  estas tarjetas. Se conservan la variante acotada, los controles de lectura,
  el zoom y la apertura. Coil documenta el coste de la subcomposición en
  listas. [Documentación de Coil](https://coil-kt.github.io/coil/compose/).
- La intención inicial de reproducir pertenece a la cuenta y al video
  seleccionado desde su composición. La instalación de una lista fija esa
  intención antes de preparar Media3, comprobando que el destino siga siendo
  el seleccionado. El control común sigue pausando durante gestos,
  superposiciones y segundo plano y aplica la preferencia de datos móviles.
- La precarga del vecino se cancela cuando deja de estar permitida la
  reproducción, incluido el inicio del gesto. Espera el margen protegido del
  video visible y conserva sus límites de bytes y tiempo. Los cambios de
  contadores no cambian la lista de fuentes vecinas.
- La portada conserva un indicador de carga hasta el primer cuadro durante
  el inicio automático. El botón de reproducir corresponde a una pausa
  solicitada o al permiso de datos móviles. Los errores conservan su acción
  de reintento.

## Volumen de la primera página

Un ensayo HTTP local usó el manejador real `/v1/data/feed` con filas
ficticias y base de datos simulada. La ventana de 60 devolvió 39.734 bytes
y la de 24 devolvió 15.500 bytes, una reducción del 61,0 %. Las consultas
de enriquecimiento recibieron respectivamente 60 y 24 identificadores.
La búsqueda inicial de candidatos siguió pidiendo 240 filas.

La continuación desde el cursor 24 recuperó las siguientes 60 publicaciones.
Las primeras 60 del resultado combinado coincidieron con la página original
y las 84 resultantes tuvieron identificadores únicos. Esto comprueba el
contrato de paginación con ese conjunto. No mide una mejora de latencia de
PostgreSQL en producción. El acceso SSH volvió a requerir autenticación y
esa medición del servidor sigue pendiente.

## Validación y límites

Pasaron 20 controles locales de mantenimiento, dependencias Media3 y avisos.
La compilación de referencia y su prueba de primer cuadro en Wi-Fi terminaron
correctamente. El ensayo limitado de referencia falló como se describe arriba.

La compilación local posterior de la aplicación y de la suite instrumentada
terminó correctamente. Se usó el worktree de validación existente, con las
configuraciones locales ignoradas y la clave de depuración existente. La APK
comprobó las cuatro definiciones necesarias para arrancar antes de instalarse.
La revisión automática había rechazado una orden combinada anterior con el
mensaje `blocked by policy`, sin una razón más específica. Las órdenes de
compilación posteriores sí se ejecutaron y sustituyen ese bloqueo como estado.

La batería JVM completa aprobó **2.673 pruebas en 506 clases**, sin fallos,
errores u omisiones. Se corrigió el entorno de las pruebas del diario de
compartidos: Windows no puede reemplazar un archivo existente mediante
`File.renameTo` como el sistema POSIX de Android. Las 20 pruebas JVM usan
el protocolo de copia de respaldo de Android 9 y una URI de archivo compatible
con la unidad de Windows. Cuatro pruebas nuevas ejecutaron el protocolo moderno
de `AtomicFile` sobre Android 15 del emulador, sin esa sustitución de URI.
Comprobaron reemplazo, reapertura, aislamiento de cuentas, entrega durable de
adjuntos, cierre y recuperación de escritura interrumpida. El diario de
producción no se cambió para acomodarlo a Windows.

Pasaron **15 pruebas instrumentadas** de paginación/visibilidad de Social,
apertura de fotos y estado de error, posición inicial de Videos, caché de fotos
por invitado, pausa/reanudación y diario de compartidos. La comprobación nativa
R2 también pasó: Media3 recibió 4.096 bytes con `206` y `source=r2`, iguales a
Ubuntu, y ambas rutas rechazaron la sesión inválida con `401`.

La comparación del mismo video público, en el mismo AVD LOW con Wi-Fi, produjo:

| Apertura posterior al ajuste DNS | Primer byte | Primer cuadro | Fuente |
| --- | --- | --- | --- |
| 1 | 2.249 ms | 4.378 ms | R2 |
| 2 | 746 ms | 2.025 ms | R2 |
| 3 | 836 ms | 1.742 ms | R2 |
| Mediana | 836 ms | 2.025 ms | R2 |

La mediana del primer cuadro pasó de 11.711 a 2.025 ms en este ensayo. Es una
comparación de transporte y decodificación del mismo objeto público, con
sesiones invitadas, no una medida del recorrido completo del feed de miembro.
La lectura HTTP de Social en esa ejecución tardó 4.911 ms y la de Videos,
297 ms. No se atribuye a ese ensayo una carga instantánea de Social.

Al repetir con red móvil predeterminada, 2.500 kbit/s de subida y bajada y
100 ms, se agotó otra vez el límite de 25 segundos del primer cuadro. La traza
registró DNS en 33 ms, un primer intento TCP fallido tras 15.006 ms y una segunda
dirección que sí conectó. El primer byte llegó a los 22.304 ms. Se redujo el
presupuesto de cada intento TCP multimedia a cinco segundos, conservando los
30 segundos de espera entre lecturas y los reintentos de rutas. Esta versión
compiló ambos paquetes y volvió a aprobar las 2.673 pruebas JVM y las 15
instrumentadas anteriores. La lectura nativa R2 volvió a pasar. Sus aperturas
Wi-Fi dieron primeros cuadros de 4.444, 1.411 y 1.581 ms, con mediana de
1.581 ms, desde R2 en las tres. Los primeros bytes llegaron a 3.031, 693 y
704 ms. Social público tardó 1.750 ms y Videos, 287 ms en esa ejecución.

El ensayo limitado sigue fallando: recibió el primer byte a los 9.628 ms,
pero solo 5.467 bytes en 25 segundos, sin cuadro ni búfer reproducible.
El fragmento de 4 KiB sí se leyó correctamente desde ambos orígenes en la
misma configuración y rechazó ambas sesiones inválidas. Un diagnóstico
acotado comparó 64 KiB desde R2 y Ubuntu, con y sin `Range`, usando HTTP/2
y HTTP/1.1. Ninguna de esas seis lecturas completó el tramo dentro de su
presupuesto de 15 segundos. La demora no queda aislada a R2 ni a HTTP/2.
Un control con el perfil UMTS y 100 ms también agotó el primer cuadro.
Estos resultados no justifican desactivar HTTP/2 ni declarar corregida la
carga bajo esas condiciones. Sigue pendiente aislar la limitación efectiva
del emulador 36.2.12 y repetir la comparación con una red calibrada.
El control local sin TLS, mediante el puente del emulador al host, completó
64 KiB con ambas configuraciones, pero no recorre la misma salida pública y
no se usa para certificar la velocidad de esa salida. Las pruebas conservan
sus métricas numéricas también cuando fallan, sin ampliar sus límites.

Con datos móviles como ruta predeterminada, velocidad completa y sin retardo
artificial, las tres aperturas sí pasaron desde R2: primer cuadro de 4.471,
1.520 y 1.471 ms, con mediana de 1.520 ms. Los primeros bytes llegaron a 2.522,
697 y 640 ms. La consulta pública de Social tardó 1.642 ms y la de Videos,
270 ms. Se restauraron Wi-Fi, datos móviles y límites del AVD al terminar
cada ensayo. No se modifica el resultado fallido de la simulación limitada.

El pipeline de GitHub del commit `4ac5544a` terminó con errores. El trabajo
Android `quality-gate` se detuvo en `processDebugGoogleServices` porque
faltaba `google-services.json`, antes de compilar Kotlin. La misma causa ya
había afectado a la referencia. Tampoco hubo ejecución de las pruebas Android
nuevas en ese entorno. [Ejecución de este bloque](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/41672870771).

La ejecución anterior de GitHub, correspondiente a la referencia
`6001ad95`, falló antes de compilar Kotlin porque faltaba la configuración
Firebase `google-services.json` en el entorno de CI. Esa configuración local
está ignorada por Git. El archivo de producción no se debe publicar para
resolver el entorno de pruebas. [Ejecución de referencia](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/41667820172).

El trabajo compartido `backend-contracts` también falló al arrancar
`signaling-server/index.js` desde una prueba porque no encontraba
`dotenv`. Ese job instala únicamente las dependencias de la API. Hay que
completar su preparación antes de usar esa batería como evidencia de una
instalación limpia. Los trabajos de web también terminaron con errores y
conservan su revisión posterior al móvil. No se deduce un fallo del servidor
desplegado a partir de esas instalaciones de CI.

Faltan resolver la medición limitada y recorrer el feed autenticado con desplazamiento,
pausa, reanudación y precarga. También falta medir percentiles de cuadros en
dispositivos físicos de distintas capacidades. No hay una certificación de 60 FPS.
El host y el renderizado por software afectan las medidas del emulador.
[Guía de medición Android](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile).

Las evidencias locales están en la carpeta de trabajo, con prefijos
`feed103-` y `feed104-`. Incluyen las dos APK de referencia y sus hashes, las trazas
numéricas, los dos resultados instrumentados, el ensayo HTTP de paginación
y los controles de mantenimiento. La prueba conectada guarda un digest de la
ruta del objeto público y resultados numéricos. Las credenciales temporales
de invitado se mantienen en memoria.

Para revertir este bloque se revierte su commit en las dos ramas y se
comprueba de nuevo la igualdad de sus árboles. Las modificaciones de este
bloque son de cliente y pruebas, sin migraciones de datos.
