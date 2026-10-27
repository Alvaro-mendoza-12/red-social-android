# Prompt de continuidad: Novvin en producción

Este texto reformula la solicitud de auditoría para continuar el proyecto existente. Es una instrucción de trabajo, no una certificación de resultados ni una afirmación de que las tareas siguientes estén terminadas.

---

Actúa como ingeniero senior responsable de la estabilidad, rendimiento, seguridad y crecimiento de **Novvin**, nuestra red social que **ya está en producción**, con usuarios, conversaciones y contenido reales. Continúa el trabajo de este proyecto desde su último estado comprobado.

## 1. Continúa el trabajo existente

- Revisa el estado Git, la documentación vigente de `docs/oficial/`, las incidencias y evidencias. Empieza por las auditorías 75, 76, 78 y 79: Android sigue con aceptación física pendiente; el host ya está actualizado y reiniciado, el transporte web público está desplegado y la carga detectó un límite que requiere optimización. No confundas solicitudes de indexación con páginas ya indexadas.
- Conserva el contexto útil de la conversación y las autorizaciones ya concedidas. Contrástalo con el código, los artefactos y los servicios actuales. No presupongas memoria completa del proyecto ni resultados que no puedas verificar.
- Identifica qué está implementado, probado, desplegado y observado por usuarios. Son estados distintos. No repitas un trabajo aprobado sin una regresión, cambio o incertidumbre que lo justifique.
- Termina o deja estabilizada la tarea en curso antes de abrir otra que dependa de ella. Mantén un registro breve del punto exacto donde continuar.
- El candidato Android identificado en esta continuidad es **9.1.27 / versionCode 167**. Confirma cuál está realmente publicado en Play y qué commit contiene el APK/AAB que vas a probar. Un candidato generado no equivale a una actualización publicada.

## 2. Trabaja sobre nuestra arquitectura real

La base comprobada es Android Kotlin con Compose, MVVM, Room, Coil y Media3; web React 19 con TypeScript y Vite; backend Ubuntu 24.04 con Docker Compose, Node.js, PostgreSQL, Redis, gateway de tiempo real y signaling/WebRTC. Confirma las versiones antes de cambiarlas.

La web identificada es `https://red-social-android.vercel.app`. El ingreso público de API identificado es `https://conectate.tail9b267c.ts.net`. Verifica sus respuestas actuales y la revisión efectivamente desplegada. Un push a GitHub no prueba un despliegue de Vercel.

No migres frameworks, no reescribas módulos que funcionan, no cambies el algoritmo de Social por preferencia personal y no rediseñes la interfaz sin una necesidad demostrada. Aprovecha las herramientas, componentes y patrones existentes. Corrige con cambios pequeños, revisables y compatibles.

## 3. Orden de trabajo y protección de producción

Prioriza cierres, pérdida de datos, accesos indebidos y caídas del servicio. Después atiende la revisión del servidor en curso y los defectos críticos pendientes. La visibilidad orgánica de la web y la sincronización en tiempo real son prioridades de crecimiento; no deben desplazar una incidencia de estabilidad activa.

Trabaja con cuentas y contenido de prueba. Conserva datos, permisos y sesiones reales. Usa paquetes Android separados y comprueba si contienen una sesión restaurada antes de ejecutar pruebas que exijan una instalación nueva. No borres ni cierres una sesión real para satisfacer una prueba.

Antes de una actualización sensible, comprueba el respaldo, su restauración y la reversión. Conserva una versión anterior utilizable. Respeta las autorizaciones vigentes para commits, sincronización de ramas y despliegues; no vuelvas a pedirlas sin un cambio de alcance. Solicita autorización para nuevas acciones destructivas, costes o interrupciones no cubiertas. Si falta acceso administrativo, identifica el comando y el bloqueo concreto; no confundas falta de permisos con falta de autorización ni intentes eludir restricciones.

No hagas pruebas masivas contra producción. Define entorno, concurrencia, duración, límites de recursos y condiciones de parada. No debilites validaciones de archivos, autenticación, privacidad ni aislamiento para que una prueba pase o una operación parezca más rápida.

## 4. Auditoría y correcciones funcionales

Construye una matriz basada en las funciones existentes y en los reportes del proyecto. Para cada incidencia registra: reproducción, plataforma y versión, causa confirmada o hipótesis, cambio, prueba, resultado y riesgo pendiente. Incluye:

**Acceso y estabilidad Android.** Instalación, arranque frío y caliente, invitado, credenciales, registro, recuperación, permisos, restauración de sesión, cambio de cuenta, cierre de sesión y segundo plano. Verifica redirecciones, Atrás, estados de error y ausencia de crashes o ANR. Revisa especialmente el editor nativo: emojis del teclado y de la app, Unicode, callbacks del corrector, composición, pegado, envío inmediato y borradores problemáticos. Preserva conversaciones y texto válido.

**Mensajería individual, grupos y comunidades.** Apertura en el último mensaje, paginación, anclaje, scroll nativo con inercia, teclado, apodos, responder con doble clic en web, selección y eliminación según autoría. Confirma que abrir una conversación sin enviar nada no produzca una conversación vacía visible para el destinatario. Mantén mensajes y adjuntos optimistas con vista previa local, identificadores estables, confirmación del servidor y reintentos idempotentes. Distingue pendiente, enviado, recibido y leído; no simules una entrega.

**Multimedia y stickers.** Fotos, vídeo, audio y documentos compartidos desde otras apps, incluidos Excel y proveedores `content://`. Comprueba firma real, MIME, extensión, acceso al URI, tamaño, miniaturas y reproducción. Conserva controles de seguridad. Verifica stickers animados, favoritos, tiempos superpuestos, panel de emojis completo, toque corto y pulsación larga, y contenido legible en notificaciones.

**Caché y navegación.** Fotos y avatares animados no deben volver a iniciales al navegar cuando sus imágenes ya están disponibles. Revisa claves, remontajes, invalidación, caché de memoria/disco, precarga acotada y solicitudes duplicadas. Mantén posiciones y contenido útil al volver a Social, conservando el algoritmo existente que permite descubrir publicaciones nuevas al reingresar. Recibir novedades no debe obligar a descargar otra vez imágenes que siguen válidas. No compartas caché privada entre personas ni con invitado; invalida al cambiar versión, permisos o identidad.

**Vídeos y directos.** Mide tiempo hasta el primer fotograma, errores, buffering y recuperación desde feed, perfiles, ranking y enlaces. Reutiliza Media3 y el reproductor web; limita instancias, memoria, datos y precarga. Evalúa transcodificación, rangos HTTP, streaming adaptativo o CDN solo donde las mediciones lo justifiquen. Para LIVE verifica admisión real, permisos, señalización, medios, TURN/SFU, reconexión y salida; un socket conectado no prueba que el usuario haya entrado ni recibido vídeo.

**Salas de voz.** Conserva lo que ya funciona. Verifica que los indicadores de micrófono/silencio correspondan al estado real. Audita audio de pestaña/pantalla, pistas, mezcla con micrófono, permisos, eco y finalización. Implementa captura compartida donde el navegador y el sistema la admitan e informa claramente cuando no sea compatible. Comprueba enlaces de invitación y navegación al destino correcto con sus permisos.

**Social, perfiles, Momentos, notificaciones y Ajustes.** Verifica crear, editar, compartir, visibilidad, comentar, reaccionar, seguir, bloquear y denunciar cuando esas acciones formen parte del producto. Comprueba persistencia y sincronización de apodos, personalización, contadores y preferencias. Ordena notificaciones con UTC e identificadores estables; muestra la hora local correctamente. Verifica reacciones a Momentos, avisos de directos seguidos, duplicados, contadores y apertura al contenido correcto. Los controles de privacidad deben cumplirse en servidor.

## 5. Rendimiento basado en evidencia

Mide por separado interfaz, red, API, base de datos, propagación de eventos y descarga/decodificación de medios. Registra dispositivo, compilación, red, tamaño de muestra y percentiles p50/p95/p99.

Busca respuesta local cercana a 100 ms y scroll cercano al presupuesto de 16,7 ms por cuadro en pantallas de 60 Hz, usando objetivos por dispositivo y escenario. Registra cuadros lentos y ANR. Compara antes y después bajo condiciones equivalentes. No extrapoles los tiempos de un endpoint de salud al envío de mensajes o a la carga de imágenes.

Prueba rendimiento con optimización de producción. Las mediciones de una QA debug o un emulador no certifican los FPS de un teléfono. Un test que confirma que la lista se mueve no demuestra fluidez. Una inicialización fría y una navegación con caché caliente requieren mediciones separadas.

## 6. Servidor, seguridad y recuperación

Revisa salud y versiones reales de contenedores, reinicios, OOM, CPU, RAM, disco, latencias, pools, consultas lentas, índices, logs, certificados, reloj, puertos y aislamiento. Contrasta los Dockerfiles con las imágenes que se ejecutan: que el repositorio esté actualizado no acredita que el servidor use esa versión.

Actualiza runtimes y dependencias vulnerables con pruebas y reversión. Limpia únicamente temporales o cachés cuya falta de uso hayas comprobado. Conserva bases, volúmenes, respaldos, imágenes de recuperación y datos reales. No uses una limpieza global indiscriminada.

Audita autenticación, autorización por objeto, sesiones, revocación, aislamiento entre cuentas, WebSockets, CORS, CSRF, XSS, SSRF, inyecciones, límites de uso, subidas y secretos. Usa documentación oficial y versiones vigentes de OWASP ASVS/MASVS. No confundas un antivirus sano con una garantía de que todo el servidor está libre de compromisos.

Mantén observabilidad útil sin conversaciones, credenciales ni tokens en informes, capturas o Git. Verifica restauración aislada y recuperación del servicio; especifica qué parte de la recuperación se probó.

## 7. SEO, buscadores y recursos promocionales

Reutiliza el prerenderizado público que ya existe en Vite antes de plantear SSR o una migración. Inspecciona HTML inicial, robots, sitemap, canonical, metadatos, datos estructurados, enlaces, redirecciones, errores HTTP y acceso de robots. Distingue navegadores de motores de búsqueda.

Comprueba dominio y despliegue públicos, Search Console, Bing Webmaster Tools e IndexNow donde sean aplicables y exista acceso. Diferencia rastreabilidad técnica, URL enviada e indexación confirmada. No prometas posición o permanencia en Google, Bing, DuckDuckGo, Brave u otros servicios externos. No publiques contenido privado para facilitar indexación.

Prepara capturas promocionales y material para Play, web y campañas a partir de funciones reales y cuentas ficticias. Respeta la identidad visual, especificaciones vigentes, derechos y privacidad. Optimiza formatos, tamaños, ALT y contexto público; añade imágenes al sitemap cuando corresponda. La publicación requiere una autorización vigente que cubra ese material.

## 8. Crecimiento y campañas

El objetivo de 500.000 usuarios es una meta de crecimiento, no una capacidad certificada. Define registrados, DAU/MAU, concurrencia, conexiones, mensajes por segundo, vídeos y directos simultáneos, volumen multimedia, retención y presupuesto.

Mide saturación en un entorno autorizado. Propón etapas y umbrales verificables para base de datos, almacenamiento, ancho de banda, caché, colas y distribución multimedia. No introduzcas microservicios, balanceadores o servicios de pago por anticipación. Justifica cada incorporación con el cuello de botella y su coste. Define una campaña inicial acorde a la capacidad demostrada y criterios para detener o ampliar tráfico.

## 9. Entrega y continuidad

- Actualiza la documentación oficial existente y su índice. Evita crear informes duplicados con estados contradictorios.
- Registra una matriz con funcionalidad, plataforma, versión/commit, escenario, resultado, evidencia y pendiente. Separa código implementado, build aprobado, prueba local, dispositivo real, redes distintas y producción observada.
- Mantén las correcciones solicitadas en `main` y `feature/live-gifts`, sin sobrescribir trabajo ajeno ni forzar el historial. Verifica ramas remotas y despliegues por separado. Excluye secretos, respaldos, APK/AAB y logs privados de los commits.
- Comprueba el artefacto firmado concreto: versión válida para Play, firma, configuración de producción, R8, compatibilidad requerida de 16 KB y arranque completo. Si subes un código de versión, verifica primero el valor publicado; no cambies el código de nuevo por cada continuación del chat.
- Repite las verificaciones pertinentes cuando haya cambios o fallos nuevos. No consumas tiempo repitiendo baterías aprobadas sin motivo.
- Entrega el resultado principal, causas y cambios, pruebas y métricas, estado desplegado y límites pendientes. Define preparación para **seguir operando**, **publicar una actualización**, **iniciar una campaña controlada** y **aumentar concurrencia** por separado.

Continúa ejecutando las correcciones autorizadas, empezando por el punto pendiente de la revisión en curso. No sustituyas la ejecución por una lista genérica de recomendaciones. Cuando falten dispositivos, credenciales, permisos o acceso a servicios externos, continúa las tareas independientes y deja el bloqueo concreto documentado, sin inventar resultados.
