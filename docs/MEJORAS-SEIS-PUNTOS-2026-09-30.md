# Mejoras y verificación de los seis puntos de Android

Rama exclusiva: `feature/live-gifts`, en `D:/red social - live-gifts`.

## Alcance de esta sesión

El propietario se lleva el Motorola a la universidad. Esta pasada usa el emulador
Android y el servidor real. No acredita dos teléfonos físicos, otro operador,
escucha humana, consumo de batería real ni entrega en Google Play.
Los retiros permanecen fuera del alcance por indicación del propietario.

## 1. Fluidez de LIVE, llamadas y salas

- Captura nativa limitada según gama del dispositivo, con orientación conservada
  y respeto de resoluciones menores solicitadas por el usuario.
- Perfiles de respaldo decrecientes: ya no intentan una resolución mayor después
  de fallar el perfil inicial.
- Codificación de cámara prioriza cadencia (`MAINTAIN_FRAMERATE`) y usa límites
  de bitrate por dispositivo. Pantalla compartida prioriza legibilidad.
- Se reaplica la política al reemplazar la pista, además de negociar la sesión.
- Salas en gama baja solicitan hasta 24 FPS con resolución limitada.
- Compilación debug e instrumentación: aprobadas. 971 pruebas unitarias, cero
  fallos, errores u omisiones, incluidas cinco nuevas de presupuesto de captura.
- La primera referencia en emulador falló al cambiar de cámara: su configuración
  tenía `hw.camera.front=none`. Se repite con ambas cámaras virtuales habilitadas;
  ese primer fallo no se contabiliza como aprobado ni como defecto del Motorola.

Mediciones nuevas: sala 8,62 FPS, primera imagen 1,86 s; llamada con TURN
obligatorio 8,62 FPS, primera imagen 3,585 s. Ambos ensayos conservaron los
controles de cámara y recibieron audio RTP. Son dos extremos en un emulador;
no acreditan escucha humana ni aumento de FPS en el Motorola.

La repetición de llamada falló primero por cuota de registros (HTTP 429): se
prepararon dos cuentas sintéticas reutilizables, sin desactivar límites.
También se detectó caída de la entrada pública con ruta privada sana. Reiniciar
únicamente `tailscaled` recuperó las seis comprobaciones públicas. Una asignación
TURN TLS autenticada pasó desde la PC. El emulador todavía no generaba candidatos
relay; reiniciarlo con DNS públicos explícitos recuperó TURN. Esa configuración
pertenece al emulador, no altera el DNS del usuario ni rebaja TLS.

## 2. Cambios de red y recuperación

- Se invalida una recuperación nativa encolada cuando hay otro cambio de red,
  salida o reinicio de sesión. Cancelar la coroutine no bastaba para retirar
  trabajo ya encolado en el ejecutor de WebRTC.
- Ensayos de ICE-restart: sala 2,527 s y llamada relay 2,424 s hasta recuperar
  frames nuevos. Ambos preservaron la pista de cámara y el micrófono silenciado.
- El mismo ejecutor serializa la recuperación y la limpieza. Los callbacks
  habituales de Android y el SDK de colaboración mantienen su reconexión.
- Un ICE-restart solicitado en prueba no equivale a apagar Wi-Fi y pasar a un
  operador real. Esa matriz física permanece pendiente cuando haya dispositivos.
- `build/six-points-02-build.log`: compilación y suite unitarias aprobadas.

## 3. Llamadas fuera de la app y rutas de audio

En curso. Se requiere repetir la matriz final con dos teléfonos físicos.

## 4. Carga y resistencia del servidor

En curso.

## 5. Interfaz, accesibilidad y diez heurísticas

En curso.

## 6. Sesiones largas y entrega

En curso. No existe una clave de firma de producción configurada en este
checkout; se conserva el control que impide publicar con una clave de depuración.

## Fuentes técnicas

- [Adaptación de video de WebRTC](https://webrtc.googlesource.com/src/+/HEAD/video/g3doc/adaptation.md).
- [Configuración de video de LiveKit](https://docs.livekit.io/transport/media/advanced/).
- [Llamadas Android](https://developer.android.com/develop/connectivity/telecom/voip-app).
- [Calidad de Android](https://developer.android.com/docs/quality-guidelines/core-app-quality).
