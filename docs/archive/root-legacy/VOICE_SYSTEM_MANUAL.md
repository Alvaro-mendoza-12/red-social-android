# Manual del Sistema de Voz (WebRTC + Socket.io)

Este documento detalla el funcionamiento técnico del nuevo sistema de salas de voz de **Conéctate**, el cual reemplaza a la solución anterior basada en Agora.

## 🏗️ Arquitectura General

El sistema utiliza tres componentes principales:
1. **Cliente Android (Kotlin)**: Implementa WebRTC nativo para el procesamiento de audio y Socket.io para la señalización.
2. **Servidor de Señalización (Node.js)**: Actúa como intermediario para que los clientes intercambien su información de conexión.
3. **Supabase (Realtime)**: Se utiliza para el estado de la UI (lista de participantes, quién habla, chat de la sala).

## 📡 Servidor de Señalización

El servidor (`signaling-server/`) es vital para establecer la conexión Punto a Punto (P2P).

### Eventos Manejados:
- `join-room`: Asocia un socket a una sala específica (`roomId`).
- `offer`: Envía una oferta SDP a un usuario específico.
- `answer`: Responde a una oferta con su propia SDP.
- `ice-candidate`: Intercambia candidatos de red para atravesar firewalls (NAT traversal).

## 📱 Implementación en Android

### Componentes Clave:
- **`WebRTCManager.kt`**: Gestiona la factoría de PeerConnections, pistas de audio (AudioTracks) y el ciclo de vida de WebRTC.
- **`VoiceRoomRepository.kt`**: Coordina la conexión de Socket.io y la sincronización con Supabase.
- **`VoiceForegroundService.kt`**: Mantiene la voz activa incluso cuando la app está en segundo plano mediante una Notificación Persistente.

### Flujo de Conexión:
1. El usuario se une a una sala en la app.
2. La app resuelve el **UUID** de la sala (estandarización para evitar fallos de sincronización).
3. Se conecta al Servidor de Señalización vía Socket.io.
4. Se une al canal de Supabase Realtime para la UI.
5. El servidor notifica a los demás participantes (`user-joined`).
6. Se inicia el intercambio de WebRTC (`offer` -> `answer` -> `ice-candidate`).

## 🛠️ Mantenimiento y Solución de Problemas

### 1. El audio no se escucha
- Verifica que el servidor de señalización esté corriendo (`npm start`).
- Verifica la URL en `VoiceRoomRepository.kt`. Si usas celular real, **DEBE** ser una URL de ngrok o la IP local de tu PC.
- Asegúrate de que ambos dispositivos tengan permisos de Micrófono concedidos.

### 2. No veo a los demás participantes
- El sistema ahora usa estrictamente el **UUID** de la base de datos. Asegúrate de que las tablas `voice_rooms` tengan IDs válidos.
- Revisa los logs de Supabase Realtime en el Dashboard.

### 3. La app se cierra al entrar
- Revisa que las notificaciones del sistema estén permitidas. El `VoiceForegroundService` requiere mostrar una notificación inmediatamente después de iniciarse.

## 🚀 Despliegue a Producción
Cuando subas el servidor de voz a un servidor real (VPS, Heroku, AWS):
1. Actualiza `SIGNALING_URL` en la app con la URL HTTPS de tu servidor.
2. Asegúrate de que el servidor soporte WebSockets.
