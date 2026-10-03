# 59 — Registro de visitas con sesión válida

Fecha: 03/10/2026. Backend Ubuntu; revisión posterior a `c204ad9`.

## Fallo reproducido

El recorrido Android entre dos cuentas propias de QA mostraba el perfil, pero no
creaba la visita. Una petición autenticada al mismo endpoint devolvió HTTP 401
`AUTH_REQUIRED`. La consulta interna de perfiles recibía una identidad resuelta
(`localUserId`, `ids`) donde esperaba un sujeto autenticado (`sub`).

## Corrección

La consulta usa el sujeto local de la identidad ya autenticada. Se mantienen la
validación del visitante, el rechazo de visitas al propio perfil y la preferencia
explícita de privacidad de ambas cuentas. La preferencia ausente sigue activada
por defecto. No se aceptan identificadores de visitante ajenos a la sesión.

## Pruebas locales

Ocho pruebas Node aprobadas: cinco regresiones del registro y tres de valores
por defecto/privacidad. Incluyen identidad pública y local, visitante falsificado,
perfil propio y desactivación explícita de cada participante.

## Validación operativa

API desplegada con revisión `e5e3ce1`. Se conservaron la imagen anterior y las
fuentes en `/home/alvaro/conectate-source-backups/visits-20261003T122025Z`, usando
el respaldo verificado descrito en la revisión 58. No hubo migración del esquema.
Los nueve servicios quedaron saludables y 100/100 fuentes JavaScript coincidieron
entre repositorio, host y contenedor. Se actualizó el marcador de release.

La misma petición pasó de HTTP 401 a 201. Después de reiniciar únicamente el
proceso de la app y abrir el perfil de QA desde la otra cuenta, una lectura
autenticada del propietario confirmó una visita, sin duplicar los recorridos
de caché y red. Ambas cuentas tenían activada la preferencia de visitas.

La prueba utilizó cuentas propias de auditoría; no modificó preferencias de
personas reales. Las pruebas de redes móviles y dispositivos físicos siguen
fuera de esta ronda.
