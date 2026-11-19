# APK y AAB Android 9.1.43 (183) con correcciones de acceso y salas

18 de noviembre de 2026. La candidata se compiló desde `725a4141561dfdad3e3f2e63fc50c8ed6b93c614`, en un checkout limpio y separado de las dos ramas. Incluye la búsqueda por visita de cuenta, la corrección de entrada y salida de salas, el reintento de comprobación de usuario al restaurar el registro y el borrado de contraseñas al recrear los formularios.

`assembleRelease` y `bundleRelease` terminaron en 24 min 43 s. APK y AAB pasaron los 14 controles del paquete y coinciden en 32 entradas DEX y nativas. Las firmas coinciden con el certificado de carga esperado. Lint Release terminó con cero errores y 346 advertencias. Los archivos están en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-release-check/725a4141561d/artifacts/`.

## Restauración del acceso

La APK se instaló en el emulador limpio. Tras mostrar una contraseña ficticia y terminar el proceso en segundo plano, Novvin volvió a abrir en un proceso nuevo. El correo se restauró y el campo de contraseña quedó vacío, con el control «Mostrar contraseña» visible. El ensayo se hizo en modo avión, sin enviar credenciales.

## Arranque en el emulador

Ambas rondas emplean arranque de proceso en frío registrado por `am start -W`, un emulador de API 36 en modo de solo lectura y red desconectada. Cada fila presenta seis muestras por versión, después de un arranque de preparación excluido del cálculo.

La referencia 182 corresponde a `b834cf0ff3465e26cce9198431afb3175b60196d`. Su prefijo de compilación coincide con la cadena localizada en el DEX del APK medido. Ambas versiones contienen cuatro archivos DEX y un perfil de referencia para ART. Los DEX suman 30.646.204 bytes en la 182 y 30.832.600 bytes en la 183.

| Orden | Mediana v182 | Mediana v183 | Diferencia de v183 |
| --- | ---: | ---: | ---: |
| 182 → 183 | 2,543 s | 4,148 s | +63,1 % |
| 183 → 182 | 2,203 s | 3,126 s | +41,9 % |

La v183 arrancó más despacio en las dos rondas. Queda abierta la atribución del coste y su contraste en un Android físico. La muestra mide el primer fotograma de la actividad fuera de red.

## Trazas de arranque

Se capturaron cuatro arranques en frío con GPU del host, modo avión y Perfetto. Cada captura se asoció con el PID de Novvin y pasó el control de pérdidas del trazado. Las duraciones de las fases anidadas se muestran de forma independiente.

| Versión | Primer fotograma (ms) | Inicio de `bindApplication` (ms) | Duración de `bindApplication` (ms) | Tramo `doFrame` observado (ms) | CPU del hilo principal (ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 182, traza 1 | 2124 | 734 | 495 | 253 | 456 |
| 182, traza 2 | 1105 | 229 | 261 | 231 | 354 |
| 183, traza 1 | 4832 | 2152 | 762 | 570 | 522 |
| 183, traza 2 | 3793 | 1064 | 1019 | 580 | 741 |

En la 183, `bindApplication` empezó más tarde y duró más en las dos capturas. También creció el tiempo del primer fotograma. La apertura de DEX tardó 77–174 ms en la 182 y 122–443 ms en la 183. La variación entre capturas dejó abierta la atribución a una función concreta.

[Método de captura con Perfetto en Android](https://perfetto.dev/docs/learning-more/android).
En el emulador alternativo, `dexopt` confirmó `speed-profile` en ambos APK. La mediana fue 1554 ms para la 182 y 2962 ms para la 183. Este contraste de ART usa un perfil de emulador propio. [Método de medición de perfiles ART](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure).

## Restauración del registro

El identificador ficticio se comprobó antes de entrar a la etapa final. Tras recrear el proceso sin conexión se conservó esa etapa y apareció «Reintentar comprobación». El primer reintento tras reactivar Wi-Fi mantuvo el error. Falta repetir con una conexión validada desde Android antes de pulsar el control. Se preservaron ambos estados de la pantalla y el recibo. No se envió el alta.

Una consulta independiente de disponibilidad desde Windows devolvió HTTP 200 con TLS válido y el identificador ficticio esperado en 154 ms. El recibo conserva esta lectura separada del ensayo en Android.

## Evidencia y pendientes

El recibo de compilación, las comprobaciones de binarios y los intentos de dispositivo conservan sus propios resultados bajo `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-release-check/725a4141561d/`. La recreación de la etapa final de registro con conexión, el uso físico de micrófono y la sesión autenticada de extremo a extremo siguen en el registro de la auditoría. También siguen pendientes el despliegue de los cambios del servidor a Ubuntu y la entrega en Google Play.

Las ramas estables incorporaron después las correcciones de llamadas del commit `4196f510e21301a2dfd31f4615a263a0f1bd70a4`. La APK y el AAB de esta ficha conservan su huella y su alcance del commit `725a4141561d`.
