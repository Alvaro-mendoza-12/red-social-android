-- SOLUCIÓN FINAL REALTIME: RESET COMPLETO (CORREGIDO)

-- 1. Asegurar Replica Identity Full
ALTER TABLE public.moments REPLICA IDENTITY FULL;
ALTER TABLE public.moment_views REPLICA IDENTITY FULL;

-- 2. Reiniciar la Publicación 'supabase_realtime' DE FORMA DRÁSTICA (NUCLEAR)
-- Borrar la publicación existente para asegurar que no quede configuración basura
DROP PUBLICATION IF EXISTS supabase_realtime;

-- Crear la publicación limpia
CREATE PUBLICATION supabase_realtime;

-- 3. Agregar las tablas
ALTER PUBLICATION supabase_realtime ADD TABLE public.moments, public.moment_views;

-- 4. Permisos
GRANT SELECT ON public.moments TO anon, authenticated, service_role;
GRANT SELECT ON public.moment_views TO anon, authenticated, service_role;

-- 5. Política de Seguridad "Puerta Abierta" (Ya existente o nueva)
DROP POLICY IF EXISTS "Enable read access for all users" ON public.moments;
DROP POLICY IF EXISTS "Realtime Global Access" ON public.moments;

CREATE POLICY "Realtime Global Access" ON public.moments
FOR SELECT
USING (true);
