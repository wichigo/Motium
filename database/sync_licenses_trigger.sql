-- ============================================
-- ⚠️ DEPRECATED - DO NOT USE THIS FILE
-- ============================================
-- This trigger has been SUPERSEDED by database/migrations/bugfix_005_006.sql
-- The newer version has important fixes:
-- - Uses 'EXPIRED' instead of 'FREE' (constraint compliance)
-- - Does NOT revoke on 'canceled' or 'unlinked' (keeps access until renewal)
-- - Only 'suspended' revokes immediately (payment failure)
--
-- RUN bugfix_005_006.sql INSTEAD
-- ============================================
--
-- OLD DESCRIPTION (for reference):
-- Supabase Trigger: Sync subscription_type on license changes
-- This trigger automatically updates users.subscription_type when:
-- 1. A license is assigned (linked_account_id set) -> LICENSED
-- 2. A license is unassigned (linked_account_id cleared) -> EXPIRED
--
-- This serves as a backup to the Android-side updates in LicenseRemoteDataSource.kt

-- Drop existing trigger and function if they exist
DROP TRIGGER IF EXISTS on_license_change ON licenses;
DROP FUNCTION IF EXISTS sync_subscription_type();

-- Create the sync function
CREATE OR REPLACE FUNCTION sync_subscription_type()
RETURNS TRIGGER AS $$
BEGIN
    -- Case 1: License assigned (linked_account_id was null, now has a value)
    IF (OLD.linked_account_id IS NULL AND NEW.linked_account_id IS NOT NULL) THEN
        -- Only update if license is active
        IF NEW.status = 'active' THEN
            UPDATE users
            SET subscription_type = 'LICENSED',
                updated_at = NOW()
            WHERE id = NEW.linked_account_id;

            RAISE LOG 'License % assigned to user %, subscription_type set to LICENSED',
                NEW.id, NEW.linked_account_id;
        END IF;
    END IF;

    -- Case 2: License unassigned (linked_account_id had a value, now null)
    IF (OLD.linked_account_id IS NOT NULL AND NEW.linked_account_id IS NULL) THEN
        -- Check if user has any other active licenses before setting to FREE
        IF NOT EXISTS (
            SELECT 1 FROM licenses
            WHERE linked_account_id = OLD.linked_account_id
            AND status = 'active'
            AND id != OLD.id
        ) THEN
            UPDATE users
            SET subscription_type = 'EXPIRED',
                updated_at = NOW()
            WHERE id = OLD.linked_account_id;

            RAISE LOG 'License % unassigned from user %, subscription_type set to EXPIRED',
                OLD.id, OLD.linked_account_id;
        END IF;
    END IF;

    -- Case 3: License status changed to non-active (cancelled, expired)
    IF (OLD.status = 'active' AND NEW.status != 'active' AND NEW.linked_account_id IS NOT NULL) THEN
        -- Check if user has any other active licenses
        IF NOT EXISTS (
            SELECT 1 FROM licenses
            WHERE linked_account_id = NEW.linked_account_id
            AND status = 'active'
            AND id != NEW.id
        ) THEN
            UPDATE users
            SET subscription_type = 'EXPIRED',
                updated_at = NOW()
            WHERE id = NEW.linked_account_id;

            RAISE LOG 'License % deactivated for user %, subscription_type set to EXPIRED',
                NEW.id, NEW.linked_account_id;
        END IF;
    END IF;

    -- Case 4: License status changed to active while assigned
    IF (OLD.status != 'active' AND NEW.status = 'active' AND NEW.linked_account_id IS NOT NULL) THEN
        UPDATE users
        SET subscription_type = 'LICENSED',
            updated_at = NOW()
        WHERE id = NEW.linked_account_id;

        RAISE LOG 'License % activated for user %, subscription_type set to LICENSED',
            NEW.id, NEW.linked_account_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Create the trigger
CREATE TRIGGER on_license_change
AFTER UPDATE ON licenses
FOR EACH ROW EXECUTE FUNCTION sync_subscription_type();

-- Also handle INSERT case (when license is created already assigned and active)
CREATE OR REPLACE FUNCTION sync_subscription_type_on_insert()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.linked_account_id IS NOT NULL AND NEW.status = 'active' THEN
        UPDATE users
        SET subscription_type = 'LICENSED',
            updated_at = NOW()
        WHERE id = NEW.linked_account_id;

        RAISE LOG 'New license % assigned to user % on creation, subscription_type set to LICENSED',
            NEW.id, NEW.linked_account_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_license_insert ON licenses;
CREATE TRIGGER on_license_insert
AFTER INSERT ON licenses
FOR EACH ROW EXECUTE FUNCTION sync_subscription_type_on_insert();

-- Grant necessary permissions
GRANT EXECUTE ON FUNCTION sync_subscription_type() TO service_role;
GRANT EXECUTE ON FUNCTION sync_subscription_type_on_insert() TO service_role;

-- ============================================
-- Verification query (run manually to verify)
-- ============================================
-- SELECT
--     u.id,
--     u.email,
--     u.subscription_type,
--     l.id as license_id,
--     l.status as license_status,
--     l.linked_account_id
-- FROM users u
-- LEFT JOIN licenses l ON l.linked_account_id = u.id AND l.status = 'active'
-- WHERE u.role = 'ENTERPRISE';
