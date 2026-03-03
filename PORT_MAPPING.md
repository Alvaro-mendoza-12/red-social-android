# Configuración Permanente de Puertos (Voice System)

Para evitar confusiones futuras, aquí se detalla cómo está configurada la red de las salas de voz:

## 🏗️ Arquitectura de Red

1. **Servidor (Dentro de VirtualBox)**:
   - **Archivo**: `signaling-server/index.js`
   - **Puerto**: `3000` (El servidor escucha aquí).
   - **Comando**: `node index.js` o `npm start`.

2. **VirtualBox (Reenvío de Puertos)**:
   - **Nombre**: `Server`
   - **Host IP**: `127.0.0.1`
   - **Host Port**: `3001`
   - **Guest Port**: `3000`
   - *Explicación*: Lo que llega al puerto 3001 de tu PC (Host) se manda al puerto 3000 de la máquina virtual (Guest).

3. **ngrok (Corriendo en Windows/Host)**:
   - **Comando**: `.\ngrok.exe http 3001`
   - **Puerto**: `3001` (ngrok apunta al puerto host que VB está escuchando).
   - **Destino**: El túnel de ngrok conecta Internet -> Host:3001 -> Guest:3000.

4. **App Android (VoiceRoomRepository.kt)**:
   - **Constante**: `SIGNALING_URL`
   - **Valor**: La URL HTTPS que te da ngrok (ej. `https://xxx.ngrok-free.dev`).

## 🚀 Pasos para Encender

### Paso 1: Encender el Servidor (En la VM)
```bash
cd ~/mi-servidor-voz # o la carpeta del servidor
node index.js
```
*Debe decir: "Signaling server running on port 3000"*

### Paso 2: Encender el Túnel (En Windows)
```powershell
cd "d:\red social"
.\ngrok.exe http 3001
```

### Paso 3: Configurar la App
Copia la URL de ngrok y pégala en `VoiceRoomRepository.kt` en la variable `SIGNALING_URL`.

---

## 🗺️ Mapa de Funciones (Los 8 Iconos)

He verificado que el sistema maneja las 8 funciones de la siguiente manera:

| Icono | Función | Manejado por... | ¿Funciona? |
| :--- | :--- | :--- | :--- |
| 1️⃣ | **Compartir Pantalla** | `index.js` (WebRTC Signaling) | ✅ Sí |
| 2️⃣ | **Emojis / Reacciones** | Supabase Realtime (Broadcast) | ✅ Sí |
| 3️⃣ | **Invitar Amigos** | Supabase Realtime (Presence/Invite) | ✅ Sí |
| 4️⃣ | **Cámara (Video)** | `index.js` (WebRTC Signaling) | ✅ Sí |
| 5️⃣ | **Micrófono (Mute)** | `index.js` (WebRTC Signaling) | ✅ Sí |
| 6️⃣ | **Chat de Sala** | Supabase Realtime (Broadcast) | ✅ Sí |
| 7️⃣ | **Volumen** | App Android (Local) | ✅ Sí |
| 8️⃣ | **Colgar / Salir** | Socket.io + Supabase (Cleanup) | ✅ Sí |

**Nota técnica**: El servidor `index.js` es el responsable de la parte más compleja: el intercambio de datos para que la voz, el video y la pantalla compartida lleguen a los demás. Las funciones de chat y emojis usan Supabase porque es más eficiente para mensajes de texto cortos.
