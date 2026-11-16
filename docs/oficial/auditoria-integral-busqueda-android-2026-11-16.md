# Auditoría integral de Novvin · búsqueda Android

Continuación del 16 de noviembre de 2026. La base comprobada es el árbol 5b31b0991c6713d4e75b90cc9d67dde68b65baeb, compartido por main y feature/live-gifts al empezar este bloque. La [revisión móvil 101](101-mobile-inicio-cuentas-y-comentarios-2026-10-31.md) y el [bloque de cierres 171](171-cierres-precarga-avatares-y-fondos-2026-11-16.md) conservan sus verificaciones pendientes.

## Defectos y cambios

- **Confirmar desde el teclado.** La pantalla pide una búsqueda inmediata desde submitSearch. El controlador descartaba cualquier petición con la misma consulta normalizada mientras el trabajo estuviera activo. Ahora la confirmación puede liberar la espera de escritura. Se conserva el mismo trabajo y, si ya inició, los resultados y las peticiones activas.
- **Paginación.** Cada página volvía a calcular las claves de todos los resultados previos y de los recién recibidos. El controlador guarda las claves de la consulta actual y comprueba solo los elementos de cada nueva página. Se conserva la primera coincidencia, el orden y el reintento de una página fallida. Al limpiar o cambiar la consulta, se reinician las claves. Esta optimización conserva un conjunto en memoria mientras se muestran los resultados.
- **Limpieza.** Se sustituyeron la espera fija y la deduplicación repetida en UnifiedSearchController.kt. El bloque requiere cero dependencias nuevas y conserva los archivos y recursos existentes.

El controlador tiene tres consumidores de producción. La búsqueda general usa la confirmación inmediata y la paginación. La búsqueda de salas de voz y la búsqueda de invitaciones desde una sala usan la espera al escribir. Se leyeron los puntos de integración y se conservó su contrato.

## Verificación

Se compilaron el controlador real y UnifiedSearchControllerTest.kt con Kotlin 2.2.10, JDK 21 y objetivo JVM 17. Se ejecutó la clase directamente con JUnit 4.13.2 y Coroutines Test 1.10.2 del caché local. Esta comprobación es independiente de Gradle Android.

| Comprobación | Base | Corrección |
| --- | --- | --- |
| Clase JVM completa | 10 aprobadas y 2 fallos de aserción reproducidos | 12 aprobadas |
| Consulta confirmada en el ms 100 | Inicio en el ms 350 | Inicio en el ms 100 |
| Cálculos de clave en tres páginas de prueba | 12 | 7 |

La demora corresponde al reloj virtual del controlador. El recuento compara trabajo determinista en una muestra con duplicados. Quedan por medir tiempo de red, uso de memoria, fluidez y efecto en dispositivos. Los controles también cubren resultados rápidos de personas con contenido lento, fallos independientes, respuestas antiguas, cancelación, reintento y cursores sin progreso.

## Sugerencias y etiquetas

La pantalla observaba dos flujos derivados. Los dos recorrían el contenido para calcular las etiquetas, y editar la consulta volvía a leer el mismo contenido aunque la página seguía igual. Ahora la pantalla observa un estado con sugerencias y etiquetas. El flujo prepara etiquetas, títulos y claves al recibir contenido nuevo, y normaliza las entradas del historial cuando cambia el historial. Editar la consulta solo vuelve a seleccionar y ordenar las sugerencias ya preparadas. El estado se limpia cuando termina el período de suscripción.

La prueba con dos resultados, una edición de la consulta y un cambio en el historial registró 16 lecturas de la lista con las dos expresiones originales del ViewModel y cuatro con el flujo nuevo. El recuento mide trabajo en JVM. El ensayo mantiene el orden de las sugerencias, el límite de seis, los acentos, la deduplicación y el recuento de etiquetas. También comprueba que limpiar y reemplazar contenido e historial retire las sugerencias de la búsqueda anterior.

En la referencia, 18 de 19 pruebas pasaron y falló la comprobación de lecturas repetidas. Después del cambio, pasaron las 19 pruebas de búsqueda y los cuatro controles del generador de cobertura. Compilaron directamente SearchDiscovery, SearchUsersViewModel y SearchUsersScreen con Kotlin y el compilador de Compose, usando las clases Android compiladas previamente y los JAR de dependencias del caché local. La compilación del módulo completo y la medición en dispositivo siguen pendientes.

## Pendientes

- Integrar el resultado de compilación, lint y pruebas Gradle de la variante que incorpore esta corrección. Verificar en dispositivo el teclado, la navegación, el cambio de cuenta y la paginación con el servidor propio.
- Completar la revisión Android por módulo y dispositivo, incluidas las regresiones multimedia de 171 y las terminaciones por memoria. Después continúan web y la integración con el servidor Ubuntu, según la prioridad del propietario.
- Quedan pendientes la limpieza general, la comparación del tamaño de los paquetes y la validación de una nueva entrega. Este bloque no produjo una APK ni un despliegue.

La [matriz de cobertura](review-coverage.json) registra lectura de código por huella. Los estados de lectura y los resultados de pruebas tienen alcances distintos.
