# Acceso web en pantallas cortas

28 de noviembre de 2026. En WebKit, a 320 × 568, el botón «Iniciar sesión» quedaba cortado al final de la primera vista y el enlace de recuperación terminaba a los 604 px. El formulario concentra el encabezado y reduce la separación vertical en pantallas de hasta 480 px de ancho y 660 px de alto.

El ensayo a 320 × 568 situó el final del acceso en 505 px y el de recuperación en 532 px. El botón mantuvo 50 px de alto y el campo de contraseña 52 px. Se comprobaron cuatro tamaños, el enlace de recuperación con destino de regreso y la ausencia de desplazamiento horizontal. La regla de espaciado afecta solo a la página de acceso en el rango indicado.

El candidato pasó la compilación, ESLint y 188 pruebas de Node. Pasaron también 19 casos de acceso, QR y recuperación. Las 1.362 pruebas Jest del árbol estable anterior pasaron antes de aplicar el ajuste visual.

Chromium abrió 13 pantallas con datos simulados en cinco anchos entre 320 y 1440 px. Comprobó los controles táctiles del chat, el bloqueo del compositor ante una solicitud pendiente, el mensaje de sala no disponible, la mesa favorita, el perfil y la navegación de Ajustes entre Privacidad, Seguridad y Acceso y QR. El despliegue queda pendiente.
