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

El despliegue y la repetición desde Android se documentarán con su resultado
medido; las pruebas locales no equivalen a validación del servidor desplegado.
