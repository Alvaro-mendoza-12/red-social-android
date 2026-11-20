# 215 · Cumpleaños sin año en los perfiles web

Las vistas de perfil propio y público usan el mismo formateador. Este ya mostraba el día y el mes de una fecha completa. La referencia devolvía una etiqueta vacía al recibir `--10-17`. Ahora se muestra «17 de octubre» y se admite el 29 de febrero con el formato sin año acordado en [Android 207](207-privacidad-y-formato-de-cumpleanos-2026-11-19.md). Las fechas de calendario imposibles siguen produciendo una etiqueta vacía. El formateador trabaja únicamente en memoria al presentar la página.

La prueba nueva falló con la implementación anterior y pasó al cubrir ambos formatos. Durante la batería completa falló además la restauración de una lectura en la bandeja. Su prueba dependía de una fecha fija de octubre que había salido de los 30 días de retención. Se conservó la retención y la prueba ahora crea el mensaje anterior y el posterior respecto del momento de la lectura simulada.

Tras ambos cambios pasaron los 1.341 casos Jest en 208 suites. La compilación verificó las 22 páginas públicas, las rutas canónicas y el sitemap. Logs de referencia y finales en `D:/Novvin-Entregas/evidencias/2026-11-19-audit214/`. El hallazgo N019 sigue abierto en el servidor y en los clientes Android instalados que requieren una fecha completa.
