-- =============================================================================
-- MOTIUM PRO - LICENSE POOL SYSTEM MIGRATION
-- =============================================================================
-- This migration implements the license pool system with:
-- 1. Licenses go into a pool when purchased
-- 2. Pro can assign licenses to linked accounts
-- 3. 30-day unlink notice period
-- 4. Per-account billing day
-- Run this in the Supabase SQL Editor.
-- =============================================================================

-- =============================================================================
-- 1. ADD NEW COLUMNS TO LICENSES TABLE
-- =============================================================================

-- Unlink request tracking (30-day notice period)
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS unlink_requested_at TIMESTAMPTZ DEFAULT NULL;
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS unlink_effective_at TIMESTAMPTZ DEFAULT NULL;

-- Billing start date (for new licenses, when billing starts)
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS billing_starts_at TIMESTAMPTZ DEFAULT NULL;

-- Make linked_account_id truly nullable (it might already be, but let's be sure)
-- Licenses in the pool have NULL linked_account_id
ALTER TABLE licenses ALTER COLUMN linked_account_id DROP NOT NULL;

-- =============================================================================
-- 2. ADD BILLING_DAY TO PRO_ACCOUNTS TABLE
-- =============================================================================
-- Day of month (1-28) when all licenses are billed

ALTER TABLE pro_accounts ADD COLUMN IF NOT EXISTS billing_day INTEGER DEFAULT 5
    CHECK (billing_day >= 1 AND billing_day <= 28);

-- =============================================================================
-- 3. CREATE INDEXES FOR PERFORMANCE
-- =============================================================================

-- Index for available licenses (in the pool, not assigned)
DROP INDEX IF EXISTS idx_licenses_available;
CREATE INDEX idx_licenses_available
ON licenses(pro_account_id, status)
WHERE linked_account_id IS NULL AND status = 'active';

-- Index for licenses pending unlink
DROP INDEX IF EXISTS idx_licenses_pending_unlink;
CREATE INDEX idx_licenses_pending_unlink
ON licenses(unlink_effective_at)
WHERE unlink_effective_at IS NOT NULL;

-- Index for licenses by linked account
DROP INDEX IF EXISTS idx_licenses_linked_account;
CREATE INDEX idx_licenses_linked_account ON licenses(linked_account_id)
WHERE linked_account_id IS NOT NULL;

-- =============================================================================
-- 4. RPC FUNCTION: PROCESS EXPIRED UNLINKS
-- =============================================================================
-- Called periodically (e.g., daily) to process licenses whose 30-day
-- notice period has expired. Returns licenses to the pool.

CREATE OR REPLACE FUNCTION process_expired_unlinks(p_now TIMESTAMPTZ DEFAULT NOW())
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    WITH updated AS (
        UPDATE licenses
        SET
            linked_account_id = NULL,
            linked_at = NULL,
            unlink_requested_at = NULL,
            unlink_effective_at = NULL,
            updated_at = p_now
        WHERE unlink_effective_at IS NOT NULL
          AND unlink_effective_at <= p_now
          AND linked_account_id IS NOT NULL
        RETURNING id
    )
    SELECT COUNT(*) INTO v_count FROM updated;

    RETURN v_count;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Grant execute permission
GRANT EXECUTE ON FUNCTION process_expired_unlinks(TIMESTAMPTZ) TO authenticated;

-- =============================================================================
-- 5. RPC FUNCTION: REQUEST LICENSE UNLINK
-- =============================================================================
-- Starts the 30-day unlink notice period for a license

CREATE OR REPLACE FUNCTION request_license_unlink(
    p_license_id UUID,
    p_pro_account_id UUID
)
RETURNS BOOLEAN AS $$
DECLARE
    v_now TIMESTAMPTZ;
    v_effective_date TIMESTAMPTZ;
BEGIN
    v_now := NOW();
    v_effective_date := v_now + INTERVAL '30 days';

    UPDATE licenses
    SET
        unlink_requested_at = v_now,
        unlink_effective_at = v_effective_date,
        updated_at = v_now
    WHERE id = p_license_id
      AND pro_account_id = p_pro_account_id
      AND linked_account_id IS NOT NULL
      AND unlink_requested_at IS NULL  -- Not already in unlink process
      AND status = 'active';

    RETURN FOUND;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION request_license_unlink(UUID, UUID) TO authenticated;

-- =============================================================================
-- 6. RPC FUNCTION: CANCEL UNLINK REQUEST
-- =============================================================================
-- Cancels an unlink request before it takes effect

CREATE OR REPLACE FUNCTION cancel_license_unlink(
    p_license_id UUID,
    p_pro_account_id UUID
)
RETURNS BOOLEAN AS $$
BEGIN
    UPDATE licenses
    SET
        unlink_requested_at = NULL,
        unlink_effective_at = NULL,
        updated_at = NOW()
    WHERE id = p_license_id
      AND pro_account_id = p_pro_account_id
      AND unlink_requested_at IS NOT NULL
      AND unlink_effective_at > NOW();  -- Can only cancel if not yet effective

    RETURN FOUND;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION cancel_license_unlink(UUID, UUID) TO authenticated;

-- =============================================================================
-- 7. RPC FUNCTION: ASSIGN LICENSE TO ACCOUNT
-- =============================================================================
-- Assigns an available license from the pool to a linked account

CREATE OR REPLACE FUNCTION assign_license_to_account(
    p_license_id UUID,
    p_pro_account_id UUID,
    p_linked_account_id UUID
)
RETURNS BOOLEAN AS $$
DECLARE
    v_now TIMESTAMPTZ;
BEGIN
    v_now := NOW();

    UPDATE licenses
    SET
        linked_account_id = p_linked_account_id,
        linked_at = v_now,
        updated_at = v_now
    WHERE id = p_license_id
      AND pro_account_id = p_pro_account_id
      AND linked_account_id IS NULL  -- Must be in pool (unassigned)
      AND status = 'active';

    RETURN FOUND;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION assign_license_to_account(UUID, UUID, UUID) TO authenticated;

-- =============================================================================
-- 8. RPC FUNCTION: GET AVAILABLE LICENSES COUNT
-- =============================================================================
-- Returns count of licenses in the pool (available for assignment)

CREATE OR REPLACE FUNCTION get_available_licenses_count(p_pro_account_id UUID)
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM licenses
    WHERE pro_account_id = p_pro_account_id
      AND linked_account_id IS NULL
      AND status = 'active';

    RETURN v_count;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION get_available_licenses_count(UUID) TO authenticated;

-- =============================================================================
-- 9. VIEW: LICENSE SUMMARY FOR PRO ACCOUNTS
-- =============================================================================

DROP VIEW IF EXISTS license_pool_summary;
CREATE VIEW license_pool_summary AS
SELECT
    pro_account_id,
    COUNT(*) as total_licenses,
    COUNT(*) FILTER (WHERE linked_account_id IS NULL AND status = 'active') as available_licenses,
    COUNT(*) FILTER (WHERE linked_account_id IS NOT NULL AND status = 'active' AND unlink_requested_at IS NULL) as active_assigned_licenses,
    COUNT(*) FILTER (WHERE unlink_requested_at IS NOT NULL AND unlink_effective_at > NOW()) as pending_unlink_licenses,
    COUNT(*) FILTER (WHERE is_lifetime = true) as lifetime_licenses,
    COUNT(*) FILTER (WHERE is_lifetime = false AND status = 'active') as monthly_licenses,
    COALESCE(SUM(price_monthly_ht) FILTER (WHERE is_lifetime = false AND status = 'active'), 0) as monthly_total_ht
FROM licenses
GROUP BY pro_account_id;

GRANT SELECT ON license_pool_summary TO authenticated;

-- =============================================================================
-- MIGRATION COMPLETE
-- =============================================================================
-- Changes made:
--
-- licenses table:
--   + unlink_requested_at (TIMESTAMPTZ): When unlink was requested
--   + unlink_effective_at (TIMESTAMPTZ): When unlink takes effect (30 days later)
--   + billing_starts_at (TIMESTAMPTZ): When billing starts for this license
--
-- pro_accounts table:
--   + billing_day (INTEGER 1-28): Day of month for billing all licenses
--
-- Functions added:
--   - process_expired_unlinks(): Process licenses past their 30-day notice
--   - request_license_unlink(): Start the 30-day unlink notice
--   - cancel_license_unlink(): Cancel an unlink request
--   - assign_license_to_account(): Assign a pool license to an account
--   - get_available_licenses_count(): Count available licenses in pool
--
-- Views added:
--   - license_pool_summary: Summary of license states per Pro account
-- =============================================================================
