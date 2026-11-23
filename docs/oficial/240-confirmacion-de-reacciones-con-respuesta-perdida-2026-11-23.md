# Confirmación de una reacción cuando se pierde la respuesta

En Social, una conexión cerrada después de guardar el cambio podía provocar un mensaje de error y revertir el icono. El servidor ya podía conservar el nuevo estado. El caso se confirmó cortando la respuesta HTTP después de que PostgreSQL almacenara el cambio. La captura del propietario carece de la petición y de la versión instalada para atribuirle esa ejecución exacta. [Corrección anterior del servidor](235-reacciones-y-auditoria-juegos-compartir-2026-11-22.md)

El repositorio Android comprueba el estado guardado si la operación contra Ubuntu devuelve un error de transporte. Hace una lectura autenticada con un límite total de 2,5 segundos y verifica que corresponda a la misma publicación y cuenta. Si el estado coincide, sigue el recorrido habitual de caché y confirmación. Si la lectura falla o devuelve otro estado, conserva el error original. Una cancelación de sesión detiene el recorrido. Los rechazos HTTP conservan su manejo actual. La comprobación usa solo una lectura.

| Evidencia | Resultado |
| --- | --- |
| Referencia y política JVM | Fallaron cuatro de ocho controles en la referencia sin lectura de confirmación. Aprobaron los ocho con el cambio. |
| Android Gradle | Aprobaron 40 pruebas en 5 clases. El repositorio hizo POST seguido por GET para el acuse perdido y solo POST para el rechazo explícito. |
| API y persistencia | Siete pruebas aprobaron con PGlite y siete con PostgreSQL 16.9 en loopback. La regresión corta el acuse tras guardar, consulta el estado como el autor de la reacción y como otra cuenta, y repite para la baja. |
| Conectividad | El origen HTTPS configurado para Android respondió correctamente. El dominio web respondió HTTP 402 con DEPLOYMENT_DISABLED de Vercel. |

La corrección aplica a Social, Perfil, Ranking y Detalle mediante el repositorio común. La prueba HTTP usó cuentas sintéticas y aislamiento local. Queda pendiente la comprobación en dispositivos y el despliegue efectivo. Las partidas nativas y la cobertura completa de Compartir siguen abiertas. [Auditoría y secuencia de Compartir y Juegos](236-fase-0-novvin-compartir-juegos-api-2026-11-22.md)
