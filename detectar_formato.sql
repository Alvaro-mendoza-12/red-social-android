-- ============================================
-- PASO 1: DETECTAR FORMATO DE COLUMNAS
-- ============================================
-- Ejecuta SOLO esta parte primero para saber qué formato usas

SELECT 
    'VIDEOS usa: ' || 
    CASE 
        WHEN EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_name = 'videos' AND column_name = 'userName'
        ) THEN 'camelCase (userName, userId)'
        WHEN EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_name = 'videos' AND column_name = 'user_name'
        ) THEN 'snake_case (user_name, user_id)'
        ELSE 'TABLA NO ENCONTRADA'
    END as formato_videos,
    
    'VIDEO_COMMENTS usa: ' ||
    CASE 
        WHEN EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_name = 'video_comments' AND column_name = 'userId'
        ) THEN 'camelCase (userId)'
        WHEN EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_name = 'video_comments' AND column_name = 'user_id'
        ) THEN 'snake_case (user_id)'
        ELSE 'TABLA NO ENCONTRADA O SIN userId'
    END as formato_comments;

-- ============================================
-- DESPUÉS DE EJECUTAR LO DE ARRIBA, AVÍSAME EL RESULTADO
-- Y TE DARÉ EL SCRIPT CORRECTO
-- ============================================
