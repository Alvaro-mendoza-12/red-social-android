# Rendimiento de Social y Videos Android

Estado del 3 de noviembre de 2026. Las correcciones de este bloque están
implementadas y su validación Android está pendiente. La revisión integral
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

La revisión automática rechazó ejecutar la compilación y las pruebas de la
versión modificada con el mensaje `blocked by policy`. No dio una razón más
específica. Las regresiones de resolución, observación visible de Social y
estados de las fotos están añadidas, con ejecución Android pendiente.
El pipeline habitual de GitHub debe contrastarse con el commit que se publique.
Un resultado anterior no valida los cambios de este documento.

La ejecución anterior de GitHub, correspondiente a la referencia
`6001ad95`, falló antes de compilar Kotlin porque faltaba la configuración
Firebase `google-services.json` en el entorno de CI. Esa configuración local
está ignorada por Git. El archivo de producción no se debe publicar para
resolver el entorno de pruebas. [Ejecución de referencia](https://github.com/Alvaro-mendoza-12/red-social-android/actions/runs/41667820172).

Faltan compilar y ensayar esta versión, repetir el primer cuadro del mismo
objeto con ambas conexiones y recorrer el feed autenticado con desplazamiento,
pausa, reanudación y precarga. También falta medir percentiles de cuadros en
dispositivos físicos de distintas capacidades. No hay una comparación de
primer cuadro posterior a la corrección ni una certificación de 60 FPS.
El host y el renderizado por software afectan las medidas del emulador.
[Guía de medición Android](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile).

Las evidencias locales están en la carpeta de trabajo, con prefijo
`feed103-`. Incluyen las dos APK de referencia y sus hashes, las trazas
numéricas, los dos resultados instrumentados, el ensayo HTTP de paginación
y los controles de mantenimiento. La prueba conectada guarda un digest de la
ruta del objeto público y resultados numéricos. Las credenciales temporales
de invitado se mantienen en memoria.

Para revertir este bloque se revierte su commit en las dos ramas y se
comprueba de nuevo la igualdad de sus árboles. Las modificaciones de este
bloque son de cliente y pruebas, sin migraciones de datos.
