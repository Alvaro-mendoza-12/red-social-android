-- ============================================
-- Hashtag System & Full-Text Search
-- FECHA: 2026-04-25
-- PURPOSE: Tagging para posts, videos y búsqueda por contenido
-- ============================================

-- 1. Tabla principal de hashtags
CREATE TABLE IF NOT EXISTS hashtags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    post_count INT NOT NULL DEFAULT 0,
    video_count INT NOT NULL DEFAULT 0,
    trending_score DECIMAL(5,2) DEFAULT 0.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Índices para búsqueda
CREATE INDEX IF NOT EXISTS idx_hashtags_name ON hashtags(name);
CREATE INDEX IF NOT EXISTS idx_hashtags_trending ON hashtags(trending_score DESC);

-- 2. Tabla intermedia posts <-> hashtags (many-to-many)
CREATE TABLE IF NOT EXISTS post_hashtags (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    hashtag_id UUID NOT NULL REFERENCES hashtags(id) ON DELETE CASCADE,
    position INT NOT NULL DEFAULT 0, -- posición en el texto (para orden)
    PRIMARY KEY (post_id, hashtag_id)
);

CREATE INDEX IF NOT EXISTS idx_post_hashtags_post ON post_hashtags(post_id);
CREATE INDEX IF NOT EXISTS idx_post_hashtags_hashtag ON post_hashtags(hashtag_id);

-- 3. Tabla intermedia videos <-> hashtags (many-to-many)
CREATE TABLE IF NOT EXISTS video_hashtags (
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    hashtag_id UUID NOT NULL REFERENCES hashtags(id) ON DELETE CASCADE,
    position INT NOT NULL DEFAULT 0,
    PRIMARY KEY (video_id, hashtag_id)
);

CREATE INDEX IF NOT EXISTS idx_video_hashtags_video ON video_hashtags(video_id);
CREATE INDEX IF NOT EXISTS idx_video_hashtags_hashtag ON video_hashtags(hashtag_id);

-- 4. Full-Text Search vector column en posts y videos
-- Nota: PostgreSQL FTS usando GIN indexes
ALTER TABLE posts ADD COLUMN IF NOT EXISTS content_tsv tsvector;
UPDATE posts SET content_tsv = to_tsvector('spanish', coalesce(content, ''));
CREATE INDEX IF NOT EXISTS idx_posts_content_fts ON posts USING gin(content_tsv);

ALTER TABLE videos ADD COLUMN IF NOT EXISTS description_tsv tsvector;
UPDATE videos SET description_tsv = to_tsvector('spanish', coalesce(description, ''));
CREATE INDEX IF NOT EXISTS idx_videos_description_fts ON videos USING gin(description_tsv);

-- 5. Trigger para actualizar tsvector automáticamente
CREATE OR REPLACE FUNCTION posts_tsvector_trigger() RETURNS trigger AS $$
BEGIN
    NEW.content_tsv := to_tsvector('spanish', coalesce(NEW.content, ''));
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION videos_tsvector_trigger() RETURNS trigger AS $$
BEGIN
    NEW.description_tsv := to_tsvector('spanish', coalesce(NEW.description, ''));
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tsvectorupdate ON posts;
CREATE TRIGGER tsvectorupdate BEFORE INSERT OR UPDATE OF content ON posts
    FOR EACH ROW EXECUTE FUNCTION posts_tsvector_trigger();

DROP TRIGGER IF EXISTS tsvectorupdate_videos ON videos;
CREATE TRIGGER tsvectorupdate_videos BEFORE INSERT OR UPDATE OF description ON videos
    FOR EACH ROW EXECUTE FUNCTION videos_tsvector_trigger();

-- 6. Función RPC para buscar hashtags por nombre (autocomplete)
CREATE OR REPLACE FUNCTION search_hashtags(
    p_query TEXT,
    p_limit INT DEFAULT 10
)
RETURNS TABLE (
    id UUID,
    name TEXT,
    post_count INT,
    video_count INT,
    trending_score DECIMAL(5,2)
) AS $$
BEGIN
    RETURN QUERY
    SELECT h.id, h.name, h.post_count, h.video_count, h.trending_score
    FROM hashtags h
    WHERE h.name ILIKE ('%' || p_query || '%')
    ORDER BY h.trending_score DESC, h.post_count + h.video_count DESC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 7. Función RPC para obtener hashtags trending (basado en score y reciente)
CREATE OR REPLACE FUNCTION get_trending_hashtags(
    p_days_back INT DEFAULT 7,
    p_limit INT DEFAULT 20
)
RETURNS TABLE (
    id UUID,
    name TEXT,
    post_count INT,
    video_count INT,
    trending_score DECIMAL(5,2)
) AS $$
BEGIN
    -- Por ahora simple: ordenar por trending_score (que debería calcularse periódicamente)
    -- En futura versión: trending_score = log(post_count+1) * 0.5 + (recent_posts_last_24h * 0.5)
    RETURN QUERY
    SELECT h.id, h.name, h.post_count, h.video_count, h.trending_score
    FROM hashtags h
    ORDER BY h.trending_score DESC, h.updated_at DESC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 8. Función para extraer hashtags de un texto y normalizarlos
CREATE OR REPLACE FUNCTION extract_hashtags(
    p_text TEXT,
    p_max_hashtags INT DEFAULT 5
)
RETURNS TEXT[] AS $$
DECLARE
    raw_tags TEXT[] := '{}';
    result TEXT[] := ARRAY[]::TEXT[];
    current_tag TEXT;
BEGIN
    IF p_text IS NULL OR p_text = '' THEN
        RETURN result;
    END IF;

    -- Extraer palabras que empiezan con #hashtag
    -- Soporta: #hola, #hola_mundo, #hola-mundo, #Hola123
    raw_tags := ARRAY(
        SELECT DISTINCT LOWER(TRIM(BOTH '#' FROM word))
        FROM regexp_matches(p_text, '#[a-zA-Z0-9_]+', 'g') AS word
    );

    -- Limpiar y normalizar: solo alfanuméricos y guion bajo, longitud 2-50
    FOREACH current_tag IN ARRAY raw_tags LOOP
        IF LENGTH(current_tag) BETWEEN 2 AND 50 AND current_tag ~ '^[a-z0-9_]+$' THEN
            result := array_append(result, current_tag);
        END IF;
    END LOOP;

    -- Limitar cantidad y eliminar duplicados
    result := ARRAY(SELECT DISTINCT unnest(result) LIMIT p_max_hashtags);

    RETURN result;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- 9. Trigger para auto-etiquetar posts al insertar/actualizar
CREATE OR REPLACE FUNCTION auto_tag_post()
RETURNS TRIGGER AS $$
DECLARE
    tags TEXT[] := '{}';
    current_tag TEXT;
    hashtag_id UUID;
BEGIN
    tags := COALESCE(extract_hashtags(NEW.content), '{}'::TEXT[]);

    -- Para cada hashtag, upsert en tabla hashtags y asociar al post
    FOREACH current_tag IN ARRAY tags LOOP
        -- Upsert hashtag (incrementar post_count si ya existe)
        INSERT INTO hashtags (name) VALUES (current_tag)
        ON CONFLICT (name) DO UPDATE SET post_count = hashtags.post_count + 1;

        -- Obtener id
        SELECT id INTO hashtag_id FROM hashtags WHERE name = current_tag;

        -- Asociar post-hashtag
        INSERT INTO post_hashtags (post_id, hashtag_id) VALUES (NEW.id, hashtag_id)
        ON CONFLICT DO NOTHING;
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION auto_tag_video()
RETURNS TRIGGER AS $$
DECLARE
    tags TEXT[] := '{}';
    current_tag TEXT;
    hashtag_id UUID;
BEGIN
    tags := COALESCE(extract_hashtags(NEW.description), '{}'::TEXT[]);

    FOREACH current_tag IN ARRAY tags LOOP
        INSERT INTO hashtags (name) VALUES (current_tag)
        ON CONFLICT (name) DO UPDATE SET video_count = hashtags.video_count + 1;

        SELECT id INTO hashtag_id FROM hashtags WHERE name = current_tag;
        INSERT INTO video_hashtags (video_id, hashtag_id) VALUES (NEW.id, hashtag_id)
        ON CONFLICT DO NOTHING;
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Triggers para auto-tagging (mover al final después de crear tablas)
-- DROP TRIGGER IF EXISTS auto_tag_post_trigger ON posts;
-- CREATE TRIGGER auto_tag_post_trigger
--     AFTER INSERT OR UPDATE OF content ON posts
--     FOR EACH ROW EXECUTE FUNCTION auto_tag_post();

-- DROP TRIGGER IF EXISTS auto_tag_video_trigger ON videos;
-- CREATE TRIGGER auto_tag_video_trigger
--     AFTER INSERT OR UPDATE OF description ON videos
--     FOR EACH ROW EXECUTE FUNCTION auto_tag_video();

-- 10. Politicas RLS para hashtags (lectura pública, escritura solo por server/autenticado)
ALTER TABLE hashtags ENABLE ROW LEVEL SECURITY;
ALTER TABLE post_hashtags ENABLE ROW LEVEL SECURITY;
ALTER TABLE video_hashtags ENABLE ROW LEVEL SECURITY;

-- Política: anyone can read hashtags (público)
CREATE POLICY "hashtags_select_public" ON hashtags
    FOR SELECT USING (auth.role() = 'authenticated' OR true);

-- Política: solo inserts via RPC (no direct insert desde client)
DROP POLICY IF EXISTS "hashtags_insert_rpc" ON hashtags;
CREATE POLICY "hashtags_insert_rpc" ON hashtags
    FOR INSERT WITH CHECK (auth.role() = 'service_role');

-- Política: updates solo via RPC
DROP POLICY IF EXISTS "hashtags_update_rpc" ON hashtags;
CREATE POLICY "hashtags_update_rpc" ON hashtags
    FOR UPDATE USING (auth.role() = 'service_role');

-- Post hashtags: read público, insert/delete solo via RPC
CREATE POLICY "post_hashtags_select_public" ON post_hashtags
    FOR SELECT USING (true);

CREATE POLICY "post_hashtags_insert_rpc" ON post_hashtags
    FOR INSERT WITH CHECK (auth.role() = 'service_role');

CREATE POLICY "video_hashtags_select_public" ON video_hashtags
    FOR SELECT USING (true);

CREATE POLICY "video_hashtags_insert_rpc" ON video_hashtags
    FOR INSERT WITH CHECK (auth.role() = 'service_role');

-- 11. View para búsqueda combinada (posts + videos + hashtags)
CREATE OR REPLACE VIEW searchable_content AS
SELECT 
    'post' AS content_type,
    p.id AS content_id,
    p.content AS searchable_text,
    p.created_at,
    u.username AS author_username,
    u.display_name AS author_display_name,
    ARRAY_AGG(DISTINCT h.name) FILTER (WHERE h.name IS NOT NULL) AS hashtags
FROM posts p
JOIN profiles u ON u.id = p.user_id
LEFT JOIN post_hashtags ph ON ph.post_id = p.id
LEFT JOIN hashtags h ON h.id = ph.hashtag_id
GROUP BY p.id, u.username, u.display_name

UNION ALL

SELECT 
    'video' AS content_type,
    v.id AS content_id,
    COALESCE(v.description, '') AS searchable_text,
    v.created_at,
    u.username AS author_username,
    u.display_name AS author_display_name,
    ARRAY_AGG(DISTINCT h.name) FILTER (WHERE h.name IS NOT NULL) AS hashtags
FROM videos v
JOIN profiles u ON u.id = v.user_id
LEFT JOIN video_hashtags vh ON vh.video_id = v.id
LEFT JOIN hashtags h ON h.id = vh.hashtag_id
GROUP BY v.id, u.username, u.display_name;

-- Índice FTS sobre la vista materializada? No se puede, pero podemos buscar directo en tablas

-- 12. Función RPC de búsqueda global (posts + videos + hashtags)
CREATE OR REPLACE FUNCTION search_content(
    p_query TEXT,
    p_limit INT DEFAULT 20,
    p_offset INT DEFAULT 0
)
RETURNS TABLE (
    content_type TEXT,
    content_id UUID,
    title TEXT,
    description TEXT,
    author_username TEXT,
    author_display_name TEXT,
    hashtags TEXT[],
    rank REAL
) AS $$
BEGIN
    -- Búsqueda usando tsvector en posts y videos + hashtags LIKE
    RETURN QUERY
    WITH post_results AS (
        SELECT 
            'post'::TEXT AS content_type,
            p.id AS content_id,
            p.content AS title,
            NULL::TEXT AS description,
            u.username AS author_username,
            u.display_name AS author_display_name,
            ARRAY_AGG(DISTINCT h.name) AS hashtags,
            ts_rank(p.content_tsv, plainto_tsquery('spanish', p_query)) AS rank
        FROM posts p
        JOIN profiles u ON u.id = p.user_id
        LEFT JOIN post_hashtags ph ON ph.post_id = p.id
        LEFT JOIN hashtags h ON h.id = ph.hashtag_id
        WHERE p.content_tsv @@ plainto_tsquery('spanish', p_query)
        GROUP BY p.id, u.username, u.display_name, p.content_tsv
    ),
    video_results AS (
        SELECT 
            'video'::TEXT AS content_type,
            v.id AS content_id,
            v.title AS title,
            v.description AS description,
            u.username AS author_username,
            u.display_name AS author_display_name,
            ARRAY_AGG(DISTINCT h.name) AS hashtags,
            ts_rank(v.description_tsv, plainto_tsquery('spanish', p_query)) AS rank
        FROM videos v
        JOIN profiles u ON u.id = v.user_id
        LEFT JOIN video_hashtags vh ON vh.video_id = v.id
        LEFT JOIN hashtags h ON h.id = vh.hashtag_id
        WHERE v.description_tsv @@ plainto_tsquery('spanish', p_query)
        GROUP BY v.id, u.username, u.display_name, v.description_tsv
    ),
    hashtag_results AS (
        SELECT 
            'hashtag'::TEXT AS content_type,
            h.id AS content_id,
            h.name AS title,
            NULL::TEXT AS description,
            NULL::TEXT AS author_username,
            NULL::TEXT AS author_display_name,
            ARRAY[]::TEXT[] AS hashtags,
            1.0 AS rank  -- Hashtags exactos tienen alta relevancia
        FROM hashtags h
        WHERE h.name ILIKE ('%' || p_query || '%')
    )
    SELECT * FROM (
        SELECT * FROM post_results
        UNION ALL
        SELECT * FROM video_results
        UNION ALL
        SELECT * FROM hashtag_results
    ) AS combined
    ORDER BY rank DESC, content_type, content_id
    LIMIT p_limit OFFSET p_offset;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- 13. Incluir en release manifest
-- (Agregar este archivo a sql/release-manifest.txt)

-- ============================================
-- NOTAS DE IMPLEMENTACIÓN ANDROID/WEB:
-- ============================================
-- 1. Extraer hashtags en el cliente usando `extract_hashtags` o regex local (#\w+)
-- 2. Al crear post/video, enviar array de hashtags junto al contenido
-- 3. Backend (RPC) llamará a auto_tag_post/auto_tag_video automáticamente
-- 4. Para búsqueda, llamar a `search_content(query)` vía Supabase RPC
-- 5. UI: Hashtags clickeables que navegan a página de hashtag (ej. /hashtag/hola)
-- 6. Página de hashtag muestra posts/videos con ese tag
-- 7. Trending page llama a get_trending_hashtags()
-- ============================================
