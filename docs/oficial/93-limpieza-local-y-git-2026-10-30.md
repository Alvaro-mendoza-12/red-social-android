# 93 — Limpieza local de las dos carpetas y de Git

Fecha de cierre: 2026-10-30, Bogotá. Inventario iniciado el 29/10. Alcance: disco D de desarrollo; no se eliminan datos del servidor.

## Resultado medido

Los tamaños son bytes lógicos convertidos a GiB, sin seguir junctions ni contar dos veces `main/live`.

| Carpeta | Antes | Después |
| --- | ---: | ---: |
| red social - copia | 244,45 GiB | 7,91 GiB |
| red social - live-gifts | 62,66 GiB | 2,67 GiB |

El espacio libre real de D pasó de 39.393.992.704 a 356.178.817.024 bytes: aproximadamente **295,03 GiB recuperados**, con 331,72 GiB libres al cierre del inventario. La diferencia entre tamaño lógico y espacio físico no debe sumarse como si fueran dos recuperaciones distintas. Las compilaciones posteriores pueden volver a generar cachés.

## Qué se eliminó y qué se conservó

Se retiraron 383 destinos revisados de compilaciones, cachés Gradle locales duplicadas, emuladores de auditorías terminadas, entornos temporales de pruebas y binarios de diagnóstico. Un segundo lote retiró 25 APK/AAB o firmas auxiliares obsoletos de versiones 2.x/3.x, por 4.663.728.805 bytes. No hubo fallos de borrado en el primer lote; el segundo terminó correctamente.

Cada destino se comprobó dentro de las carpetas autorizadas, sin archivos versionados. Se usó PowerShell con rutas literales. Los enlaces internos se desvincularon sin recorrer sus destinos; se conserva la junction `red social - copia/live`. El código compartido y las dependencias activas no se eliminaron.

Se preservaron claves de firma, configuración privada, fuentes, almacenamiento y bases de datos, recursos originales, archivos de respaldo de fuentes/Git y la entrega anterior 169. Los APK/AAB 9.1.30 / 170 entregados fuera de D se verificaron por SHA-256 antes de retirar sus duplicados; no se genera otra versión Android por esta limpieza.

Git pasó de unos 70 GiB a un pack de aproximadamente 123 MiB. Se compactaron los objetos y se retiraron objetos inalcanzables previamente revisados. Se conservaron ramas, referencias y reflogs; las referencias se compararon antes y después. `git fsck --full --no-dangling` terminó con código 0. Queda una advertencia de un directorio vacío de metadatos de worktree, sin impacto de espacio ni reparación destructiva.

Se ampliaron las exclusiones de `.gitignore` para que resultados de auditoría, APK copiados, temporales nativos y salidas del navegador no vuelvan a tratarse como fuentes. Esto no elimina archivos versionados.

## Límites y continuidad

La igualdad requerida entre las ramas corresponde al árbol de fuentes versionadas. Las cachés locales y los archivos personales no versionados pueden diferir. No se ejecuta una compilación Android completa para comprobar una limpieza que precisamente retira sus resultados regenerables; se verificaron integridad Git, configuración protegida y entregas firmadas.

La auditoría de reacciones y navegación web continúa por separado en la revisión 94. No se atribuye la lentitud de producción al tamaño de estas carpetas de desarrollo.
