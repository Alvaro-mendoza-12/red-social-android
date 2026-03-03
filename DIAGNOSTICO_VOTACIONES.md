# 🔍 Diagnóstico: Error Persistente en Votaciones

## 📊 Situación Actual

**Fecha del problema:** 2026-02-08
**Estado de la función:** Ya fue creada el 2026-02-07
**Error que sigue apareciendo:** `Error: {"code":"42P10",...}`

---

## 🧐 Posibles Causas del Error Persistente

Dado que **YA ejecutaste el SQL correcto ayer**, el error puede deberse a:

### 1. ⚠️ Constraint Único Bloqueando el Voto

**Problema:** El constraint `unique_vote_per_challenge` permite **un solo voto por reto activo**.

**Esto significa:**
- ✅ Puedes votar por **UNA** submission en el reto actual
- ❌ NO puedes votar por **OTRA** submission diferente en el mismo reto
- ❌ Si intentas votar por otra, te da error

**Ejemplo:**
```
Reto: "Dibuja un unicornio"
- Usuario vota por la submission de Juan ✅
- Usuario intenta votar por la submission de María ❌ ERROR
```

**Solución:** Esto es **comportamiento intencional** para evitar que un usuario vote múltiples veces. 
Si quieres permitir votos múltiples, necesitamos modificar el constraint.

---

### 2. 🔄 Caché de la App o Supabase

**Problema:** La app puede estar usando una conexión cacheada con la versión antigua de la función.

**Solución:**
1. **Cerrar completamente la app** (force stop)
2. **Limpiar caché de la app:**
   ```bash
   # En Android Studio o terminal
   adb shell pm clear conectate.com
   ```
3. **Volver a abrir la app**

---

### 3. 🗄️ La Función No Se Recreó Correctamente

**Problema:** Puede que la función no se haya eliminado/recreado correctamente.

**Verificación en Supabase SQL Editor:**
```sql
-- Ver si existe la función con la firma correcta
SELECT 
    proname AS nombre_funcion,
    pg_get_function_arguments(oid) AS argumentos,
    pg_get_function_result(oid) AS retorno
FROM pg_proc
WHERE proname = 'v2026_challenge_vote'
AND pronamespace = 'public'::regnamespace;
```

**Debería mostrar:**
```
nombre_funcion         | argumentos | retorno
-----------------------|------------|--------
v2026_challenge_vote   | p_id text  | jsonb
```

Si muestra algo diferente o múltiples filas, ejecuta este fix:

```sql
-- Eliminar TODAS las versiones
DROP FUNCTION IF EXISTS public.v2026_challenge_vote(uuid);
DROP FUNCTION IF EXISTS public.v2026_challenge_vote(text);
DROP FUNCTION IF EXISTS public.v2026_challenge_vote();

-- Recrear la correcta
CREATE OR REPLACE FUNCTION public.v2026_challenge_vote(p_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_count INTEGER;
    v_voted BOOLEAN;
    v_voter_id UUID := auth.uid();
    v_target_id UUID;
    v_active_id UUID;
BEGIN
    IF v_voter_id IS NULL THEN RAISE EXCEPTION 'No autenticado'; END IF;
    v_target_id := p_id::UUID;

    SELECT active_challenge_id INTO v_active_id 
    FROM challenge_submissions 
    WHERE id = v_target_id;
    
    IF v_active_id IS NULL THEN RAISE EXCEPTION 'No se encontró la participación'; END IF;

    IF EXISTS (SELECT 1 FROM challenge_votes WHERE submission_id = v_target_id AND voter_id = v_voter_id) THEN
        DELETE FROM challenge_votes WHERE submission_id = v_target_id AND voter_id = v_voter_id;
        v_voted := false;
    ELSE
        INSERT INTO challenge_votes (submission_id, voter_id, active_challenge_id)
        VALUES (v_target_id, v_voter_id, v_active_id)
        ON CONFLICT (submission_id, voter_id) DO NOTHING;
        v_voted := true;
    END IF;

    SELECT count(*)::int INTO v_count FROM challenge_votes WHERE submission_id = v_target_id;
    UPDATE challenge_submissions SET vote_count = v_count WHERE id = v_target_id;
    
    RETURN jsonb_build_object('vote_count', v_count, 'voted_by_me', v_voted);
END;
$$;

GRANT EXECUTE ON FUNCTION public.v2026_challenge_vote(TEXT) TO authenticated, anon;
NOTIFY pgrst, 'reload schema';
```

---

### 4. 🚫 El Constraint Único Está Duplicando

**Problema:** El constraint `unique_vote_per_challenge` puede estar impidiendo que voten.

**Verificación:**
```sql
-- Ver los constraints en challenge_votes
SELECT 
    conname AS nombre_constraint,
    pg_get_constraintdef(oid) AS definicion
FROM pg_constraint
WHERE conrelid = 'challenge_votes'::regclass;
```

**Si ves el constraint `unique_vote_per_challenge`**, esto significa:
- Solo puedes votar UNA VEZ por reto activo
- No puedes cambiar tu voto a otra submission

**Para permitir cambiar de voto** (quitar voto de una submission y ponerlo en otra):
```sql
-- Eliminar el constraint que limita a un voto por reto
ALTER TABLE challenge_votes 
DROP CONSTRAINT IF EXISTS unique_vote_per_challenge;

-- Mantener solo el constraint por submission
-- (esto permite votar/desvotar la misma submission, pero votar múltiples en el mismo reto)
```

**⚠️ ADVERTENCIA:** Si eliminas `unique_vote_per_challenge`, un usuario podría votar por TODAS las submissions. Si quieres esto, está bien. Si NO, déjalo como está.

---

## 🎯 Acción Recomendada

Basándome en el error en la imagen, mi recomendación es:

### **Opción A: Permitir UN voto por reto (actual - más restrictivo)**
- El usuario solo puede votar UNA vez por reto
- No puede cambiar su voto
- Si intenta votar otra submission, da error ❌

### **Opción B: Permitir cambiar de voto (más flexible)**
1. Eliminar el constraint `unique_vote_per_challenge`
2. Permitir que el usuario quite su voto de una submission y vote otra
3. Esto requiere cambiar la lógica de la función

### **Opción C: Permitir votos múltiples (más permisivo)**
1. Eliminar el constraint `unique_vote_per_challenge`
2. Permitir que los usuarios voten por todas las submissions que quieran
3. Solo mantener el constraint que previene votar la misma submission dos veces

---

## 📋 Siguiente Paso

**Dime cuál de estas opciones prefieres:**

1. **¿Quieres que un usuario solo pueda votar UNA submission por reto?** (Opción A - actual)
2. **¿Quieres que pueda CAMBIAR su voto de una submission a otra?** (Opción B)
3. **¿Quieres que pueda votar MÚLTIPLES submissions en el mismo reto?** (Opción C)

Una vez que me digas, te daré el SQL exacto para implementar esa lógica.

---

## 🔍 Debug Inmediato

Mientras tanto, ejecuta esto en Supabase SQL Editor para ver el estado actual:

```sql
-- 1. Ver la función
SELECT proname, pg_get_function_arguments(oid), pg_get_function_result(oid)
FROM pg_proc
WHERE proname = 'v2026_challenge_vote';

-- 2. Ver los constraints
SELECT conname, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'challenge_votes'::regclass;

-- 3. Ver tus votos actuales
SELECT cv.*, cs.active_challenge_id
FROM challenge_votes cv
JOIN challenge_submissions cs ON cs.id = cv.submission_id
WHERE cv.voter_id = auth.uid()
ORDER BY cv.created_at DESC
LIMIT 10;
```

Copia y pega el resultado aquí para que pueda ayudarte mejor. 🚀
