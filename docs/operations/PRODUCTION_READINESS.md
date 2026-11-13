# Preparación del candidato 124

**APTO PARA STAGING. La decisión de publicación sigue pendiente.** Los resultados corresponden a código y servicios locales de las ramas estables, a la compilación Android de depuración y a tráfico sintético. El ámbito con evidencia incluye mensajería web, documentos, stickers, llamadas locales, privacidad de listas y un recorrido de juego sobre API.

| Antes de producción | Evidencia necesaria |
| --- | --- |
| Llamadas y LIVE | Dos dispositivos, Android–web, Wi-Fi y datos, TURN seleccionado correctamente, medios visibles/audibles y salida de un invitado sin interrumpir al anfitrión. |
| Multimedia | Subida/lectura por R2 del mismo original, rangos parciales, aislamiento de cuenta y recuperación de una caída del origen; tiempos hasta primer cuadro en equipos representativos. |
| Dinero y notificaciones | Proveedor en modo de pruebas, conciliación y reversos, entrega push con la app en segundo plano. |
| Operación | Restablecer una copia en destino aislado, medir recuperación y ensayar reversión de clientes/servicios compatibles. |
| Entrega | Revisar la [matriz](../quality/ACCEPTANCE_MATRIX.csv), la [cobertura vigente](../oficial/review-coverage.json) y los cambios locales de otros colaboradores antes de decidir la publicación. |

**Cambio reversible.** Los commits agrupan privacidad, servicios de tiempo real, web y documentación. No hay migración de esquema ni modificación de datos productivos. Una reversión se prepara desde el commit anterior, se valida en staging y requiere la misma decisión de publicación. Las capturas y los informes históricos del proyecto conservan su fecha y alcance.
