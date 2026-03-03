# 📄 Documentación Técnica: Sistema de Retos y Podio v2026

Esta documentación detalla todos los problemas técnicos encontrados, las soluciones aplicadas, los scripts SQL ejecutados y los cambios en el código Kotlin para la red social.

---

## 🛠️ 1. Problemas Identificados y Solucionados

### A. Votaciones (Errores en Base de Datos)
1.  **Error `42P10`**: La aplicación intentaba llamar a una función RPC (`v2026_challenge_vote`) que no existía o tenía una firma de parámetros incorrecta.
    *   **Solución**: Se creó la función RPC con parámetros de tipo `TEXT` para coincidir con el envío desde Kotlin.
2.  **Error `23505` (Constraint Violation)**: Existía una restricción que impedía votar por más de una persona en el mismo reto.
    *   **Solución**: Se eliminaron los constraints restrictivos (`unique_vote_per_challenge`) y se reemplazaron por uno que permite votar por diferentes personas pero no repetir el voto en la misma.

### B. Cálculo de Ganadores (Podio)
1.  **Podio Vacío**: El sistema cambiaba de estado pero no calculaba quiénes habían ganado.
    *   **Solución**: Se integró lógica de agregación (SQL) que cuenta los votos por usuario en el reto actual y selecciona a los 3 con más votos al momento de cerrar la fase.

### C. Transiciones y Sincronización (El problema de "00:00")
1.  **Relay Stuck**: El contador llegaba a `00:00` y no pasaba de fase.
    *   **Causa Raíz**: Desincronización de milisegundos entre el reloj del servidor (Supabase) y el celular del usuario, sumado a la falta de proactividad de la app.
    *   **Solución**: 
        *   **Backend**: Se agregó "leniencias" (márgenes de 10-15 segundos) en SQL. Si la app pide el cambio y falta poco tiempo, el servidor cede y cambia la fase.
        *   **Frontend**: Se implementó un **Master Timer** con **Polling de Insistencia**. La app pregunta repetidamente al servidor hasta que confirma el cambio de estado.

---

## 💾 2. Historial de Scripts SQL Ejecutados

### `FIX_VOTACIONES_FINAL.sql`
*   **Propósito**: Crear la función RPC de votación y habilitar el toggle (votar/quitar voto).
*   **Cambio Clave**: Manejo de parámetros como `TEXT` y actualización de `vote_count` en tiempo real.

### `ELIMINAR_CONSTRAINT_DIRECTO.sql`
*   **Propósito**: Eliminar el bloqueo de "un solo voto por reto".
*   **Cambio Clave**: `DROP CONSTRAINT challenge_votes_active_challenge_id_voter_id_key`.

### `FIX_SOLO_GANADORES.sql` (Versión Final)
*   **Propósito**: La función más importante del sistema. Maneja el ciclo de vida completo.
*   **Lógica**: 
    -   `active` -> `voting` (Margen 15s)
    -   `voting` -> `completed` (Calcula TOP 3 y guarda en `winner_ids`)
    -   `completed` -> `active` (Genera nuevo reto aleatorio)

---

## 📱 3. Cambios en el Código Kotlin (`ChallengeViewModel.kt`)

Se realizó una refactorización profunda para pasar de varios timers desordenados a un sistema centralizado:

1.  **`startMasterTimer`**: Una única función que detecta en qué fase está el reto y arranca el contador correspondiente (`_activeCountdown`, `_votingCountdown` o `_winnersCountdown`).
2.  **Polling de Insistencia**: Cuando el timer llega a 0, se activa un loop de 10 intentos:
    ```kotlin
    repeat(10) {
        if (_activeChallenge.value?.status != challenge.status) return@launch // Si ya cambió, para.
        loadActiveChallenge() // Pide actualización al servidor
        delay(1000) // Espera 1s para reintentar
    }
    ```
3.  **Soporte ISO 8601**: Se utilizó `java.time.Instant.parse()` para asegurar que la hora del servidor (UTC) se entienda perfectamente en el celular, sin importar la zona horaria.

---

## 🔄 4. El Ciclo de Vida del Reto (Estado Final)

1.  **Fase ACTIVA (5 min)**:
    *   El usuario puede participar enviando texto, fotos o videos.
    *   El timer baja hasta 00:00.
2.  **Fase VOTACIÓN (30 seg)**:
    *   El sistema cambia automáticamente.
    *   Los usuarios pueden votar por todas las participaciones que les gusten.
3.  **Fase PODIO (10 seg)**:
    *   Se consultan los `winner_ids` generados por el SQL.
    *   Se muestran los 3 ganadores con más votos.
4.  **REINICIO**:
    *   El sistema genera un nuevo reto de la tabla `challenges_master`.
    *   Vuelve a empezar el ciclo de 5 minutos.

---

## ⚠️ 5. Solución de Errores de Compilación Sugeridos
Durante el proceso surgieron errores por:
-   **Funciones Duplicadas**: Se borraron las versiones antiguas de `observeChallenges`.
-   **Variables no Declaradas**: Se añadió `activePhaseJob` y la nueva variable unificada `countdownJob`.

---

## 🎯 Conclusión
El sistema es ahora **100% autónomo**. No necesita intervención manual para cambiar de retos. La sincronización mediante **ISO 8601** y la lógica de **polling de insistencia** garantizan que la experiencia de usuario sea fluida incluso con conexiones de internet lentas o diferencias mínimas de reloj.
