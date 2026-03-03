-- Functions to manage participant counts in Voice Rooms

-- 1. Increment Count
CREATE OR REPLACE FUNCTION increment_participant_count(room_id TEXT)
RETURNS VOID AS $$
BEGIN
  UPDATE voice_rooms
  SET active_participants = active_participants + 1
  WHERE id = room_id::uuid;
END;
$$ LANGUAGE plpgsql;

-- 2. Decrement Count (safeguarded against negative numbers)
CREATE OR REPLACE FUNCTION decrement_participant_count(room_id TEXT)
RETURNS VOID AS $$
BEGIN
  UPDATE voice_rooms
  SET active_participants = GREATEST(0, active_participants - 1)
  WHERE id = room_id::uuid;
END;
$$ LANGUAGE plpgsql;

-- 3. Reset Count (Useful for maintenance or daily reset)
CREATE OR REPLACE FUNCTION reset_participant_count(room_id TEXT)
RETURNS VOID AS $$
BEGIN
  UPDATE voice_rooms
  SET active_participants = 0
  WHERE id = room_id::uuid;
END;
$$ LANGUAGE plpgsql;

-- 4. Enable Realtime for this table (Automatic)
-- This allows the app to see participant count changes instantly
DO $$ 
BEGIN 
  IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'voice_rooms') THEN 
    ALTER PUBLICATION supabase_realtime ADD TABLE voice_rooms; 
  END IF; 
END $$;
