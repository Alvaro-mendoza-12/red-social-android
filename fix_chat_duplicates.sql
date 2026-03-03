-- ========================================
-- FIX CHAT DUPLICATES FOR 1-1 CONVERSATIONS
-- ========================================
-- This script ONLY affects 1-1 chats (is_group = FALSE)
-- Does NOT touch groups, communities, or voice rooms

-- ========================================
-- STEP 1: CREATE NORMALIZATION FUNCTION
-- ========================================
-- This function sorts participants to create a canonical order
CREATE OR REPLACE FUNCTION normalize_participants(participants TEXT[])
RETURNS TEXT AS $$
BEGIN
    -- Sort the array and convert to comma-separated string
    RETURN array_to_string(ARRAY(SELECT unnest(participants) ORDER BY 1), ',');
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Test the function (should return same result regardless of order)
-- SELECT normalize_participants(ARRAY['user1', 'user2']);
-- SELECT normalize_participants(ARRAY['user2', 'user1']);

-- ========================================
-- STEP 2: FIND EXISTING DUPLICATES
-- ========================================
-- Run this to see if there are duplicated 1-1 chats
SELECT 
    normalize_participants(participants) as normalized,
    COUNT(*) as duplicate_count,
    array_agg(id ORDER BY created_at) as chat_ids,
    array_agg(created_at ORDER BY created_at) as created_dates,
    MIN(created_at) as oldest_chat
FROM public.chats
WHERE is_group = FALSE
  AND array_length(participants, 1) = 2  -- Only 1-1 chats
GROUP BY normalize_participants(participants)
HAVING COUNT(*) > 1  -- Only show duplicates
ORDER BY duplicate_count DESC;

-- ========================================
-- STEP 3: PREVIEW CONSOLIDATION PLAN
-- ========================================
-- This shows which chats would be kept (oldest) and which would be merged
WITH duplicates AS (
    SELECT 
        normalize_participants(participants) as norm_p,
        array_agg(id ORDER BY created_at) as chat_ids,
        MIN(created_at) as oldest_date,
        (array_agg(id ORDER BY created_at))[1] as keep_chat_id,
        array_length(array_agg(id ORDER BY created_at), 1) - 1 as to_merge_count
    FROM public.chats
    WHERE is_group = FALSE
      AND array_length(participants, 1) = 2
    GROUP BY normalize_participants(participants)
    HAVING COUNT(*) > 1
)
SELECT 
    norm_p,
    keep_chat_id as "Chat to Keep",
    to_merge_count as "Chats to Merge",
    oldest_date as "Oldest Chat Date"
FROM duplicates
ORDER BY to_merge_count DESC;

-- ========================================
-- STEP 4: CONSOLIDATE DUPLICATES (CAREFUL!)
-- ========================================
-- ⚠️ ONLY RUN THIS AFTER REVIEWING THE PREVIEW ABOVE ⚠️
-- This will:
-- 1. Move all messages from duplicate chats to the oldest chat
-- 2. Delete duplicate chats
-- 3. Keep the oldest chat for each pair of users

-- UNCOMMENT THE BLOCK BELOW TO EXECUTE:

DO $$ 
DECLARE
    dup_record RECORD;
    oldest_chat_id UUID;
    duplicate_chat_id UUID;
    messages_moved INTEGER := 0;
    chats_deleted INTEGER := 0;
BEGIN
    -- For each set of duplicated chats
    FOR dup_record IN 
        SELECT 
            normalize_participants(participants) as norm_p,
            array_agg(id ORDER BY created_at) as chat_ids
        FROM public.chats
        WHERE is_group = FALSE
          AND array_length(participants, 1) = 2
        GROUP BY normalize_participants(participants)
        HAVING COUNT(*) > 1
    LOOP
        -- Keep the oldest chat (first in sorted array)
        oldest_chat_id := dup_record.chat_ids[1];
        
        -- Process each duplicate chat (skip the first one)
        FOR i IN 2..array_length(dup_record.chat_ids, 1) LOOP
            duplicate_chat_id := dup_record.chat_ids[i];
            
            -- Move all messages from duplicate to oldest
            UPDATE public.messages
            SET chat_id = oldest_chat_id
            WHERE chat_id = duplicate_chat_id;
            
            GET DIAGNOSTICS messages_moved = ROW_COUNT;
            RAISE NOTICE 'Moved % messages from chat % to %', 
                messages_moved, duplicate_chat_id, oldest_chat_id;
            
            -- Delete chat_participants entries
            DELETE FROM public.chat_participants
            WHERE chat_id = duplicate_chat_id;
            
            -- Delete the duplicate chat
            DELETE FROM public.chats
            WHERE id = duplicate_chat_id;
            
            chats_deleted := chats_deleted + 1;
        END LOOP;
    END LOOP;
    
    RAISE NOTICE '✅ Consolidation complete: % duplicate chats merged', chats_deleted;
END $$;


-- ========================================
-- STEP 5: CREATE UNIQUE INDEX
-- ========================================
-- This prevents future duplicates at the database level
-- ONLY applies to 1-1 chats (is_group = FALSE)

-- Drop existing index if it exists
DROP INDEX IF EXISTS idx_chats_unique_dm;

-- Create new unique index
CREATE UNIQUE INDEX idx_chats_unique_dm 
ON public.chats (normalize_participants(participants))
WHERE is_group = FALSE 
  AND array_length(participants, 1) = 2;

-- ========================================
-- STEP 6: VERIFY THE FIX
-- ========================================
-- This should return 0 rows if there are no more duplicates
SELECT 
    normalize_participants(participants) as normalized,
    COUNT(*) as count
FROM public.chats
WHERE is_group = FALSE
  AND array_length(participants, 1) = 2
GROUP BY normalize_participants(participants)
HAVING COUNT(*) > 1;

-- ========================================
-- STEP 7: CHECK FOR INVALID 1-1 CHATS
-- ========================================
-- This finds chats marked as 1-1 (is_group = FALSE) but having != 2 participants
SELECT 
    id,
    participants,
    array_length(participants, 1) as participant_count,
    created_at
FROM public.chats
WHERE is_group = FALSE
  AND array_length(participants, 1) != 2;

-- If you find any, they are likely corrupted. You might want to:
-- 1. Mark them as groups: UPDATE chats SET is_group = TRUE WHERE id = '...';
-- 2. Or delete them if they have no messages.

-- ========================================
-- EXECUTION INSTRUCTIONS
-- ========================================
-- 1. Run STEP 1 (create function)
-- 2. Run STEP 2 (see duplicates)
-- 3. Run STEP 3 (preview consolidation plan)
-- 4. IF you're satisfied, uncomment and run STEP 4 (consolidation logic)
-- 5. Run STEP 5 (create unique index for future prevention)
-- 6. Run STEP 6 & 7 (final verification)

