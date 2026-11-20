# 229. Recuperación de una copia completa de NOVVIN con sus permisos

**Veredicto del 20 de noviembre de 2026. NO APTA PARA PRODUCCIÓN.** El entorno privado recuperó la base, los permisos, la sesión de un usuario y sus dos archivos después de apagar el origen. El resultado cubre datos pequeños y escrituras en reposo. Sigue pendiente medir una recuperación del tamaño del servidor y demostrar la coherencia entre la base y los archivos con escrituras simultáneas.

## Dos correcciones verificadas

| Corrección | Fallo observado | Resultado |
| --- | --- | --- |
| Verificación de respaldos, `ad74d1be` | El verificador podía emitir `BACKUP_VERIFIED` cuando el manifiesto también omitía un archivo obligatorio o el recibo estaba truncado. | En Linux, ocho de los trece casos fallaban antes y los trece pasaron después. Se conservó la lectura del formato histórico sin metadatos y de las versiones 1, 2 y 3. |
| Ensayo aislado con roles, `286279c5` | El ensayo anterior omitía propietarios y permisos. Un intento independiente con un administrador inicial de otro nombre falló al recrear una membresía concedida por `conectate`. | El modo `--with-roles` restauró 201 tablas con PostgreSQL 16.16. Un archivo de roles con huella correcta y una concesión a un rol ausente detuvo el ensayo y liberó el contenedor. Pasaron 17 de 17 regresiones Linux. |

En una copia fabricada por el script real, los tres conjuntos completos v1–v3 pasaron tanto antes como después. Las versiones antiguas se reconstruyeron siguiendo el formato de sus productores. Cuatro variantes con pérdida del Compose v1, pérdida del archivo del chatbot v2, JSON truncado o tamaño declarado incorrecto habían pasado y ahora se rechazan. La inspección independiente con `pg_restore --list` leyó 1.070 entradas del volcado.

El ensayo con roles arranca un clúster efímero con el nombre de su administrador de origen. Se omite únicamente la creación duplicada de ese rol. Se conservan las sentencias de configuración y de concesión, se exige éxito en toda la transacción y se recrea la base desde el archivo con su propietario, permisos y configuración. El ensayo histórico de base sola también recuperó 201 tablas. [Manual de GRANT de PostgreSQL 16](https://www.postgresql.org/docs/16/sql-grant.html) · [Manual de pg_dumpall](https://www.postgresql.org/docs/16/app-pg-dumpall.html)

## Simulacro conjunto

Se ejecutó `backup-ubuntu-primary.sh` desde una extracción de `ad74d1be`, en una red privada sin salida, con datos sintéticos y volúmenes separados. El servicio API usó el proceso real, UID 10001, y anunció 25 capacidades. Dos imágenes se subieron por la API antes de la copia. Se conservaron el volcado de base, el archivo de roles sin contraseñas, los medios, los datos del chatbot y el Compose. El almacenamiento del chatbot contenía un archivo de prueba en un volumen. Su motor de conversación queda fuera de este ensayo.

| Medida observada | Resultado |
| --- | --- |
| Creación del respaldo | 3,115 s |
| Restauración de base, roles y archivos en volúmenes nuevos | 19,610 s |
| Desde el comienzo de la recuperación hasta la lectura autenticada de ambos medios | 26,685 s |
| Tablas y catálogo de permisos | 201 tablas. Coincidieron los propietarios y permisos de 206 relaciones de usuario y las huellas de 37 migraciones. |
| Control de acceso | Se conservaron el otorgante de la membresía, la lectura del rol restringido, la denegación de escritura y creación, y la revocación del acceso público a la base. |
| Sesión y medios | La sesión anterior abrió el avatar y el medio de publicación con HTTP 200 y el mismo SHA-256 en origen y destino. |
| Estado y punto de corte | Se recuperaron los bytes del estado sintético del chatbot y se excluyeron los marcadores creados después de la copia. |

Los servicios de origen estaban apagados antes de recuperar el destino. Se aprovisionó una contraseña de ejecución nueva. Se comprobó que los roles restaurados de aplicación y ensayo carecían de sus contraseñas de origen. Las duraciones describen una sola ejecución con dos medios. El objetivo de tiempo de recuperación y la pérdida admisible de datos del Ubuntu operativo siguen pendientes de medición.

## Estado operativo y siguiente comprobación

- **VERIFICADO Y APROBADO.** Los dos ingresos HTTPS de Ubuntu devolvieron HTTP 200 en `/ready`, TLS 1.3, 25 capacidades y el backend `cc6a022e`. El recibo conserva el instante de ambas consultas. Esta comprobación fue de lectura.
- **PENDIENTE.** Recuperar una copia de escala representativa, comprobar escrituras coincidentes entre el volcado SQL y los medios y contrastar las duraciones con objetivos operativos acordados. El procedimiento de recuperación está en el [manual de Ubuntu](../UBUNTU_OPERATIONS_BACKUP_RUNBOOK_2026.md).
- **PENDIENTE.** Persisten el hallazgo crítico del conversor Office, las pruebas de llamadas, Bluetooth, Doze y FCM en equipos físicos y el acceso administrativo al servidor sujeto a renovación. Las comprobaciones anteriores de capacidad, Android y web y las 38 pruebas condicionales constan en los [informes 224](224-certificacion-independiente-2026-11-20.md), [226](226-capacidad-conmutacion-y-ci-2026-11-20.md) y [228](228-pruebas-condicionales-de-postgresql-redis-y-medios-2026-11-20.md).
- **Reversión.** Los dos cambios están en commits separados. Para retirar el modo opcional se puede revertir `286279c5`. Revertir `ad74d1be` volvería a permitir los falsos positivos documentados.

Las huellas de los cinco artefactos, los contadores antes y después, los permisos y las cuatro salidas del ensayo CLI figuran en el [recibo reproducible](evidencias/229-respaldo-y-restauracion-con-permisos.json).
