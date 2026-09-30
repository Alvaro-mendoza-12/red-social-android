# Auditoría de medios y enlaces

Proyecto exclusivo: `D:\red social - live-gifts`, rama `feature/live-gifts`. Fecha: 30/09/2026.

## Correcciones

- El emisor que responde a la negociación adopta primero los canales del otro equipo y después conecta cámara y micrófono. Evita canales duplicados sin negociar y conexiones ICE que terminaban sin imagen.
- La reconfirmación del micrófono compara la identidad nativa del transmisor. Comparar objetos Java distintos desconectaba la pista de audio. La identidad se obtiene antes de volver a consultar los transmisores: WebRTC libera las referencias de la consulta anterior.
- La fuente de audio se libera antes que la fábrica que posee su hilo. Las pruebas encontraron y reprodujeron un cierre nativo al terminar una sesión con el orden anterior.
- Una renovación de TURN no bloquea el inicio cuando las credenciales guardadas aún sirven. Una configuración ausente o vencida sí debe esperar su actualización.
- Los enlaces de salas de voz muestran progreso, error y reintento, permiten volver atrás y cerrar sin abandonar la sala. Se impide acceso a archivos/contenido local y contenido mixto. Una carga que supera 20 segundos ofrece recuperación.

## Pruebas reales

Las pruebas usaron cuentas sintéticas y el servidor HTTPS activo. Se actualizó el Motorola sin borrar sus datos ni cambiar su cuenta.

| Flujo entre Motorola y emulador | Imagen recibida | Audio recibido en ambos equipos | Primer fotograma en el receptor | FPS recibidos | RTT |
|---|---|---|---|---|---|
| Llamada forzada por TURN | Sí | Sí | 3,095 s | 9,73 | 268 ms |
| Sala de voz | Sí | Sí | 2,280 s | 9,66 | 4 ms |
| Directo | Sí | Sí | 3,137 s | 9,60 | 286 ms |

Estos tiempos son muestras de inicio, no retardo audiovisual medido. Las pruebas de audio verifican RTP entrante; no sustituyen escuchar una conversación entre dos personas. La cámara del Motorola entregó unos 10 FPS en estas condiciones; no se certifican 24/30 FPS ni ausencia de retardo.

- Motorola, llamada por push real: llegó estando fuera de la app, apareció el control para contestar, se pulsó desde la notificación de Android y el repositorio real alcanzó `CONNECTED`. La cuenta personal siguió siendo la misma. Notificación: 1,962 s; preparación de pulsación y conexión: 6,023 s (incluye abrir el panel de Android). La prueba canceló su llamada y eliminó su propia notificación remota.
- Motorola, prueba de medios con relay: cámara apagada/encendida y cambio de cámara transmitieron nuevos fotogramas, primer fotograma 2,205 s, candidatos relay/relay y audio en ambos sentidos.
- Dos videos publicados realmente en el servidor se reprodujeron, adelantaron y reanudaron con el reproductor, caché y perfil de carga usados por la app. Primeros fotogramas: 2,322 y 3,108 s en la primera muestra.
- Cuatro pruebas visuales: recuperación por correo, llamada entrante, tarjetas de rondas y error/reintento/cierre de enlaces. Aprobadas en el Motorola. Las rondas se comprobaron con fuente al 160 % y temas claros/oscuros.
- 903 pruebas unitarias Android aprobadas. Backend: 275 aprobadas de 276, cero fallidas y una omitida por plataforma.
- Ocho servicios del mini-PC `running/healthy`. Nueve lecturas/autorizaciones HTTPS aprobadas: 195–307 ms en la repetición posterior al despliegue.

El ensayo con dos motores en un solo teléfono mostró una ausencia intermitente de audio de retorno. Esa medición se registra sin presentarla como audio bidireccional; la prueba entre equipos separados sí exige recepción en ambos y pasó. Se conserva la comprobación obligatoria de imagen y audio entrante en el ensayo de un teléfono.

## Heurísticas aplicadas al visor de enlaces

1. Estado: progreso visible y final de carga.
2. Lenguaje comprensible: mensaje de conexión sin códigos técnicos.
3. Control: atrás y cerrar.
4. Consistencia: controles y colores Material.
5. Prevención: esquemas restringidos y espera acotada.
6. Reconocimiento: acciones visibles, sin pasos que recordar.
7. Eficiencia: reintento dentro de la misma sala.
8. Presentación: barra compacta y host abreviado si no cabe.
9. Recuperación: error explicado y botón de reintento.
10. Ayuda contextual: indicación de comprobar conexión o cerrar y seguir en la sala.

Esta revisión cubre los cambios y las pruebas enumeradas; no declara toda la aplicación certificada ni comparada bajo carga con otras redes sociales. Compartir pantalla, invitados/batallas por SFU y carga masiva requieren sus propias verificaciones.

Referencias: [ciclo de vida de transmisores WebRTC](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnection.java), [diez heurísticas de Nielsen](https://www.nngroup.com/articles/ten-usability-heuristics/).
