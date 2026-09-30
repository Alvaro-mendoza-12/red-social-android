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

- El servicio FCM crea el repositorio de llamadas bajo demanda; una notificación
  corriente ya no fuerza esa inicialización específica.
- Prueba en el emulador fuera de la app: notificación de llamada visible con
  acciones de contestar/rechazar e intención de pantalla completa. La prueba
  usa una invitación sintética; no acredita entrega FCM entre dos teléfonos.
- Cámara, silencio y audio RTP se comprobaron en los tres flujos nativos.
- Se revisó el enrutamiento existente a auricular, altavoz, cable, USB y Bluetooth
  y la limpieza de la ruta al finalizar. Hace falta repetirlo con hardware real.
- Android decide si permite pantalla completa. No se acredita funcionamiento
  después de forzar detención, bloqueo del fabricante o ausencia de permisos.

## 4. Carga y resistencia del servidor

- Sonda nueva de lecturas limitada a `/health` y `/ready`: máximo 200 peticiones,
  24 simultáneas y timeout explícito. Rechaza rutas de escritura y credenciales
  en la URL. Genera percentiles y cuenta los fallos sin ocultarlos.
- PC, resolución DNS del sistema: 48 peticiones, 8 simultáneas, cero fallos;
  p95 104 ms, máximo 144 ms. Esta ruta puede usar Tailscale privado.
- Dentro del servidor, `/ready`: 120 peticiones, 24 simultáneas, cero fallos;
  p95 918 ms, máximo 1.335 ms. El tiempo interno máximo declarado fue 4,1 ms;
  la latencia total incluye cliente y concurrencia.
- La entrada pública sí se comprobó por separado forzando las direcciones
  públicas con TLS y SNI: seis respuestas correctas después de su recuperación.
- Monitor desplegado con copia de respaldo. Comprueba la entrada pública antes
  de pedir el token: un fallo de base de datos queda registrado como degradado.
  Si falla la entrada pública, omite las lecturas que no pueden demostrar acceso
  externo. Se comprueba 90–105 segundos después de acabar la ejecución anterior
  y alerta tras tres fallos consecutivos; no reinicia servicios automáticamente.
- Cinco pruebas de carga/monitor en Linux aprobaron, incluidos fallos simulados,
  recuperación del estado y límite de concurrencia. Los nueve servicios Docker
  estaban sanos y el monitor desplegado terminó con `status=ok`.
- Esto no acredita capacidad con cientos de usuarios ni recuperación ante pérdida
  real de la base de datos. No se detuvo la base de datos de usuarios para probarla.
- La copia del 30/09 pasó SHA-256 y lectura de los archivos y del índice PostgreSQL.
  La primera restauración aislada falló al entrar durante el servidor provisional
  del instalador. Se corrigió la espera para exigir el proceso PostgreSQL definitivo
  y guardar logs en caso de fallo. La repetición restauró 216 tablas y limpió su
  contenedor. Una prueba Linux reprodujo arranque provisional y fallo de restauración.
  Es recuperación de la base de datos en aislamiento; no arranca toda la aplicación
  restaurada ni extrae los archivos de usuarios.

## 5. Interfaz, accesibilidad y diez heurísticas

- Botones de llamada apilados cuando falta ancho o el texto supera 1,5×, separados
  12 dp y con altura mínima de 56 dp. Se mantiene desplazamiento vertical.
- Seis pruebas instrumentadas pasaron: acciones de llamada fuera de la app,
  pantalla de 320×480 dp con texto 2×, recuperación por correo con pasos separados,
  errores/reintento/cierre en salas y colaboración LIVE, y rondas con títulos largos,
  texto 1,6× y ambos temas. Se inspeccionaron las capturas de llamada y ronda.
- El recorte superior de la captura estrecha corresponde al desplazamiento hacia
  los botones; el contenido anterior sigue accesible desplazándose hacia arriba.
- La revisión de estas pantallas relaciona las diez heurísticas con lo observable:

| Heurística | Comprobación en los flujos revisados |
|---|---|
| Visibilidad del estado | Llamada entrante, estado de ronda y paso actual de recuperación. |
| Lenguaje familiar | Contestar, rechazar, código y nueva contraseña separados. |
| Control y salida | Cerrar enlaces, rechazar llamadas y reintentar sin reiniciar todo. |
| Consistencia | Acciones Android de llamada y botones reconocibles. |
| Prevención de errores | Código incompleto y contraseña sin confirmar no permiten avanzar. |
| Reconocer antes que recordar | Acciones y campos visibles; no hay comandos que memorizar. |
| Flexibilidad | Pantalla estrecha, texto ampliado y reintentos explícitos. |
| Información necesaria | Pasos de recuperación separados y títulos de ronda legibles. |
| Recuperación de errores | Error visible con reintento en enlaces/sesiones fallidas. |
| Ayuda contextual | Etiquetas y explicación del paso de recuperación. |

Esta matriz no certifica todas las pantallas de la APK ni sustituye pruebas
completas con TalkBack y usuarios. La parte del sistema Android aparece en inglés
porque el emulador usa ese idioma; los botones propios revisados están en español.

## 6. Sesiones largas y entrega

En curso. No existe una clave de firma de producción configurada en este
checkout; se conserva el control que impide publicar con una clave de depuración.

## Fuentes técnicas

- [Adaptación de video de WebRTC](https://webrtc.googlesource.com/src/+/HEAD/video/g3doc/adaptation.md).
- [Configuración de video de LiveKit](https://docs.livekit.io/transport/media/advanced/).
- [Llamadas Android](https://developer.android.com/develop/connectivity/telecom/voip-app).
- [Calidad de Android](https://developer.android.com/docs/quality-guidelines/core-app-quality).
