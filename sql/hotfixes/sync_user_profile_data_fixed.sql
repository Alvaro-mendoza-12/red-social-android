-- ============================================
-- SINCRONIZACIÓN AUTOMÁTICA DE DATOS DE PERFIL
-- VERSIÓN CORREGIDA PARA SNAKE_CASE
-- ============================================
-- Este script crea triggers que automáticamente actualizan
-- los datos de usuario en posts, videos y comentarios cuando
-- el perfil del usuario cambia.
--
-- Ejecutar en: Supabase Dashboard → SQL Editor
-- ============================================

-- ============================================
-- 1. FUNCIÓN DEL TRIGGER
-- ============================================
-- Esta función se ejecuta automáticamente cuando se actualiza
-- un perfil y sincroniza los cambios a todo el contenido del usuario

CREATE OR REPLACE FUNCTION sync_user_profile_to_content()
RETURNS TRIGGER AS $$
DECLARE
    display_name_value TEXT;
    updated_posts_count INT;
    updated_videos_count INT;
    updated_comments_count INT;
BEGIN
    -- Solo proceder si los campos relevantes cambiaron
    IF (OLD.full_name IS DISTINCT FROM NEW.full_name) OR
       (OLD.display_name IS DISTINCT FROM NEW.display_name) OR
       (OLD.username IS DISTINCT FROM NEW.username) OR
       (OLD.avatar_url IS DISTINCT FROM NEW.avatar_url) OR
       (OLD.is_verified IS DISTINCT FROM NEW.is_verified) THEN
        
        -- Calcular el nombre de visualización (misma lógica que User.kt)
        -- Prioridad: display_name > full_name > username > email
        display_name_value := COALESCE(
            NEW.display_name, 
            NEW.full_name, 
            NEW.username, 
            split_part(NEW.email, '@', 1)
        );
        
        -- ========================================
        -- ACTUALIZAR TABLA: posts
        -- ========================================
        UPDATE public.posts
        SET user_name = display_name_value,
            user_profile_image = NEW.avatar_url,
            is_verified = NEW.is_verified
        WHERE user_id = NEW.id;
        
        GET DIAGNOSTICS updated_posts_count = ROW_COUNT;
        
        -- ========================================
        -- ACTUALIZAR TABLA: videos (probando ambos formatos)
        -- ========================================
        -- Intentar con camelCase primero
        BEGIN
            UPDATE public.videos
            SET userName = display_name_value,
                userProfileImage = NEW.avatar_url,
                is_verified = NEW.is_verified
            WHERE userId = NEW.id;
            
            GET DIAGNOSTICS updated_videos_count = ROW_COUNT;
        EXCEPTION WHEN undefined_column THEN
            -- Si falla, intentar con snake_case
            UPDATE public.videos
            SET user_name = display_name_value,
                user_profile_image = NEW.avatar_url,
                is_verified = NEW.is_verified
            WHERE user_id = NEW.id;
            
            GET DIAGNOSTICS updated_videos_count = ROW_COUNT;
        END;
        
        -- ========================================
        -- ACTUALIZAR TABLA: video_comments
        -- ========================================
        -- Intentar con camelCase primero
        BEGIN
            UPDATE public.video_comments
            SET user_name = display_name_value,
                user_profile_image = NEW.avatar_url
            WHERE userId = NEW.id;
            
            GET DIAGNOSTICS updated_comments_count = ROW_COUNT;
        EXCEPTION WHEN undefined_column THEN
            -- Si falla, intentar con snake_case
            UPDATE public.video_comments
            SET user_name = display_name_value,
                user_profile_image = NEW.avatar_url
            WHERE user_id = NEW.id;
            
            GET DIAGNOSTICS updated_comments_count = ROW_COUNT;
        END;
        
        -- Log para debugging (visible en Supabase logs)
        RAISE LOG 'Profile sync for user %: % posts, % videos, % comments updated', 
                  NEW.id,
                  updated_posts_count,
                  updated_videos_count,
                  updated_comments_count;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ============================================
-- 2. CREAR EL TRIGGER
-- ============================================
-- Este trigger llama a la función cada vez que se actualiza un perfil

-- Primero eliminar si ya existe (para re-ejecuciones)
DROP TRIGGER IF EXISTS trigger_sync_profile_to_content ON public.profiles;

-- Crear el trigger
CREATE TRIGGER trigger_sync_profile_to_content
    AFTER UPDATE ON public.profiles
    FOR EACH ROW
    EXECUTE FUNCTION sync_user_profile_to_content();

-- ============================================
-- 3. MIGRACIÓN DE DATOS EXISTENTES
-- ============================================
-- Esta sección ARREGLA todos los posts/videos antiguos que tienen
-- datos desactualizados (verificado, foto de perfil, nombre)
-- SE EJECUTA AUTOMÁTICAMENTE para limpiar todo de una vez

DO $$
DECLARE
    affected_posts INT := 0;
    affected_videos INT := 0;
    affected_comments INT := 0;
    display_name_calc TEXT;
BEGIN
    RAISE NOTICE '========================================';
    RAISE NOTICE 'INICIANDO MIGRACIÓN DE DATOS ANTIGUOS';
    RAISE NOTICE '========================================';
    
    -- ========================================
    -- Arreglar TODOS los posts existentes
    -- ========================================
    RAISE NOTICE 'Sincronizando posts...';
    
    UPDATE public.posts p
    SET user_name = COALESCE(pr.display_name, pr.full_name, pr.username, split_part(pr.email, '@', 1)),
        user_profile_image = pr.avatar_url,
        is_verified = pr.is_verified
    FROM public.profiles pr
    WHERE p.user_id = pr.id
      AND (p.user_name IS DISTINCT FROM COALESCE(pr.display_name, pr.full_name, pr.username, split_part(pr.email, '@', 1))
           OR p.user_profile_image IS DISTINCT FROM pr.avatar_url
           OR p.is_verified IS DISTINCT FROM pr.is_verified);
    
    GET DIAGNOSTICS affected_posts = ROW_COUNT;
    RAISE NOTICE '✅ Posts actualizados: %', affected_posts;
    
    -- ========================================
    -- Arreglar TODOS los videos existentes
    -- ========================================
    RAISE NOTICE 'Sincronizando videos...';
    
    -- Intentar primero con camelCase
    BEGIN
        UPDATE public.videos v
        SET userName = COALESCE(pr.display_name, pr.full_name, pr.username, split_part(pr.email, '@', 1)),
            userProfileImage = pr.avatar_url,
            is_verified = pr.is_verified
        FROM public.profiles pr
        WHERE v.userId = pr.id
          AND (v.userName IS DISTINCT FROM COALESCE(pr.display_name, pr.full_name, pr.username, split_part(pr.email, '@', 1))
               OR v.userProfileImage IS DISTINCT FROM pr.avatar_url
               OR v.is_verified IS DISTINCT FROM pr.is_verified);
        
        GET DIAGNOSTICS affected_videos = ROW_COUNT;
    EXCEPTION WHEN undefined_column THEN
        -- Si falla, usar snake_case
        RAISE NOTICE 'Detectado snake_case en videos, ajustando...';
        UPDATE public.videos v
        SET user_name = COALESCE(pr.display_name, pr.full_name, pr.username, split_part(pr.email, '@', 1)),
            user_profile_image = pr.avatar_url,
            is_verified = pr.is_verified
        FROM public.profiles pr
        WHERE v.user_id = pr.id
          AND (v.user_name IS DISTINCT FROM COALESCE(pr.display_name, pr.full_name, pr.username, split_part(pr.email, '@', 1))
               OR v.user_profile_image IS DISTINCT FROM pr.avatar_url
               OR v.is_verified IS DISTINCT FROM pr.is_verified);
        
        GET DIAGNOSTICS affected_videos = ROW_COUNT;
    END;
    
    RAISE NOTICE '✅ Videos actualizados: %', affected_videos;
    
    -- ========================================
    -- Arreglar TODOS los comentarios existentes
    -- ========================================
    RAISE NOTICE 'Sincronizando comentarios de video...';
    
    -- Intentar primero con camelCase para userId
    BEGIN
        UPDATE public.video_comments vc
        SET user_name = COALESCE(pr.display_name, pr.full_name, pr.username, split_part(pr.email, '@', 1)),
            user_profile_image = pr.avatar_url
        FROM public.profiles pr
        WHERE vc.userId = pr.id
          AND (vc.user_name IS DISTINCT FROM COALESCE(pr.display_name, pr.full_name, pr.username, split_part(pr.email, '@', 1))
               OR vc.user_profile_image IS DISTINCT FROM pr.avatar_url);
        
        GET DIAGNOSTICS affected_comments = ROW_COUNT;
    EXCEPTION WHEN undefined_column THEN
        -- Si falla, usar snake_case
        RAISE NOTICE 'Detectado snake_case en video_comments, ajustando...';
        UPDATE public.video_comments vc
        SET user_name = COALESCE(pr.display_name, pr.full_name, pr.username, split_part(pr.email, '@', 1)),
            user_profile_image = pr.avatar_url
        FROM public.profiles pr
        WHERE vc.user_id = pr.id
          AND (vc.user_name IS DISTINCT FROM COALESCE(pr.display_name, pr.full_name, pr.username, split_part(pr.email, '@', 1))
               OR vc.user_profile_image IS DISTINCT FROM pr.avatar_url);
        
        GET DIAGNOSTICS affected_comments = ROW_COUNT;
    END;
    
    RAISE NOTICE '✅ Comentarios actualizados: %', affected_comments;
    
    RAISE NOTICE '========================================';
    RAISE NOTICE 'MIGRACIÓN COMPLETADA EXITOSAMENTE';
    RAISE NOTICE 'Total actualizado: % registros', affected_posts + affected_videos + affected_comments;
    RAISE NOTICE '========================================';
END $$;

-- ============================================
-- 4. QUERIES DE VERIFICACIÓN
-- ============================================
-- Ejecuta estas queries para verificar que el trigger funcione

-- Verificar que el trigger fue creado
SELECT trigger_name, event_manipulation, event_object_table 
FROM information_schema.triggers 
WHERE trigger_name = 'trigger_sync_profile_to_content';

-- ============================================
-- ROLLBACK (SI ES NECESARIO)
-- ============================================
-- Si necesitas deshacer los cambios, ejecuta:

/*
DROP TRIGGER IF EXISTS trigger_sync_profile_to_content ON public.profiles;
DROP FUNCTION IF EXISTS sync_user_profile_to_content();
*/

-- ============================================
-- FIN DEL SCRIPT
-- ============================================
