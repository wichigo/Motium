-- =============================================================================
-- MOTIUM - SUBSCRIPTION & LICENSE SYSTEM V2 MIGRATION
-- =============================================================================
-- This migration aligns the database schema with the audit specifications:
-- - Updates users.subscription_type constraint (no FREE, use EXPIRED instead)
-- - Updates licenses.status constraint (available, active, suspended, canceled, unlinked, paused)
-- - Adds pro_accounts.status and trial_ends_at columns
-- - Creates missing RPC functions
-- Run this in the Supabase SQL Editor.
-- =============================================================================

-- =============================================================================
-- 1. UPDATE USERS.SUBSCRIPTION_TYPE CONSTRAINT
-- =============================================================================

-- First, migrate any existing FREE users to EXPIRED
UPDATE users SET subscription_type = 'EXPIRED' WHERE subscription_type = 'FREE';

-- Drop old constraint if exists
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_subscription_type_check;

-- Add new constraint (TRIAL, PREMIUM, LIFETIME, LICENSED, EXPIRED - no FREE)
ALTER TABLE users ADD CONSTRAINT users_subscription_type_check
    CHECK (subscription_type IN ('TRIAL', 'PREMIUM', 'LIFETIME', 'LICENSED', 'EXPIRED'));

-- Update default to TRIAL (not FREE)
ALTER TABLE users ALTER COLUMN subscription_type SET DEFAULT 'TRIAL';

-- =============================================================================
-- 2. UPDATE LICENSES.STATUS CONSTRAINT
-- =============================================================================

-- First, migrate any existing pending licenses to available (new default for pool licenses)
UPDATE licenses SET status = 'available' WHERE status = 'pending';

-- Migrate cancelled (British) to canceled (American) if any exist
UPDATE licenses SET status = 'canceled' WHERE status = 'cancelled';

-- Migrate expired licenses to suspended
UPDATE licenses SET status = 'suspended' WHERE status = 'expired';

-- Drop old constraint if exists
ALTER TABLE licenses DROP CONSTRAINT IF EXISTS licenses_status_check;

-- Add new constraint (available, active, suspended, canceled, unlinked, paused)
ALTER TABLE licenses ADD CONSTRAINT licenses_status_check
    CHECK (status IN ('available', 'active', 'suspended', 'canceled', 'unlinked', 'paused'));

-- Update default to available (for pool licenses)
ALTER TABLE licenses ALTER COLUMN status SET DEFAULT 'available';

-- =============================================================================
-- 3. ADD PRO_ACCOUNTS.STATUS AND TRIAL_ENDS_AT COLUMNS
-- =============================================================================

-- Add status column if not exists
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                   AND table_name = 'pro_accounts'
                   AND column_name = 'status') THEN
        ALTER TABLE pro_accounts ADD COLUMN status TEXT DEFAULT 'trial'
            CHECK (status IN ('trial', 'active', 'expired', 'suspended'));
    END IF;
END $$;

-- Add trial_ends_at column if not exists
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                   AND table_name = 'pro_accounts'
                   AND column_name = 'trial_ends_at') THEN
        ALTER TABLE pro_accounts ADD COLUMN trial_ends_at TIMESTAMPTZ DEFAULT NULL;
    END IF;
END $$;

-- Set trial_ends_at for existing pro_accounts without it (7 days from creation)
UPDATE pro_accounts
SET trial_ends_at = created_at + INTERVAL '7 days'
WHERE trial_ends_at IS NULL AND status = 'trial';

-- =============================================================================
-- 4. RPC FUNCTION: ASSIGN_LICENSE_TO_COLLABORATOR
-- =============================================================================
-- Assigns a license to a collaborator with full validation:
-- - Blocks assignment to LIFETIME/LICENSED users
-- - Handles PREMIUM users (returns needs_cancel_existing)
-- - Direct assignment for TRIAL/EXPIRED users

CREATE OR REPLACE FUNCTION assign_license_to_collaborator(
    p_license_id UUID,
    p_collaborator_id UUID,
    p_pro_account_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_license RECORD;
    v_collaborator RECORD;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    -- Check license exists and is available
    SELECT * INTO v_license FROM licenses
    WHERE id = p_license_id
      AND pro_account_id = p_pro_account_id
      AND linked_account_id IS NULL
      AND status = 'active';

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'LICENSE_NOT_AVAILABLE',
            'message', 'La licence n''est pas disponible ou n''existe pas'
        );
    END IF;

    -- Check collaborator exists and get their subscription_type
    SELECT * INTO v_collaborator FROM users
    WHERE id = p_collaborator_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'COLLABORATOR_NOT_FOUND',
            'message', 'Collaborateur introuvable'
        );
    END IF;

    -- Block if collaborator already has LIFETIME subscription
    IF v_collaborator.subscription_type = 'LIFETIME' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'ALREADY_LIFETIME',
            'message', 'Ce collaborateur a deja un abonnement a vie'
        );
    END IF;

    -- Block if collaborator already has a license (LICENSED)
    IF v_collaborator.subscription_type = 'LICENSED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'ALREADY_LICENSED',
            'message', 'Ce collaborateur a deja une licence active'
        );
    END IF;

    -- Handle PREMIUM users - need to cancel existing subscription first
    IF v_collaborator.subscription_type = 'PREMIUM' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'NEEDS_CANCEL_EXISTING',
            'message', 'Ce collaborateur a un abonnement Premium actif qui doit etre resilie',
            'needs_cancel_existing', true,
            'collaborator_id', p_collaborator_id,
            'license_id', p_license_id
        );
    END IF;

    -- TRIAL or EXPIRED - can assign directly
    -- Assign license
    UPDATE licenses SET
        linked_account_id = p_collaborator_id,
        linked_at = v_now,
        updated_at = v_now
    WHERE id = p_license_id;

    -- Update user subscription_type to LICENSED
    UPDATE users SET
        subscription_type = 'LICENSED',
        subscription_expires_at = CASE
            WHEN v_license.is_lifetime THEN NULL
            ELSE v_license.end_date
        END,
        updated_at = v_now
    WHERE id = p_collaborator_id;

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Licence attribuee avec succes',
        'license_id', p_license_id,
        'collaborator_id', p_collaborator_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION assign_license_to_collaborator(UUID, UUID, UUID) TO authenticated;

-- =============================================================================
-- 5. RPC FUNCTION: CHECK_PREMIUM_ACCESS
-- =============================================================================
-- Checks if a user has premium access based on their subscription_type

CREATE OR REPLACE FUNCTION check_premium_access(p_user_id UUID)
RETURNS JSONB AS $$
DECLARE
    v_user RECORD;
    v_now TIMESTAMPTZ := NOW();
    v_has_access BOOLEAN;
    v_reason TEXT;
BEGIN
    SELECT * INTO v_user FROM users WHERE id = p_user_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'has_access', false,
            'reason', 'USER_NOT_FOUND'
        );
    END IF;

    -- Check based on subscription_type
    CASE v_user.subscription_type
        WHEN 'TRIAL' THEN
            v_has_access := v_user.trial_ends_at IS NULL OR v_user.trial_ends_at > v_now;
            v_reason := CASE WHEN v_has_access THEN 'TRIAL_ACTIVE' ELSE 'TRIAL_EXPIRED' END;

        WHEN 'PREMIUM' THEN
            v_has_access := v_user.subscription_expires_at IS NULL OR v_user.subscription_expires_at > v_now;
            v_reason := CASE WHEN v_has_access THEN 'PREMIUM_ACTIVE' ELSE 'PREMIUM_EXPIRED' END;

        WHEN 'LIFETIME' THEN
            v_has_access := true;
            v_reason := 'LIFETIME';

        WHEN 'LICENSED' THEN
            v_has_access := v_user.subscription_expires_at IS NULL OR v_user.subscription_expires_at > v_now;
            v_reason := CASE WHEN v_has_access THEN 'LICENSED_ACTIVE' ELSE 'LICENSED_EXPIRED' END;

        WHEN 'EXPIRED' THEN
            v_has_access := false;
            v_reason := 'EXPIRED';

        ELSE
            v_has_access := false;
            v_reason := 'UNKNOWN_STATUS';
    END CASE;

    RETURN jsonb_build_object(
        'has_access', v_has_access,
        'reason', v_reason,
        'subscription_type', v_user.subscription_type,
        'expires_at', v_user.subscription_expires_at,
        'trial_ends_at', v_user.trial_ends_at
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION check_premium_access(UUID) TO authenticated;

-- =============================================================================
-- 6. RPC FUNCTION: CANCEL_LICENSE
-- =============================================================================
-- Sets license status to 'canceled' (pending deletion at next billing cycle)

CREATE OR REPLACE FUNCTION cancel_license(
    p_license_id UUID,
    p_pro_account_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_license RECORD;
BEGIN
    SELECT * INTO v_license FROM licenses
    WHERE id = p_license_id
      AND pro_account_id = p_pro_account_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'LICENSE_NOT_FOUND',
            'message', 'Licence introuvable'
        );
    END IF;

    -- Update license status to canceled
    UPDATE licenses SET
        status = 'canceled',
        updated_at = NOW()
    WHERE id = p_license_id;

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Licence resiliee, sera supprimee a la prochaine date de facturation',
        'license_id', p_license_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION cancel_license(UUID, UUID) TO authenticated;

-- =============================================================================
-- 7. RPC FUNCTION: FINALIZE_LICENSE_ASSIGNMENT
-- =============================================================================
-- Called after PREMIUM user cancels their subscription to complete license assignment

CREATE OR REPLACE FUNCTION finalize_license_assignment(
    p_license_id UUID,
    p_collaborator_id UUID,
    p_pro_account_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_license RECORD;
    v_collaborator RECORD;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    -- Check license exists and is available
    SELECT * INTO v_license FROM licenses
    WHERE id = p_license_id
      AND pro_account_id = p_pro_account_id
      AND linked_account_id IS NULL
      AND status = 'active';

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'LICENSE_NOT_AVAILABLE',
            'message', 'La licence n''est plus disponible'
        );
    END IF;

    -- Check collaborator exists and is now EXPIRED (subscription was canceled)
    SELECT * INTO v_collaborator FROM users
    WHERE id = p_collaborator_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'COLLABORATOR_NOT_FOUND',
            'message', 'Collaborateur introuvable'
        );
    END IF;

    -- Verify collaborator is no longer PREMIUM (subscription was canceled)
    IF v_collaborator.subscription_type NOT IN ('EXPIRED', 'TRIAL') THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'SUBSCRIPTION_STILL_ACTIVE',
            'message', 'L''abonnement du collaborateur n''a pas encore ete resilie'
        );
    END IF;

    -- Assign license
    UPDATE licenses SET
        linked_account_id = p_collaborator_id,
        linked_at = v_now,
        updated_at = v_now
    WHERE id = p_license_id;

    -- Update user subscription_type to LICENSED
    UPDATE users SET
        subscription_type = 'LICENSED',
        subscription_expires_at = CASE
            WHEN v_license.is_lifetime THEN NULL
            ELSE v_license.end_date
        END,
        updated_at = v_now
    WHERE id = p_collaborator_id;

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Licence attribuee avec succes apres resiliation',
        'license_id', p_license_id,
        'collaborator_id', p_collaborator_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION finalize_license_assignment(UUID, UUID, UUID) TO authenticated;

-- =============================================================================
-- 8. RPC FUNCTION: UNLINK_COLLABORATOR
-- =============================================================================
-- Sets license status to 'unlinked' and calculates effective date

CREATE OR REPLACE FUNCTION unlink_collaborator(
    p_license_id UUID,
    p_pro_account_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_license RECORD;
    v_effective_date TIMESTAMPTZ;
    v_billing_anchor INT;
BEGIN
    SELECT l.*, pa.billing_anchor_day INTO v_license
    FROM licenses l
    JOIN pro_accounts pa ON l.pro_account_id = pa.id
    WHERE l.id = p_license_id
      AND l.pro_account_id = p_pro_account_id
      AND l.linked_account_id IS NOT NULL
      AND l.status = 'active';

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'LICENSE_NOT_FOUND_OR_NOT_ASSIGNED',
            'message', 'Licence introuvable ou non assignee'
        );
    END IF;

    -- Calculate effective date (next billing anchor date or 30 days, whichever is later)
    v_billing_anchor := COALESCE(v_license.billing_anchor_day, 1);

    -- Find next billing date
    v_effective_date := (
        date_trunc('month', NOW()) +
        INTERVAL '1 month' +
        (v_billing_anchor - 1) * INTERVAL '1 day'
    )::TIMESTAMPTZ;

    -- Ensure at least 30 days notice
    IF v_effective_date < NOW() + INTERVAL '30 days' THEN
        v_effective_date := v_effective_date + INTERVAL '1 month';
    END IF;

    -- Update license status to unlinked
    UPDATE licenses SET
        status = 'unlinked',
        unlink_requested_at = NOW(),
        unlink_effective_at = v_effective_date,
        updated_at = NOW()
    WHERE id = p_license_id;

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Deliaison planifiee',
        'license_id', p_license_id,
        'effective_date', v_effective_date
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION unlink_collaborator(UUID, UUID) TO authenticated;

-- =============================================================================
-- 9. INDEX UPDATES
-- =============================================================================

-- Index for available licenses (in pool with new status)
DROP INDEX IF EXISTS idx_licenses_available_v2;
CREATE INDEX idx_licenses_available_v2
ON licenses(pro_account_id, status)
WHERE linked_account_id IS NULL AND status IN ('active', 'available');

-- Index for suspended licenses
DROP INDEX IF EXISTS idx_licenses_suspended;
CREATE INDEX idx_licenses_suspended
ON licenses(pro_account_id)
WHERE status = 'suspended';

-- Index for canceled/unlinked licenses (pending processing)
DROP INDEX IF EXISTS idx_licenses_pending_processing;
CREATE INDEX idx_licenses_pending_processing
ON licenses(pro_account_id)
WHERE status IN ('canceled', 'unlinked');

-- =============================================================================
-- MIGRATION COMPLETE
-- =============================================================================
-- Changes made:
--
-- users table:
--   - subscription_type constraint updated (no FREE)
--   - Default changed to TRIAL
--
-- licenses table:
--   - status constraint updated (available, active, suspended, canceled, unlinked, paused)
--   - Default changed to available
--   - Migrated pending → available, cancelled → canceled, expired → suspended
--
-- pro_accounts table:
--   + status (TEXT): trial, active, expired, suspended
--   + trial_ends_at (TIMESTAMPTZ)
--
-- Functions added:
--   - assign_license_to_collaborator(): Assign with LIFETIME/LICENSED/PREMIUM checks
--   - check_premium_access(): Check user's premium access
--   - cancel_license(): Cancel a license (status → canceled)
--   - finalize_license_assignment(): Complete assignment after PREMIUM cancellation
--   - unlink_collaborator(): Unlink with effective date calculation
--
-- Indexes added:
--   - idx_licenses_available_v2
--   - idx_licenses_suspended
--   - idx_licenses_pending_processing
-- =============================================================================
