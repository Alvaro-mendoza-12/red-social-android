# 230. Una sustitución de avatar durante el respaldo

**NO APTA PARA PRODUCCIÓN.** En el ensayo anterior, una copia con cinco huellas válidas pasó la verificación y restauró una fila de 135 bytes junto a un avatar de 243. La solicitud autenticada de sustitución había terminado después del volcado SQL y antes del archivo de medios. La API recuperada devolvió HTTP 200 con la imagen equivocada para ese punto de recuperación. El otro medio conservó su huella. El [informe 229](229-respaldo-y-restauracion-con-permisos-2026-11-20.md) describe el ensayo anterior con las escrituras en reposo.

## Cambio en el productor

El commit `ae8e71e5` toma el volcado y la relación de archivos desde una misma instantánea de PostgreSQL. El servidor sigue atendiendo solicitudes. El productor recorre el tar, comprueba presencia y tamaño y contrasta los SHA-256 almacenados en la base. Para las filas históricas que aún carecen de una huella, comprueba también que su versión de PostgreSQL siga visible al cerrar la copia. En caso de discrepancia, termina con error antes de actualizar `latest`. La herramienta utiliza SQLite temporal en el disco del respaldo. Los catálogos auxiliares se eliminan al terminar.

## Resultado con la API y PostgreSQL reales

Se ejecutaron el productor, PostgreSQL 16.16 y la API con el código de las dos correcciones de respaldo. Se usaron datos sintéticos, volúmenes separados, una red privada sin salida y una barrera de tiempo que dejó terminar la petición HTTP entre el volcado y el archivo. El avatar inicial incluía una ruta histórica vacía. La API lo resolvía por su ubicación canónica.

| Operación durante la copia | Respuesta de la API | Decisión del productor | Última copia |
| --- | --- | --- | --- |
| Sustitución de 135 por 243 bytes | 200 | Rechazada por tamaño y versión de fila | Se conservó la copia válida previa |
| Sustitución de 243 por otros 243 bytes | 200 | Rechazada por versión de fila | Se conservó la copia válida previa |
| Alta independiente después de la instantánea | 200 | Aceptada con los dos objetos que figuraban en la base capturada | Se publicó la nueva copia de laboratorio |

Los dos fallos dejaron cero directorios parciales. Se recuperó la copia buena en volúmenes distintos después de apagar el origen. El endpoint privado `/v1/auth/me` identificó al titular de la sesión anterior, y las dos lecturas de medios coincidieron byte a byte con los originales. En esta ejecución transcurrieron 20,271 s hasta restaurar base, roles y archivos y 27,680 s hasta comprobar el destino por HTTP.

## Regresiones y coste

- **CORREGIDO Y VERIFICADO.** Pasaron 26 de 26 regresiones Linux, entre ellas nueve nuevas sobre desaparición, sustitución, tamaño, huella, versión de fila, enlaces duros, rutas con coma, catálogos vacíos y el final del gzip.
- **VERIFICADO Y APROBADO.** En 1.536 objetos sintéticos de 64 KiB, 96 MiB antes de comprimir, la inspección GNU tar ya existente tardó una mediana de 0,852 s. La comprobación nueva añadió 0,703 s con el catálogo histórico o 0,883 s con huellas registradas. Se alternaron los tres lectores en tres rondas sobre caché caliente. Los tiempos excluyen las consultas SQL y el volcado.
- **PENDIENTE.** En medios históricos con igual tamaño, una alteración externa al producto que deja intacta la fila carece de una huella de origen contra la que comparar. Tampoco se ha demostrado un punto único de recuperación para las escrituras del chatbot, ni el coste y la frecuencia de reintentos sobre el volumen del Ubuntu físico.
- **PENDIENTE.** Para la decisión de producción siguen abiertos el conversor Office, los recorridos físicos Android y la capacidad y recuperación del servidor a escala operativa. La comprobación de lectura en los dos ingresos de Ubuntu devolvió HTTP 200, TLS 1.3 y la revisión `cc6a022e`.

El [recibo técnico](evidencias/230-coherencia-de-medios-durante-el-respaldo.json) conserva las huellas de los scripts ensayados, los códigos de salida, las medidas individuales y las observaciones HTTP. El [manual de operaciones](../UBUNTU_OPERATIONS_BACKUP_RUNBOOK_2026.md) recoge cómo atender un rechazo y comprobar la antigüedad de la copia válida. Revertir `ae8e71e5` retiraría el control y volvería a permitir la combinación inconsistente observada.
