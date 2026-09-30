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

Mediciones nuevas: LIVE 8,87 FPS, primera imagen 1,502 s; sala 8,62 FPS,
primera imagen 1,86 s; llamada con TURN
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
  y devuelve fallo del servicio tras tres fallos consecutivos; no reinicia
  servicios automáticamente ni envía un aviso por correo por sí mismo.
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

- Prueba nueva optativa de LIVE sostenido con lectura de frames cada 15 segundos,
  muestras PSS, CPU y bytes RTP. No guarda claves ni tokens en el reporte.
- 240 segundos de video: primera imagen 1,412 s, 8,47 FPS recibidos, 2.034 frames
  durante la ventana de medida. Todas las ventanas recibieron frames nuevos.
  Después recuperó ICE en 2,426 s y permitió silenciar, apagar/encender y girar cámara.
- PSS creció 8.318 KiB respecto al comienzo de la medida. Las muestras ordinarias
  estuvieron cerca de 383–397 MiB expresados originalmente en KiB; una muestra
  devolvió 54.795 KiB. Se conserva ese dato anómalo, no se usa como prueba de ahorro.
  Cuatro minutos no descartan fugas a largo plazo. Hay dos motores nativos en el
  mismo proceso de prueba, por lo que no representa una llamada normal de un teléfono.
- CPU del proceso: 97.811 ms; carga RTP del emisor: 1.405.404 bytes, unos 46,85 kbps.
  El patrón de la cámara virtual comprime fácilmente: no predice el consumo de
  datos de una escena real ni incluye todo el tráfico de transporte/servidor.
- Prueba de ciclo de vida: 16 entradas/salidas, recuperación durante salida y
  liberación ante presión de memoria, sin peers pendientes o reaparecidos.
  Ambas pruebas instrumentadas aprobaron. La compilación final y las 971 pruebas
  unitarias aprobaron sin fallos, errores ni omisiones.
- APK de prueba: `9.0.19-debug`, código 116; se instala conservando datos en el
  emulador. Firma APK v2 verificada. No se instala en el Motorola porque se lo
  llevó el propietario; no es una publicación de producción.
- El control de firma de producción se ejecutó y bloqueó correctamente la tarea:
  no hay `keystore.properties` ni las cuatro variables `NOVVIN_UPLOAD_*` configuradas.
  Se requiere la clave original de publicación antes de generar una entrega final.
- APK debug universal: unos 258 MiB; excede los presupuestos de tamaño, DEX y
  bibliotecas nativas definidos para producción. Contiene cuatro arquitecturas y
  código sin R8. Falta medir el AAB release optimizado y su descarga por dispositivo;
  no se ampliaron los presupuestos para ocultar estos resultados.

## Pendientes que necesitan otro entorno

1. Motorola y otro teléfono en Wi-Fi/datos de operadores diferentes: imagen,
   escucha humana, Bluetooth, pantalla bloqueada y cambio real de red.
2. Sesiones de 30–60 minutos o más en hardware físico: batería, temperatura,
   memoria y datos con escenas reales; la prueba actual dura cuatro minutos.
3. Carga representativa con usuarios, archivos y transmisiones simultáneas en
   un entorno aislado dimensionado para ello. Las lecturas de salud no la sustituyen.
4. Firma original, AAB optimizado, presupuestos de descarga y prueba de instalación
   de release antes de publicar. Debug no se presenta como entrega de producción.

Los seis puntos se trabajaron con el entorno disponible. Estos pendientes siguen
abiertos; no se certifica toda la APK ni ausencia absoluta de errores.

## Fuentes técnicas

- [Adaptación de video de WebRTC](https://webrtc.googlesource.com/src/+/HEAD/video/g3doc/adaptation.md).
- [Configuración de video de LiveKit](https://docs.livekit.io/transport/media/advanced/).
- [Llamadas Android](https://developer.android.com/develop/connectivity/telecom/voip-app).
- [Calidad de Android](https://developer.android.com/docs/quality-guidelines/core-app-quality).
- [Diez heurísticas de Nielsen](https://www.nngroup.com/articles/ten-usability-heuristics/).
- [Notificaciones Android de llamada](https://developer.android.com/develop/ui/compose/notifications/call-style).
- [Accesibilidad Android](https://developer.android.com/guide/topics/ui/accessibility/apps).
