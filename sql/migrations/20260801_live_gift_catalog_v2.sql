-- Catálogo Novvin raster v1: 24 regalos originales y claves visuales estables.
-- Los precios son monedas virtuales. price_credits queda en cero hasta que
-- Finanzas apruebe y configure una conversión únicamente en servidor.

-- Aparta filas antiguas que podrían chocar con los nombres canónicos. No se
-- eliminan porque transacciones históricas pueden seguir referenciando sus UUID.
UPDATE public.virtual_gifts
SET name = '__legacy_gift_' || id::text,
    is_active = false
WHERE id NOT IN (
    '7a100001-0000-4000-8000-000000000001'::uuid,
    '7a100002-0000-4000-8000-000000000002'::uuid,
    '7a100003-0000-4000-8000-000000000003'::uuid,
    '7a100004-0000-4000-8000-000000000004'::uuid,
    '7a100005-0000-4000-8000-000000000005'::uuid,
    '7a100006-0000-4000-8000-000000000006'::uuid,
    '7a100007-0000-4000-8000-000000000007'::uuid,
    '7a100008-0000-4000-8000-000000000008'::uuid,
    '7a100009-0000-4000-8000-000000000009'::uuid,
    '7a100010-0000-4000-8000-000000000010'::uuid,
    '7a100011-0000-4000-8000-000000000011'::uuid,
    '7a100012-0000-4000-8000-000000000012'::uuid,
    '7a100013-0000-4000-8000-000000000013'::uuid,
    '7a100014-0000-4000-8000-000000000014'::uuid,
    '7a100015-0000-4000-8000-000000000015'::uuid,
    '7a100016-0000-4000-8000-000000000016'::uuid,
    '7a100017-0000-4000-8000-000000000017'::uuid,
    '7a100018-0000-4000-8000-000000000018'::uuid,
    '7a100019-0000-4000-8000-000000000019'::uuid,
    '7a100020-0000-4000-8000-000000000020'::uuid,
    '7a100021-0000-4000-8000-000000000021'::uuid,
    '7a100022-0000-4000-8000-000000000022'::uuid,
    '7a100023-0000-4000-8000-000000000023'::uuid,
    '7a100024-0000-4000-8000-000000000024'::uuid
)
AND (
    image_url LIKE 'conectate://gift-assets/%'
    OR image_url LIKE 'https://example.com/gifts/%'
    OR lower(name) IN (
        'corazon', 'corazón', 'rosa', 'trofeo', 'fuegos artificiales', 'superestrella',
        'semilla estelar', 'gota aurora', 'chispa prisma', 'mariposa luminosa',
        'flor cometa', 'luna de cristal', 'colibrí solar', 'medusa nebulosa',
        'zorro boreal', 'ballena estelar', 'jardín de estrellas', 'orquídea prisma',
        'nave prisma', 'guardián del bosque', 'fénix aurora', 'tren estelar',
        'dragón celeste', 'isla flotante', 'mundo celeste', 'ópera de luz',
        'palacio boreal', 'reino del tiempo', 'ascenso galáctico', 'génesis aurora'
    )
);

-- La versión intermedia usó estos mismos UUID con otros nombres. Se renombran
-- temporalmente para que UNIQUE(name) no bloquee los cambios de posición.
UPDATE public.virtual_gifts
SET name = '__novvin_catalog_migration_' || id::text
WHERE id IN (
    '7a100001-0000-4000-8000-000000000001'::uuid,
    '7a100002-0000-4000-8000-000000000002'::uuid,
    '7a100003-0000-4000-8000-000000000003'::uuid,
    '7a100004-0000-4000-8000-000000000004'::uuid,
    '7a100005-0000-4000-8000-000000000005'::uuid,
    '7a100006-0000-4000-8000-000000000006'::uuid,
    '7a100007-0000-4000-8000-000000000007'::uuid,
    '7a100008-0000-4000-8000-000000000008'::uuid,
    '7a100009-0000-4000-8000-000000000009'::uuid,
    '7a100010-0000-4000-8000-000000000010'::uuid,
    '7a100011-0000-4000-8000-000000000011'::uuid,
    '7a100012-0000-4000-8000-000000000012'::uuid,
    '7a100013-0000-4000-8000-000000000013'::uuid,
    '7a100014-0000-4000-8000-000000000014'::uuid,
    '7a100015-0000-4000-8000-000000000015'::uuid,
    '7a100016-0000-4000-8000-000000000016'::uuid,
    '7a100017-0000-4000-8000-000000000017'::uuid,
    '7a100018-0000-4000-8000-000000000018'::uuid,
    '7a100019-0000-4000-8000-000000000019'::uuid,
    '7a100020-0000-4000-8000-000000000020'::uuid,
    '7a100021-0000-4000-8000-000000000021'::uuid,
    '7a100022-0000-4000-8000-000000000022'::uuid,
    '7a100023-0000-4000-8000-000000000023'::uuid,
    '7a100024-0000-4000-8000-000000000024'::uuid
);

INSERT INTO public.virtual_gifts (
    id, name, description, image_url, price_diamonds, price_credits,
    animation_url, is_active, sort_order
) VALUES
('7a100001-0000-4000-8000-000000000001', 'Semilla Estelar', 'Una chispa pequeña para iniciar la celebración', 'conectate://gift-assets/starlight-seed', 5, 0.00, 'conectate://gift-animations/starlight-seed', true, 1),
('7a100002-0000-4000-8000-000000000002', 'Gota Aurora', 'Una gota de luz suave para saludar al creador', 'conectate://gift-assets/aurora-drop', 10, 0.00, 'conectate://gift-animations/aurora-drop', true, 2),
('7a100003-0000-4000-8000-000000000003', 'Chispa Prisma', 'Un destello multicolor que despierta el directo', 'conectate://gift-assets/prism-spark', 15, 0.00, 'conectate://gift-animations/prism-spark', true, 3),
('7a100004-0000-4000-8000-000000000004', 'Mariposa Luminosa', 'Alas brillantes para un saludo ligero', 'conectate://gift-assets/luminous-butterfly', 25, 0.00, 'conectate://gift-animations/luminous-butterfly', true, 4),
('7a100005-0000-4000-8000-000000000005', 'Flor Cometa', 'Pétalos que dejan una estela en el cielo', 'conectate://gift-assets/comet-flower', 40, 0.00, 'conectate://gift-animations/comet-flower', true, 5),
('7a100006-0000-4000-8000-000000000006', 'Luna de Cristal', 'Una luna tallada en reflejos boreales', 'conectate://gift-assets/crystal-moon', 60, 0.00, 'conectate://gift-animations/crystal-moon', true, 6),
('7a100007-0000-4000-8000-000000000007', 'Colibrí Solar', 'Un vuelo veloz cargado de energía', 'conectate://gift-assets/solar-hummingbird', 90, 0.00, 'conectate://gift-animations/solar-hummingbird', true, 7),
('7a100008-0000-4000-8000-000000000008', 'Medusa Nebulosa', 'Una criatura celeste que flota entre estrellas', 'conectate://gift-assets/nebula-jellyfish', 130, 0.00, 'conectate://gift-animations/nebula-jellyfish', true, 8),
('7a100009-0000-4000-8000-000000000009', 'Zorro Boreal', 'Un compañero ártico envuelto en auroras', 'conectate://gift-assets/boreal-fox', 170, 0.00, 'conectate://gift-animations/boreal-fox', true, 9),
('7a100010-0000-4000-8000-000000000010', 'Ballena Estelar', 'Una travesía serena por el océano cósmico', 'conectate://gift-assets/cosmic-whale', 200, 0.00, 'conectate://gift-animations/cosmic-whale', true, 10),
('7a100011-0000-4000-8000-000000000011', 'Jardín de Estrellas', 'Un jardín que florece con luz estelar', 'conectate://gift-assets/star-garden', 250, 0.00, 'conectate://gift-animations/star-garden', true, 11),
('7a100012-0000-4000-8000-000000000012', 'Orquídea Prisma', 'Cristales florales que reflejan todo el directo', 'conectate://gift-assets/crystal-orchid', 300, 0.00, 'conectate://gift-animations/crystal-orchid', true, 12),
('7a100013-0000-4000-8000-000000000013', 'Nave Prisma', 'Una nave de colores lista para despegar', 'conectate://gift-assets/prism-ship', 350, 0.00, 'conectate://gift-animations/prism-ship', true, 13),
('7a100014-0000-4000-8000-000000000014', 'Guardián del Bosque', 'El protector luminoso de un bosque encantado', 'conectate://gift-assets/forest-guardian', 450, 0.00, 'conectate://gift-animations/forest-guardian', true, 14),
('7a100015-0000-4000-8000-000000000015', 'Fénix Aurora', 'Renace entre fuego frío y luces boreales', 'conectate://gift-assets/aurora-phoenix', 600, 0.00, 'conectate://gift-animations/aurora-phoenix', true, 15),
('7a100016-0000-4000-8000-000000000016', 'Tren Estelar', 'Un viaje brillante que cruza toda la escena', 'conectate://gift-assets/star-train', 750, 0.00, 'conectate://gift-animations/star-train', true, 16),
('7a100017-0000-4000-8000-000000000017', 'Dragón Celeste', 'Un guardián majestuoso para grandes celebraciones', 'conectate://gift-assets/celestial-dragon', 900, 0.00, 'conectate://gift-animations/celestial-dragon', true, 17),
('7a100018-0000-4000-8000-000000000018', 'Isla Flotante', 'Un mundo suspendido que aparece sobre el directo', 'conectate://gift-assets/floating-island', 1100, 0.00, 'conectate://gift-animations/floating-island', true, 18),
('7a100019-0000-4000-8000-000000000019', 'Mundo Celeste', 'Un planeta completo nacido entre constelaciones', 'conectate://gift-assets/celestial-world', 1400, 0.00, 'conectate://gift-animations/celestial-world', true, 19),
('7a100020-0000-4000-8000-000000000020', 'Ópera de Luz', 'Una escena musical de luz, color y movimiento', 'conectate://gift-assets/light-opera', 1800, 0.00, 'conectate://gift-animations/light-opera', true, 20),
('7a100021-0000-4000-8000-000000000021', 'Palacio Boreal', 'La celebración imperial del cielo nocturno', 'conectate://gift-assets/aurora-palace', 2200, 0.00, 'conectate://gift-animations/aurora-palace', true, 21),
('7a100022-0000-4000-8000-000000000022', 'Reino del Tiempo', 'Un reino fantástico que controla cada instante', 'conectate://gift-assets/time-kingdom', 2800, 0.00, 'conectate://gift-animations/time-kingdom', true, 22),
('7a100023-0000-4000-8000-000000000023', 'Ascenso Galáctico', 'Una subida épica hacia el centro de la galaxia', 'conectate://gift-assets/galactic-ascent', 3600, 0.00, 'conectate://gift-animations/galactic-ascent', true, 23),
('7a100024-0000-4000-8000-000000000024', 'Génesis Aurora', 'El gran final Novvin: un universo nace en pantalla', 'conectate://gift-assets/aurora-genesis', 5000, 0.00, 'conectate://gift-animations/aurora-genesis', true, 24)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    image_url = EXCLUDED.image_url,
    price_diamonds = EXCLUDED.price_diamonds,
    price_credits = EXCLUDED.price_credits,
    animation_url = EXCLUDED.animation_url,
    is_active = true,
    sort_order = EXCLUDED.sort_order;
