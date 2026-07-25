# Matriz Wi‑Fi ↔ datos móviles — WebRTC

Fecha de preparación: 22 de julio de 2026.

## Evidencia automática completada

- signaling público autenticado: oferta, respuesta e ICE pasan en `/`, `/call`
  y `/live`;
- emitir y entrar a un directo funciona a nivel de sesión;
- un espectador no puede reemplazar al anfitrión;
- el endpoint entrega TURN REST con vencimiento y rutas UDP/TCP/TLS;
- desde una máquina externa se crearon asignaciones y se transportaron paquetes
  por UDP 3478, TCP 3478 y TLS 443;
- certificado de `turn.198-251-72-174.sslip.io` validado sin errores;
- signaling, API y Coturn quedaron saludables.

## Prueba obligatoria con dos teléfonos

Usar exactamente el mismo APK nuevo en ambos equipos. Una versión de Play Store
anterior contra una versión local nueva no cuenta como validación, porque puede
tener un contrato de signaling/SDP distinto. Desactivar VPN, ahorro de batería y
Bluetooth durante la primera pasada. En cada caso hablar al menos 60 segundos,
alternar micrófono y, cuando corresponda, cámara/pantalla.

| Caso | Equipo A | Equipo B | Resultado esperado | Estado físico |
|---|---|---|---|---|
| Llamada de audio | Wi‑Fi | Datos móviles | Audio bidireccional; un solo tono por equipo; sin eco ni duplicación | Pendiente |
| Llamada de audio inversa | Datos móviles | Wi‑Fi | Audio bidireccional; quien llama y quien recibe oyen normalmente | Pendiente |
| Auricular/altavoz | Wi‑Fi | Datos móviles | Inicia en auricular; el botón conmuta al altavoz y vuelve sin cortar audio | Pendiente |
| Llamada con video | Wi‑Fi | Datos móviles | Audio y video bidireccionales; ambos activan/desactivan cámara sin reconectar | Pendiente |
| Llamada con video inversa | Datos móviles | Wi‑Fi | El receptor Wi‑Fi ve cámara remota y la cámara local llega al móvil | Pendiente |
| Sala de voz | Wi‑Fi | Datos móviles | Ambos aparecen; audio bidireccional; reconexión al cambiar red | Pendiente |
| Sala de voz inversa | Datos móviles | Wi‑Fi | Igual al caso anterior | Pendiente |
| Cámara en sala | Wi‑Fi | Datos móviles | Cada participante puede publicar cámara y el otro la ve, sin tapar avatares ajenos | Pendiente |
| Pantalla en sala | Datos móviles | Wi‑Fi | La pantalla compartida llega al otro equipo; al detener vuelve cámara/avatar | Pendiente |
| Directo: anfitrión | Wi‑Fi | Datos móviles | Espectador recibe audio/video y cambios cámara/pantalla | Pendiente |
| Directo: anfitrión inverso | Datos móviles | Wi‑Fi | Espectador Wi‑Fi recibe audio/video | Pendiente |
| Directo: cambio de fuente | Datos móviles | Wi‑Fi | Cámara → pantalla → cámara llega al espectador sin pantalla negra permanente | Pendiente |
| Salir/volver al directo | Cualquiera | Cualquiera | No queda pantalla negra ni espectador fantasma | Pendiente |
| Red restrictiva | Wi‑Fi corporativa, si existe | Datos móviles | ICE usa TLS 443 y llega a CONNECTED/COMPLETED | Pendiente |

## Registro por caso

Anotar hora, operador móvil, modelos Android y resultado. Si falla, capturar en
ambos dispositivos:

```text
package:conectate.com & (tag:WebRTC | tag:WebRTCManager | tag:CallRepository | tag:VoiceRoomRepository | tag:LiveRepository)
```

Además de marcar el resultado, anotar si el candidato ICE seleccionado fue
`host`, `srflx` o `relay`. En la prueba de red restrictiva debe aparecer `relay`.

El caso solo se aprueba si hay audio real en ambos sentidos y el medio remoto se
ve en el otro teléfono. Que los avatares se vean, que el contador avance o que
Socket.IO indique “conectado” no demuestra transporte multimedia. El eco se
evalúa con los teléfonos en habitaciones separadas; juntos físicamente pueden
generar realimentación acústica aunque WebRTC esté correcto.
