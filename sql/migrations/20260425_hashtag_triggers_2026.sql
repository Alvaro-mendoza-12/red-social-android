-- ============================================
-- Hashtag Triggers (Ejecutar DESPUÉS de las tablas)
-- FECHA: 2026-04-25
-- PURPOSE: Triggers para auto-etiquetado después de crear tablas
-- ============================================

-- Triggers para auto-tagging
DROP TRIGGER IF EXISTS auto_tag_post_trigger ON posts;
CREATE TRIGGER auto_tag_post_trigger
    AFTER INSERT OR UPDATE OF content ON posts
    FOR EACH ROW EXECUTE FUNCTION auto_tag_post();

DROP TRIGGER IF EXISTS auto_tag_video_trigger ON videos;
CREATE TRIGGER auto_tag_video_trigger
    AFTER INSERT OR UPDATE OF description ON videos
    FOR EACH ROW EXECUTE FUNCTION auto_tag_video();