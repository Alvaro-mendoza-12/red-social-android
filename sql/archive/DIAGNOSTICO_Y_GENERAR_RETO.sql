-- ================================================================
-- DIAGNÓSTICO Y FIX: Generación de Nuevos Retos
-- ================================================================
-- Cuando el podio termina, debería generar un nuevo reto automáticamente
-- Este SQL verifica y arregla el problema
-- ================================================================

-- PASO 1: Ver cuántos retos hay disponibles en challenges_master
DO $$
DECLARE
    total_challenges INTEGER;
BEGIN
    SELECT COUNT(*) INTO total_challenges FROM challenges_master;
    
    RAISE NOTICE '================================================';
    RAISE NOTICE '📊 DIAGNÓSTICO DE RETOS';
    RAISE NOTICE '================================================';
    RAISE NOTICE 'Total de retos en la base de datos: %', total_challenges;
    
    IF total_challenges = 0 THEN
        RAISE WARNING '❌ NO HAY RETOS! La base de datos está vacía';
        RAISE NOTICE 'Se insertarán retos de ejemplo...';
    ELSIF total_challenges < 5 THEN
        RAISE NOTICE '⚠️ Pocos retos (menos de 5)';
        RAISE NOTICE 'Se agregarán más retos...';
    ELSE
        RAISE NOTICE '✅ Hay suficientes retos';
    END IF;
    RAISE NOTICE '';
END $$;

-- PASO 2: Ver el estado actual de active_challenges
DO $$
DECLARE
    current_status TEXT;
    current_id UUID;
    time_remaining INTERVAL;
BEGIN
    SELECT status, id, 
           CASE 
               WHEN status = 'voting' THEN voting_expires_at - NOW()
               ELSE expires_at - NOW()
           END
    INTO current_status, current_id, time_remaining
    FROM active_challenges
    ORDER BY created_at DESC
    LIMIT 1;
    
    IF current_status IS NULL THEN
        RAISE NOTICE '📋 Estado actual: No hay reto activo';
    ELSE
        RAISE NOTICE '📋 Estado actual:';
        RAISE NOTICE '   Status: %', current_status;
        RAISE NOTICE '   ID: %', current_id;
        RAISE NOTICE '   Tiempo restante: %', time_remaining;
    END IF;
    RAISE NOTICE '';
END $$;

-- PASO 3: Insertar retos si no hay suficientes
INSERT INTO challenges_master (title, description, challenge_type, difficulty)
VALUES 
    ('Confesión anónima', 'Escribe algo que nadie sepa de ti.', 'text', 'easy'),
    ('Adivina el acertijo', 'Me tienes hoy y me esperas mañana. ¿Qué soy?', 'text', 'medium'),
    ('Dibuja un autorretrato', 'Usa el lienzo para dibujar tu cara sin mirarte al espejo.', 'drawing', 'medium'),
    ('Foto graciosa', 'Tómate la selfie más graciosa que puedas.', 'camera', 'easy'),
    ('Canta tu canción favorita', 'Graba un audio cantando el coro de una canción.', 'audio', 'medium'),
    ('Baile improvisado', 'Sube un video de 5 segundos bailando lo más raro posible.', 'camera', 'hard'),
    ('Dibuja un unicornio punk', 'Usa el lienzo para dibujar un unicornio con estilo punk rock.', 'drawing', 'hard'),
    ('Teléfono Malogrado', 'Describe el último sueño que tuviste en una frase.', 'text', 'easy'),
    ('Dibujo a ciegas', 'Dibuja tu animal favorito sin levantar el dedo de la pantalla.', 'drawing', 'medium'),
    ('Chiste malo', 'Escribe el peor chiste que conozcas.', 'text', 'easy')
ON CONFLICT DO NOTHING;

-- PASO 4: Limpiar retos stuck en estado completed
UPDATE active_challenges
SET status = 'active',
    expires_at = NOW() - INTERVAL '1 second'  -- Hacer que expire inmediatamente
WHERE status = 'completed' 
  AND expires_at > NOW();  -- Si todavía no ha expirado pero está completed

-- PASO 5: Forzar generación de un nuevo reto si no hay ninguno activo
DO $$
DECLARE
    v_current_status TEXT;
BEGIN
    SELECT status INTO v_current_status
    FROM active_challenges
    ORDER BY created_at DESC
    LIMIT 1;
    
    -- Si no hay reto o está completed y ya expiró, generar uno nuevo
    IF v_current_status IS NULL OR 
       (v_current_status = 'completed' AND 
        (SELECT expires_at FROM active_challenges ORDER BY created_at DESC LIMIT 1) <= NOW())
    THEN
        RAISE NOTICE '🔄 Generando nuevo reto...';
        
        INSERT INTO active_challenges (challenge_id, expires_at, status)
        SELECT id, NOW() + INTERVAL '10 minutes', 'active'
        FROM challenges_master
        ORDER BY RANDOM()
        LIMIT 1;
        
        RAISE NOTICE '✅ Nuevo reto generado!';
    END IF;
END $$;

-- PASO 6: Verificación final
DO $$
DECLARE
    final_status TEXT;
    final_title TEXT;
    final_expires TIMESTAMP WITH TIME ZONE;
BEGIN
    SELECT 
        ac.status,
        cm.title,
        ac.expires_at
    INTO final_status, final_title, final_expires
    FROM active_challenges ac
    JOIN challenges_master cm ON cm.id = ac.challenge_id
    ORDER BY ac.created_at DESC
    LIMIT 1;
    
    RAISE NOTICE '================================================';
    RAISE NOTICE '✅ VERIFICACIÓN FINAL';
    RAISE NOTICE '================================================';
    RAISE NOTICE 'Reto actual:';
    RAISE NOTICE '  Título: %', final_title;
    RAISE NOTICE '  Estado: %', final_status;
    RAISE NOTICE '  Expira en: % segundos', EXTRACT(EPOCH FROM (final_expires - NOW()));
    RAISE NOTICE '';
    RAISE NOTICE '📱 SIGUIENTE PASO:';
    RAISE NOTICE '  1. Cierra y abre la app';
    RAISE NOTICE '  2. Ve a la sección de Retos';
    RAISE NOTICE '  3. Deberías ver un nuevo reto activo ✅';
    RAISE NOTICE '================================================';
END $$;
