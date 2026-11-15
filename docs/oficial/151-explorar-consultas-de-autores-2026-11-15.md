# 151 · Consultas de autores en Explorar

El feed web de invitado consultaba el perfil una vez por tarjeta. Un autor con varias publicaciones generaba varias solicitudes incluso cuando las tarjetas estaban montadas al mismo tiempo.

La vista comparte una sola consulta por autor entre sus tarjetas y conserva el resultado al mostrar más contenido. Al volver a cargar la vista o salir de ella se cancelan las lecturas anteriores. Las tarjetas siguen usando el endpoint público de perfiles y su regla de visibilidad para el avatar.

## Verificación

- En Chromium de 390 px, con las mismas diez tarjetas de prueba y seis autores, la versión anterior envió diez consultas de perfil y la candidata seis, **40 % menos consultas**. Las dos mostraron las diez tarjetas con el nombre de autor y no registraron errores de JavaScript. Esta prueba mide solicitudes, no tiempo de renderizado.
- El recorrido con el catálogo real emitió diez consultas para seis autores en la versión inicial. El build candidato emitió cinco para cinco autores en otra muestra. El orden del feed puede cambiar entre aperturas.
- La regresión focalizada falló inicialmente porque dos tarjetas pidieron el mismo perfil dos veces. Tras la corrección, las dos mostraron el resultado compartido y abandonar Explorar canceló el ámbito de las consultas. Aprobaron 42 pruebas en cinco suites y el build de producción con 21 páginas prerenderizadas.
- Se repitió la comprobación en producción de los enlaces de la revisión 150. Un video y una foto reales abrieron y decodificaron sin sesión y sin errores de JavaScript en Chromium.

La revisión móvil queda abierta. La evidencia del Moto g04 en 145 valida el primer cuadro de video, transporte de prefijos y avatares en laboratorio. La reproducción sostenida y los cuadros perdidos al desplazarse requieren una medición propia del feed.
