---
description: Guía de configuración del servidor de señalización con VirtualBox y Ngrok
---

# Configuración del Servidor de Voz (VirtualBox + Ngrok)

Esta guía explica la arquitectura de red para que el servidor de voz funcione correctamente desde una máquina virtual.

## 1. Arquitectura de Puertos

Debido a que el servidor corre dentro de una Máquina Virtual (VM), usamos un reenvío de puertos (Port Forwarding):

| Componente | Dirección/Puerto | Descripción |
| :--- | :--- | :--- |
| **VM (Interno)** | `localhost:3000` | El servidor Node.js escucha aquí dentro de Linux. |
| **VirtualBox (Regla)** | `Host 3001 -> Guest 3000` | Mapea el puerto de tu Windows al de la VM. |
| **Host (Windows)** | `localhost:3001` | Puerto donde Windows "ve" al servidor de la VM. |
| **Ngrok (Túnel)** | `ngrok http 3001` | Expone el puerto del Host a Internet. |

## 2. Pasos para Iniciar el Servidor

1. **En la Máquina Virtual (Linux):**
   ```bash
   cd ~/tu-servidor-voz
   node index.js
   ```
   *El servidor dirá: "Servidor corriendo en el puerto 3000".*

2. **En Windows (CMD/PowerShell):**
   ```bash
   ngrok http 3001
   ```
   *Nota: Se usa el 3001 porque es el puerto mapeado en las reglas de VirtualBox.*

3. **En la Aplicación Android:**
   Asegúrate de que la constante `SIGNALING_URL` en `VoiceRoomRepository.kt` apunte a la nueva URL que te entregó ngrok.

## 3. Resolución de Problemas

- **¿No hay conexión?** Verifica en VirtualBox (Configuración -> Red -> Avanzadas -> Reenvío de puertos) que la regla "Server" tenga: Puerto anfitrión `3001` y Puerto invitado `3000`.
- **¿Ngrok falla?** Asegúrate de no tener otro proceso usando el puerto 3001 en Windows.

---
*Este documento fue creado para evitar confusiones con el mapeo de puertos entre el Host y la VM.*
