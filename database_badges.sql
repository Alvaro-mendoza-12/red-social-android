-- ================================================
-- BADGE SYSTEM - Database Schema & Functions
-- ================================================

-- ================================================
-- STEP 1: Create Tables
-- ================================================

-- Badges table
CREATE TABLE IF NOT EXISTS badges (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    description TEXT,
    icon TEXT NOT NULL,
    color TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('follower', 'manual', 'admin', 'verified')),
    threshold INTEGER,
    is_admin_badge BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    created_by UUID REFERENCES auth.users(id)
);

-- User badges junction table
CREATE TABLE IF NOT EXISTS user_badges (
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    badge_id UUID REFERENCES badges(id) ON DELETE CASCADE,
    granted_by UUID REFERENCES auth.users(id),
    granted_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, badge_id)
);

-- Add admin and verified columns to users if not exists
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_admin BOOLEAN DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_verified BOOLEAN DEFAULT false;

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_user_badges_user_id ON user_badges(user_id);
CREATE INDEX IF NOT EXISTS idx_user_badges_badge_id ON user_badges(badge_id);
CREATE INDEX IF NOT EXISTS idx_badges_type ON badges(type);

-- ================================================
-- STEP 2: Insert Predefined Badges
-- ================================================

INSERT INTO badges (name, description, icon, color, type, threshold, is_admin_badge) VALUES
('10K Seguidores', 'Alcanzó 10,000 seguidores', '🌟', '#FFD700', 'follower', 10000, false),
('50K Seguidores', 'Alcanzó 50,000 seguidores', '⭐', '#FFA500', 'follower', 50000, false),
('100K Seguidores', 'Alcanzó 100,000 seguidores', '💫', '#FF6347', 'follower', 100000, false),
('500K Seguidores', 'Alcanzó 500,000 seguidores', '🔥', '#FF4500', 'follower', 500000, false),
('1M Seguidores', 'Alcanzó 1 millón de seguidores', '👑', '#8B00FF', 'follower', 1000000, false),
('Bug Hunter', 'Encontró un error crítico en la aplicación', '🐛', '#00CED1', 'manual', NULL, false),
('Colaborador Oficial', 'Colaborador oficial de Conectate', '🤝', '#32CD32', 'manual', NULL, false),
('Administrador', 'Administrador de Conectate', '⚡', '#FF0000', 'admin', NULL, true)
ON CONFLICT DO NOTHING;

-- ================================================
-- STEP 3: RPC Functions
-- ================================================

-- Grant badge to user
CREATE OR REPLACE FUNCTION grant_badge_to_user(
    p_user_id UUID,
    p_badge_id UUID,
    p_granted_by UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    -- Check if admin
    IF NOT EXISTS (
        SELECT 1 FROM users WHERE id = p_granted_by AND is_admin = true
    ) THEN
        RAISE EXCEPTION 'Only admins can grant badges';
    END IF;
    
    -- Insert if not exists
    INSERT INTO user_badges (user_id, badge_id, granted_by)
    VALUES (p_user_id, p_badge_id, p_granted_by)
    ON CONFLICT (user_id, badge_id) DO NOTHING;
    
    RETURN true;
END;
$$;

-- Revoke badge from user
CREATE OR REPLACE FUNCTION revoke_badge_from_user(
    p_user_id UUID,
    p_badge_id UUID,
    p_revoked_by UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    -- Check if admin
    IF NOT EXISTS (
        SELECT 1 FROM users WHERE id = p_revoked_by AND is_admin = true
    ) THEN
        RAISE EXCEPTION 'Only admins can revoke badges';
    END IF;
    
    DELETE FROM user_badges 
    WHERE user_id = p_user_id AND badge_id = p_badge_id;
    
    RETURN true;
END;
$$;

-- Create custom badge
CREATE OR REPLACE FUNCTION create_custom_badge(
    p_name TEXT,
    p_description TEXT,
    p_icon TEXT,
    p_color TEXT,
    p_created_by UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_badge_id UUID;
BEGIN
    -- Check if admin
    IF NOT EXISTS (
        SELECT 1 FROM users WHERE id = p_created_by AND is_admin = true
    ) THEN
        RAISE EXCEPTION 'Only admins can create badges';
    END IF;
    
    INSERT INTO badges (name, description, icon, color, type, created_by, is_admin_badge)
    VALUES (p_name, p_description, p_icon, p_color, 'manual', p_created_by, false)
    RETURNING id INTO v_badge_id;
    
    RETURN v_badge_id;
END;
$$;

-- Set user verified status
CREATE OR REPLACE FUNCTION set_user_verified(
    p_user_id UUID,
    p_verified BOOLEAN,
    p_set_by UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    -- Check if admin
    IF NOT EXISTS (
        SELECT 1 FROM users WHERE id = p_set_by AND is_admin = true
    ) THEN
        RAISE EXCEPTION 'Only admins can set verified status';
    END IF;
    
    UPDATE users SET is_verified = p_verified WHERE id = p_user_id;
    
    RETURN true;
END;
$$;

-- Get user's badges (helper function)
CREATE OR REPLACE FUNCTION get_user_badges(p_user_id UUID)
RETURNS TABLE (
    id UUID,
    name TEXT,
    description TEXT,
    icon TEXT,
    color TEXT,
    type TEXT,
    is_admin_badge BOOLEAN,
    granted_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    RETURN QUERY
    SELECT 
        b.id,
        b.name,
        b.description,
        b.icon,
        b.color,
        b.type,
        b.is_admin_badge,
        ub.granted_at
    FROM user_badges ub
    JOIN badges b ON b.id = ub.badge_id
    WHERE ub.user_id = p_user_id
    ORDER BY b.is_admin_badge DESC, ub.granted_at DESC;
END;
$$;

-- ================================================
-- STEP 4: Auto-grant follower badges (Trigger)
-- OPTIONAL: Only if followers_count column exists
-- ================================================

CREATE OR REPLACE FUNCTION auto_grant_follower_badges()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    badge RECORD;
BEGIN
    -- Only process if followers_count increased
    IF NEW.followers_count > COALESCE(OLD.followers_count, 0) THEN
        -- Find badges that should be granted
        FOR badge IN 
            SELECT id, threshold 
            FROM badges 
            WHERE type = 'follower' 
            AND threshold <= NEW.followers_count
            AND threshold > COALESCE(OLD.followers_count, 0)
        LOOP
            -- Grant badge if not already granted (self-granted for auto badges)
            INSERT INTO user_badges (user_id, badge_id, granted_by)
            VALUES (NEW.id, badge.id, NEW.id)
            ON CONFLICT (user_id, badge_id) DO NOTHING;
        END LOOP;
    END IF;
    
    RETURN NEW;
END;
$$;

-- Create trigger only if followers_count column exists
DO $$
BEGIN
    -- Check if followers_count column exists
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'users' 
        AND column_name = 'followers_count'
    ) THEN
        -- Drop trigger if exists
        DROP TRIGGER IF EXISTS trigger_auto_grant_follower_badges ON users;
        
        -- Create trigger
        CREATE TRIGGER trigger_auto_grant_follower_badges
        AFTER UPDATE OF followers_count ON users
        FOR EACH ROW
        EXECUTE FUNCTION auto_grant_follower_badges();
        
        RAISE NOTICE 'Auto-grant follower badges trigger created successfully';
    ELSE
        RAISE NOTICE 'Column followers_count does not exist. Trigger not created. You can grant follower badges manually.';
    END IF;
END;
$$;

-- ================================================
-- STEP 5: Enable RLS
-- ================================================

-- Enable RLS on badges
ALTER TABLE badges ENABLE ROW LEVEL SECURITY;

-- Everyone can read badges
CREATE POLICY "Anyone can view badges" ON badges
    FOR SELECT USING (true);

-- Only admins can insert/update/delete
CREATE POLICY "Only admins can manage badges" ON badges
    FOR ALL USING (
        auth.uid() IN (SELECT id FROM users WHERE is_admin = true)
    );

-- Enable RLS on user_badges
ALTER TABLE user_badges ENABLE ROW LEVEL SECURITY;

-- Users can view their own badges and others' badges
CREATE POLICY "Anyone can view user badges" ON user_badges
    FOR SELECT USING (true);

-- Only admins can grant/revoke badges
CREATE POLICY "Only admins can manage user badges" ON user_badges
    FOR ALL USING (
        auth.uid() IN (SELECT id FROM users WHERE is_admin = true)
    );

-- ================================================
-- END OF BADGE SYSTEM SCHEMA
-- ================================================
