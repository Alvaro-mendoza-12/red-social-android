# Conversor Office, compatibilidad de libxml2 y fidelidad de los PDF

20 de noviembre de 2026. **Corrección validada en laboratorio. Certificación de producción abierta.**

El conversor Debian de la candidata incorpora una corrección local para CVE-2026-6653. Cuando el analizador se detiene, la función devuelve el resultado que obtuvo al entregar la entrada a la pila. Así conserva la propiedad de la entrada y el llamador recibe el resultado correcto. Se aplicó sobre las fuentes exactas de Debian, con sus parches. La corrección adapta la solución documentada por TuxCare. Debian sigue clasificando su paquete de trixie como vulnerable. [Aviso del proveedor](https://errata.tuxcare.com/els_os/debian10els/CLSA-2026-1789202279.html) · [Seguimiento de Debian](https://security-tracker.debian.org/tracker/CVE-2026-6653)

| Comprobación | Resultado |
| --- | --- |
| Fuentes y compilación | Las tres descargas coinciden con sus SHA-256. La versión fuente debe coincidir con el paquete instalado. Los archivos exportados por Git son idénticos con los tres modos de conversión de saltos de línea. |
| Interfaz pública en Linux amd64 | Las 47 cabeceras instaladas coinciden byte a byte con el paquete de desarrollo oficial. Coinciden las 1.786 exportaciones dinámicas. Los consumidores compilados con ambos juegos de cabeceras leyeron un XML ordinario con ambas bibliotecas. |
| Tamaño de estructuras | xmlEntity ocupa 136 bytes y xmlParserInput ocupa 104 bytes en ambos paquetes. La alternativa anterior daba 152 y 120. |
| API y aislamiento | PostgreSQL 16.16 con 37 migraciones, Redis, registro HTTP 201 y 25 capacidades disponibles. La API se ejecutó como UID 10001. El conversor se ejecutó como UID 1001, con raíz de solo lectura, todas las capacidades retiradas y red interna. |
| Vista previa autenticada | Diez respuestas HTTP 200 con PDF, texto y páginas esperados y caché desactivada. Se probaron DOC, DOCX, XLS, XLSX, PPT y PPTX, un DOCX después de reiniciar y tres documentos de formato complejo. |
| Fidelidad visual | Las tres entradas complejas tienen el mismo SHA-256 que las del control. Los 2.750.672 píxeles de sus seis páginas coinciden, incluidas las tablas, las imágenes, los caracteres acentuados y el gráfico. |

El cambio afecta a uno de los 205 paquetes fuente inventariados. Los ejecutables de Gotenberg, LOK, LibreOffice y curl coinciden byte a byte con la candidata anterior. curl conserva la versión 8.23.0 y LibreOffice la 26.8.2.2. La consulta de OSV utilizó la versión original de Debian para libxml2, de modo que el sufijo local conserve visibles sus avisos. El registro sigue mostrando CVE-2026-6653. [Evidencia con huellas y resultados por documento](evidencias/232-conversor-office-compatibilidad-y-fidelidad.json)

Se compiló una segunda imagen sobre Fedora 45. El inventario instalado mostró LibreOffice 26.8.3.1, libxml2 2.15.4 y curl 8.21.0-9.fc45. El conversor respondió a las seis conversiones directas. Su historial acredita dos de los cinco avisos de curl revisados en el informe 225. Quedó sin acreditar el mismo conjunto de correcciones para la biblioteca completa. Se conserva como ensayo privado. [Historial de curl en Fedora](https://packages.fedoraproject.org/pkgs/curl/curl/fedora-45-updates.html)

## Riesgos y alcance

OSV devolvió 330 avisos de distribución, dos más que en el informe 225. Ambos corresponden a libeot. La candidata contiene el paquete binario libeot0 0.01-5+b2. Debian registra trixie como vulnerable y publica las correcciones de las dos incidencias en inestable. Queda por comprobar su incorporación a la imagen estable. [CVE-2026-132998](https://security-tracker.debian.org/tracker/CVE-2026-132998) · [CVE-2026-132999](https://security-tracker.debian.org/tracker/CVE-2026-132999)

La ruta pública y la privada de Ubuntu devolvieron HTTP 200, TLS 1.3 y 25 capacidades sobre la revisión operativa cc6a022e. La candidata solo se ejecutó en infraestructura desechable. Siguen pendientes los recorridos físicos Android y la recuperación completa en otro servidor. Para revertir esta corrección, se puede revertir el commit de código indicado en la evidencia y volver a construir la imagen en laboratorio.
