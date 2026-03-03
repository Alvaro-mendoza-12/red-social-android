-- ============================================
-- VERIFICAR ESTRUCTURA DE TABLAS
-- ============================================
-- Ejecuta este SQL primero para ver qué columnas tienes exactamente

-- Ver columnas de la tabla VIDEOS
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_name = 'videos' 
AND table_schema = 'public'
ORDER BY ordinal_position;

-- Ver columnas de la tabla POSTS
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_name = 'posts' 
AND table_schema = 'public'
ORDER BY ordinal_position;

-- Ver columnas de la tabla VIDEO_COMMENTS
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_name = 'video_comments' 
AND table_schema = 'public'
ORDER BY ordinal_position;
