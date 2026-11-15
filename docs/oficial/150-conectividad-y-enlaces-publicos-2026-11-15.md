# Conectividad y enlaces públicos de Novvin

La ruta web oficial puede emitir la sesión de invitado mientras la inicialización comprueba la disponibilidad del servidor. Los orígenes que dependen de descubrimiento esperan el resultado antes de emitir la sesión. La recuperación de la pasarela permite un reintento cuando el descubrimiento de confianza cambia el origen y la identidad de invitado sigue vigente.

Los enlaces canónicos de video y publicación abren la vista de invitado cuando el visitante no tiene cuenta iniciada. El servidor autoriza el contenido individual con el mismo criterio de visibilidad pública del catálogo. Una respuesta 404 muestra «Contenido no disponible». Los enlaces para acceder o crear cuenta conservan el destino original con sus parámetros y fragmento. Los enlaces de perfil conservan su vista pública existente.

## Mediciones

| Comparación | Mediana anterior | Mediana alternativa | Muestra |
| --- | ---: | ---: | --- |
| Arranque HTTP de invitado en el dominio oficial | 771 ms | 384 ms | Cinco pares, después de calentamiento, alternando el orden |
| Primer byte de salud desde Windows | 628 ms | 535 ms | Siete pares, conexiones nuevas, HTTP 200 |

La primera comparación ejecutó `GET /ready` seguido de `POST /v1/guest/session` frente al mismo POST directo. El cliente HTTP reutilizó conexiones y validó el rol de invitado. Los tiempos en ms, como pares secuencial/directo, fueron 1414/373, 802/385, 771/386, 771/384 y 752/368. El 50,2 % corresponde a esta secuencia HTTP aislada. El parche permite la ejecución en paralelo. Sigue pendiente la medición de extremo a extremo tras publicación.

La segunda comparación usó la ruta web pública frente a un túnel temporal de Cloudflare con un servicio que comprueba el `/ready` de Ubuntu y responde con un cuerpo fijo. El 14,7 % observado es una comparación del primer byte del piloto. El proceso de prueba terminó al expirar su temporizador. El subdominio `api.novvinapp.com` carecía de resolución pública y el dominio usaba DNS de GoDaddy. El túnel nombrado requiere acceso administrativo a Cloudflare y al DNS del dominio.

En Ubuntu, cuatro solicitudes a su puerto local rondaron 5–6 ms. Desde Windows, cuatro pares ordenados a la IP pública del Funnel, fijada con DNS público para evitar la respuesta privada de MagicDNS, y al dominio web dieron medianas de primer byte de 895 y 949 ms respectivamente. Las ocho respuestas fueron HTTP 200. Una tanda adicional midió 78 ms vía tailnet privada. Se observó variación entre tandas y queda pendiente una comparación simultánea y representativa de un túnel permanente con tráfico real de API.

## Alcance comprobado

- En `novvinapp.com` y `www.novvinapp.com`, un video público, una publicación pública con imagen y un perfil público devolvieron HTML con Open Graph e imagen JPEG HTTP 200. Las URLs canónicas usaron el dominio sin `www`. Se probó el protocolo de la tarjeta, con agente de Facebook, fuera de una sesión personal.
- Los dos hosts sirvieron `/.well-known/assetlinks.json` para `conectate.com`. El manifiesto Android declara ambos hosts con verificación. La huella publicada corresponde al firmante del AAB local registrado en la revisión 119. La coincidencia con el certificado de distribución de Google Play y la apertura en un Android instalado siguen pendientes.
- Ubuntu informó 11 306 MiB de memoria disponible y ninguna unidad fallida. La API activa se reportó saludable, sin reinicios ni OOM en la lectura coordinada. Este control puntual no constituye una prueba de capacidad bajo carga.
- Aprobaron 1 274 pruebas web en 200 suites y la compilación de producción con 21 páginas prerenderizadas. En Chromium de 390 px con el build de producción y respuestas controladas se comprobaron enlace de video, enlace de foto y publicación no disponible. Los tres enviaron el POST antes de que terminara `/ready`, y conservaron el destino de retorno. Este ensayo cubre el flujo de navegación y los elementos multimedia del DOM, con las respuestas de red simuladas.
- El cambio de código es web. Sigue abierta la medición móvil con build de QA en curso y sin equipo conectado a ADB al revisar.
