# Candidata Android Novvin 9.1.43 (183)

18 de noviembre de 2026. El APK y AAB Release se generaron desde main `1aa044aaddb9b97294c0ce52d97df64389ea05c7`, árbol `0a9da3686a3b6376f79f37b32d33ad0742010ccf`, en un checkout limpio y separado. Incluyen las correcciones de monedero, recibos de regalos, orden del saldo y búsqueda publicadas hasta193. Las correcciones posteriores de salida de salas y acceso/registro pertenecen a la candidata del commit `725a4141561d`.

El paquete `conectate.com` declara `minSdk` 24 y `targetSdk` 36. Aprobó 14 controles de archivo, manifiestos, certificado de carga esperado, alineación de 16 KiB, clases críticas R8 y coincidencia de 32 entradas DEX y nativas entre APK y AAB. El análisis limitado de patrones recorrió 938 entradas. Lint aprobó con cero errores y 346 advertencias. Después de corregir la lectura regional de Java y los finales de línea de `aapt2`, se verificaron los mismos binarios por huella. El APK y AAB conservan sus firmas originales en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-release-check/1aa044aaddb9/artifacts/`.

El origen configurado en la APK respondió 200 en `/health` y `/ready` desde ambas direcciones IPv4 del DNS público. Los cuatro tiempos de una muestra desde Windows fueron 0,83 s, 0,83 s, 3,16 s y 3,70 s, con TLS verificado. El recorrido autenticado desde Android sigue pendiente. El primer ensayo de arranque se detuvo antes de instalar porque el emulador tenía dos paquetes QA. La comparación se preparó en un AVD de API 36 nuevo y quedó en cola para la siguiente candidata.

El recibo de binarios está en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-release-check/1aa044aaddb9/verification/result.json`. Quedan pendientes la instalación medida, la entrega en Play y el despliegue del contrato de regalos v2 previo a pruebas monetarias. La auditoría integral sigue abierta.

| Archivo | Bytes | SHA-256 |
| --- | ---: | --- |
| `Novvin-9.1.43-183-1aa044aaddb9.apk` | 221746357 | `c5936ae71c2549d27d1fe1f76de6e5bbc3d272b35532a4389ffbc2454371d887` |
| `Novvin-9.1.43-183-1aa044aaddb9.aab` | 133124673 | `53a045ac732cdd4a9688beab801ec647605377de7707458ed4d94d2785a6cf8b` |
