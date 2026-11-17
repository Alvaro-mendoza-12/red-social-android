# Errores con personalidad en NOVVIN

La galería está en Configuración → Centro de ayuda en Android y en `/ayuda/errores` en la web. Las tarjetas permiten explorar seis personajes y doce códigos. Cada personaje conserva el rostro, la ropa y los objetos de la imagen facilitada por el propietario. Los retratos son seis recortes WebP individuales de la referencia corregida. Pesan aproximadamente 50 kB en cada plataforma y el encabezado usa el recurso oficial del proyecto.

| Personaje | Códigos | Movimiento |
| --- | --- | --- |
| La curiosa | 400, 409, 422 | Inclina la cabeza |
| La guardiana | 401 | Se incorpora |
| La protectora | 403, 429 | Se desplaza como gesto de bloqueo |
| La detective | 404 | Se inclina y se eleva con su lupa |
| El técnico | 500, 502 | Se sacude |
| El dormilón | 408, 503, 504 | Respira lentamente |

Los avisos de datos o permisos, los problemas de conexión y los fallos críticos tienen textos distintos. Un error sin respuesta HTTP puede mostrar el personaje de conexión o de fallo crítico sin inventar un código. La voz lee el número en palabras, el título y la descripción disponibles para el usuario. Empieza por acción explícita y se detiene al pulsar «Detener», cambiar de personaje, salir o mandar la pantalla al fondo. Android usa la síntesis del dispositivo y su escala de animación. La web usa SpeechSynthesis y desactiva las animaciones con movimiento reducido. La descripción queda disponible cuando falta una voz compatible. [TextToSpeech de Android](https://developer.android.com/reference/android/speech/tts/TextToSpeech), [SpeechSynthesis](https://developer.mozilla.org/es/docs/Web/API/SpeechSynthesis)

## Integración

- Android conserva el estado HTTP en el cliente y el detalle de Videos. El reproductor conserva la acción para volver. El estado global sin conexión conserva Reintentar y Continuar en Mensajes. El traductor común consume el catálogo en fallos HTTP tipados y mantiene mensajes específicos de acceso, moderación y almacenamiento.
- Web usa el catálogo desde el traductor común, las barreras de error de la aplicación y de las rutas, la exploración pública y los detalles de publicaciones y videos. El contenido ya cargado conserva su vista y muestra un aviso. El 404 HTTP conserva su condición de página no indexada y sus enlaces en HTML. Una entrada web dedicada activa solo los controles interactivos al cargar esa página.
- Los avisos locales que ya tienen acciones específicas conservan sus controles. El catálogo presenta únicamente texto fijo de NOVVIN y no lee cuerpos HTTP, identificadores privados, direcciones internas ni trazas.

## Validación

- Android generó los recursos Debug y compiló quince fuentes del cambio con Kotlin, Compose y las dependencias del proyecto. Pasaron 17 pruebas JVM de códigos, mensaje seguro y detalle de Videos. Aprobaron dos pruebas instrumentadas en un emulador API 36 de 1080 × 2220 píxeles y 440 dpi. El laboratorio compiló los componentes y recursos reales en un paquete aislado. La galería mostró los doce códigos y conservó Atrás. El estado sin conexión ejecutó sus dos acciones. El empaquetado completo de Android quedó pendiente por memoria.
- Web aprobó compilación, ESLint focalizado, 37 pruebas Jest y 72 controles de HTML, HTTP y SEO. Chrome comprobó los doce códigos, los seis movimientos, la conexión de los botones a una voz simulada en español, las rutas de recuperación y el 404 a 320, 390, 768 y 1366 píxeles. Los doce textos se sintetizaron además con una voz española instalada en Windows. Esta prueba de escritorio no acredita la pronunciación con el motor de un teléfono.
- Los registros y capturas se guardan en `D:/Novvin-Entregas/evidencias/2026-11-17-errores/`. La aceptación en teléfono y la promoción a producción permanecen pendientes.
