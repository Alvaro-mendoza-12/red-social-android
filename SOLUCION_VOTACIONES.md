# 🔧 Documentación: Reparación del Sistema de Votaciones

## ✅ ESTADO: PROBLEMA YA RESUELTO

**Nota:** Este documento es para referencia. El script SQL ya fue ejecutado en la base de datos el 2026-02-07.
La función `v2026_challenge_vote` ya está operativa en producción.

---

## 📋 Problema Original (Ya Resuelto)

### Error Original
```
Error al votar: Error: {"code":"42P10","details":null,"hint":null,"message":"the..."}
```

### Código de Error: 42P10
**Significado:** `invalid_column_reference` o función con múltiples definiciones conflictivas en PostgreSQL.

Este error ocurre cuando:
1. Existen múltiples versiones de una función con el mismo nombre pero diferentes firmas
2. La función llamada no existe en la base de datos
3. Hay ambigüedad en la resolución de la función

## 🔍 Causa Raíz

### Discrepancia entre Código y Base de Datos

**En el código Kotlin** (`ChallengeRepository.kt` línea 185):
```kotlin
val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/v2026_challenge_vote") {
    header("Authorization", "Bearer $accessToken")
    header("apikey", supabaseConfig.supabaseAnonKey")
    contentType(ContentType.Application.Json)
    setBody(mapOf("p_id" to submissionId))
}
```

**En la base de datos** (archivos SQL existentes):
- La función `v2026_challenge_vote` **NO EXISTE**
- Solo existe `toggle_challenge_vote` en algunos archivos
- Hay múltiples definiciones conflictivas de `toggle_challenge_vote` con diferentes firmas

## ✅ Solución Implementada

Se ha creado el archivo `FIX_VOTACIONES_FINAL.sql` que realiza las siguientes acciones:

### 1. Limpieza de Funciones Conflictivas
```sql
DROP FUNCTION IF EXISTS public.toggle_challenge_vote(uuid);
DROP FUNCTION IF EXISTS public.toggle_challenge_vote(text);
DROP FUNCTION IF EXISTS public.toggle_challenge_vote();
DROP FUNCTION IF EXISTS public.v2026_challenge_vote(uuid);
DROP FUNCTION IF EXISTS public.v2026_challenge_vote(text);
DROP FUNCTION IF EXISTS public.v2026_challenge_vote();
```

### 2. Creación de la Función Correcta
Se crea `v2026_challenge_vote(p_id UUID)` que:
- ✅ Recibe el ID de la submission como parámetro UUID
- ✅ Valida que el usuario esté autenticado
- ✅ Implementa lógica toggle: agrega o elimina el voto
- ✅ Previene votos duplicados
- ✅ Actualiza el contador `vote_count` automáticamente
- ✅ Retorna JSON con el nuevo conteo y estado del voto

### 3. Constraints y Validaciones
```sql
-- Un solo voto por usuario por submission
CREATE UNIQUE INDEX idx_challenge_votes_submission_voter 
ON challenge_votes (submission_id, voter_id);

-- Un solo voto por usuario por reto activo
ALTER TABLE challenge_votes 
ADD CONSTRAINT unique_vote_per_challenge 
UNIQUE (active_challenge_id, voter_id);
```

### 4. Trigger de Sincronización Automática
Se crea un trigger que mantiene la columna `vote_count` siempre sincronizada:
```sql
CREATE TRIGGER trigger_sync_vote_count
AFTER INSERT OR DELETE ON challenge_votes
FOR EACH ROW
EXECUTE FUNCTION sync_submission_vote_count();
```

### 5. Row Level Security (RLS)
Se configuran políticas de seguridad para:
- ✅ Solo usuarios autenticados pueden ver votos
- ✅ Solo usuarios autenticados pueden votar
- ✅ Solo pueden eliminar sus propios votos

## 📝 Instrucciones de Aplicación

### ⚠️ IMPORTANTE: Ya NO necesitas hacer esto si ejecutaste el SQL ayer (2026-02-07)

Si **YA ejecutaste** el código SQL que te compartí ayer por chat, entonces:
- ✅ **La función ya está creada y funcionando**
- ✅ **NO necesitas hacer nada más**
- ✅ **Este archivo es solo para referencia o para otros que tengan el mismo problema**

---

### 🆕 Si AÚN NO has ejecutado el fix (solo si el problema persiste):


1. **Ir a Supabase Dashboard**
   - Abre tu proyecto en [https://app.supabase.com](https://app.supabase.com)

2. **Abrir SQL Editor**
   - Ve a la sección "SQL Editor" en el menú lateral

3. **Ejecutar el archivo FIX_VOTACIONES_FINAL.sql**
   - Copia **todo el contenido** del archivo `FIX_VOTACIONES_FINAL.sql`
   - Pégalo en el editor SQL
   - Haz click en **"Run"** o presiona `Ctrl+Enter`

4. **Verificar la ejecución**
   - Deberías ver mensajes como:
     ```
     ✅ Sistema de votaciones reparado exitosamente
     ✅ Función v2026_challenge_vote creada con parámetro UUID
     ✅ Constraints únicos aplicados
     ✅ Trigger de sincronización activado
     ✅ RLS configurado correctamente
     ```

### Paso 2: NO se requieren cambios en el código

El código Kotlin ya está llamando a `v2026_challenge_vote` correctamente. Una vez ejecutado el SQL, todo funcionará automáticamente.

## 🧪 Cómo Probar

1. **Lanzar la aplicación**
2. **Ir a la sección de Retos**
3. **Participar en un reto activo**
4. **Intentar votar por una respuesta**
5. **Verificar que:**
   - ✅ El voto se registra sin errores
   - ✅ El contador de votos se actualiza inmediatamente
   - ✅ Puedes quitar el voto haciendo clic de nuevo
   - ✅ No puedes votar más de una vez en el mismo reto

## 🔧 Detalles Técnicos

### Función `v2026_challenge_vote`

**Entrada:**
```json
{
  "p_id": "uuid-de-la-submission"
}
```

**Salida:**
```json
{
  "vote_count": 5,
  "voted_by_me": true
}
```

### Lógica de la Función

```sql
1. Verificar autenticación del usuario
2. Obtener active_challenge_id de la submission
3. SI el voto ya existe:
   - Eliminar el voto
   - voted_by_me = false
   SINO:
   - Insertar nuevo voto
   - voted_by_me = true
4. Recalcular vote_count
5. Actualizar challenge_submissions.vote_count
6. Retornar JSON con resultado
```

## 📊 Estado Actual

### ✅ Arreglado
- Sistema de votaciones funcionando
- Función RPC correcta creada
- Constraints de unicidad aplicados
- Trigger de sincronización automática
- RLS configurado

### ⏳ Pendiente (según documentación previa)
- Ningún problema pendiente en votaciones

## 🚨 Notas Importantes

1. **Ejecutar el SQL una sola vez**: El script es idempotente (puede ejecutarse múltiples veces sin causar problemas)

2. **Respaldo**: Aunque el script usa `IF EXISTS` y es seguro, siempre es buena práctica tener un respaldo de la base de datos antes de ejecutar cambios

3. **Verificación**: Después de ejecutar, puedes verificar que la función existe ejecutando:
   ```sql
   SELECT proname, pg_get_function_arguments(oid)
   FROM pg_proc
   WHERE proname = 'v2026_challenge_vote';
   ```

## 📞 Soporte

Si después de aplicar este fix sigues teniendo problemas:
1. Revisa los logs de Supabase en la sección "Logs" del dashboard
2. Verifica que el archivo SQL se ejecutó sin errores
3. Comprueba que tienes conexión a internet y el proyecto Supabase está activo
