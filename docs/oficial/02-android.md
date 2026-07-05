# 02 — Aplicación Android

La app Android es el cliente principal de Conéctate. Está desarrollada en Kotlin con Jetpack Compose y sigue una organización por capas, ViewModels, repositorios, servicios Android y módulos de datos.

## Configuración de build

Archivo:

```text
app/build.gradle.kts
```

Estado observado:

- `namespace`: `conectate.com`
- `applicationId`: `conectate.com`
- `compileSdk`: 35
- `minSdk`: 24
- `targetSdk`: 35
- `versionCode`: 41
- `versionName`: 3.0.1
- Java/Kotlin target: 17
- Compose activo.
- Release signing por `keystore.properties`.
- `minifyEnabled`: false en release actualmente.

## Secrets de build

La app requiere `secrets.properties`.

Ejemplo base:

```properties
FALLBACK_API_URL=https://conectate-alvaro.duckdns.org
BACKEND_DISCOVERY_HOST=conectate-alvaro.duckdns.org
BACKEND_CONFIG_URL=

SUPABASE_URL=
SUPABASE_ANON_KEY=

OPENRELAY_TURN_URL=
OPENRELAY_USERNAME=
OPENRELAY_PASSWORD=
```

Reglas:

- No subir `secrets.properties` real.
- No subir `keystore.properties`.
- No subir keystores.
- No meter URLs temporales `trycloudflare.com` como endpoint final de producción.
- La app de producción debe poder descubrir la URL actual del backend.

## Capas principales

Rutas relevantes:

```text
app/src/main/java/conectate/com/data/
app/src/main/java/conectate/com/domain/
app/src/main/java/conectate/com/presentation/
app/src/main/java/conectate/com/services/
app/src/main/java/conectate/com/firebase/
```

### `data/`

Contiene modelos, entidades locales, repositorios, APIs y WebRTC.

Repositorios importantes:

- `AuthRepository.kt`
- `UserRepository.kt`
- `MessageRepository.kt`
- `ConversationRepository.kt`
- `GroupRepository.kt`
- `CommunityRepository.kt`
- `VoiceRoomRepository.kt`
- `CallRepository.kt`
- `CallHistoryRepository.kt`
- `VideoRepository.kt`
- `PostRepository.kt`
- `NotificationRepository.kt`
- `StickerRepository.kt`

### `presentation/`

Contiene pantallas Compose y ViewModels:

- mensajes;
- grupos;
- comunidades;
- llamadas;
- salas de voz;
- videos;
- perfil;
- admin;
- componentes compartidos.

### `services/`

Servicios Android para:

- llamadas;
- llamadas entrantes;
- salas activas;
- foreground services;
- chat heads.

Ejemplos:

```text
CallForegroundService.kt
IncomingCallForegroundService.kt
VoiceForegroundService.kt
LiveForegroundService.kt
```

### `firebase/`

FCM:

```text
MyFirebaseMessagingService.kt
```

## Flujos principales

### Autenticación

Casos que deben distinguirse:

1. Correo no existe:
   - mostrar mensaje de registro/no tienes cuenta;
   - no mostrar “cambia contraseña por migración”.

2. Cuenta importada de Supabase sin contraseña local final:
   - mostrar flujo de creación/cambio de contraseña una sola vez.

3. Cuenta local Ubuntu con contraseña incorrecta:
   - mostrar contraseña incorrecta;
   - no mostrar migración Supabase.

4. Cuenta local válida:
   - iniciar sesión normal.

### Mensajes 1 a 1

La bandeja debe mostrar:

- último mensaje real;
- hora del último mensaje;
- prefijo `Tú:` cuando el último mensaje fue enviado por el usuario actual;
- contador de no leídos cuando corresponde;
- estado leído/entregado si aplica;
- pin/archive/favorite/delete según estado persistente del servidor.

Problemas conocidos que deben vigilarse:

- chat eliminado que reaparece después de reiniciar;
- último mensaje que no se actualiza con mensajes entrantes;
- contador que aparece aunque no haya chat visible;
- swipe vertical confundido con swipe lateral.

### Grupos

Debe soportar:

- lista de grupos;
- contador por grupo con mensajes no leídos;
- participantes visibles;
- agregar/quitar participantes;
- llamadas grupales;
- mensajes persistentes;
- estado leído que no reaparece como no leído después de refrescar.

### Comunidades

Debe soportar:

- “Mis comunidades”;
- “Explorar comunidades”;
- permisos por rol:
  - creador;
  - admin;
  - miembro;
- expulsar usuarios;
- promover/degradar admin;
- editar comunidad;
- restringir mensajes a admins si la comunidad está configurada así;
- límite de mensajes para usuarios normales si está activado.

No confundir grupos con comunidades. Son módulos relacionados, pero no idénticos.

### Videos

Debe soportar:

- feed vertical;
- reproducción fluida;
- cache local;
- thumbnails;
- barra de progreso tipo TikTok;
- precarga del siguiente video cuando sea razonable;
- no parpadear negro/blanco al cambiar;
- no hacer resize brusco del layout.

Para videos largos:

- si supera el límite configurado, mostrar recorte;
- permitir reproducir el fragmento;
- permitir ajustar inicio/fin;
- subir versión optimizada.

### Stickers

Un sticker no debe comportarse como imagen normal.

Debe:

- renderizarse con tamaño controlado;
- no abrir visor de imagen al toque simple;
- permitir menú contextual:
  - añadir a favoritos;
  - quitar de favoritos;
  - reenviar;
  - eliminar si corresponde;
  - reportar si aplica.

### Llamadas y salas de voz

Módulos Android relevantes:

- `CallViewModel.kt`
- `CallScreen.kt`
- `VoiceRoomsViewModel.kt`
- `VoiceRoomActiveViewModel.kt`
- `VoiceRoomActiveScreen.kt`
- `WebRTCManager.kt`

Reglas:

- Nunca crashear si falla WebRTC.
- Mostrar error recuperable.
- Liberar cámara, micrófono y pantalla al salir.
- Solicitar permisos antes de inicializar.
- Validar conectividad ICE y mostrar estado.
- No dejar foreground service colgado.

## Cache móvil

La app debe cachear:

- fotos de perfil;
- thumbnails;
- últimos perfiles vistos;
- conversaciones recientes;
- previews de mensajes.

Debe evitar:

- pedir perfil completo cada vez que se abre una pantalla;
- recargar imágenes que no cambiaron;
- bloquear UI mientras espera red;
- quedarse en pantalla negra sin skeleton/placeholder.

Recomendación:

- usar Coil con memoria + disco;
- usar Room para datos de bandeja/perfiles básicos;
- usar timestamps/ETags si backend los expone;
- invalidar por `updated_at`.

## Buenas prácticas para cambios Android

Antes de editar:

```powershell
git status --short
```

Compilar rápido:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Compilar release:

```powershell
.\gradlew.bat :app:compileReleaseKotlin
```

Generar AAB:

```powershell
.\gradlew.bat :app:bundleRelease
```

Salida esperada:

```text
app/build/outputs/bundle/release/app-release.aab
```

Antes de Play Store:

- subir `versionCode`;
- probar instalación limpia;
- probar login;
- probar mensajes;
- probar notificaciones;
- probar llamadas/salas en Android real;
- revisar crash logs.

