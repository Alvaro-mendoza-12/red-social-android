# Editor móvil de Momentos: música, letras y duración

Fecha: 4 de octubre de 2026. Rama: `feature/live-gifts`.

## Interacción del producto

La foto o el video se muestran en un lienzo separado de los controles superiores y del comentario. El editor mide el espacio que ocupan esos controles; el teclado y un comentario de varias líneas reducen el lienzo disponible. La música permanece dentro de la zona visible de la imagen o el video.

Al añadir música, la tarjeta aparece en el centro del contenido. Se puede arrastrar con un dedo, cambiar su tamaño pellizcando y girar con dos dedos. Las acciones de accesibilidad permiten mover, centrar, cambiar el tamaño, girar y restablecer la presentación. La opción «Quitar música» está en el menú del selector.

El diseño se mide a un tamaño de referencia y se escala uniformemente según el contenido. La posición se guarda como coordenadas normalizadas de la foto o video. El tamaño y el giro tienen campos propios, independientes del texto añadido al momento. El visor y los destacados usan el mismo componente para mostrar esa colocación.

## Presentaciones

El selector muestra hasta tres opciones visibles:

- **Tarjeta:** portada, título y artista.
- **Etiqueta:** portada pequeña, título y artista en una fila compacta.
- **Letras:** disponible cuando la pista incluye letras con tiempos reales. Si no las incluye, esta tercera opción es **Solo música**.

«Solo música, sin tarjeta ni letras» también está en el menú. Conserva el audio y deja libre el contenido.

Las letras muestran la línea anterior y la siguiente con menor énfasis y la actual en blanco y negrita. El reloj procede del reproductor de audio, o del video cuando la música está incrustada. Pausas, saltos y repeticiones usan la posición real de reproducción. Las marcas LRC indican los cambios de línea; no se calculan tiempos a partir de párrafos ni se inventa texto.

Si el fragmento elegido no contiene una línea cantada, «Usar audio» queda deshabilitado para la presentación Letras. El selector explica el motivo y ofrece «Ir al inicio de la letra». Se puede elegir otra parte o cambiar de presentación.

El servidor obtiene la letra de la pista aprobada de Comunidad. Cuando recorta el audio para publicar, desplaza los tiempos de la letra al inicio del recorte y conserva los cambios reales, incluidos los silencios. No permite sustituir la letra del catálogo con texto enviado por otro usuario.

## Añadir letras a música propia

Al subir una canción a Comunidad se puede elegir «Añadir letras (.lrc) · opcional». El selector nativo muestra archivos LRC de las ubicaciones a las que el usuario dio acceso. Una vista previa muestra el número de líneas sincronizadas y el primer texto, hasta dos líneas; «Quitar letra» permite retirarlas antes de publicar. La canción también puede publicarse sin letras.

La importación admite UTF-8 válido y tiene límites de bytes y caracteres. Un archivo demasiado grande, con codificación incorrecta o sin marcas de tiempo utilizables muestra un error. No se generan tiempos para texto sin sincronización. Si se recorta el audio, las letras se ajustan al inicio exacto de ese recorte; el intervalo seleccionado se captura antes de empezar la exportación para evitar que cambie durante el proceso.

## Dos duraciones diferentes

**Tiempo visible del momento.** «Visible por … h» abre un diálogo con 6, 12 y 24 horas y botones para ajustar entre 5 y 24 horas. «Guardar» aplica la elección; «Cancelar» conserva el valor previo. El creador puede eliminar el momento antes de su vencimiento.

**Fragmento musical.** Una línea de tiempo ampliada muestra marcas de segundos, el recuadro del fragmento y la posición real de reproducción. Se arrastra la canción bajo el recuadro para elegir el inicio. Los botones −1 s, +1 s y «Al inicio» permiten un ajuste preciso. Se muestran inicio, fin y duración; no se dibuja una forma de onda inventada.

La barra mide **48 dp de alto**, frente a los 88 dp anteriores: una reducción aproximada del 45 %. Las marcas y el recuadro se reducen proporcionalmente y quedan dentro de los bordes redondeados. La superficie para deslizar conserva esos 48 dp. La revisión incluye tanto su medida como una captura del teléfono; comprobar gestos por sí solo no valida la proporción visual.

Las fotos conservan los seis segundos de reproducción definidos por el servidor. Los fragmentos de videos respetan su duración y el límite actual de 25 segundos del editor. Cambiar las horas de visibilidad no amplía el audio.

## Validación y estado

Producto Android verificado: `fed15463ecf493e90cfdc18014c7bb3c8f83d486`. Incluye el editor, la importación LRC de `403fc0d` y la barra compacta de `fed1546`. Los cambios posteriores de Sorteos de otro chat están fuera de este APK y de estos resultados.

La compilación completa terminó en **21 min 49 s**: aplicación debug, APK de instrumentación, pruebas unitarias y R8 release. Pasaron **1.294 pruebas unitarias en 282 suites**, sin fallos, errores ni omisiones. Se verificaron el identificador `fed1546` en DEX y los seis métodos conservados de SurveysBridge. Se excluyó la comprobación de configuración de firma para evaluar R8; esta tanda no creó un AAB firmado.

El APK se instaló en el **Moto g04, Android 14/API 34, 720 × 1612, densidad 280 dpi**, mediante reemplazo compatible. Se conservó la identidad del directorio de datos; no se desinstaló ni se borraron los datos. El SHA-256 del APK instalado coincide con el construido: `0baba93b840aa4f485bf26615d4416e2f34c0a0d92f334c9a977ac529d26be76`.

La tanda física final reúne **78 casos únicos aprobados**, sin omisiones:

| Grupo | Casos | Tiempo | Alcance |
| --- | ---: | ---: | --- |
| Interfaz y encuestas | 52 | 97,237 s | Social, avisos, editor, gestos, letras y regresión móvil; encuestas usan HTTPS y PostgreSQL locales desechables. |
| Medios remotos propios | 5 | 20,903 s | Foto y audio sintéticos propios; Gradle inactivo. |
| Llamadas locales y disposición de LIVE | 18 | 78,238 s | Invitaciones locales y estado RTC simulado, sin llamada con otra persona ni FCM. Tanda final sin depurador. |
| Rechazo de llamada | 3 | 11,092 s | Invitaciones propias y cuenta ausente, sin cambiar la sesión humana. |

Los 52 casos de interfaz se ejecutaron mientras R8 trabajaba en el PC; son comprobaciones funcionales, no una medición de latencia. La prueba musical comprueba la altura exacta de 48 dp y mantiene esa área táctil. Ambas IA revisaron capturas del teléfono, incluidas letras giradas y una etiqueta con texto a escala 1,5. Las pruebas de importación cubren su componente nativo, pero no la selección de archivos del sistema ni una subida autenticada a producción.

### Incidencia de llamadas conservada

Dos tandas anteriores de 18 casos tuvieron **17 aprobados y un fallo** en `theRingTimeoutAlsoClosesTheAnswerScreen`, con «Timeout did not mark the expired invitation»: 38,253 s y 59,715 s de tanda completa. Se conservaron ambos resultados. No se cambiaron el producto, la prueba, su límite de 55 segundos ni el temporizador de 45 segundos para obtener las repeticiones.

El mismo caso aislado pasó en 46,951 s. Una tanda diagnóstica con JDWP pasó 18 casos en 70,459 s: la pila mostró `closeIfCancelled` desde `observeCancellation`, y el cierre ocurrió 45,679 s después de crear la pantalla del caso. Esa tanda con depurador no se incluye en los 78. La tanda normal posterior pasó los 18 en 78,238 s.

**La causa de los dos cierres anticipados todavía no está demostrada.** La repetición aprobada no acredita una corrección de ese problema ni permite certificar globalmente las llamadas. La consulta sobre posible interacción con el teléfono sigue pendiente; no se asume una respuesta.

### API y entrega

- API del checkpoint `e6de71c`, cuyos archivos musicales mantienen los mismos hashes en `fed1546`: 450 casos, 448 aprobados y dos omitidos por requerir bash en Linux; cero fallos. No se presentó esa suite anterior como una ejecución nueva de 450.
- Focal de API repetido sobre `fed1546`: 18 aprobados, sin fallos ni omisiones.
- Contrato de Momentos con PostgreSQL/HTTP: seis comprobaciones aprobadas de migración, creación, lectura, reemplazo y eliminación. La prueba aísla autenticación, almacenamiento y transcodificación.
- El candidato de servidor quedó preparado con 183 archivos iguales byte por byte a Git. **No está desplegado**: sigue pendiente la autenticación de Tailscale. El último servidor verificado conserva `f42a349`.
- Los tiempos observados hasta el control Pausar fueron 5.270 ms para audio de perfil y 3.445 ms para Comunidad; imagen lista en 158 ms. No representan primer sonido percibido ni un SLA, y no acreditan una mejora de velocidad por este cambio visual.

La revisión visual detectó además bajo contraste en la etiqueta fija «6 s». La duración también aparece de forma legible junto al intervalo; esa observación secundaria continúa pendiente y no se ocultó al revisar la captura.

Evidencia: [Android y teléfono](../../VALIDACION_EDITOR_MOMENTOS_MOTO_FED1546.json), [API original](../../VALIDACION_MOMENTOS_MUSICA_API_E6DE71C.json), [continuidad y candidato de API](../../VALIDACION_CONTINUIDAD_MOMENTOS_API_FED1546.json).

Capturas del teléfono con contenido propio de prueba: [barra compacta](capturas/moto-g04-fed1546/qa-music-compact-timeline-fed1546.png), [letras](capturas/moto-g04-fed1546/qa-moment-editor-lyrics-fed1546.png), [etiqueta y texto grande](capturas/moto-g04-fed1546/qa-moment-editor-label-fed1546.png).

Estos resultados cubren un teléfono físico y los escenarios descritos. La publicación del AAB firmado sigue realizándose con la llave original en Android Studio; la entrega completa a producción requiere cerrar los puntos pendientes indicados.
