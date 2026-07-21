# Guía de Configuración - Conéctate

Esta guía te ayudará a configurar completamente el proyecto paso a paso.

## 📋 Índice

1. [Configuración de Android Studio](#1-configuración-de-android-studio)
2. [Configuración de Supabase](#2-configuración-de-supabase)
3. [Configuración del Servidor de Voz (Signaling)](#3-configuración-del-servidor-de-voz-signaling)
4. [Primera Ejecución](#4-primera-ejecución)
5. [Problemas Comunes](#5-problemas-comunes)

---

## 1. Configuración de Android Studio

### Requisitos

- **Android Studio**: Hedgehog (2023.1.1) o superior
- **JDK**: OpenJDK 17
- **Gradle**: 8.2.0 (incluido en el proyecto)
- **Android SDK**: API 26 (mínimo) - API 34 (target)

### Pasos

1. **Abrir el proyecto**:
   ```
   File > Open > navegar a d:\RED SOCIAL
   ```

2. **Esperar Gradle Sync**:
   - La primera sincronización puede tardar varios minutos
   - Android Studio descargará todas las dependencias

3. **Configurar el emulador** (opcional):
   ```
   Tools > Device Manager > Create Device
   - Seleccionar: Pixel 6 Pro
   - API Level: 34 (Android 14)
   - Habilitar: Play Store
   ```

---

## 2. Configuración de Supabase

Este proyecto usa Supabase como backend para Auth, Base de datos (Postgres/PostgREST), Storage y Realtime.

### 2.1 Claves y `secrets.properties`

1. Crea un proyecto en https://app.supabase.com
2. Ve a `Project Settings > API` y copia la `URL` y la `anon key`.
3. Crea un archivo `secrets.properties` en la raíz del repo o usa variables de entorno. Formato (usa `secrets.properties.example` como plantilla):

```
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
```

> Nota: `secrets.properties` está en `.gitignore`. NO comitees `secrets.properties`.

### 2.2 Tablas y RPCs necesarias (sugestión)

Las siguientes tablas y funciones son esperadas por la app; puedes adaptarlas según tu esquema:

- `profiles` (id PK, full_name, username, avatar_url, country, city, push_token, created_at)
- `videos`, `video_comments`, `video_likes` (para feed y comentarios)
- `messages`, `chats`, `chat_participants` (para mensajería)
- `voice_rooms`, `voice_room_participants`, `voice_room_speakers`

Funciones RPC sugeridas:
- `get_or_create_chat(p_user_id1, p_user_id2)` → devuelve Chat
- `get_user_chats_with_last_message(p_user_id)` → lista de chats con último mensaje
- `get_unread_messages_count(p_chat_id, p_user_id, p_last_read_message_id)` → entero

### 2.3 Storage y Realtime

1. Habilita Storage y crea buckets: `videos`, `avatars`, `images`, `audios`, etc.
2. Configura Realtime si deseas suscripciones a tablas (mensajes, videos, etc.).

### 2.4 Recomendaciones de seguridad

1. Habilita Row Level Security (RLS) y crea políticas que usen `auth.uid()` para restringir acceso.
2. No comitees `secrets.properties`; usa secret manager para producción.

---

## 3. Configuración del Servidor de Voz (Signaling)

Las salas de voz ya no usan Agora. Ahora usan un sistema basado en WebRTC nativo con un servidor de señalización personalizado en Node.js.

### 3.1 Requisitos

- **Node.js**: Versión 16 o superior
- **ngrok**: Necesario para conectar un dispositivo físico al servidor local

### 3.2 Pasos de Instalación

1. **Navegar a la carpeta del servidor**:
   ```bash
   cd "d:\red social\signaling-server"
   ```

2. **Instalar dependencias**:
   ```bash
   npm install
   ```

3. **Iniciar el servidor**:
   ```bash
   # Puerto por defecto: 3001
   npm start
   ```

### 3.3 Conexión con la App (Android)

1. **Obtener URL de conexión**:
   - Si usas el **Emulador**: Usa `http://10.0.2.2:3001` (esto ya está configurado como fallback).
   - Si usas un **Celular Real**:
     - Ejecuta `ngrok http 3001`
     - Copia la URL `https://xxxx.ngrok-free.dev` generada.

2. **Configurar en el Código**:
   Abre `d:\red social\app\src\main\java\conectate\com\data\repository\VoiceRoomRepository.kt` y actualiza la constante:

   ```kotlin
   private val SIGNALING_URL = "LA_URL_DE_NGROK_AQUI"
   ```

### 3.4 Notas Técnicas

- El servidor gestiona el intercambio de SDP (Offers/Answers) e ICE Candidates entre los pares de WebRTC.
- Utiliza **Socket.io** para mantener la conexión persistente.
- El servidor corre por defecto en el puerto **3001**.

---

## 4. Primera Ejecución

### 4.1 Compilar el Proyecto

En Android Studio:

1. Seleccionar **Build > Make Project**
2. Esperar a que compile (puede tardar unos minutos la primera vez)
3. Verificar que no haya errores

Si hay errores de compilación:
```bash
# Limpiar y reconstruir
Build > Clean Project
Build > Rebuild Project
```

### 4.2 Ejecutar en Emulador

1. **Iniciar el emulador**:
   - Tools > Device Manager
   - Seleccionar tu dispositivo y clic en "Play"

2. **Ejecutar la app**:
   - Clic en el botón verde "Run" (▶️)
   - O presionar Shift + F10

### 4.3 Ejecutar en Dispositivo Físico

1. **Habilitar modo desarrollador** en tu teléfono:
   - Settings > About Phone
   - Tocar 7 veces en "Build Number"

2. **Habilitar USB Debugging**:
   - Settings > Developer Options
   - Activar "USB Debugging"

3. **Conectar el teléfono** vía USB

4. **Ejecutar** desde Android Studio

### 4.4 Probar la Autenticación

1. Al abrir la app, verás la pantalla de login.
2. Para crear una cuenta de prueba en el flujo de UI:
   - Email: `test@ejemplo.com`
   - Contraseña: `Test123456`

3. Verifica en el Dashboard de Supabase (Auth → Users) que el usuario existe.

---

## 5. Problemas Comunes

### Error: "google-services.json no encontrado" (solo si usas FCM)

**Solución**: `google-services.json` solo es necesario si usas Firebase Cloud Messaging (FCM).
Si decides no usar FCM, puedes ignorar este paso. Si quieres habilitar FCM:
1. Descarga `google-services.json` desde Firebase Console
2. Colócalo en `app/`

### Error: "Unresolved reference: R"

**Solución**:
```bash
Build > Clean Project
Build > Rebuild Project
File > Invalidate Caches / Restart
```

### Error: Gradle Sync Failed

**Solución**:
1. Verificar conexión a internet
2. File > Project Structure > Project > Gradle Version: 8.2
3. File > Settings > Build > Gradle > Gradle JDK: JDK 17

### La app se cierra al iniciar

**Solución**: Revisa Logcat en Android Studio:
1. View > Tool Windows > Logcat
2. Busca errores en rojo
3. Verifica que Supabase esté configurado correctamente (revisa `secrets.properties` y el dashboard de Supabase)

### Error de compilación de Agora SDK

**Solución**: Si no vas a usar salas de voz aún, comenta la dependencia:
```kotlin
// En app/build.gradle.kts
// implementation("io.agora.rtc:full-sdk:4.3.0")
```

### Emulador muy lento

**Solución**:
1. Usar un dispositivo con menos resolución
2. Tools > Device Manager > Create Device > Pixel 5
3. O usar un dispositivo físico

---

## 📚 Recursos Adicionales

- [Documentación de Supabase](https://supabase.com/docs)
- [Documentación de Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Documentación de Hilt](https://dagger.dev/hilt/)
- [Documentación de Agora](https://docs.agora.io/en/voice-calling/overview/product-overview)

---

## 🆘 Soporte

Si encuentras problemas no cubiertos en esta guía:

1. Revisa los logs en Logcat
2. Busca el error en Stack Overflow
3. Consulta la documentación oficial
4. Contacta al equipo de desarrollo

---

¡Listo! Ya puedes empezar a desarrollar. 🚀
