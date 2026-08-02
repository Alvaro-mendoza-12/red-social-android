-- Catálogo Novvin v2: arte propio, claves visuales estables y sin duplicados heredados.
-- Los UUID siguen siendo la identidad contable; image_url selecciona el arte versionado del cliente.

UPDATE virtual_gifts
SET is_active = false
WHERE image_url LIKE 'https://example.com/gifts/%'
   OR lower(name) IN (
       'corazon', 'corazón', 'rosa', 'trofeo',
       'fuegos artificiales', 'superestrella'
   );

INSERT INTO virtual_gifts (
    id, name, description, image_url, price_diamonds, price_credits,
    animation_url, is_active, sort_order
) VALUES
('7a100001-0000-4000-8000-000000000001', 'Pulso Novvin', 'Un pulso luminoso para apoyar al creador', 'conectate://gift-assets/pulse', 5, 0.50, 'conectate://gift-animations/pulse', true, 1),
('7a100002-0000-4000-8000-000000000002', 'Ritmo Neón', 'Una onda musical dibujada para el directo', 'conectate://gift-assets/rhythm', 10, 1.00, 'conectate://gift-animations/rhythm', true, 2),
('7a100003-0000-4000-8000-000000000003', 'Ala Boreal', 'Alas de color para un saludo ligero', 'conectate://gift-assets/butterfly', 15, 1.50, 'conectate://gift-animations/butterfly', true, 3),
('7a100004-0000-4000-8000-000000000004', 'Flor Aurora', 'Pétalos de luz originales de Novvin', 'conectate://gift-assets/aurora', 25, 2.50, 'conectate://gift-animations/aurora', true, 4),
('7a100005-0000-4000-8000-000000000005', 'Órbita', 'Un planeta de energía Novvin', 'conectate://gift-assets/orbit', 35, 3.50, 'conectate://gift-animations/orbit', true, 5),
('7a100006-0000-4000-8000-000000000006', 'Copa Prisma', 'Una copa brillante para celebrar logros', 'conectate://gift-assets/prism', 50, 5.00, 'conectate://gift-animations/prism', true, 6),
('7a100007-0000-4000-8000-000000000007', 'Marea Celeste', 'Una ola luminosa que cruza la pantalla', 'conectate://gift-assets/wave', 75, 7.50, 'conectate://gift-animations/wave', true, 7),
('7a100008-0000-4000-8000-000000000008', 'Lluvia de Luz', 'Una celebración que llena la pantalla', 'conectate://gift-assets/fireworks', 100, 10.00, 'conectate://gift-animations/fireworks', true, 8),
('7a100009-0000-4000-8000-000000000009', 'Arco Solar', 'Un arco de luz para celebrar juntos', 'conectate://gift-assets/rainbow', 150, 15.00, 'conectate://gift-animations/rainbow', true, 9),
('7a100010-0000-4000-8000-000000000010', 'Nova', 'Una explosión estelar para grandes momentos', 'conectate://gift-assets/nova', 200, 20.00, 'conectate://gift-animations/nova', true, 10),
('7a100011-0000-4000-8000-000000000011', 'Cristal Novvin', 'Una gema de energía para destacar', 'conectate://gift-assets/crystal', 250, 25.00, 'conectate://gift-animations/crystal', true, 11),
('7a100012-0000-4000-8000-000000000012', 'Cometa', 'Una estela veloz para destacar el directo', 'conectate://gift-assets/comet', 350, 35.00, 'conectate://gift-animations/comet', true, 12),
('7a100013-0000-4000-8000-000000000013', 'Impulso', 'Un despegue completo para grandes metas', 'conectate://gift-assets/rocket', 450, 45.00, 'conectate://gift-animations/rocket', true, 13),
('7a100014-0000-4000-8000-000000000014', 'Corona Cósmica', 'Una celebración destacada del catálogo', 'conectate://gift-assets/crown', 500, 50.00, 'conectate://gift-animations/crown', true, 14),
('7a100015-0000-4000-8000-000000000015', 'Portal Prisma', 'Abre una celebración envolvente', 'conectate://gift-assets/portal', 750, 75.00, 'conectate://gift-animations/portal', true, 15),
('7a100016-0000-4000-8000-000000000016', 'Fénix Aurora', 'La celebración máxima del catálogo', 'conectate://gift-assets/phoenix', 1000, 100.00, 'conectate://gift-animations/phoenix', true, 16)
ON CONFLICT (name) DO UPDATE SET
    description = EXCLUDED.description,
    image_url = EXCLUDED.image_url,
    price_diamonds = EXCLUDED.price_diamonds,
    price_credits = EXCLUDED.price_credits,
    animation_url = EXCLUDED.animation_url,
    is_active = true,
    sort_order = EXCLUDED.sort_order;
