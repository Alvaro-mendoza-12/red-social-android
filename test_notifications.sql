-- =====================================================
-- TEST NOTIFICATION SYSTEM - MANUAL VERIFICATION
-- =====================================================

-- STEP 1: Insert a test notification manually
-- Replace YOUR_USER_ID with your actual user UUID from the logs: 24eecc96-1c29-4183-bae9-bc6dd951e25b

INSERT INTO public.notifications (
    recipient_id, 
    actor_id, 
    type, 
    target_id, 
    target_type,
    content
)
VALUES (
    '24eecc96-1c29-4183-bae9-bc6dd951e25b'::uuid,  -- YOUR USER ID (recipient)
    '24eecc96-1c29-4183-bae9-bc6dd951e25b'::uuid,  -- YOUR USER ID (actor - for testing)
    'follow',
    '24eecc96-1c29-4183-bae9-bc6dd951e25b'::uuid,
    'follow',
    'Test notification created manually'
);

-- STEP 2: Verify it was created
SELECT 
    id,
    recipient_id,
    actor_id,
    type,
    content,
    created_at
FROM public.notifications
ORDER BY created_at DESC
LIMIT 5;

-- STEP 3: Check if triggers exist
SELECT 
    trigger_name,
    event_object_table,
    action_statement
FROM information_schema.triggers
WHERE trigger_name IN ('on_new_follow', 'on_new_post_like', 'on_new_video_like');

-- STEP 4: Verify RLS policies allow you to read your own notifications
-- This should return 1 row if RLS is working correctly
SELECT COUNT(*) as can_i_see_my_notifications
FROM public.notifications
WHERE recipient_id = '24eecc96-1c29-4183-bae9-bc6dd951e25b'::uuid;
