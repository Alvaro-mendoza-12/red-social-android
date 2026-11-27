# Auditoría web y personalización de Juegos · 26 de noviembre de 2026

La revisión comenzó en Mensajes, Social, Videos, Llamadas y Salas de voz. Después se revisó el acceso web a los juegos de mesa. Se usó Chromium con cuentas, red y datos simulados en una compilación de producción servida localmente.

| Área | Verificación y cambios |
| --- | --- |
| Mensajes | Bandeja y chats directos en 320, 390, 768, 1024 y 1440 píxeles. La solicitud saliente mantiene oculto el compositor hasta que se puede escribir. A 320 píxeles se verificaron los cuatro controles de la cabecera, sus áreas táctiles de 44 píxeles y el acceso a las seis reacciones. |
| Social | Publicaciones con contenido y Momentos en estado vacío. Se revisaron el ancho y los errores de ejecución en las vistas recorridas. |
| Videos | Se reprodujo un clip de prueba. La preparación local de archivos ahora tiene un tiempo límite, libera la URL temporal y puede exportar el primer fotograma sin buscar otra posición. Chromium generó un JPEG con una entrada WebM real. |
| Llamadas | Se revisó la vista de llamadas y el acceso a iniciar una llamada en los cinco anchos. |
| Salas de voz | Se revisaron directorio y detalle. El directorio ignora respuestas anteriores, omite salas que carecen de identificador y limpia el error al recuperar resultados. El detalle muestra un mensaje en español si falta la sala en la respuesta. |
| Juegos | El acceso a juegos de mesa ofrece siete tableros, recuerda el favorito por cuenta en ese navegador y enlaza directamente con el tablero. Se comprobó que conserva Gomoku al recargar y que un valor desconocido abre Ajedrez. |

## Rendimiento y mantenimiento

- Retiradas 18 dependencias directas sin referencias en la web versionada, entre ellas Workbox, los paquetes de virtualización, `web-vitals` y `zustand`. El lockfile pasó de 904 a 883 entradas y completó una instalación limpia con los scripts de dependencias deshabilitados.
- Vite permite que las dependencias de uso diferido se agrupen con sus rutas. La suma de imports estáticos del arranque pasó de 1.786.188 a 739.699 bytes sin compresión y de 512.245 a 227.944 bytes con gzip por archivo. La medición corresponde a la carga inicial y excluye la descarga posterior de rutas y recursos.
- `ReelsPage.tsx` tiene 188 líneas menos. `reelFilePreparation.ts` concentra la lectura local de metadatos y la captura y exportación de fotogramas. El acceso de juegos de mesa tiene su propio componente y estilos.
- Los siguientes candidatos a revisión detallada por tamaño son `ChatDetailPage.tsx`, `screens.css`, `RoomChatPage.tsx` y `VoiceRoomPage.tsx`. La preparación de videos ofrecía una responsabilidad separable y verificable con archivos de prueba.

## Evidencia y alcance

- 211 suites de Jest con 1.360 pruebas aprobadas y 171 pruebas adicionales de Node. La compilación web y la comprobación de 22 páginas públicas prerenderizadas finalizaron correctamente.
- 50 combinaciones de vista y ancho en Chromium sin desbordamiento horizontal del documento ni excepciones de ejecución. Se añadieron verificaciones de interacción para el chat, el detalle de una sala incompleta y el tablero favorito.
- `npm audit` informó cero avisos conocidos y Gitleaks no reportó secretos en la instantánea web rastreada. La revisión incluyó fuentes versionadas, orígenes del lockfile y scripts de instalación declarados. Queda limitada a los archivos y patrones examinados.
- Quedan pendientes el audio y video entre cuentas reales, el micrófono en salas de voz y la validación en producción. La evidencia de cada lectura se registra por archivo y huella en `review-coverage.json`.
