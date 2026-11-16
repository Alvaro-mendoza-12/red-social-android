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

## Pendientes

- Integrar el resultado de compilación, lint y pruebas Gradle de la variante que incorpore esta corrección. Verificar en dispositivo el teclado, la navegación, el cambio de cuenta y la paginación con el servidor propio.
- Continuar la inspección del cálculo de sugerencias y etiquetas, que se ejecuta desde más de un flujo del ViewModel. Su costo aún no se midió.
- Completar la revisión Android por módulo y dispositivo, incluidas las regresiones multimedia de 171 y las terminaciones por memoria. Después continúan web y la integración con el servidor Ubuntu, según la prioridad del propietario.
- Quedan pendientes la limpieza general, la comparación del tamaño de los paquetes y la validación de una nueva entrega. Este bloque no produjo una APK ni un despliegue.

La [matriz de cobertura](review-coverage.json) registra lectura de código por huella. Los estados de lectura y los resultados de pruebas tienen alcances distintos.
