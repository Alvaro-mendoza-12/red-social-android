# Música comunitaria, audio de Momentos y batallas Cross-LIVE

Fecha inicial: 2026-08-14  
Última actualización: 2026-08-21  
Rama: `feature/live-gifts`  
Estado: implementado para prueba cerrada; requiere migraciones, moderación y configuración externa antes de producción.

## 1. Audio de Momentos

El visor usa un único reproductor Media3 por Momento y lo libera con el ciclo de vida de la pantalla. La reproducción:

- espera a que la imagen y la música estén preparadas antes de iniciar el progreso;
- usa atributos de audio de contenido musical y volumen explícito;
- respeta `musicStartMs`, `musicEndMs`, `musicVolume` y `originalAudioVolume`;
- reinicia únicamente el fragmento seleccionado;
- reintenta de forma acotada ante un fallo transitorio;
- pausa y libera el reproductor al cambiar de Momento o abandonar el visor;
- utiliza la caché compartida de medios para no descargar la misma pista repetidamente.

Una imagen dura exactamente **6.000 ms**. Si tiene música, el fragmento reproducido también se limita a esos mismos 6.000 ms y se detiene al finalizar la imagen. Android y servidor aplican la misma política; el temporizador comienza únicamente después de que Coil haya cargado la imagen y Media3 haya preparado el audio, por lo que una descarga o decodificación lenta ya no consume parte del tiempo visible. El servidor normaliza también Momentos antiguos de 2 o 3 segundos.

El pager conserva la señal de preparación de los Momentos adyacentes precargados. No debe reiniciarla al cambiar de página: Coil y Media3 pueden no emitir por segunda vez `onSuccess` o `STATE_READY`, lo que dejaría el reloj detenido. Las URL de imagen y música se reescriben al endpoint Ubuntu descubierto en tiempo de ejecución para evitar hosts temporales vencidos.

La música local ya no se intenta subir a `moment-media`. Ese bucket acepta imágenes y videos, por lo que enviar `audio/mpeg` producía `Unsupported content type`. La música aportada por usuarios usa exclusivamente `community-music` y pasa por revisión antes de poder adjuntarse a un Momento público.

## 2. Catálogo musical aportado por usuarios

### Flujo

1. Desde **Crear Momento > Añadir música > Mi música**, Android muestra la biblioteca MediaStore del teléfono con título, artista, álbum, duración y portada cuando existen.
2. Para hacerla reutilizable, el usuario continúa a **Publicar mi música para la comunidad**; no se publica una URL local ni se salta la declaración de derechos.
3. Android valida contenedor, MIME, tamaño (máximo 80 MB) y duración (5 segundos a 15 minutos).
4. El usuario completa título, artista, género, estado explícito, permisos de reutilización y declaración de derechos; los metadatos detectados solo sirven como borrador editable.
5. El archivo se almacena en `community-music`; la API verifica que el objeto pertenece al usuario y que firma, MIME, tamaño y ruta coinciden.
6. La pista queda `PENDING_REVIEW`; no aparece en Comunidad ni puede usarse en Momentos de terceros.
7. Un moderador puede aprobar, solicitar cambios, rechazar, desactivar o retirar.
8. Solo `APPROVED` + visibilidad pública + `allow_reuse=true` aparece en Comunidad y puede publicarse en un Momento.

El selector de biblioteca es un diálogo anidado dentro del flujo de publicación. Al confirmar una pista entrega el resultado al formulario y cierra únicamente la biblioteca; nunca debe cerrar también el formulario padre. **Usar la canción completa** confirma la selección en un toque. Después se muestra la revisión de metadatos y el botón **Publicar para la Comunidad**.

La captura desde **Crear Momento > Cámara > Tomar foto** conserva la URI de destino con estado guardable mientras la cámara externa está abierta. Al volver, copia la captura a caché privada, abre el recorte y continúa al editor; una recreación de Activity ya no devuelve al usuario a la galería.

El servidor ignora título, autor, licencia y URL musical enviados por el cliente al publicar un Momento. Para `novvin_community` vuelve a cargar el registro aprobado por UUID y guarda los datos autoritativos. No se aceptan URLs arbitrarias.

### Estados y derechos

Estados: `UPLOADING`, `PROCESSING`, `PENDING_REVIEW`, `APPROVED`, `REJECTED`, `CHANGES_REQUIRED`, `DISABLED`, `TAKEDOWN_PENDING`, `REMOVED`.

Declaraciones iniciales: autor original, licenciatario autorizado, dominio público o CC0. La aceptación queda auditada con versión de términos, evidencia, hash de IP y agente de usuario. La versión actual `community-music-2026-08-14-draft` es un borrador técnico y **debe ser revisada por asesoría legal antes de habilitar publicación pública**.

### API

- `GET /v1/data/music/terms`
- `POST /v1/data/music/upload`
- `PATCH /v1/data/music/:trackId`
- `POST /v1/data/music/:trackId/publish`
- `GET /v1/data/music/:trackId/status`
- `GET /v1/data/music/my`
- `GET /v1/data/music/dashboard`
- `GET /v1/data/music/search`
- `GET /v1/data/music/recommendations`
- `GET /v1/data/music/tracks/:trackId`
- favoritos, seguimiento de artista, inicio/final de escucha, reportes y retirada del creador;
- cola y decisión de moderación bajo rutas de administración.

`GET /ready` debe anunciar `community_music_v1`, `moment_music_v1` e `image_moments_exact_6s_v1`. Android rechaza un túnel que responda HTTP 200 pero no anuncie esas capacidades, y el despliegue falla si alguna falta. Esto evita que una API antigua parezca saludable mientras devuelve `404 NOT_FOUND` en funciones nuevas.

Las escuchas requieren sesión única y tiempo reproducido verificable antes de contarse. Tres reportes abiertos de usuarios distintos llevan preventivamente la pista a retirada pendiente.

### Persistencia

Aplicar `sql/migrations/20260814_community_music_catalog.sql`. Crea pistas, declaraciones de derechos, historial de moderación, favoritos, seguimientos, escuchas, reportes, índices, RLS cerrada y el bucket con tipos de audio permitidos.

## 3. Batalla entre dos LIVE reales

La batalla Cross-LIVE no reutiliza una tarjeta local ni duplica al mismo invitado. El flujo es:

1. El creador consulta otros directos activos con batallas habilitadas.
2. Envía una invitación con caducidad de dos minutos.
3. El segundo creador acepta o rechaza desde su propia sesión LIVE.
4. Al aceptar, el servidor asigna una sala compartida `novvin-battle-{battleId}`.
5. Host y audiencia de ambos directos reciben tokens LiveKit de corta duración para esa misma sala.
6. Cada creador publica cámara/micrófono y se suscribe al rival; la UI renderiza pista real o avatar si la cámara está apagada.
7. Eventos de puntuación idempotentes actualizan ambos lados y se notifican a los dos directos.
8. Cualquiera de los dos hosts puede terminar; se calcula victoria o empate y ambos regresan a su sala normal.

La API impide que un LIVE participe simultáneamente en dos invitaciones/batallas activas y valida propiedad del directo al invitar, responder y finalizar.

Aplicar `sql/migrations/20260814_cross_live_battles.sql`.

Variables requeridas, solo en servidor:

- `LIVE_GUESTS_BATTLES_ENABLED=true`
- `LIVE_SFU_ENABLED=true`
- `LIVEKIT_URL`
- `LIVEKIT_API_KEY`
- `LIVEKIT_API_SECRET`
- token interno para eventos de puntuación
- signaling y TURN accesibles desde redes externas.

## 4. Evidencia del 14 de agosto

- `:app:compileDebugKotlin`: PASS.
- `:app:testDebugUnitTest`: PASS.
- `:app:assembleDebug`: PASS.
- `:app:lintDebug`: PASS, 0 errores.
- `npm run check`: PASS.
- `test:moments`: 7/7.
- `test:music`: 3/3.
- colaboración/batalla/LiveKit y moderación LIVE: PASS.
- instalación de actualización en `emulator-5554`: PASS, sesión y datos conservados.
- Momento de imagen con pista visible después de 4 segundos y `AudioTrack` activo sin excepción: PASS.
- recorrido Social y visor sin `FATAL EXCEPTION`: PASS.
- selector final: catálogo Commons, preescucha con foco de audio y entrada de publicación comunitaria: PASS.
- APK debug: 308.390.533 bytes; SHA-256 `C4A810028C13CB2B5EA7407D86342BD542305F415F0A80CBF0670D187DAB8FF3`.

## 5. Puertas pendientes de producción

No se declara producción pública todavía. Se necesita:

- aplicar ambas migraciones en staging y ejecutar rollback ensayado;
- revisión legal de términos, copyright, retirada y apelación;
- moderadores reales y SLA de denuncias;
- prueba host A + host B + espectador en dos o tres teléfonos físicos y redes distintas;
- verificar voz bidireccional, cambio frontal/trasera/pantalla, cámara apagada, pérdida de red y retorno sin pantalla negra;
- prueba de carga de búsqueda, escuchas, reportes y eventos de batalla;
- métricas/alertas para preparación de audio, errores Media3, joins LiveKit, reconnects y latencia de puntuación.

El sondeo Android de batalla es válido para prueba cerrada, pero antes de una escala grande debe sustituirse o complementarse con el canal realtime de colaboración ya existente, con backoff y recuperación por snapshot autoritativo.
