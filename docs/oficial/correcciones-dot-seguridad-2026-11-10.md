# Correcciones de seguridad de la auditoría, 10 de noviembre de 2026

Base: `main` en `455d26999111116f59ae8bd76dae56a5e137aee3`.
Destino de trabajo: `correcciones-dot`. No se inició ningún despliegue manual ni se modificaron `main` o `feature/live-gifts`. Al publicar el commit, la integración existente de Vercel inició automáticamente un despliegue y notificó éxito. El propietario confirmó en su panel que ese despliegue corresponde a Preview y que Production usa `main`. Esta confirmación no presupone el destino de despliegues futuros.

## Cambios

- Las lecturas de compatibilidad de publicaciones aplican la política de audiencia del feed y la política vigente del autor: perfil privado/oculto, seguimiento, bloqueo, suspensión y eliminación. La decisión precede al filtrado del cliente, la paginación y los recuentos HEAD. Comentarios y reacciones requieren una publicación legible.
- Las lecturas de Momentos aplican destinatarios seleccionados/excluidos, seguidores, chats directos, privacidad, eliminación y caducidad. Los chats nativos autorizados también se consideran. Una audiencia desconocida no concede acceso.
- Los perfiles no entregan publicaciones privadas a seguidores ni incluyen esas publicaciones en el contador. Las anulaciones de seguimiento también se respetan.
- Las inserciones con ID elegido por el cliente verifican colisiones contra filas nativas, importadas y de compatibilidad, sin depender de una página limitada o filtrada. La comprobación y la escritura usan una conexión transaccional, bloqueo por recurso y comparación de la fila autorizada antes de fusionar datos.
- PATCH no puede cambiar IDs, propietario, anfitrión o referencias de ámbito para sobrescribir otro recurso. Su persistencia genérica usa la misma comprobación de colisiones. Se conservan las equivalencias de la cuenta local/pública y las proyecciones nativas de salas/mensajes. Las visitas de perfil repetidas pueden renovar únicamente su pareja validada de perfil/visitante, con fecha del servidor.
- Los errores de login sin cuenta, con contraseña incorrecta local o con contraseña migrada incorrecta comparten estado y respuesta. Una cuenta inexistente también realiza una comparación bcrypt ficticia de coste 12. Esto no demuestra indistinguibilidad temporal completa.
- El verificador Android rechaza el aviso de entradas sin firmar aunque jarsigner imprima `jar verified.`. Conserva el aviso normal de certificado de subida autofirmado.
- El job de contratos Android instala las dependencias de signaling-server, necesarias para comprobar su configuración productiva. Las nuevas regresiones se incluyen en `test:audit-security`, ejecutado por el bucle de contratos existente.

## Verificación de este bloque

En el equipo cloud, con Node 24.22.1 y dependencias del lockfile:

- `npm test`: **1.061 aprobadas, cero fallidas, 16 omitidas** (1.077 casos reconocidos por el runner).
- `npm run check`: aprobado; comprobación sintáctica de los archivos y scripts declarados por el proyecto.
- `npm run test:audit-security`: **37 aprobadas, cero fallidas, una omitida**. La omitida necesita una base PostgreSQL aislada configurada explícitamente.
- La función de comprobación SQL de esa prueba se ejecutó adicionalmente en PostgreSQL embebido PGlite: rechaza colisiones nativas/importadas/de compatibilidad, conserva una fusión propia y reconoce identidades sintéticas de proyecciones. Es una comprobación SQL **secuencial**, no una prueba de concurrencia entre conexiones PostgreSQL.
- Herramientas Python Android: **20 aprobadas**, incluidas las regresiones negativa/positiva del parser de firma.
- `git diff --check`: sin errores.
- Las nuevas regresiones de privacidad, reescritura, login y aviso de firma fallaron antes de su corrección. La revisión adicional detectó y corrigió casos de visitas repetidas, alias de cuenta, proyecciones de salas y Momentos de chat directo.

Las pruebas de rutas usan dependencias externas controladas y datos sintéticos. No se publicaron posts, se cambiaron contraseñas ni se manipularon cuentas reales.

## Límites y pendientes

- La primera ejecución de `npm audit --omit=dev` registró **seis avisos moderados**, sin corrección automática ofrecida, en la cadena firebase-admin/Firestore/google-gax/glob/rimraf/minimatch. Al integrar Android y web, una nueva consulta registró **cuatro moderados y correcciones disponibles**, sin avisos altos o críticos. No hubo cambios en las dependencias backend entre ambas consultas: esa variación no se atribuye a una corrección de código. Falta evaluar y probar las actualizaciones ofrecidas; no se forzó un cambio incompatible ni se desactivó la puerta de dependencias.
- No hay ejecuciones de GitHub Actions para este commit ni se compiló Android, generó una APK/AAB, usó un dispositivo o desplegó manualmente el backend en este bloque. El estado de Vercel del commit `13593bb315ba8bb5e96c7f45af94d4098d4363f7` indica éxito para el despliegue automático [9xecJXjrFXZSp5Eoaj8qNTF5Q7te](https://vercel.com/alvaro-s-projects1/red-social-android/9xecJXjrFXZSp5Eoaj8qNTF5Q7te); el propietario confirmó su entorno Preview. El parser de firma se probó con salidas controladas; falta un AAB real alterado y la decisión de verificación estricta de certificados.
- Falta probar concurrencia real entre conexiones y carga sobre PostgreSQL, además de los recorridos móviles/web restantes de la auditoría. Los 16 casos omitidos del conjunto completo siguen requiriendo sus entornos o servicios específicos.
- La matriz global de revisión no se regenera desde esta materialización parcial: el generador inventaría solamente los archivos presentes y eliminaría entradas no descargadas. No se declara lectura completa de los grandes archivos modificados. Debe actualizarse en un checkout íntegro al integrar el conjunto de correcciones.
- Las otras correcciones Android/web se trabajan por separado. Este documento no cierra la auditoría completa ni certifica cumplimiento normativo.

## Reversión

Antes de una futura integración o despliegue, conservar la evidencia y el respaldo correspondiente. Este bloque no cambia esquemas ni datos productivos. La reversión de código consiste en revertir su commit en la rama de trabajo, no en sobrescribir las ramas estables ni ejecutar operaciones sobre producción.
