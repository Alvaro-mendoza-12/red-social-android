# Hotfix 2026-07-02: Estabilidad de Chats y Mensajería

Esta documentación detalla las correcciones aplicadas a la compilación de Android para lograr estabilidad en producción en las fases 5 y 6.

1. **Problema de resurrección de chats ("Vaciar chat")**: Se corrigió un problema crítico donde la función de "vaciar un chat" localmente borraba el registro visual del chat pero no eliminaba los mensajes físicos de SQLite (`messageDao.deleteMessagesByChatId`). Esto causaba que, en la próxima resincronización, el servidor detectara el último mensaje antiguo intacto y obligara a la app a revivir el chat con todos sus mensajes anteriores.
2. **Jerarquía estricta de fechas**: Se implementó una jerarquía estricta de ordenamiento por fechas en `MessageRepository.kt`, considerando `cleared_at` como límite inferior. El servidor ya no sobreescribe una fecha reciente de un chat si el servidor mismo no contiene mensajes más nuevos que la fecha de borrado. Esto evita problemas de fechas en la interfaz.
3. **Build APK estabilizado**: Se corrigieron errores de sintaxis (`MyFirebaseMessagingService.kt`) que rompían la generación del APK en R8/KSP bajo `assembleRelease`. La compilación ahora pasa sin errores y la mensajería, capa de Room/SQLite en Android están operando de forma 100% confiable y lista para producción.
