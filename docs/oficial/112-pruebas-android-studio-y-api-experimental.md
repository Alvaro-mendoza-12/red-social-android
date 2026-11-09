# Probar las ramas desde Android Studio y estado de la API

Actualización: 4 de noviembre de 2026, America/Bogota.

## Elegir el proyecto

Las carpetas son worktrees distintos. Para probar el prototipo, usar `File → Open`
y abrir `C:\Users\gamer\.codex\worktrees\audit98-delivery\red social - live-gifts`.
Su rama es `codex/experiencias-con-proposito`. No es necesario cambiar la rama de
`D:\red social - live-gifts` (`feature/live-gifts`) ni la de
`D:\red social - copia` (`main`). `correciones` queda intacta.

Sincronizar Gradle, abrir `Build Variants` y elegir la variante de `app`:

| Variante | Uso |
| --- | --- |
| `audit` | Pruebas funcionales con depuración. Se instala como `Novvin QA`, paquete `conectate.com.qa`. |
| `auditRelease` | Comparar fluidez con R8 y reducción de recursos, sin depuración. También usa `Novvin QA`. Ejecutar con `Run`, sin adjuntar el depurador. |
| `debug` | Depuración del paquete principal. Sus tiempos no son una medición de la versión publicada. |

El 8 de noviembre se comprobó que `D:\AndroidSTUDIO\jbr` ya no contiene
`bin\java.exe`. Si Gradle apunta a ese directorio, en Android Studio ir a
**Settings → Build, Execution, Deployment → Build Tools → Gradle** y elegir
**Gradle JDK → Add JDK**. En esta PC está instalado
`C:\Users\gamer\.jdks\jbr-21.0.8`. Sincronizar de nuevo, elegir `auditRelease`,
seleccionar el celular autorizado por USB y pulsar **Run ▶**. La ruta del JDK
es una configuración local de la máquina.

Las variantes QA conservan la instalación de Play y requieren su propio inicio
de sesión. Las ramas comparten el mismo identificador QA: instalar la otra
sustituye esa instalación de pruebas. No usar `startupAuditPrimaryPackage=true`
para esta validación. Esa excepción elimina el aislamiento del paquete.

Estas variantes QA aún no tienen registro propio de Firebase. Sirven para
probar las pantallas, la API y el dibujo; la entrega de notificaciones push debe
validarse con una instalación configurada para ese servicio. Su ausencia en QA
no permite concluir que la entrega de la aplicación publicada esté fallando.

El propietario informó tirones al ejecutar desde Android Studio la carpeta
LightGif en su celular. Se comprobó que `debug` tiene `isDebuggable=true` y
`isMinifyEnabled=false`. Eso es un posible contribuyente, no una prueba de que
explique todos los tirones ni de que la versión optimizada sea fluida en ese
equipo. La [guía de rendimiento de Compose](https://developer.android.com/develop/ui/compose/performance)
recomienda medir con R8 y modo release. La red afecta la llegada de contenido;
el tiempo de dibujar y desplazar contenido ya cargado debe medirse por separado.

El diagnóstico nativo de scroll registra variante, depuración efectiva,
depurador adjunto, hardware, commit y plazos de los cuadros. Un resultado de
emulador con renderizado por software no certifica FPS en el celular del
propietario. La prueba física de fluidez sigue pendiente.

## Activación del backend experimental

La API activa pasó a `novvin-api:purpose111-20261104_4c4350e0` el
5 de noviembre a las 03:35:43 UTC (4 de noviembre, 22:35:43 local). Su imagen es
`sha256:4b39ad0ca02627aaa1189e9743ea046d51887f80f317f824a6fa70fc120c4100`.
Se construyó sobre la API estable de la revisión 110, sustituyendo únicamente
trece fuentes del commit experimental `4c4350e0e371b02dfd211232133b35ba180febdf`.
La comprobación como UID/GID 10001 precedió al despliegue.

Se activaron `NOVVIN_EXPERIMENTAL_AGREEMENTS` y `NOVVIN_EXPERIMENTAL_PURPOSES` y
se verificaron las cuatro tablas experimentales y la columna de archivo de
acuerdos. El respaldo previo está en
`/home/alvaro/novvin-candidates/recovery110-20261104/purpose111-prechange-20261105T033528Z`:
dump de 7.261.969 bytes, catálogo `pg_restore`, esquema y huellas comprobados,
además de la configuración anterior. Sólo se reinició el servicio API.

Las lecturas de salud y disponibilidad terminaron con HTTP 200 y Docker
`healthy`; las dos capacidades experimentales rechazaron una consulta sin
autenticación con 401. Se verificaron las huellas de las trece fuentes activas.
La proyección de la publicación de la incidencia conservó cuatro reacciones y
dos comentarios antes y después. No se crearon publicaciones, mensajes ni
cuentas de prueba en producción. Hubo respuestas 502 durante el arranque de la
única réplica, antes de recuperar disponibilidad. El marcador histórico de
`/ready` no identifica este overlay: usar la imagen y las huellas registradas.

La configuración persistente tiene SHA-256
`c016112677c9c41f0e9f688c04b934cd5be118bebec96b469c0f35063141f52b`.
Para una reversión funcional posterior se deben desactivar las banderas y
conservar las tablas y su limpieza de cuentas. No restaurar una base antigua ni
borrar registros experimentales para revertir pantallas.

Activar estos contratos no incorpora pantallas experimentales a las ramas
estables. Su código se publica sólo en `codex/experiencias-con-proposito`; el
propietario los validará antes de decidir su promoción. El AAB estable sigue
siendo **9.1.40 / 180**, de la revisión 110, sin esas pantallas. Esta actualización
operativa y las herramientas QA no son una APK estable nueva ni el cierre de
la revisión móvil integral.
