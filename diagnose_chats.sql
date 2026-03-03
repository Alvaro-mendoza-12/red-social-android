-- DIAGNOSTIC SCRIPT: CHECK CHATS AND PARTICIPANTS
-- Run this to see raw data samples

-- 1. Check a few chats to see duplicate IDs or strange participant formats
SELECT 
    id, 
    participants, 
    creator_id, -- if exists
    is_group,
    group_name
FROM public.chats
ORDER BY created_at DESC
LIMIT 5;

-- 2. Check if there are any messages recently inserted
SELECT * FROM public.messages 
ORDER BY created_at DESC 
LIMIT 5;

-- 3. Check Policies specifically for messages
SELECT * FROM pg_policies WHERE tablename = 'messages';

-- 4. Test RLS assumption:
-- Function to check if a hypothetical user can insert into a chat
-- (You can run this if you know a specific user ID and chat ID)
-- EXAMPLE USAGE (Replace with real IDs after running step 1):
-- SELECT * FROM public.chats WHERE 'user_uuid'::text = ANY(participants);
