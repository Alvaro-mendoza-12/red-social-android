-- Dedicated public storage for music selected from a user's device.
-- Object names start with auth.uid(), which lets write policies keep ownership
-- isolated while profile visitors can stream the selected song.

INSERT INTO storage.buckets (
    id,
    name,
    public,
    file_size_limit,
    allowed_mime_types
)
VALUES (
    'profile-audio',
    'profile-audio',
    TRUE,
    41943040,
    ARRAY[
        'audio/mpeg',
        'audio/mp4',
        'audio/wav',
        'audio/ogg',
        'audio/webm',
        'audio/flac'
    ]
)
ON CONFLICT (id) DO UPDATE SET
    public = TRUE,
    file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

DROP POLICY IF EXISTS "Profile audio public read" ON storage.objects;
DROP POLICY IF EXISTS "Profile audio owner insert" ON storage.objects;
DROP POLICY IF EXISTS "Profile audio owner update" ON storage.objects;
DROP POLICY IF EXISTS "Profile audio owner delete" ON storage.objects;

CREATE POLICY "Profile audio public read"
ON storage.objects
FOR SELECT
USING (bucket_id = 'profile-audio');

CREATE POLICY "Profile audio owner insert"
ON storage.objects
FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'profile-audio'
    AND (storage.foldername(name))[1] = auth.uid()::text
);

CREATE POLICY "Profile audio owner update"
ON storage.objects
FOR UPDATE
TO authenticated
USING (
    bucket_id = 'profile-audio'
    AND (storage.foldername(name))[1] = auth.uid()::text
)
WITH CHECK (
    bucket_id = 'profile-audio'
    AND (storage.foldername(name))[1] = auth.uid()::text
);

CREATE POLICY "Profile audio owner delete"
ON storage.objects
FOR DELETE
TO authenticated
USING (
    bucket_id = 'profile-audio'
    AND (storage.foldername(name))[1] = auth.uid()::text
);
