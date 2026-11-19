# 210 · Audio y video tras reconectar sala, llamada y directo

19 de noviembre de 2026. Se ensayaron los servicios de API y señalización del commit `eba72cdce26d81653652badf5235a9bd62f30717` con PostgreSQL 17 en una instancia local aislada. Dos extremos Chromium con cuentas ficticias dependieron de esos servicios para intercambiar las ofertas y respuestas SDP. En cada extremo se midieron la señal PCM decodificada y los cuadros de video entrantes.

La sesión de voz se creó mediante la API. La llamada directa usó un chat real de las cuentas de ensayo y pasó por preparación y aceptación. En el directo se conectaron el anfitrión y un espectador. En cada recorrido se desconectó y volvió a conectar el primer participante, se comprobó la lista de participantes y se estableció otra conexión de medios. El recorrido terminó con la limpieza de las cuentas de ensayo y del proceso de PostgreSQL.

| Recorrido | Momento | Hasta recibir medios en ambos extremos (ms) | Tono A / B (Hz) | Cuadros recibidos A / B |
| --- | --- | ---: | ---: | ---: |
| Sala de voz | Entrada | 901 | 445 / 422 | 3 / 3 |
| Sala de voz | Reconexión | 565 | 422 / 422 | 3 / 3 |
| Llamada directa | Entrada | 704 | 445 / 445 | 3 / 4 |
| Llamada directa | Reconexión | 737 | 445 / 445 | 3 / 4 |
| Directo | Entrada | 711 | 422 / 422 | 3 / 3 |
| Directo | Reconexión | 600 | 445 / 422 | 3 / 3 |

El cronómetro comienza antes de crear la oferta y termina cuando los dos extremos reciben la señal y al menos tres cuadros. El estímulo fue un tono de 440 Hz y un video de 160 × 120. La estimación por cruces de cero usa 2.048 muestras. La comprobación de energía usó PCM recibido con salida local silenciada. Son seis observaciones funcionales en loopback. Los tiempos incluyen negociación y decodificación en el mismo equipo.

El primer entorno carecía de la operación de base de datos necesaria para el borrado final de las cuentas. Se repitió en un clúster nuevo, con las operaciones del proyecto 10, 15, 20, 22 y 16 y un rol de aplicación sin privilegios administrativos. Los primeros intentos del arnés de medios fallaron en la extracción del evento, el permiso de la página de prueba o la medición de audio. Se conservaron por separado. La prueba final usó una página local propia y mantuvo la política de permisos del servidor de señalización.

La API emitió credenciales TURN de ensayo. El transporte de estos medios fue directo. Permanecen pendientes el tránsito efectivo por relé, una red móvil, la interrupción por bloqueo del teléfono, la conmutación de cámara y micrófono físicos, los receptores Android y los escenarios con varios dispositivos de la misma cuenta.

El [recibo de medios](evidencias/210-medios-rtc-locales.json) conserva medidas por sentido, estados, códecs y huellas del arnés. Los archivos de reproducción y los intentos completos están en `D:/Novvin-Entregas/evidencias/2026-11-19-android-signaling-loopback/`.
