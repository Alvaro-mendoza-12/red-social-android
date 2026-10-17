# Novvin 9.0.40: privacidad del chat y mejoras de Social

Fecha: 17 de octubre de 2026. Web 9.0.48 publicada en https://red-social-android.vercel.app.

## Conversaciones

Vaciar es una acción personal: no borra mensajes compartidos del servidor ni el historial de la otra cuenta. La ruta comprueba pertenencia al chat. Las rutas de compatibilidad impiden modificar las preferencias o la fecha de vaciado de otra persona y eliminar una fila de conversación compartida. Un chat vacío conserva su fila; ocultarlo es una acción distinta. Solo actividad posterior puede devolver un chat oculto a la lista. La lectura respeta las preferencias locales actuales sobre las filas importadas.

En Android, vaciar requiere confirmación del servidor y una sesión vigente. Vaciar y ocultar conservan las filas locales de mensajes compartidas por cuentas vinculadas. La actualización de la bandeja aplica el corte devuelto para la cuenta actual.

Prueba real con dos cuentas temporales: historial de A vacío e historial de B intacto; persistencia tras iniciar sesión de nuevo; mutaciones contra B rechazadas o sin efecto; ocultación solo para A y reaparición al recibir un mensaje nuevo. Ambas cuentas fueron eliminadas. No se ha restablecido masivamente ninguna fecha de vaciado ni se afirma haber recuperado la conversación histórica que comunicó el usuario.

## Publicidad y presentación

- Gestor separado del anuncio ocultable, con tres pasos: contenido, tarjetas y revisión. Edición de una tarjeta a la vez, destinos reconocibles, vista previa, publicación explícita y progreso de subida.
- El anuncio aparece después de cuatro publicaciones orgánicas. Un feed corto puede no mostrar publicidad. El acceso a gestionar permanece disponible desde el menú.
- Abrir mensajes desde un anuncio cambia directamente la pestaña del navegador activo de Inicio; se verificó que abre la bandeja sin cerrar la aplicación. Editar presentación tiene su destino registrado en el navegador principal.
- Presentación contiene únicamente información del usuario. Videos, publicaciones, compartidos y favoritos permanecen en Inicio. Se actualizan los datos al entrar en Presentación; si falta la fecha propia, se ofrece añadirla.
- Confeti decorativo de duración limitada al abrir una carta, sin capturar toques. La web respeta la preferencia de reducir movimiento.

## Carpetas

Una carpeta de más de 5.000 entradas se divide automáticamente en ZIPs independientes, también por tamaño, manteniendo las rutas. Se informa de lectura y compresión. Los archivos ZIP ya preparados se adjuntan directamente; se normaliza su MIME de Windows sin cambiar los bytes. Continúa el límite real de 500 MB por archivo del servidor. Las carpetas tienen una cota de 100.000 entradas y 32 niveles; un archivo individual mayor de 500 MB requiere preparación externa, no se presenta como un envío ilimitado.

Se comprobó en la web pública una selección de 5.001 archivos: dos ZIPs entregados y descargados por la otra cuenta. Las pruebas de integridad comparan también los nombres internos y las rutas. Ambas cuentas temporales fueron eliminadas.

## Criterios de diseño

Se consultaron las [heurísticas de Nielsen Norman Group](https://www.nngroup.com/articles/ten-usability-heuristics/) y la [documentación oficial de creación de campañas de TikTok](https://ads.tiktok.com/help/article/campaign-set-up). TikTok organiza objetivos y configuración antes de entregar; aquí se adaptó esa separación a publicidad interna de Novvin. Las capturas aportadas por el usuario guiaron el carrusel. La página de Meta redirigió a acceso restringido y no se usó para afirmar una equivalencia con su gestor.

| Heurística | Aplicación en el gestor |
| --- | --- |
| Estado visible | Paso actual y progreso de subida. |
| Lenguaje familiar | Contenido, tarjetas, revisar y publicar. |
| Control | Atrás, cerrar, editar y pausar. |
| Consistencia | Botones y destinos iguales en cada tarjeta. |
| Prevención | Campos obligatorios y revisión antes de publicar. |
| Reconocimiento | Destinos seleccionables y vista previa. |
| Eficiencia | Una imagen o carrusel, una tarjeta a la vez. |
| Minimalismo | Tres pantallas breves en lugar de todos los campos juntos. |
| Recuperación | Un fallo de subida conserva los cambios y permite reintentar. |
| Ayuda | Indicaciones junto a imagen, enlace y texto opcional. |

Es una aplicación razonada de criterios de diseño, no una certificación de usabilidad con participantes externos.

## Evidencia

Los recibos en audit/2026-10-07-integral incluyen `polish-9040-chat-clear-e2e.json`, `polish-9040-web-live.json`, `polish-9040-web-ui.json`, `polish-9040-api-release.json` y las verificaciones finales de Android. La instalación física se acredita únicamente con el recibo ADB correspondiente.

## Resultado final

- Android 9.0.40 (137): 1.682 pruebas unitarias aprobadas y recorridos reales en emulador sobre el mismo SHA256 del APK entregado. Presentación → Editar, Publicidad → Mensajes y carta de cumpleaños verificados; cuentas temporales eliminadas.
- Web 9.0.48: 556 pruebas aprobadas; ESLint sin errores, con ocho advertencias previas; auditoría de dependencias de ejecución sin vulnerabilidades reportadas. Vista adaptable del gestor comprobada en 390×844, 844×390 y 1440×960.
- API Linux: 840 pruebas aprobadas, siete omitidas por condiciones explícitas de la suite; integraciones PostgreSQL de preguntas y sorteos aprobadas por separado. La imagen probada coincide con la desplegada.
- SEO técnico: 48 comprobaciones HTTP aprobadas. Esto no garantiza un puesto en los buscadores.
- Motorola: APK probado y firmado disponible, instalación pendiente porque el teléfono dejó de enumerarse por USB. No se desinstaló ni borró la app.
