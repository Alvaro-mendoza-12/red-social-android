-- FILTRO DE CADUCIDAD: Solo mostrar momentos que no hayan expirado
DROP POLICY IF EXISTS "Realtime Global Access" ON public.moments;

CREATE POLICY "Realtime Global Access" ON public.moments
FOR SELECT
USING (
    (expires_at > (timezone('utc', now())))
);

-- Asegurarse de que el Realtime respete esta política
ALTER TABLE public.moments REPLICA IDENTITY FULL;
