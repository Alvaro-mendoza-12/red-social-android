# 207 · Privacidad y formato de cumpleaños en Android

19 de noviembre de 2026.

La pantalla de perfil solo interpretaba `only_me` como cumpleaños privado. Una respuesta con una fecha y el valor heredado `nobody` todavía la mostraba a visitantes. Ahora se respetan los dos valores de privacidad, incluida su variante de mayúsculas o espacios, y el propietario conserva su vista.

La presentación admite una fecha completa de los servicios anteriores y la representación `--MM-DD` sin año. Valida el día y el mes, acepta el 29 de febrero en el nuevo formato, y usa el mismo valor para la etiqueta y para el botón de felicitación. Sigue usando la proyección de permisos del servidor para las secciones de seguidores y conexiones.

La referencia reproduce dos fallos en seis casos. El arnés extrae la comprobación literal y el análisis de fecha de la función original, fijada a `0470542c5f1b`. La candidata compila la pantalla real con Compose y pasa los seis. Los resultados quedan en `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-audit207-profile-birthday/`.

La reducción del año en la respuesta de Ubuntu, su integración web y la compatibilidad de clientes ya instalados siguen abiertas en N019. El APK que estaba compilándose desde `0470542c5f1b` corresponde al código anterior a esta continuación.
