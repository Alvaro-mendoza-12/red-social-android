# Novvin: presentación y cartas de cumpleaños

Versiones preparadas: Android 9.0.39 (136), web 9.0.46.

## Cambios

- Presentación organiza biografía, estudios, profesión, trabajo, ubicación, intereses y redes. El cumpleaños aparece al final, como día y mes.
- El cumpleaños es visible por defecto. «Mostrar mi cumpleaños» permite ocultarlo; la API elimina la fecha de los perfiles ajenos y el trabajador de cumpleaños excluye los avisos ocultos. Las restricciones de seguidores se conservan.
- «Felicítalo ahora» abre el chat con una carta editable y vista previa. No envía nada automáticamente. Hay tres estilos y un máximo de 600 caracteres Unicode.
- El borrador de la carta queda separado del texto normal y de otras cuentas. Las cartas pasan por la cola, los permisos, reintentos, recibos y borrado existentes.
- La web puede crear y abrir la misma carta. La API valida el formato y restringe este tipo al chat directo, sin adjuntos ni encuestas.
- El acceso web muestra una indicación específica cuando el navegador deniega el permiso de red local para el servidor Tailscale.

## Referencias de diseño

Se consultaron las descripciones oficiales de Meta sobre [presentación y control de campos del perfil](https://about.fb.com/news/2015/09/improving-mobile-profiles/amp/) y [tarjetas desde el aviso de cumpleaños](https://about.fb.com/news/2019/05/birthday-stories/amp/). La carta de Novvin se entrega en el chat y tiene implementación propia.

## Verificación

La web pasó 554 pruebas y la comprobación de escritorio, vertical y horizontal con API simulada: apertura, borrador independiente y envío explícito de una carta estructurada. La verificación del servidor y del APK se registra por separado en audit/2026-10-07-integral. Este documento no representa una certificación global ni confirma una instalación física sin su recibo de ADB.
