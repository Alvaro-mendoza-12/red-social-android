# Estadísticas del panel y ciclo de Rondas en Ubuntu

27 de noviembre de 2026 · Continúa el [ensayo de mensajería y operación de Ubuntu](266-indices-y-solicitudes-ubuntu-2026-11-27.md).

## Estadísticas de administración

La imagen que estaba activa en Ubuntu devolvió HTTP 500 al abrir Estadísticas con una cuenta administradora sintética en una base nueva. La consulta requería las tablas históricas `profiles`, `users` y `videos`. El servidor activo tiene las tres. Se comprobó mediante una consulta de solo lectura que la cuenta de PostgreSQL de la API puede ver sus columnas de identificación.

Estadísticas consulta qué tablas históricas tienen la columna `id` e incorpora las disponibles. Siempre incluye los datos nativos y de compatibilidad. La consulta conserva la deduplicación de identidades y videos, y excluye las eliminaciones registradas.

Pasaron las pruebas del módulo de moderación y 34 peticiones HTTP en una red interna sin puertos publicados. Cuatro cuentas sintéticas entraron por el flujo de contraseña y probaron los niveles de acceso normal, lectura, edición y completo. Las estadísticas devolvieron cuatro cuentas con la base nativa, cinco tras una importación parcial y seis con la importación completa. Los identificadores repetidos contaron una vez. El conteo de videos incluyó el video importado. El panel también mostró el catálogo y la lista de usuarios a los roles habilitados.

Solo el administrador completo publicó y retiró el aviso de prueba. Un reintento conservó el aviso existente. La API mostró el aviso de código 192 a un cliente con código 191 y lo omitió para el código 192. El enlace devuelto apuntó a la ficha oficial de Google Play. La prueba usó datos sintéticos y no distribuyó avisos a cuentas de producción.

## Rondas

Una copia aislada de la imagen activa de Ubuntu superó 46 peticiones con dos cuentas de prueba. La creación simultánea conservó una ronda única. Se comprobó la continuidad de las participaciones tras vincular su identidad histórica, la invitación, los votos reintentados, el podio y la apertura de la ronda siguiente. Al consultar una ronda iniciada hacía 36 horas con todos sus plazos vencidos, la API respondió con una ronda nueva y activa.

La candidata de administración permanece en el laboratorio. La promoción de Ubuntu sigue pendiente mientras `/ready` responda 503 por las firmas del antivirus de almacenamiento. La revisión integral sigue abierta.
