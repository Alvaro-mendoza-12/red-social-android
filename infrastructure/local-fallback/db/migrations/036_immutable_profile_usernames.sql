-- A permanent handle cannot be replaced or removed through another API path.
-- The first assignment is allowed for legacy accounts without a handle.
CREATE OR REPLACE FUNCTION protect_fallback_profile_username()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NULLIF(trim(OLD.profile->>'username'), '') IS NOT NULL
     AND lower(trim(OLD.profile->>'username')) IS DISTINCT FROM
         lower(trim(NEW.profile->>'username')) THEN
    RAISE EXCEPTION 'USERNAME_IMMUTABLE' USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS fallback_profile_username_immutable ON fallback_profile_overrides;
CREATE TRIGGER fallback_profile_username_immutable
BEFORE UPDATE ON fallback_profile_overrides
FOR EACH ROW EXECUTE FUNCTION protect_fallback_profile_username();
