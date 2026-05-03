-- Add column to users table to control access
ALTER TABLE users 
ADD COLUMN IF NOT EXISTS voice_access_granted BOOLEAN DEFAULT FALSE;

-- Create table for access requests
CREATE TABLE IF NOT EXISTS voice_access_requests (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    status TEXT CHECK (status IN ('pending', 'approved', 'rejected')) DEFAULT 'pending',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()),
    UNIQUE(user_id)
);

-- RLS Policies
ALTER TABLE voice_access_requests ENABLE ROW LEVEL SECURITY;

-- Users can view their own requests
CREATE POLICY "Users can view their own requests" 
ON voice_access_requests FOR SELECT 
USING (auth.uid() = user_id);

-- Users can create their own requests
CREATE POLICY "Users can create their own requests" 
ON voice_access_requests FOR INSERT 
WITH CHECK (auth.uid() = user_id);

-- Admins can view all requests (assuming is_admin check exists or using service role)
-- Adjust this policy based on how 'is_admin' is checked in your RLS helper functions
-- For now allowing public read context limited by logic or app side for admins
CREATE POLICY "Admins can view all requests"
ON voice_access_requests FOR SELECT
USING (
  EXISTS (
    SELECT 1 FROM users 
    WHERE users.id = auth.uid() AND users.is_admin = true
  )
);

-- Admins can update requests
CREATE POLICY "Admins can update requests"
ON voice_access_requests FOR UPDATE
USING (
  EXISTS (
    SELECT 1 FROM users 
    WHERE users.id = auth.uid() AND users.is_admin = true
  )
);
