-- ========================================================
-- FIX: Sync Verified Status with profiles table
-- ========================================================

-- 1. Ensure columns exist in public.profiles (NOT just auth.users or a dummy users table)
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS is_admin BOOLEAN DEFAULT false;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS is_verified BOOLEAN DEFAULT false;

-- 2. Update set_user_verified RPC to point to profiles
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
    -- Check if the persona setting the status is actually an admin
    -- We check it in profiles
    IF NOT EXISTS (
        SELECT 1 FROM public.profiles WHERE id = p_set_by AND is_admin = true
    ) THEN
        -- Failsafe: check by hardcoded email if columns are not yet correctly set
        IF NOT EXISTS (
            SELECT 1 FROM auth.users 
            WHERE id = p_set_by 
            AND email IN ('gamergamerff017@gmail.com', 'alvaromendozamarin004@gmail.com', 'gamergamerff004@gmail.com')
        ) THEN
            RAISE EXCEPTION 'Only admins can set verified status';
        END IF;
    END IF;
    
    -- Update the public profiles table
    UPDATE public.profiles 
    SET is_verified = p_verified, updated_at = NOW()
    WHERE id = p_user_id;
    
    RETURN true;
END;
$$;

-- 3. Update grant_badge_to_user to use profiles for admin check
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
    -- Check if admin in profiles
    IF NOT EXISTS (
        SELECT 1 FROM public.profiles WHERE id = p_granted_by AND is_admin = true
    ) THEN
        IF NOT EXISTS (
            SELECT 1 FROM auth.users 
            WHERE id = p_granted_by 
            AND email IN ('gamergamerff017@gmail.com', 'alvaromendozamarin004@gmail.com', 'gamergamerff004@gmail.com')
        ) THEN
            RAISE EXCEPTION 'Only admins can grant badges';
        END IF;
    END IF;
    
    -- Insert if not exists
    INSERT INTO user_badges (user_id, badge_id, granted_by)
    VALUES (p_user_id, p_badge_id, p_granted_by)
    ON CONFLICT (user_id, badge_id) DO NOTHING;
    
    RETURN true;
END;
$$;

-- 4. Sync existing values from a potential "users" table if it exists
-- This is risky if "users" is auth.users, so we only do it if public.users exists
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'users') THEN
        UPDATE public.profiles p
        SET 
            is_admin = u.is_admin,
            is_verified = u.is_verified
        FROM public.users u
        WHERE p.id = u.id;
        
        RAISE NOTICE 'Profiles synced with users table';
    END IF;
END;
$$;
