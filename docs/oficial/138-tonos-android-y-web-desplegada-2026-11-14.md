# 138 — Integración de tonos Android y web desplegada

14 de noviembre de 2026. Continúa [137](137-seguidores-social-asistente-y-menu-web-2026-11-14.md). La [evidencia estructurada](../quality/evidence/138-tonos-y-web-desplegada.json) identifica fuentes, informes y despliegue.

## Android: trabajo contrastado entre los dos asistentes

El asistente de escritorio entregó CallTonePlayer.kt, sus pruebas y [el informe acotado](../quality/CALL_TONE_RACE_2026-11-14.md), sin modificar índices ni publicar. Codex revisó completos esos cuatro archivos, comprobó los blobs de producción y pruebas y contrastó los dos XML retenidos con sus huellas: dos fallos reproducidos antes y ocho pruebas aprobadas después. Los ensayos son de laboratorio.

La tarea podía esperar el monitor de audio y reproducir después de cancelar. El parche serializa inicio, parada y sustitución, y comprueba la actividad dentro del bloqueo antes de crear o reproducir el tono. stopAll protege ambas paradas con la misma exclusión. Se mantienen contratos públicos, cadencias y política de silencio/vibración. La lectura confirma la corrección de esa carrera; no atribuye a ella toda ausencia de sonido reportada por el propietario.

Se integraron únicamente los cuatro archivos cedidos y se repitió la selección de CallTonePlayerTest en el worktree de feature, con JDK 21, wrapper del proyecto y Robolectric API 34: **8 pruebas, 0 fallos, errores u omisiones**. El XML y el log de esta integración tienen huellas propias en la evidencia; esta repetición no se suma como dieciséis casos distintos. Se compilaron las clases de producción y pruebas necesarias. No se repitieron toda la batería JVM, Lint, emulador ni empaquetado firmado.

El tono entrante de los ensayos usa un shadow de Robolectric. Siguen pendientes audio físico, volumen, Bluetooth, llamadas reales, notificaciones de segundo plano, cambio de cuenta y el recorrido completo de CallRepository y LIVE. No se entrega una APK nueva. La lectura de consumidores meramente buscados no se marca como revisión completa.

## Web: código efectivo y dominio

Vercel completó el despliegue Git de main **d79c80c891cca7818e055d7f915a523afebbd664**, identificador **dpl_9tZhPhRPj2FFJoCMH4QZvQGF8yhS**, y novvinapp.com resolvió a ese despliegue READY al comprobarlo. No se promovió la antigua candidata CLI. La API integrada sigue siendo la imagen 136 documentada en su revisión; este bloque no modifica el servidor.

Se repitieron sobre el HTML, JavaScript y CSS realmente desplegados los recorridos de bandeja, chat, seguidores, asistente y Social en **1440, 768 y 375 píxeles**. Pasan el menú de seis entradas y su segundo nivel, avatares, búsqueda, respuesta estructurada, carruseles completos y columnas de escritorio. El chat desplaza contenido con la barra oculta. Se mantienen cero errores de consola/página, cero infracciones axe en los recorridos ensayados y ausencia de desbordamiento horizontal o imágenes rotas en esos datos.

Las API, imágenes y WebSockets de ese navegador son ficticios e interceptados: se comprueba el cliente desplegado, sin usar una cuenta social personal ni enviar mensajes o mutaciones reales. No equivale a un recorrido completo contra los datos productivos. La comparación visual automática sigue inconclusa por falta de referencia equivalente.

La comprobación pública HTTP del dominio aprueba **97 controles** de páginas, metadatos, activos y exclusión de rutas privadas. No garantiza indexación, posicionamiento, Core Web Vitals de campo ni la tarjeta cacheada dentro de WhatsApp. La suite local web completa de 137 sigue identificada separadamente: 1.249 pruebas, 196 suites.

## Publicación y pendientes

Se publica este bloque en main y feature/live-gifts con árboles iguales. La modificación Android no cambia el árbol de NoWing-web respecto al despliegue verificado; un despliegue posterior del commit documental conserva ese mismo código web. Las verificaciones anteriores tienen su fuente y momento propios.

Los seis cambios documentales residuales de main y los cuatro cambios web ajenos se preservan. El intercambio con el otro asistente se realizó por el panel de chat. Se mantiene la separación de correciones y del plan experimental 105.

Continúan abiertos el diagnóstico de historiales vacíos, la escala de seguidores, latencia colectiva, actualización de URL de tarjetas externas, publicidad compartida, paridad restante, medios físicos, propiedad global de LIVE y los demás recorridos móviles. La auditoría integral y la APK final siguen abiertas; este bloque no afirma preparación completa para producción o marketing masivo.
