# 231. Actualización desde el historial de migraciones del Ubuntu activo

**Veredicto del 20 de noviembre de 2026. NO APTA PARA PRODUCCIÓN.** Se recuperó el acceso administrativo de lectura por Tailscale. El historial del servidor permitió descubrir un bloqueo de actualización que las pruebas desde una base vacía pasaban por alto. La corrección `922d8459` superó el ensayo de actualización y la reanudación tras un intento parcial. La intervención en el Ubuntu se limitó a consultas de lectura.

## Qué faltaba en las pruebas

De las 37 migraciones de la candidata, el servidor tenía 35 registradas. En 33 coincidían los bytes con Git. Para `002_sync_support.sql` y `003_password_migration_notice.sql`, se verificó directamente que la huella registrada coincidía con el archivo instalado y que la única diferencia con Git eran terminaciones de línea CRLF. Quedaban pendientes `001_runtime_prerequisites.sql` y `026_pending_friendship_allows_conversation.sql`.

Además, en Windows, el archivo generado por `git archive` con `core.autocrlf=true` cambiaba los bytes de las 37 migraciones. La candidata fija LF en ese directorio. Se comparó cada entrada del tar con su objeto Git al empaquetar con los modos `true`, `false` e `input`. En los tres coincidieron las 37.

El ejecutor reconoce los dos pares históricos por nombre y SHA-256 completo. Conserva la huella que ya registró el servidor y omite la ejecución del SQL previamente aplicado. Una modificación del SQL en cualquiera de los dos archivos sigue deteniendo la actualización. Las demás migraciones mantienen la comparación exacta de sus bytes.

## Ensayo de PostgreSQL 16.15

Se usaron una base privada, datos sintéticos y un historial con las mismas 35 huellas observadas. La preparación creó el esquema de prueba y las relaciones previas necesarias para ejecutar las migraciones. Las mediciones cubren el historial y el ejecutor. La distribución de filas y los datos del servidor real quedan fuera del ensayo.

| Punto de partida | Resultado medido |
| --- | --- |
| Ejecutor anterior, 35 filas históricas | Aplicó `001`, se detuvo en la huella de `002` y dejó 36 filas. El proceso de PostgreSQL devolvió código 3. |
| Ejecutor corregido, 35 filas históricas | Conservó las 35 huellas y aplicó solo `001` y `026`. Terminó con 37 filas. |
| Reintento desde las 36 filas que dejó el fallo anterior | Reconoció 36 migraciones, aplicó la restante y terminó con 37 filas. |
| Nueva ejecución sobre las 37 filas | Reconoció las 37 y preservó el historial. |
| Regresión del repositorio con PostgreSQL real | Falló antes de la corrección y pasó después, incluyendo SQL modificado, concurrencia, rollback y conservación de las huellas originales. |

## Comprobaciones del servidor

Los servicios de la API, PostgreSQL, Realtime y Signaling estaban en ejecución y sus comprobaciones de salud pasaban. El temporizador de copias estaba activo. El historial de systemd registraba un respaldo diario satisfactorio y un simulacro anterior de base de datos con 265 tablas. La imagen activa del conversor Office contenía la versión de libxml2 que Debian clasifica como vulnerable para Trixie. [Seguimiento de Debian](https://security-tracker.debian.org/tracker/CVE-2026-6653)

La actualización se calificó en el laboratorio. Siguen pendientes la validación de un despliegue autorizado, el ensayo integral de recuperación del equipo operativo, las pruebas Android físicas y la resolución del conversor. El informe [229](229-respaldo-y-restauracion-con-permisos-2026-11-20.md) documenta la restauración conjunta y el [230](230-coherencia-de-medios-durante-el-respaldo-2026-11-20.md) cubre las sustituciones concurrentes de medios.

El [recibo técnico](evidencias/231-actualizacion-con-huellas-historicas-de-migracion.json) conserva las huellas, los resultados del ejecutor y las comparaciones de paquetes. Revertir `922d8459` retiraría la compatibilidad demostrada y el contrato de bytes de las migraciones empaquetadas.
