# Novvin: presentación y cartas de cumpleaños

Entrega del 17 de octubre de 2026: Android 9.0.39 (136) instalado en el Motorola moto g04; web 9.0.47 publicada en [Novvin Web](https://red-social-android.vercel.app).

## Cambios

- Presentación organiza biografía, estudios, profesión, trabajo, ubicación, intereses y redes. El cumpleaños aparece al final, como día y mes.
- El cumpleaños es visible por defecto. «Mostrar mi cumpleaños» permite ocultarlo; la API elimina la fecha de los perfiles ajenos y el trabajador de cumpleaños excluye los avisos ocultos. Las restricciones de seguidores se conservan.
- «Felicítalo ahora» abre el chat con una carta editable y vista previa. No envía nada automáticamente. Hay tres estilos y un máximo de 600 caracteres Unicode.
- El borrador de la carta queda separado del texto normal y de otras cuentas. Las cartas pasan por la cola, los permisos, reintentos, recibos y borrado existentes.
- La web puede crear y abrir la misma carta. La API valida el formato y restringe este tipo al chat directo, sin adjuntos ni encuestas.
- El acceso web muestra una indicación específica cuando el navegador deniega el permiso de red local para el servidor Tailscale.

## Referencias de diseño

Se consultaron las descripciones oficiales de Meta sobre [presentación y control de campos del perfil](https://about.fb.com/news/2015/09/improving-mobile-profiles/amp/) y [tarjetas desde el aviso de cumpleaños](https://about.fb.com/news/2019/05/birthday-stories/amp/). La carta de Novvin se entrega en el chat y tiene implementación propia.

## Verificación completada

- Android: 1.682 pruebas, 352 suites, cero fallos, errores u omisiones; compilación release y firma verificadas.
- Emulador con el APK final: presentación con estudios, profesión, trabajo y cumpleaños; fecha oculta para otra cuenta al desactivarla; entrada desde perfil y notificación; borrador persistente de la carta; confirmación para una primera conversación; envío explícito y entrega estructurada al destinatario; apertura de la carta. Las dos cuentas temporales fueron eliminadas.
- Motorola: actualización de 9.0.37 (134) a 9.0.39 (136) mediante instalación con reemplazo, sin desinstalación ni borrado. Certificado coincidente, SHA256 instalado idéntico al APK probado, UID, fecha original de instalación e inode de datos conservados. Inicio de la aplicación confirmado por ADB.
- Web: 554 pruebas, 88 suites, compilación correcta; lint sin errores y con ocho advertencias previas. Prueba con dos cuentas reales temporales sobre la versión pública: inicio de sesión, presentación ajena, enlace de cumpleaños, carta recibida y abierta y borrador normal conservado. Ambas cuentas eliminadas. Comprobación adicional de la carta en 390×844, 844×390 y 1440×960 sin desbordamientos.
- Servidor: imagen final verificada en Linux con 835 pruebas aprobadas y siete omitidas por su contexto, más las integraciones con PostgreSQL de preguntas y sorteos. API pública: privacidad para todos, solo el propietario y seguidores; reintentos sin duplicar cartas; rechazo de formato inválido y de envíos a grupos/comunidades. Ambas cuentas de prueba eliminadas.
- Publicación: Vercel confirmó Ready y el alias público. Los 48 controles HTTP de páginas públicas, metadatos, CSP, recursos y exclusión de rutas privadas pasaron. Auditoría de dependencias web de ejecución: cero vulnerabilidades reportadas.

## Identidad de la entrega

- APK: `artifacts/releases/Novvin-9.0.39-136-final/Novvin-9.0.39-136.apk`.
- SHA256: `475c67ab4537acdb88a91a92711342d3ff5e53160440595c4b067ffd79c71ad9`.
- Fuentes compiladas: `9b958a3c9960570704b607bb2ac4ba9e06972d8c`.
- Despliegue web: `dpl_B6oMuDe9bCRgHgGQfDPtjQgcQBrk`.
- API: `novvin-api-9.0.39-183bf2a164b5`; imagen `sha256:c7b9a66a53cdddccd895cd18fea4b2b233d04520d25ed2f420f137728e11abca`.

## Registros

Los recibos están en `audit/2026-10-07-integral`: `android-9039-sealed.json`, `android-9039-birthday-e2e.json`, `motorola-9039-install-latest.json`, `birthday-web-live-final.json`, `birthday-web-final-responsive.json`, `birthday-web-final-seo.json`, `birthday-server-e2e.json` y `birthday-profile-projection-release.json`.

Estas comprobaciones acreditan esta entrega y los recorridos descritos. No equivalen a una certificación global de todas las funciones ni garantizan posiciones o plazos de indexación en buscadores. La versión pública de Google Play no se modificó.
