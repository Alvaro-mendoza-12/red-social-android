# Estado de producción — Novvin Web 9.0.42

Comprobación: 16 de octubre de 2026. Web publicada: https://red-social-android.vercel.app.

## Dictamen

**Entrega aprobada en los recorridos comprobados; certificación integral pendiente.** No se declara que todos los apartados del móvil, la web y la operación del servidor estén certificados. La aceptación del propietario para llamadas, videollamadas, salas y directos móviles se conserva como aceptación del usuario, diferenciada de las pruebas automatizadas.

## Verificado en esta entrega

| Apartado | Evidencia y resultado |
|---|---|
| Documentos y carpetas | Dos cuentas reales envían y descargan un ZIP de 70 MiB y una carpeta de 135 MiB. SHA-256 exacto del ZIP y de los cuatro archivos descomprimidos. El chat permanece utilizable después de rechazar un adjunto demasiado grande; se conserva el borrador. |
| Límites | Documentos hasta 500 MiB por archivo, partes ZIP independientes para carpetas grandes, 5000 entradas y 32 niveles. Imagen/audio conservan 60 MiB. Videos enviados en mensajes hasta 600 segundos con tolerancia de contenedor de 250 ms. Antivirus y autorizaciones siguen aplicándose. El caso real mayor probado aquí es la carpeta de 135 MiB. |
| Interfaz | Recorte libre con ocho controles y texto; guardado accesible en vertical, horizontal y escritorio. Vista previa de publicaciones con teclado y mouse. 494 pruebas web; compilación y lint del código modificado correctos. |
| Identidad y fotos | Arreglada y desplegada la consulta SQL de propiedad de nuevos avatares. La prueba real carga, guarda y amplía el avatar del contacto sin salir del chat. Esto no recupera objetos históricos ausentes. |
| Persistencia | Favorito de sticker conservado tras nuevo inicio de sesión. Música privada comunitaria y música de perfil recuperadas desde su biblioteca tras nuevo inicio de sesión, con bytes originales. La prueba no equivale a recuperar todas las pistas históricas. |
| Eliminación de cuenta | Corrección de registros musicales huérfanos, probada con dos cuentas reales: eliminar una revoca su acceso y conserva la música de la otra. Cinco cuentas de ensayo y sus recursos comprobados eliminados en base de datos. |
| Servidor | API `novvin-api-9.0.35-438184eab74a`, fuente `438184eab74a200ad658c40ed063e2c1b293d2c45b6d52f22975e2aeaa879399`, commit `2d6696b5`. 816 pruebas aprobadas y 6 omitidas por servicios de prueba adicionales. Imagen, configuración efectiva, otros servicios y copia de seguridad verificados en el despliegue. `/ready` responde también por la dirección pública con TLS válido. |
| Android | 9.0.35, código 132. Compilación release y firma verificadas; 1667 pruebas sin fallos. Lint: 0 errores y 282 advertencias registradas. No hubo instalación ni prueba física de este nuevo APK. |
| SEO técnico | 14 páginas públicas con HTML, metadatos, datos estructurados, marca, logo, canónicas y sitemap. 44 pruebas y 48 controles HTTP aprobados. 56 combinaciones de página y resolución aprobadas en cada navegador: Chrome y Brave. |
| Entrega a buscadores | IndexNow aceptó las 14 páginas. Publicada la guía visible para «Novvyn» y otras variantes de Novvin. No hay confirmación de nuevas posiciones: Brave presentó CAPTCHA, que no se resolvió; las observaciones posteriores de Bing y DuckDuckGo no mostraron el sitio. No se sustituyen estas observaciones por resultados anteriores ni se promete una primera posición. |

La primera activación de la última API falló y el procedimiento restauró la imagen anterior. La activación posterior pasó todos los controles. Se conservan ambos recibos; no se atribuye una causa definitiva al primer intento sin evidencia suficiente.

## Pendientes para la certificación integral

1. **Dispositivo y redes:** instalar la versión exacta de Android 9.0.35 y comprobar cámara QR, descarga de archivos grandes y restauración de stickers después de reinicio/cierre de sesión. La aceptación previa del propietario no sustituye la prueba de este nuevo binario.
2. **Inventario funcional:** cerrar cada opción pendiente de web y móvil con una aceptación vigente: permisos/historial de grupos y comunidades, menciones, notificaciones con aplicación cerrada y demás opciones no recorridas en esta entrega.
3. **Multimedia histórica:** reproducir la pista concreta que desaparece y comprobar objetos/referencias históricos. La persistencia de una pista nueva no demuestra que existan todos los archivos antiguos.
4. **Operación:** completar recuperación desde copia y carga representativa del conjunto actual. Salud, copia verificada y reversión de una activación no constituyen un ensayo integral de continuidad o capacidad.
5. **Compatibilidad:** ejecutar acceso, envíos, pendientes y multimedia en Firefox y Safari si se anuncian como compatibles. Las pruebas actuales corresponden a Chrome y Brave.
6. **Buscadores:** observar el siguiente rastreo y el nombre, logo y fragmento realmente publicados por cada buscador. La posición es externa y variable; no se certifica un puesto permanente ni la actualización en una fecha concreta.

## Recibos

Directorio local: `audit/2026-10-07-integral/`. Resumen final con hashes: `web-9042-final.json`.

- `web-9042-real-delivery.json`, `web-9042-real-download-bytes.json`, `web-9042-real-music-library.json`, `web-9042-real-account-isolation.json` y `web-9042-qa-cleanup.json`.
- `web-9042-production-interface.json`, `web-9042-local-folders.json`, `web-9042-client-folder-bytes.json`, `web-9042-real-qr.json`.
- `web-9042-tests-final.log`, `web-9042-build-final.log`, `web-9042-lint-final.log`, `web-9042-dependencies.json`.
- `web-9042-production-seo.json`, `web-9042-seo-final.log`, `web-9042-brave-public.json`, `web-9042-chrome-public.json`, `web-9042-indexnow.json`, `web-9042-search-observations.json`.
- `web-9042-music-cleanup-api-rollout-first.json`, `web-9042-music-cleanup-api-rollout.json`, `web-9042-music-cleanup-api-release.json`, `web-9042-public-ready-final.json`.
- `android-9035-release-result.json`, `android-9035-unit-result.json`, `android-9035-apksigner.log`.

Referencias: [Google: nombre del sitio y actualización](https://developers.google.com/search/docs/appearance/site-names), [Google: repetición artificial de palabras](https://developers.google.com/search/docs/essentials/spam-policies#keyword-stuffing), [IndexNow](https://www.indexnow.org/documentation).
