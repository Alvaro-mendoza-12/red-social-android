# Novvin 190, dominio de marca y procedencia de Ubuntu

27 de noviembre de 2026 · La revisión integral sigue abierta. Esta entrega continúa las verificaciones de [entrada pública y producción](257-auditoria-produccion-y-entrada-web-2026-11-27.md) y de [notificaciones y Ajustes](258-notificaciones-ajustes-y-restauracion-2026-11-27.md).

## Android

La APK y el AAB de Novvin 9.2.1 (190) se construyeron a partir de `d224ca7d1cecbf45cd4b49af96783327a9faae72`. Incluye la corrección del contador de notificaciones al confirmar lecturas o eliminaciones. La revisión de código y pruebas focalizadas está en el informe 258. El primer ensayo completo terminó antes de cerrar todos los casos. La prueba de caché de vídeo registró un error de SQLite al abrir su archivo temporal y un proceso de Gradle terminó antes de tiempo. Con un directorio temporal corto en Windows, pasaron las tres pruebas de caché de vídeo y las nueve del contador. La segunda ejecución completa volvió a terminar por la salida inesperada del proceso de Gradle. El candidato aún no tiene una suite JVM completa aprobada.

El emulador Android 16 actualizó la instalación de 189 a 190. Arrancó sin conexión y no mostró un aviso de actualización falso. Con conexión permitió entrar como invitado, abrir Juegos y Perfil y volver al formulario de inicio. El registro de fallos de la aplicación quedó vacío. Se verificaron la firma, API objetivo 36, 16 KB para los binarios nativos y la integridad del AAB con Bundletool.

## Dominio de marca

Se guardaron en GoDaddy el TXT de titularidad de Firebase Hosting y los dos TXT de desafío para el certificado de `novvinapp.com` y `www.novvinapp.com`. Ambos servidores autoritativos de GoDaddy y los resolutores 1.1.1.1 y 8.8.8.8 devolvieron los tres valores esperados. Se verificó también que siguen publicados los dos MX de correo y los A del reenvío anterior.

La prueba directa contra la IP de Firebase obtuvo HTTPS válido para ambos nombres. El dominio raíz sirvió la portada, Videos, Privacidad y la asociación de Android. El inicio de sesión llegó al formulario de Ubuntu con la consulta intacta. Firebase aún devuelve 404 para www y espera su CNAME para completar la titularidad. Mientras se coordina el cambio de DNS, la [entrada pública de Firebase](https://novvin-entrada-web.web.app/) sirve esas páginas. [Configuración avanzada de Firebase Hosting](https://firebase.google.com/docs/hosting/custom-domain).

## Web de aplicación en Ubuntu

El directorio activo tiene 307 archivos. Los 198 archivos de la compilación web en línea archivada el 23 de noviembre coinciden byte a byte. La instalación contiene además 109 archivos, incluidos recursos de compatibilidad y tres de Capy SSO. La corrección de Ajustes comprobada en el informe 258 sigue en la compilación local. El despliegue de esa corrección requiere conservar las rutas de autorización y comprobar los recursos que consumen las sesiones existentes.

## API en Ubuntu

La consulta de solo lectura compara cada JavaScript de la imagen activa con un commit exacto. Normaliza finales de línea, registra por separado cambios, ausencias y archivos adicionales, y comprueba de forma independiente el marcador que declara `/ready`. Se validaron seis controles unitarios, entre ellos un directorio de trabajo modificado y un marcador desactualizado.

| Referencia Git | Coinciden | Cambiaron | Faltan en Ubuntu | Solo en Ubuntu |
| --- | ---: | ---: | ---: | ---: |
| Estable `23444cb4` | 149 | 32 | 4 | 17 |
| Etiqueta OCI `5eab22a0` | 150 | 19 | 0 | 29 |
| Revisión anunciada por `/ready`, `cc6a022e` | 163 | 12 | 0 | 23 |
| Rama experimental `codex/experiencias-con-proposito` | 157 | 20 | 0 | 21 |

El contenedor tiene 198 fuentes JavaScript y respondió listo durante la comparación inicial. La referencia que devuelve `/ready` es la que coincide con más fuentes, pero el resultado descarta paridad con esa revisión. Los 17 archivos sin correspondencia estable incluyen integraciones de Capy y funciones de la rama experimental. El despliegue íntegro requiere reconciliar esos consumidores y comprobar las dependencias y migraciones correspondientes. Esta comparación se limita a JavaScript y deja abiertas la paridad de configuración, datos y servicios restantes.

El contenedor ejecuta Node 22.23.4. Su package.json y su archivo de bloqueo coinciden con la rama estable. Se comprobó la versión de 451 entradas instaladas de producción, incluidas las 22 dependencias directas. Otras 23 entradas opcionales no están instaladas. Esta comprobación mide paridad con el archivo de bloqueo y se complementa con la auditoría npm registrada en el informe 257.

## Protección de archivos en Ubuntu

La comprobación posterior obtuvo /health 200 y /ready 503. PostgreSQL aceptaba conexiones. Los registros de la API identificaron firmas vencidas de ClamAV. El proveedor seguía anunciando la versión 28194, publicada el 25 de noviembre a las 06:26 UTC. FreshClam la reconocía como la última disponible, aunque ya excedía las 48 horas exigidas por la política de almacenamiento.

Las tres imágenes candidatas incluyen ClamAV 1.4.7. El ensayo aislado de clamd arrancó con las firmas existentes, respondió por su protocolo de salud y rechazó esas firmas como vencidas. La prueba de compilación descubrió que ClamAV rechaza los archivos de configuración empaquetados con CRLF. Git conserva ahora LF para los archivos de configuración y el JSON de política. El hash configurado para staging coincide con los bytes versionados. Cinco pruebas comprobaron versión mínima, firmas y sus huellas. La actualización de los contenedores activos y la restauración completa de /ready quedan pendientes de firmas vigentes del proveedor. [Aviso de ClamAV 1.4.7](https://blog.clamav.net/2026/11/clamav-155-147-security-patch-release.html) · [Paquetes Alpine 3.24](https://pkgs.alpinelinux.org/package/v3.24/community/x86_64/clamav).
