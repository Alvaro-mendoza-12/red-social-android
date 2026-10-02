# Negociación de llamadas y selector de documentos

Fecha: 2026-10-02. Se mantiene Android 117 / 9.0.20.

## Llamadas entre redes

La prueba física detectó ofertas ICE nuevas cada vez que se reforzaba el audio. El refuerzo ahora conserva un peer sano o en negociación; las reconexiones reales de socket siguen teniendo su recuperación. Una llamada posterior generó una sola oferta inicial y cero eventos ICE de desconexión durante la muestra.

La señalización intenta primero WebSocket directo. Si falla ese intento, se crea el transporte polling con upgrade y se conservan el motor de audio, la autenticación y la afinidad de sala. El cliente Java requiere gestionar ese cambio explícitamente; [opciones oficiales de Socket.IO Java](https://socketio.github.io/socket.io-client-java/initialization.html). La prueba física utilizó WebSocket directo; no se forzó una red que lo bloquee en esta revisión.

Motorola con LTE y tablet con Wi-Fi transmitieron paquetes de audio en ambos sentidos. Los contadores aumentaron y terminaron sin pérdida reportada en esa muestra. Ambos pudieron finalizar; se conservó la app abierta y el Motorola mostró el resumen con «Cerrar» y «Enviar mensaje». Esto demuestra el camino de transporte, no sustituye una valoración humana del sonido ni prueba llamadas prolongadas o push con la app terminada. La variante QA de la tablet tampoco certifica FCM del paquete publicado en Play.

**El objetivo de arranque menor a un segundo sigue abierto.** La prueba aún muestra varios segundos entre aceptar y disponer de medios. Se continúa investigando la preparación de conexiones y el acceso a TURN. Los tiempos sanitizados se calculan dentro del reloj de cada dispositivo. En el Motorola, el tiempo desde marcar «llamar» incluye la espera hasta que la tablet contesta; no representa solo el arranque de medios. En la tablet, la marca precede al comando de pulsación de ADB y por tanto también incluye esa sobrecarga. No se restan relojes de dispositivos distintos.

## Documentos dentro de Novvin

El selector presenta documentos autorizados, búsqueda y categorías. «Agregar» abre una explicación del permiso y permite autorizar una carpeta o varios archivos. Después, explorar sus carpetas y seleccionar un documento se hace dentro de Novvin. No se solicita acceso global al almacenamiento.

Se inspeccionaron físicamente la lista y el diálogo de acceso en el Motorola. La lista conservó sus documentos anteriores y el archivo QA. La autorización inicial utiliza el selector de permisos de Android, requisito de acceso a documentos de otras aplicaciones: [documentación oficial](https://developer.android.com/training/data-storage/shared/documents-files). No se puede enumerar todo el disco automáticamente con los permisos normales de una red social.

## Bandeja y validación

En la cuenta ficticia de la tablet se probaron añadir/quitar favorito, filtrar favoritos, archivar/desarchivar y su filtro, fijar/desfijar, silenciar/reactivar avisos, crear una carpeta y agregar el chat a ella, y búsqueda con coincidencia y sin resultados. Los cambios de favorito, archivo, fijado y silencio se revirtieron en esa cuenta. La carpeta QA se conserva para seguir probando. No se alteraron estas preferencias en la cuenta real del Motorola.

La compilación debug/audit pasó **1.071 pruebas unitarias en 239 suites**, sin fallos, errores u omisiones. Ambas APK se instalaron conservando datos. [Resultados y hashes sanitizados](../evidencias-movil-llamadas-documentos-2026-10-02/).

El servidor respondió `/ready` correctamente; una petición desde el PC tardó 95 ms. Esa muestra no representa las latencias de móviles ni garantiza un límite para todas las rutas. Se siguen verificando mensajes y sus opciones, después grupos, comunidades, llamadas, voz y LIVE. No se declara producción completa ni 60 FPS.

## Reversión

Revertir el commit de esta revisión y recompilar Android restaura el transporte y selector anteriores. No hay migración ni cambio desplegado en el backend en este bloque.
