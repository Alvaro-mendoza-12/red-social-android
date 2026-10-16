# Novvin Web 9.0.42 — carpetas, envíos y vistas previas

Fecha: 16 de octubre de 2026.

## Cambios

1. «Adjuntar carpeta» disponible desde el menú de adjuntos en chats, grupos y comunidades. El selector conserva rutas relativas y comprime automáticamente, con las mismas partes ZIP independientes que el arrastre de carpetas. El selector del navegador no informa carpetas vacías; el arrastre sigue preservándolas.
2. Los mensajes fallidos guardan un aviso comprensible en la cola local. Un rechazo de verificación explica que se pruebe otro archivo o partes menores, sin mostrar códigos del servidor. La explicación persiste al recargar y se limpia al reintentar. Se mantiene la protección del antivirus.
3. Las vistas previas locales de mensajes pendientes están ligadas a la identidad activa de la cuenta; se borran al cambiar la sesión. «Cargar vista previa» se distingue de «Reintentar» el envío.
4. Vista previa de las publicaciones del perfil al pasar el mouse o enfocar su enlace con teclado. Las imágenes usan una variante de tamaño moderado; los videos se previsualizan sin sonido. Escape, salir del elemento o cambiar de tamaño cierran la vista.
5. Controles de salas de voz compactos en móvil, conservando sus nombres accesibles y estados. No cambia el transporte de audio o video.

## Pruebas y límites

La entrega registra pruebas unitarias, compilación, navegador y comprobaciones de bytes en `audit/2026-10-07-integral/web-9042-*`. El selector real del navegador comprimió una carpeta de 141557869 bytes (135 MiB) y se verificó independientemente el SHA-256 de sus cuatro archivos al descomprimir el ZIP. Esa prueba de la interfaz utilizó una API sintética: no se presenta como entrega real al servidor. También se comprobó que el aviso de verificación fallida persiste al recargar y que el reintento manual funciona cuando el servidor de prueba vuelve a aceptar.

El servidor desplegado incluye la corrección SQL de propiedad del avatar descrita en la entrega 9.0.41. La API pasó 812 pruebas con 6 casos omitidos que requieren servicios de prueba separados; se mantienen recibos históricos de las repeticiones fallidas y su causa.

El ZIP inicial de ensayo contenía 70 MiB de una misma letra. El antivirus lo rechazó por `Heuristics.Limits.Exceeded.MaxScanTime`; no se cambió la política ni se publicó el archivo como limpio. Los siguientes ensayos usan archivos binarios generados, con hashes conocidos, para representar un archivo grande habitual y una carpeta mayor de 128 MiB. No se afirma que cualquier archivo de hasta 500 MiB siempre supere la verificación.

Las nuevas cuentas de prueba alcanzaron el límite de diez registros por hora. Se respeta el reinicio normal de la ventana; no se cambia el límite ni se evita usando otra dirección. La extensión del navegador del propietario requiere acceso a archivos locales para automatizar el selector; ese permiso no se habilitó. Esto no es un requisito de la web para adjuntar manualmente.

La aceptación global del móvil y servidor sigue diferenciada de las pruebas de esta entrega. Los pendientes vigentes se detallan en `RELEASE_WEB_9_0_41_DOCUMENTS_AND_MEDIA.md` y `WEB_PRODUCTION_AUDIT_2026_10_16.md`; no se reemplazan por una certificación total sin evidencia.
