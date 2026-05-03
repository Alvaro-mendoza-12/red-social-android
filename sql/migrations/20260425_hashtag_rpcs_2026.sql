-- ============================================
-- Hashtag RPCs: get_posts_by_hashtag, get_videos_by_hashtag
-- FECHA: 2026-04-25
-- PURPOSE: Obtener contenido filtrado por hashtag específico
-- ============================================

-- RPC: Obtener posts por hashtag
CREATE OR REPLACE FUNCTION get_posts_by_hashtag(
    p_hashtag_name TEXT,
    p_limit INT DEFAULT 20,
    p_offset INT DEFAULT 0
)
RETURNS TABLE (
    id UUID,
    user_id UUID,
    content TEXT,
    media_urls JSONB,
    created_at TIMESTAMPTZ,
    like_count INT,
    comment_count INT,
    share_count INT,
    is_verified BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        p.id, 
        p.user_id, 
        p.content, 
        p.media_urls, 
        p.created_at,
        p.like_count,
        p.comment_count,
        p.share_count,
        u.is_verified
    FROM posts p
    INNER JOIN post_hashtags ph ON ph.post_id = p.id
    INNER JOIN hashtags h ON h.id = ph.hashtag_id
    LEFT JOIN profiles u ON u.id = p.user_id
    WHERE h.name = LOWER(TRIM(p_hashtag_name))
    ORDER BY p.created_at DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- RPC: Obtener videos por hashtag
CREATE OR REPLACE FUNCTION get_videos_by_hashtag(
    p_hashtag_name TEXT,
    p_limit INT DEFAULT 20,
    p_offset INT DEFAULT 0
)
RETURNS TABLE (
    id UUID,
    title TEXT,
    description TEXT,
    video_url TEXT,
    thumbnail_url TEXT,
    user_id UUID,
    created_at TIMESTAMPTZ,
    view_count INT,
    like_count INT,
    is_verified BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        v.id, 
        v.title, 
        v.description, 
        v.video_url, 
        v.thumbnail_url,
        v.user_id,
        v.created_at,
        v.view_count,
        v.like_count,
        u.is_verified
    FROM videos v
    INNER JOIN video_hashtags vh ON vh.video_id = v.id
    INNER JOIN hashtags h ON h.id = vh.hashtag_id
    LEFT JOIN profiles u ON u.id = v.user_id
    WHERE h.name = LOWER(TRIM(p_hashtag_name))
    ORDER BY v.created_at DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- Índices para optimizar estas consultas
CREATE INDEX IF NOT EXISTS idx_post_hashtags_hashtag_id ON post_hashtags(hashtag_id);
CREATE INDEX IF NOT EXISTS idx_video_hashtags_hashtag_id ON video_hashtags(hashtag_id);

-- NOTA: Agregar este archivo a sql/release-manifest.txt después de ejecutar
