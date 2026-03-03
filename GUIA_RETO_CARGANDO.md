# 🔄 FIX: Reto Stuck en "Cargando..."

## ❌ Problema

La app muestra:
```
RETO ACTUAL
Cargando...

Buscando el próximo desafío...
```

Y **nunca carga el siguiente reto**.

---

## 🔍 Posibles Causas

1. **No hay retos en `challenges_master`** (base de datos vacía)
2. **El reto está stuck en estado `completed`** y no expira
3. **La función RPC no está generando nuevos retos**

---

## ✅ Solución

**Archivo:** `DIAGNOSTICO_Y_GENERAR_RETO.sql`

Este SQL:
1. ✅ Verifica cuántos retos hay disponibles
2. ✅ Inserta 10 retos nuevos si hacen falta
3. ✅ Limpia retos stuck en estado `completed`
4. ✅ Genera un nuevo reto automáticamente
5. ✅ Muestra diagnóstico completo

---

## 📋 INSTRUCCIONES URGENTES

### **Paso 1: Ejecutar el SQL**

1. **Abre Supabase SQL Editor**
   - [https://app.supabase.com](https://app.supabase.com)

2. **Ejecuta el archivo**
   - Abre `DIAGNOSTICO_Y_GENERAR_RETO.sql`
   - Copia TODO
   - Pega en SQL Editor
   - Click **"RUN"**

3. **Verifica el resultado:**
   ```
   ✅ VERIFICACIÓN FINAL
   
   Reto actual:
     Título: Confesión anónima
     Estado: active
     Expira en: 300 segundos
   ```

---

### **Paso 2: Reabrir la App**

1. **Cierra la app completamente** (force stop)
2. **Abre la app de nuevo**
3. **Ve a Retos**
4. **Deberías ver un nuevo reto funcionando** ✅

---

## 🎮 Qué Hace el SQL

### 1. Diagnóstico
```sql
-- Cuenta retos disponibles
SELECT COUNT(*) FROM challenges_master;

-- Muestra estado actual
SELECT status FROM active_challenges;
```

### 2. Inserción de Retos
```sql
-- Inserta 10 retos variados
INSERT INTO challenges_master (...)
VALUES 
  ('Confesión anónima', ...),
  ('Adivina el acertijo', ...),
  ('Dibuja un autorretrato', ...),
  ...
```

### 3. Limpieza
```sql
-- Limpia retos stuck
UPDATE active_challenges
SET expires_at = NOW() - INTERVAL '1 second'
WHERE status = 'completed';
```

### 4. Generación
```sql
-- Genera nuevo reto
INSERT INTO active_challenges (...)
SELECT id FROM challenges_master
ORDER BY RANDOM()
LIMIT 1;
```

---

## 📊 Retos que se Insertarán

| Título | Tipo | Dificultad |
|--------|------|------------|
| Confesión anónima | Texto | Fácil |
| Adivina el acertijo | Texto | Media |
| Dibuja un autorretrato | Dibujo | Media |
| Foto graciosa | Cámara | Fácil |
| Canta tu canción favorita | Audio | Media |
| Baile improvisado | Cámara | Difícil |
| Dibuja un unicornio punk | Dibujo | Difícil |
| Teléfono Malogrado | Texto | Fácil |
| Dibujo a ciegas | Dibujo | Media |
| Chiste malo | Texto | Fácil |

---

## ⚠️ Notas

1. **ON CONFLICT DO NOTHING:** Si algún reto ya existe, no lo duplica
2. **Generación automática:** Después del SQL, directo a un nuevo reto
3. **No afecta datos existentes:** Los votos y submissions pasadas se mantienen

---

## 🚀 Resultado Esperado

Después de ejecutar el SQL y reabrir la app:

```
┌─────────────────────────────────────┐
│         RETO ACTUAL                 │
│   Confesión anónima                 │
│                                     │
│   📝 Escribe algo que nadie         │
│      sepa de ti                     │
│                                     │
│   ⏱️ 04:58 restantes               │
│                                     │
│   [ACEPTAR RETO]                    │
└─────────────────────────────────────┘
```

✅ Funcionando normalmente

---

## 🎯 Esto es Temporal o Definitivo?

**Definitivo.** 

Una vez ejecutado:
- ✅ Siempre habrá retos disponibles
- ✅ La función generará nuevos retos automáticamente
- ✅ No necesitas volver a ejecutarlo

---

## 📞 Si Sigue Sin Funcionar

Ejecuta este SQL para ver qué está pasando:

```sql
-- Diagnóstico completo
SELECT * FROM challenges_master;
SELECT * FROM active_challenges ORDER BY created_at DESC LIMIT 1;

-- Ver si hay errores en la función
SELECT * FROM get_or_generate_active_challenge();
```

Comparte los resultados y te ayudo a diagnosticar.

---

## ✅ Checklist Final

- [ ] Ejecutar `DIAGNOSTICO_Y_GENERAR_RETO.sql` en Supabase
- [ ] Ver mensaje "✅ Nuevo reto generado!"
- [ ] Cerrar app completamente
- [ ] Abrir app
- [ ] Verificar que hay un reto activo

---

¡Ejecuta el SQL ahora y debería funcionar! 🚀
