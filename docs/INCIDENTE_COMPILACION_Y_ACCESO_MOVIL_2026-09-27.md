# Incidente de compilación y acceso móvil — 27/09/2026

## Compilación Android

El registro de Android Studio falló en `:app:hiltJavaCompileDebug` al leer
`app/build/intermediates/hilt/component_classes/debug/dagger`, una carpeta
generada que había desaparecido. Durante la investigación, una segunda
compilación simultánea falló al intentar reemplazar `R.jar` en el mismo
`app/build`. No había un error de Kotlin en las pantallas de recuperación.

`settings.gradle.kts` ahora toma un bloqueo de archivo entre procesos durante
toda la compilación del checkout. Android Studio y la consola deben esperar
su turno incluso si usan daemons de Gradle diferentes. El archivo
`.gradle/novvin-build.lock` es un marcador persistente; su existencia es
normal y no indica que siga bloqueado. No se debe borrar durante una
compilación activa. `:app:assembleDebug` terminó con `BUILD SUCCESSFUL` usando
el JBR 21 de Android Studio después del cambio.

## Conectividad móvil

La APK anterior usaba `https://conectate.tail9b267c.ts.net:8443`. Funnel
publica esa dirección, pero el puerto 8443 puede fallar en algunas redes
móviles. Un DNS de Tailscale en la PC devuelve una IP `100.x`, mientras que
los resolvedores públicos devuelven IP públicas; la respuesta interna de DNS
por sí sola no prueba que Funnel sea privado.

Funnel sirve ahora la API en el puerto HTTPS estándar 443, con rutas `/v1`,
`/rest`, `/storage`, `/socket.io`, `/ready` y `/health`. La ruta `/` continúa
en el servicio web anterior. `secrets.properties` local y
`secrets.properties.example` usan el origen sin puerto. El servidor también
configura `PUBLIC_BASE_URL` y `STABLE_PUBLIC_BASE_URL` con ese origen para que
los enlaces nuevos de archivos y video apunten a 443. El monitor del túnel
acepta el origen estable tanto en 443 como en 8443 y sigue conservando un
Quick Tunnel como respaldo. Durante la auditoría se observó que la regla
anterior rechazó el origen 443 y reescribió `PUBLIC_BASE_URL` con una URL
temporal; la reconciliación de Docker reinició API, señalización y realtime.
La regla corregida volvió a publicar el origen estable en el TXT firmado de
DuckDNS. Es una causa concreta de interrupciones intermitentes.

Se comprobó desde las dos IP públicas de Funnel: `/ready` 200 con hora de
PostgreSQL, `/health` 200, `/socket.io` 200, `/v1/data/live` 401 sin sesión,
`/rest/v1/profiles` 401 sin sesión y un objeto inexistente de `/storage` 404.
Una cuenta sintética obtuvo registro 201, login correcto 200 en 394 ms y
contraseña errónea 401; luego se eliminó. La APK nueva se instaló en un
emulador, inició sesión con otra cuenta sintética y cargó Mensajes y Videos;
esa cuenta también se eliminó. Todos los contenedores principales quedaron
`healthy` tras el cambio.

Estas comprobaciones demuestran el recorrido desde esta PC y el emulador.
Falta confirmar el acceso desde el teléfono y la red que reportaron el error;
la versión instalada y cualquier DNS o VPN del dispositivo pueden influir.
La APK instalada antes de este cambio conserva su dirección anterior hasta
que se actualice.
