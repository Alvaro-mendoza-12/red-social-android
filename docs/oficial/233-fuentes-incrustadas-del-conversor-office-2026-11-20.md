# Fuentes incrustadas en la vista previa de Office

20 de noviembre de 2026. **CVE-2026-132998 y CVE-2026-132999 corregidas en la imagen candidata.** El servidor activo sigue en la revisión operativa anterior.

El conversor incorpora el paquete oficial libeot0 1:0.0.4-1. La imagen anterior contenía 0.01-5+b2. Debian publica las correcciones en 1:0.0.3-1 y 1:0.0.4-1, respectivamente. La compilación descarga únicamente libeot0 desde la instantánea firmada de Debian del 20 de noviembre a las 06:00 UTC. APT verifica el índice, el paquete y su arquitectura. El sistema base sigue en Debian 13. [Primer aviso Debian](https://security-tracker.debian.org/tracker/CVE-2026-132998) · [Segundo aviso Debian](https://security-tracker.debian.org/tracker/CVE-2026-132999)

| Comprobación | Resultado |
| --- | --- |
| Procedencia | Dos firmas válidas del índice de Debian. Se comprobó la huella SHA-256 de los índices y de los cinco paquetes para amd64, i386, ppc64el, arm64 y armhf. |
| Carga en Linux amd64 | El binario instalado coincide con el paquete oficial. Conserva el nombre libeot.so.0, los 114 símbolos exportados anteriores y su dependencia de libc. Añade un símbolo. |
| Compatibilidad del consumidor | Entre 1.126 objetos ELF se encontró un solo consumidor directo, la biblioteca principal de LibreOffice. La cabecera nueva retira un valor del enumerado de errores. Se comprobó que el consumidor compara el resultado con EOT_SUCCESS, cuyo valor sigue siendo cero. El cambio de numeración debe considerarse si se añade otro consumidor. |
| Fuente EOT ordinaria | La fuente Font Awesome produjo los mismos 165.548 bytes TrueType con la biblioteca antigua y con la nueva. |
| Presentación con fuente incrustada | La API produjo un PDF con Boldonse-Regular. El mismo documento, tras retirar la fuente de la presentación, produjo un PDF con NotoSans-Regular. La fuente Boldonse estaba ausente del sistema. Cambiaron 6.203 píxeles. |
| Regresión del conversor | Diez solicitudes autenticadas adicionales cubrieron seis formatos, reinicio y tres documentos complejos. Todas devolvieron HTTP 200, con el texto y las páginas esperados. Los 2.750.672 píxeles de las seis páginas complejas coinciden con el control anterior. |

## Repetición en un entorno privado

El registro adjunto contiene el commit, la imagen amd64, los índices firmados, las huellas de los archivos y los resultados por documento. Para repetir la prueba de uso de la fuente, comprobar primero que el sistema del conversor carece de Boldonse. Con una cuenta sintética, enviar la presentación de LibreOffice al endpoint autenticado POST /v1/documents/preview y leer las fuentes del PDF con pdffonts. Repetir tras retirar la lista de fuentes incrustadas, la relación de fuente y el archivo de fuente del paquete PPTX. Reiniciar el conversor entre ambas peticiones. [Presentación de prueba de LibreOffice](https://github.com/LibreOffice/core/blob/libreoffice-26.8.2.2/sd/qa/unit/data/BoldonseFontEmbedded.pptx) · [Evidencia reproducible](evidencias/233-fuentes-incrustadas-del-conversor-office.json)

La imagen amd64 se ensayó con el código de API de la rama estable, PostgreSQL 16.16, 37 migraciones y Redis en una red privada. En las otras cuatro arquitecturas la comprobación alcanzó el índice firmado y la descarga íntegra del paquete. Los 330 registros de OSV para Debian 13 aún incluyen los dos avisos de libeot. La evaluación de estas dos correcciones utiliza también la versión fijada por Debian y la identidad del binario instalado. Sigue vigente la corrección local de libxml2 del informe 232. Para revertir únicamente el cambio de libeot, se puede revertir el commit de código indicado en la evidencia y reconstruir en laboratorio. [Consumidor de LibreOffice](https://github.com/LibreOffice/core/blob/libreoffice-26.8.2.2/vcl/source/gdi/embeddedfontsmanager.cxx#L313-L326)
