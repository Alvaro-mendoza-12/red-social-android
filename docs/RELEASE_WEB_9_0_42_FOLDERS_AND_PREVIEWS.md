# Novvin Web 9.0.42 — carpetas, envíos y vistas previas

Fecha: 16 de octubre de 2026.

## Cambios

1. «Adjuntar carpeta» disponible desde el menú de adjuntos en chats, grupos y comunidades. El selector conserva rutas relativas y comprime automáticamente, con las mismas partes ZIP independientes que el arrastre de carpetas. El selector del navegador no informa carpetas vacías; el arrastre sigue preservándolas.
2. Los mensajes fallidos guardan un aviso comprensible en la cola local. Un rechazo de verificación explica que se pruebe otro archivo o partes menores, sin mostrar códigos del servidor. La explicación persiste al recargar y se limpia al reintentar. Se mantiene la protección del antivirus.
3. Las vistas previas locales de mensajes pendientes están ligadas a la identidad activa de la cuenta; se borran al cambiar la sesión. «Cargar vista previa» se distingue de «Reintentar» el envío.
4. Vista previa de las publicaciones del perfil al pasar el mouse o enfocar su enlace con teclado. Las imágenes usan una variante de tamaño moderado; los videos se previsualizan sin sonido. Escape, salir del elemento o cambiar de tamaño cierran la vista.
5. Controles de salas de voz compactos en móvil, conservando sus nombres accesibles y estados. No cambia el transporte de audio o video.

## Pruebas y límites

### Cierre verificado de la entrega

- Producción web 9.0.42: Vercel `dpl_3KQMoSg9PYK3k2KAg61sBYNXu85c`, estado READY y alias `https://red-social-android.vercel.app`.
- Envío y descarga **reales** desde Chrome: ZIP de 73400462 bytes y carpeta de 141557869 bytes de origen. El ZIP descargado coincide exactamente con su SHA-256 original; los cuatro archivos de la carpeta conservan sus rutas y SHA-256 al descomprimir. El selector de carpeta es el del navegador. Estos casos superan los límites anteriores de 60 y 128 MiB; no prueban todos los archivos posibles de 500 MiB.
- 494 pruebas web en 82 suites, compilación correcta y lint del código modificado sin errores ni avisos. Auditoría de dependencias de producción sin vulnerabilidades comunicadas.
- 44 pruebas de SEO, 48 controles HTTP de producción y 56 combinaciones de páginas públicas y tamaños en cada navegador, Chrome y Brave. IndexNow aceptó las 14 URL con HTTP 200.
- Recorte libre, ocho controles, texto y acción de guardar accesible a 1440×960, 390×844 y 844×390. Vista previa del perfil con mouse, teclado, imagen decodificada y cierre mediante Escape. Esta regresión de interfaz usa API e imagen sintéticas sobre el paquete publicado; la carga real del avatar se conserva en los recibos 9.0.41.
- Música privada comunitaria y música de perfil conservadas después de un nuevo inicio de sesión; el audio original conserva sus bytes. La primera prueba consultó por error una pista privada mediante la ruta pública y obtuvo el 404 esperado. La repetición usa `/v1/data/music/my`; no se cambió la política de acceso a pistas privadas.
- La comprobación de limpieza descubrió registros musicales que permanecían al eliminar una cuenta. Se corrigió la eliminación transaccional de sus pistas, limitada al UUID local autenticado. La nueva API pasó 816 pruebas, con 6 casos omitidos que requieren servicios separados; se desplegó con copia de seguridad verificada y comprobación de hashes, configuración y salud. Una activación inicial fue revertida automáticamente; se conserva su recibo fallido y el de la activación posterior aprobada.
- La prueba real posterior confirma revocación de la cuenta eliminada y conservación de la biblioteca y audio de otra cuenta. Las cinco cuentas temporales nuevas tienen comprobación de base de datos: cero usuarios, archivos, pistas, sesiones, mensajes o chats restantes. Los dos registros privados de ensayo anteriores al arreglo se retiraron con una operación limitada a sus propietarios temporales ya eliminados y sin referencias en momentos.
- Android 9.0.35 (132): APK release compilado y firma verificada, 1667 pruebas unitarias sin fallos; lint sin errores fatales ni errores, con 282 advertencias registradas, 262 de ellas de uso de extensiones KTX. No se instaló esta compilación en un dispositivo físico durante esta entrega.

El estado integral y los pendientes numerados están en [WEB_PRODUCTION_STATUS_9_0_42.md](WEB_PRODUCTION_STATUS_9_0_42.md). Las comprobaciones siguientes describen los pasos anteriores y conservan sus límites originales.

La entrega registra pruebas unitarias, compilación, navegador y comprobaciones de bytes en `audit/2026-10-07-integral/web-9042-*`. El selector real del navegador comprimió una carpeta de 141557869 bytes (135 MiB) y se verificó independientemente el SHA-256 de sus cuatro archivos al descomprimir el ZIP. Esa prueba de la interfaz utilizó una API sintética: no se presenta como entrega real al servidor. También se comprobó que el aviso de verificación fallida persiste al recargar y que el reintento manual funciona cuando el servidor de prueba vuelve a aceptar.

El servidor desplegado incluye la corrección SQL de propiedad del avatar descrita en la entrega 9.0.41. La API pasó 812 pruebas con 6 casos omitidos que requieren servicios de prueba separados; se mantienen recibos históricos de las repeticiones fallidas y su causa.

El ZIP inicial de ensayo contenía 70 MiB de una misma letra. El antivirus lo rechazó por `Heuristics.Limits.Exceeded.MaxScanTime`; no se cambió la política ni se publicó el archivo como limpio. Los siguientes ensayos usan archivos binarios generados, con hashes conocidos, para representar un archivo grande habitual y una carpeta mayor de 128 MiB. No se afirma que cualquier archivo de hasta 500 MiB siempre supere la verificación.

Las nuevas cuentas de prueba alcanzaron el límite de diez registros por hora. Se respeta el reinicio normal de la ventana; no se cambia el límite ni se evita usando otra dirección. La extensión del navegador del propietario requiere acceso a archivos locales para automatizar el selector; ese permiso no se habilitó. Esto no es un requisito de la web para adjuntar manualmente.

La aceptación global del móvil y servidor sigue diferenciada de las pruebas de esta entrega. Los pendientes vigentes se detallan en `RELEASE_WEB_9_0_41_DOCUMENTS_AND_MEDIA.md` y `WEB_PRODUCTION_AUDIT_2026_10_16.md`; no se reemplazan por una certificación total sin evidencia.
