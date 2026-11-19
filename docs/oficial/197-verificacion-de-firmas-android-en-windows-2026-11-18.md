# Verificación de firmas Android en Windows con configuración regional distinta

18 de noviembre de 2026. El verificador del AAB busca mensajes de `jarsigner` y una huella SHA-256 de `keytool`. En la estación Windows en español, la lectura de `keytool` falló al decodificar su salida con Python en modo UTF-8 y terminó con una huella ausente. El APK de la misma compilación verificó correctamente.

La herramienta solicita el idioma inglés a los dos comandos del JDK y tolera bytes ajenos a UTF-8 en mensajes accesorios. La firma sigue sujeta al código de salida del verificador, al rechazo de entradas sin firma y a la coincidencia exacta de la huella del certificado esperado.

Nueve casos Python aprobaron, incluida una salida de herramienta con bytes de la página de códigos local y huella ASCII. Sobre el APK y AAB 9.1.43/183 compilados desde `1aa044aaddb9`, ambos verificaron con el certificado de carga esperado. Los binarios se conservaron con sus firmas originales. Recibos en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit197-signature-locale/` y logs originales del primer intento en el directorio Release `1aa044aaddb9`. El resultado cubre la firma y su herramienta; el resto de controles del candidato tiene recibos propios.
