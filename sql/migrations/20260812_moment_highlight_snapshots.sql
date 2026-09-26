-- Los destacados deben sobrevivir al vencimiento de un Momento de 24 horas.
CREATE TABLE IF NOT EXISTS public.moment_highlights (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    title text NOT NULL,
    cover_media_url text,
    sort_order integer NOT NULL DEFAULT 0,
    is_public boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.moment_highlight_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    highlight_id uuid NOT NULL REFERENCES public.moment_highlights(id) ON DELETE CASCADE,
    moment_id uuid REFERENCES public.moments(id) ON DELETE SET NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (highlight_id, moment_id)
);

ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS media_url text;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS media_type text;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS thumbnail_url text;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS caption text;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS overlay_text text;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS overlay_color text NOT NULL DEFAULT '#FFFFFFFF';
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS overlay_font text NOT NULL DEFAULT 'sans';
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS overlay_scale real NOT NULL DEFAULT 1;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS overlay_x real NOT NULL DEFAULT 0.5;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS overlay_y real NOT NULL DEFAULT 0.38;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_url text;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_title text;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_start_ms bigint NOT NULL DEFAULT 0;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_duration_ms bigint NOT NULL DEFAULT 6000;

-- Copia los datos de los Momentos que todavía existen antes de desacoplar su ciclo de vida.
UPDATE public.moment_highlight_items AS item
SET media_url = COALESCE(item.media_url, moment.media_url),
    media_type = COALESCE(item.media_type, moment.media_type),
    thumbnail_url = COALESCE(item.thumbnail_url, moment.thumbnail_url),
    caption = COALESCE(item.caption, moment.caption),
    overlay_text = COALESCE(item.overlay_text, moment.overlay_text),
    overlay_color = COALESCE(item.overlay_color, moment.overlay_color),
    overlay_font = COALESCE(item.overlay_font, moment.overlay_font),
    overlay_scale = COALESCE(item.overlay_scale, moment.overlay_scale),
    overlay_x = COALESCE(item.overlay_x, moment.overlay_x),
    overlay_y = COALESCE(item.overlay_y, moment.overlay_y),
    music_url = COALESCE(item.music_url, moment.music_url),
    music_title = COALESCE(item.music_title, moment.music_title),
    music_start_ms = COALESCE(item.music_start_ms, moment.music_start_ms),
    music_duration_ms = COALESCE(item.music_duration_ms, moment.music_duration_ms)
FROM public.moments AS moment
WHERE item.moment_id = moment.id;

DO $$
DECLARE
    existing_fk record;
BEGIN
    FOR existing_fk IN
        SELECT constraint_name
        FROM information_schema.key_column_usage
        WHERE table_schema = 'public'
          AND table_name = 'moment_highlight_items'
          AND column_name = 'moment_id'
          AND position_in_unique_constraint IS NOT NULL
    LOOP
        EXECUTE format(
            'ALTER TABLE public.moment_highlight_items DROP CONSTRAINT IF EXISTS %I',
            existing_fk.constraint_name
        );
    END LOOP;
END $$;
ALTER TABLE public.moment_highlight_items ALTER COLUMN moment_id DROP NOT NULL;
ALTER TABLE public.moment_highlight_items
    ADD CONSTRAINT moment_highlight_items_moment_id_fkey
    FOREIGN KEY (moment_id) REFERENCES public.moments(id) ON DELETE SET NULL;

ALTER TABLE public.moment_highlights ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.moment_highlight_items ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "public highlights are readable" ON public.moment_highlights;
CREATE POLICY "public highlights are readable"
    ON public.moment_highlights FOR SELECT TO authenticated
    USING (is_public OR auth.uid() = user_id);

DROP POLICY IF EXISTS "users manage own highlights" ON public.moment_highlights;
CREATE POLICY "users manage own highlights"
    ON public.moment_highlights FOR ALL TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "highlight items are readable" ON public.moment_highlight_items;
CREATE POLICY "highlight items are readable"
    ON public.moment_highlight_items FOR SELECT TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.moment_highlights AS highlight
            WHERE highlight.id = highlight_id
              AND (highlight.is_public OR highlight.user_id = auth.uid())
        )
    );

DROP POLICY IF EXISTS "users manage own highlight items" ON public.moment_highlight_items;
CREATE POLICY "users manage own highlight items"
    ON public.moment_highlight_items FOR ALL TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.moment_highlights AS highlight
            WHERE highlight.id = highlight_id AND highlight.user_id = auth.uid()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.moment_highlights AS highlight
            WHERE highlight.id = highlight_id AND highlight.user_id = auth.uid()
        )
    );

CREATE INDEX IF NOT EXISTS idx_moment_highlights_user_sort
    ON public.moment_highlights (user_id, sort_order, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_moment_highlight_items_highlight_sort
    ON public.moment_highlight_items (highlight_id, sort_order, created_at);
