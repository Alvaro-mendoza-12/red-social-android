-- Official/verified artwork for the eight curated PlayMatch titles.
-- The URLs are deliberately stored as logo/icon assets, not cover art, so Android
-- renders them fitted without cropping. Copyright/trademark ownership remains with
-- each publisher; source and license/provenance are retained for auditability.
WITH official_art(id, asset_url, source_url, asset_license, attribution) AS (
  VALUES
    ('valorant',
      'https://upload.wikimedia.org/wikipedia/commons/thumb/4/44/Valorant_logo.svg/960px-Valorant_logo.svg.png?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail',
      'https://commons.wikimedia.org/wiki/File:Valorant_logo.svg',
      'Public domain', 'Riot Games / Wikimedia Commons'),
    ('league-of-legends',
      'https://upload.wikimedia.org/wikipedia/commons/thumb/d/d8/League_of_Legends_2019_vector.svg/960px-League_of_Legends_2019_vector.svg.png?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail',
      'https://commons.wikimedia.org/wiki/File:League_of_Legends_2019_vector.svg',
      'Public domain', 'Riot Games / Wikimedia Commons'),
    ('fortnite',
      'https://upload.wikimedia.org/wikipedia/commons/thumb/0/0e/FortniteLogo.svg/960px-FortniteLogo.svg.png?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail',
      'https://commons.wikimedia.org/wiki/File:FortniteLogo.svg',
      'Public domain', 'Epic Games / Wikimedia Commons'),
    ('minecraft',
      'https://upload.wikimedia.org/wikipedia/commons/thumb/c/cb/Minecraft_Logo-en.svg/960px-Minecraft_Logo-en.svg.png?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail',
      'https://commons.wikimedia.org/wiki/File:Minecraft_Logo-en.svg',
      'Public domain', 'Mojang Studios / Wikimedia Commons'),
    ('warzone',
      'https://upload.wikimedia.org/wikipedia/commons/e/e6/Call_of_Duty_Warzone_Logo.png?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail_unscaled',
      'https://commons.wikimedia.org/wiki/File:Call_of_Duty_Warzone_Logo.png',
      'Public domain', 'Activision / Wikimedia Commons'),
    ('mobile-legends',
      'https://upload.wikimedia.org/wikipedia/commons/7/78/MobileIsLegends.png?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail_unscaled',
      'https://commons.wikimedia.org/wiki/File:MobileIsLegends.png',
      'CC BY-SA 4.0', 'Wikimedia Commons contributor'),
    ('free-fire',
      'https://dl.dir.freefiremobile.com/common/web_event/official2/dist/client/img/full_logo.969f536.png',
      'https://ff.garena.com/en/brand/',
      'Official brand asset', 'Garena Free Fire'),
    ('rocket-league',
      'https://upload.wikimedia.org/wikipedia/commons/thumb/c/c3/Rocket_League_logo.svg/960px-Rocket_League_logo.svg.png?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail',
      'https://commons.wikimedia.org/wiki/File:Rocket_League_logo.svg',
      'CC BY-SA 4.0', 'Psyonix / Wikimedia Commons')
)
UPDATE fallback_game_catalog AS game
SET image_url = art.asset_url,
    cover_url = NULL,
    logo_url = art.asset_url,
    icon_url = art.asset_url,
    cover_source_url = art.source_url,
    cover_license = art.asset_license,
    cover_attribution = art.attribution,
    source_url = COALESCE(NULLIF(game.source_url, ''), art.source_url),
    manual_override = TRUE,
    catalog_updated_at = NOW(),
    updated_at = NOW()
FROM official_art AS art
WHERE game.id = art.id;
