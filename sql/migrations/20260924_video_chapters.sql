ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS chapters JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE OR REPLACE FUNCTION public.valid_video_chapters(items JSONB, duration_seconds BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    chapter JSONB;
    chapter_title TEXT;
    start_ms BIGINT;
    previous_ms BIGINT := -1;
BEGIN
    IF items IS NULL OR jsonb_typeof(items) <> 'array' OR jsonb_array_length(items) > 50 THEN
        RETURN FALSE;
    END IF;
    FOR chapter IN SELECT value FROM jsonb_array_elements(items) LOOP
        IF jsonb_typeof(chapter) <> 'object' OR
           jsonb_typeof(chapter->'start_ms') <> 'number' OR
           (chapter->>'start_ms') !~ '^[0-9]+$' THEN
            RETURN FALSE;
        END IF;
        IF jsonb_typeof(chapter->'title') <> 'string' OR
           length(chapter->>'start_ms') > 18 THEN
            RETURN FALSE;
        END IF;
        chapter_title := btrim(chapter->>'title');
        IF chapter_title = '' OR char_length(chapter_title) > 80 OR chapter_title ~ '[[:cntrl:]]' THEN
            RETURN FALSE;
        END IF;
        start_ms := (chapter->>'start_ms')::BIGINT;
        IF start_ms <= previous_ms OR
           duration_seconds IS NULL OR duration_seconds <= 0 OR
           duration_seconds > 9223372036854775 OR
           start_ms >= duration_seconds * 1000 THEN
            RETURN FALSE;
        END IF;
        previous_ms := start_ms;
    END LOOP;
    RETURN TRUE;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'videos_chapters_valid'
          AND conrelid = 'public.videos'::regclass
    ) THEN
        ALTER TABLE public.videos
            ADD CONSTRAINT videos_chapters_valid
            CHECK (public.valid_video_chapters(chapters, duration)) NOT VALID;
    END IF;
END;
$$;
ALTER TABLE public.videos VALIDATE CONSTRAINT videos_chapters_valid;
