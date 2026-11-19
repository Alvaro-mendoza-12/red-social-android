# Privacidad de las secciones del perfil en Ubuntu

18 de noviembre de 2026. El lector de perfiles admitía «Solo yo» (`only_me`) pero convertía la variante heredada «Nadie» (`nobody`) en «Todos». En el camino habitual de consulta, esto podía devolver la fecha de nacimiento y otras secciones que el perfil había marcado como privadas. La edición también convertía ese valor en «Todos» al normalizarlo para guardar.

La normalización de lectura y de escritura trata `nobody` como `only_me`. El propietario conserva el acceso a sus propios datos. Se conserva el significado de `connections`, `followers` y `everyone`. No se necesita volver a guardar los perfiles existentes. El trabajador de cumpleaños ya excluye registros cuya visibilidad es `nobody` o `only_me`.

Dos casos de referencia fallaron sobre las funciones de producción con perfiles ficticios. Después del cambio aprobaron los quince casos Node de las tres suites de cumpleaños, privacidad de sus avisos y registro de visitas a perfiles. Las pruebas distinguen propietario, visitante y seguidor. Los logs y huellas están en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit196-profile-visibility/`.

La corrección requiere despliegue en Ubuntu. Sigue pendiente separar el día y mes público de cumpleaños del año de nacimiento. La respuesta versionada de perfil contiene la fecha completa cuando la sección se comparte y los consumidores Android instalados esperan un valor ISO completo para mostrar el día y mes. Se registró la necesidad de migrar esa respuesta y sus consumidores sin escribir una fecha artificial en el perfil privado.
