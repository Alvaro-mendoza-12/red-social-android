-- Keep existing chat bucket MIME restrictions while permitting common office
-- document formats shared from Android providers (including legacy .xls files).
-- A NULL allowlist already means unrestricted MIME types and remains NULL.
DO $$
DECLARE
    v_additional_types TEXT[] := ARRAY[
        'application/octet-stream',
        'application/vnd.ms-excel',
        'application/msword',
        'application/vnd.ms-powerpoint',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        'application/vnd.ms-excel.sheet.macroenabled.12',
        'application/vnd.ms-excel.sheet.binary.macroenabled.12',
        'application/vnd.ms-word.document.macroenabled.12',
        'application/vnd.ms-powerpoint.presentation.macroenabled.12',
        'application/vnd.oasis.opendocument.text',
        'application/vnd.oasis.opendocument.spreadsheet',
        'application/vnd.oasis.opendocument.presentation',
        'application/rtf',
        'application/epub+zip',
        'application/json',
        'application/xml',
        'text/xml',
        'application/x-7z-compressed',
        'application/vnd.rar',
        'text/tab-separated-values'
    ];
BEGIN
    UPDATE storage.buckets AS bucket
    SET allowed_mime_types = CASE
        WHEN bucket.allowed_mime_types IS NULL THEN NULL
        ELSE (
            SELECT ARRAY_AGG(DISTINCT mime_type ORDER BY mime_type)
            FROM UNNEST(bucket.allowed_mime_types || v_additional_types) AS item(mime_type)
        )
    END
    WHERE bucket.id IN ('chat-media', 'group-media', 'community-media');
END $$;
