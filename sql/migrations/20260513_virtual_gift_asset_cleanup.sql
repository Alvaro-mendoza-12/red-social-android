-- ============================================
-- Virtual Gift Asset Cleanup
-- FECHA: 2026-05-13
-- PURPOSE: reemplaza URLs placeholder example.com por referencias internas
-- ============================================

UPDATE virtual_gifts
SET
    image_url = CASE name
        WHEN 'Corazón' THEN 'conectate://gift-assets/heart'
        WHEN 'Rosa' THEN 'conectate://gift-assets/rose'
        WHEN 'Trofeo' THEN 'conectate://gift-assets/trophy'
        WHEN 'Fuegos Artificiales' THEN 'conectate://gift-assets/fireworks'
        WHEN 'Superestrella' THEN 'conectate://gift-assets/star'
        ELSE image_url
    END,
    animation_url = NULL,
    is_active = true
WHERE name IN ('Corazón', 'Rosa', 'Trofeo', 'Fuegos Artificiales', 'Superestrella')
  AND (
      image_url ILIKE 'https://example.com/%'
      OR animation_url ILIKE 'https://example.com/%'
  );
