-- DEBUG SCRIPT: CHECK TRIGGERS AND FUNCTIONS
-- Run this to verify if the internal logic of the database is healthy

-- 1. Check if the function handle_new_message exists and is valid
SELECT proname, prosrc 
FROM pg_proc 
WHERE proname = 'handle_new_message';

-- 2. Check if the trigger exists on messages table
SELECT tgname, tgtype, tgenabled 
FROM pg_trigger 
WHERE tgrelid = 'public.messages'::regclass;

-- 3. Check for any recent errors in Supabase logs (cannot access logs via SQL unfortunately)
-- But we can try to force an insert test to see the error directly
-- USER: DO NOT RUN THIS PART IF YOU DON'T KNOW A VALID CHAT ID
-- INSERT INTO public.messages (chat_id, sender_id, content) VALUES ('...', '...', 'Test');
