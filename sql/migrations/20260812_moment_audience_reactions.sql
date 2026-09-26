ALTER TABLE public.moments
    ADD COLUMN IF NOT EXISTS audience_ids uuid[] NOT NULL DEFAULT '{}'::uuid[];

CREATE TABLE IF NOT EXISTS public.moment_reactions (
    moment_id uuid NOT NULL REFERENCES public.moments(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    reaction text NOT NULL DEFAULT 'like' CHECK (reaction = 'like'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (moment_id, user_id)
);

ALTER TABLE public.moment_reactions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "moment reactions readable" ON public.moment_reactions;
CREATE POLICY "moment reactions readable"
    ON public.moment_reactions FOR SELECT
    TO authenticated
    USING (true);

DROP POLICY IF EXISTS "users manage own moment reactions" ON public.moment_reactions;
CREATE POLICY "users manage own moment reactions"
    ON public.moment_reactions FOR ALL
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);
