-- Reconcile media metadata created before fallback_users tracked avatar type.
-- The override/storage tables are runtime-managed in very old installations,
-- so the migration remains safe even during a brand-new bootstrap.
DO $$
BEGIN
    IF to_regclass('public.fallback_profile_overrides') IS NOT NULL THEN
        EXECUTE $sql$
            UPDATE fallback_users u
               SET avatar_media_type = CASE
                   WHEN lower(COALESCE(po.profile->>'avatar_media_type', '')) IN ('image', 'gif', 'video')
                       THEN lower(po.profile->>'avatar_media_type')
                   WHEN lower(COALESCE(u.avatar_url, '')) ~ '\.(mp4|m4v|mov)(\?|$)'
                       THEN 'video'
                   ELSE u.avatar_media_type
               END
              FROM fallback_profile_overrides po
             WHERE po.user_id = u.id
               AND u.avatar_media_type IS NULL
        $sql$;
    END IF;
END
$$;

-- A gamer avatar is optional and inherits the social avatar. Remove only
-- broken references to Novvin's own storage; arbitrary external HTTPS URLs
-- are left untouched for backwards compatibility.
DO $$
BEGIN
    IF to_regclass('public.fallback_storage_objects') IS NOT NULL THEN
        EXECUTE $sql$
            UPDATE fallback_gaming_profiles gp
               SET gamer_avatar_url = NULL,
                   gamer_avatar_media_type = NULL,
                   updated_at = NOW()
             WHERE NULLIF(BTRIM(gp.gamer_avatar_url), '') IS NOT NULL
               AND gp.gamer_avatar_url LIKE '%/storage/v1/object/public/%'
               AND NOT EXISTS (
                   SELECT 1
                     FROM fallback_storage_objects so
                    WHERE gp.gamer_avatar_url LIKE '%/storage/v1/object/public/' || so.bucket_id || '/' || so.name
               )
        $sql$;
    END IF;
END
$$;
