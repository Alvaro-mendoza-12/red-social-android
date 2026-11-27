# Notificaciones Android, Ajustes web y restauración

27 de noviembre de 2026 · Continuación de la [auditoría de preparación](257-auditoria-produccion-y-entrada-web-2026-11-27.md).

## Avisos en Android

Una lectura iniciada antes de marcar o borrar un aviso podía publicar un recuento obsoleto después de confirmarse el cambio. La pantalla también pedía un recuento adicional que se solapaba con el refresco del repositorio. Ahora cada lectura conserva la identidad de la visita de sesión y la confirmación invalida las lecturas anteriores. El repositorio agrupa el refresco. La pantalla sigue mostrando la lista tras confirmar la operación.

La regresión cubre una respuesta antigua durante un cambio confirmado y el regreso a la misma cuenta con una visita de sesión nueva. Compiló el candidato de release y pasaron 26 pruebas en cuatro clases, incluidas las nueve del contador. La suite completa de 3.608 casos corresponde a la APK 189 anterior a esta corrección. La consulta de solo lectura en un Moto g04 mostró Android 14 y Novvin 9.2.1 (185) instalado por Google Play. Queda pendiente validar la corrección en un binario nuevo y comprobar notificaciones entre dos teléfonos.

## Ajustes en la web

Si el servidor fallaba al entregar el perfil, el formulario dejaba visibles los valores iniciales y permitía guardarlos. Eso podía alterar la privacidad al guardar el nombre, porque la petición incluye ambas preferencias. El formulario espera un perfil válido y ofrece reintentar ante un fallo. Una respuesta anterior pierde el permiso de modificar el formulario en cuanto se inicia otra carga. El mensaje al usuario no incluye texto de la API ni nombres de configuración interna.

Las diez pruebas de Ajustes, las 1.361 pruebas Jest de la web y la compilación pasaron. Chromium simuló un error al abrir Privacidad y verificó que el formulario permaneciera oculto. Al reintentar recuperó «Cuenta privada» y «Seguidores» sin enviar escrituras. El recorrido pasó a 320, 390, 768 y 1.440 px, con el ancho del documento dentro de la ventana. Este cambio está comprobado en la compilación local.

La APK que el Moto g04 instaló desde Google Play permitió verificar el certificado de distribución. Su huella difiere de la del certificado de carga que figuraba originalmente en `assetlinks.json`. Firebase sirve ambas huellas en el canal activo. Queda verificar la apertura de enlaces tras conectar el dominio raíz y `www`.

## Respaldo real en restauración aislada

Se verificaron las huellas y archivos del respaldo del 26 de noviembre a las 03:20, hora de Bogotá. El ensayo recuperó **279 tablas** con roles, propietarios y permisos, usando un contenedor PostgreSQL 16.15 sin acceso a la red y con límites de CPU y memoria. El archivo de almacenamiento pasó su comprobación de integridad. Quedan por restaurar y ejercer el arranque de la aplicación y la lectura real de los medios.

- Pasaron **73 pruebas** dirigidas a persistencia y API con PostgreSQL 17 aislado. Una quedó omitida, la del análisis antivirus histórico. Se provisionaron las dependencias de esquema de la aplicación, un rol ficticio específico de recuperación y una clave de cursores de prueba. Se verificaron las migraciones en una base nueva, el primer mensaje y los permisos de conversación, recuperación de cuenta y recuentos que sobreviven a reabrir las conexiones.
- La API, la pasarela de eventos y señalización instaladas tienen 24, 5 y 5 dependencias npm directas de producción. Las versiones instaladas coinciden con sus respectivos lockfiles. El registro npm devolvió cero avisos para los tres inventarios. El resultado no cubre paquetes del sistema operativo o imágenes de contenedor.
- Ocho contenedores principales tenían límites de CPU y memoria y estaban en modo no privilegiado. Los roles de aplicación inspeccionados carecen de superusuario y de permiso para saltar RLS. Ninguna de las 279 tablas tiene RLS activada. La API sigue siendo el límite de autorización para las tablas y faltan verificaciones sistemáticas por operación y rol.

## Carga de cinco minutos

Con diez cuentas ficticias y cinco receptores en PostgreSQL 17, una API y un gateway locales, la prueba de llegada constante solicitó 12 operaciones por segundo durante 300 s. Completó correctamente las **3.589 peticiones despachadas** y las **898 entregas WebSocket** esperadas. El generador llegó tarde a 11 turnos de 3.600, con cero filas de espera en el pool en las muestras. Por ese déficit, la prueba no aprueba el objetivo estricto de 12 operaciones por segundo sostenidas.

| Ventana | P50 | P95 | P99 |
| --- | ---: | ---: | ---: |
| Primeros 10 s | 8,19 ms | 18,44 ms | 34,62 ms |
| Últimos 10 s | 7,78 ms | 18,29 ms | 20,27 ms |

La API alcanzó 167 MiB de RAM. El ensayo no mide el máximo de Ubuntu. La conexión de ambos nombres en GoDaddy, la conciliación de la API de Ubuntu y la verificación integral de producción siguen abiertas.
