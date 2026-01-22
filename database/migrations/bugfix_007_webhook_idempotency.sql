-- =============================================================================
-- BUGFIX-007 - Webhook Idempotency & Race Condition Fixes
-- =============================================================================
-- Issues identified by Ralph Wiggum audit:
-- 1. stripe_payments table lacks UNIQUE constraints for idempotent upserts
-- 2. licenses table lacks UNIQUE constraint on stripe_payment_intent_id
-- 3. Race condition on license assignment (no locking)
-- =============================================================================

-- =============================================================================
-- FIX 1: Add UNIQUE constraints to stripe_payments for idempotency
-- =============================================================================
-- The webhook uses upsert with onConflict, but it requires UNIQUE constraints

-- Add partial unique index for stripe_payment_intent_id (allows multiple NULLs)
CREATE UNIQUE INDEX IF NOT EXISTS idx_stripe_payments_intent_unique
ON stripe_payments (stripe_payment_intent_id)
WHERE stripe_payment_intent_id IS NOT NULL;

-- Add partial unique index for stripe_invoice_id (allows multiple NULLs)
CREATE UNIQUE INDEX IF NOT EXISTS idx_stripe_payments_invoice_unique
ON stripe_payments (stripe_invoice_id)
WHERE stripe_invoice_id IS NOT NULL;

-- =============================================================================
-- FIX 2: Add UNIQUE constraint to licenses for payment_intent idempotency
-- =============================================================================
-- Prevents duplicate license creation from webhook replay

CREATE UNIQUE INDEX IF NOT EXISTS idx_licenses_payment_intent_unique
ON licenses (stripe_payment_intent_id)
WHERE stripe_payment_intent_id IS NOT NULL;

-- =============================================================================
-- FIX 3: Atomic license assignment function with row locking
-- =============================================================================
-- Replaces the existing function with one that uses SELECT ... FOR UPDATE

CREATE OR REPLACE FUNCTION assign_license_atomic(
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
    -- ATOMIC: Lock the license row to prevent concurrent assignment
    SELECT * INTO v_license FROM licenses
    WHERE id = p_license_id
      AND pro_account_id = p_pro_account_id
      AND linked_account_id IS NULL
      AND status = 'available'
    FOR UPDATE SKIP LOCKED;  -- Skip if already being modified

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'LICENSE_NOT_AVAILABLE',
            'message', 'La licence n''est pas disponible, n''existe pas, ou est en cours de modification'
        );
    END IF;

    -- Lock and check collaborator
    SELECT * INTO v_collaborator FROM users
    WHERE id = p_collaborator_id
    FOR UPDATE;

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
            'error', 'COLLABORATOR_HAS_LIFETIME',
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

    -- Handle PREMIUM users
    IF v_collaborator.subscription_type = 'PREMIUM' THEN
        RETURN jsonb_build_object(
            'success', true,
            'action', 'CANCEL_EXISTING_SUB',
            'message', 'Ce collaborateur a un abonnement Premium actif qui doit etre resilie',
            'stripe_subscription_id', v_collaborator.stripe_subscription_id,
            'collaborator_id', p_collaborator_id,
            'license_id', p_license_id
        );
    END IF;

    -- Assign license atomically
    UPDATE licenses SET
        status = 'active',
        linked_account_id = p_collaborator_id,
        linked_at = v_now,
        updated_at = v_now
    WHERE id = p_license_id
      AND linked_account_id IS NULL;  -- Double-check in update

    IF NOT FOUND THEN
        -- Race condition: someone else assigned it between our checks
        RETURN jsonb_build_object(
            'success', false,
            'error', 'RACE_CONDITION',
            'message', 'La licence a ete attribuee par un autre processus'
        );
    END IF;

    -- Update user
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
        'action', 'ASSIGNED',
        'message', 'Licence attribuee avec succes',
        'license_id', p_license_id,
        'collaborator_id', p_collaborator_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION assign_license_atomic(UUID, UUID, UUID) TO authenticated;

-- =============================================================================
-- FIX 4: Add handle subscription paused trigger
-- =============================================================================
-- When Stripe pauses a subscription (payment_behavior: pause), we should suspend

CREATE OR REPLACE FUNCTION handle_stripe_subscription_paused()
RETURNS TRIGGER AS $$
BEGIN
    -- If subscription status changed to 'paused'
    IF (OLD.status != 'paused' AND NEW.status = 'paused') THEN
        -- For Pro licenses: suspend all licenses under this subscription
        UPDATE licenses
        SET status = 'suspended',
            updated_at = NOW()
        WHERE stripe_subscription_ref = NEW.id
          AND status = 'active';

        RAISE LOG 'Subscription % paused, licenses suspended', NEW.stripe_subscription_id;
    END IF;

    -- If subscription resumed from paused
    IF (OLD.status = 'paused' AND NEW.status = 'active') THEN
        UPDATE licenses
        SET status = 'active',
            updated_at = NOW()
        WHERE stripe_subscription_ref = NEW.id
          AND status = 'suspended';

        RAISE LOG 'Subscription % resumed, licenses reactivated', NEW.stripe_subscription_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Create trigger if not exists
DROP TRIGGER IF EXISTS on_stripe_subscription_paused ON stripe_subscriptions;
CREATE TRIGGER on_stripe_subscription_paused
AFTER UPDATE ON stripe_subscriptions
FOR EACH ROW EXECUTE FUNCTION handle_stripe_subscription_paused();

GRANT EXECUTE ON FUNCTION handle_stripe_subscription_paused() TO service_role;

-- =============================================================================
-- FIX 5: Email normalization function for trial abuse prevention
-- =============================================================================
-- Normalize emails to prevent Gmail alias abuse (user+tag@gmail.com, u.s.e.r@gmail.com)

CREATE OR REPLACE FUNCTION normalize_email(email TEXT)
RETURNS TEXT AS $$
DECLARE
    local_part TEXT;
    domain TEXT;
BEGIN
    -- Split email into local and domain parts
    local_part := SPLIT_PART(email, '@', 1);
    domain := LOWER(SPLIT_PART(email, '@', 2));

    -- Gmail-specific normalization
    IF domain IN ('gmail.com', 'googlemail.com') THEN
        -- Remove dots from local part
        local_part := REPLACE(local_part, '.', '');
        -- Remove everything after + (tag)
        local_part := SPLIT_PART(local_part, '+', 1);
        -- Normalize domain
        domain := 'gmail.com';
    ELSE
        -- For other providers, just remove + tags
        local_part := SPLIT_PART(local_part, '+', 1);
    END IF;

    RETURN LOWER(local_part) || '@' || domain;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Create index on normalized email for fast lookup
CREATE INDEX IF NOT EXISTS idx_users_normalized_email
ON users (normalize_email(email));

-- =============================================================================
-- FIX 6: Add trial abuse check function
-- =============================================================================

CREATE OR REPLACE FUNCTION check_trial_abuse(p_email TEXT, p_device_fingerprint TEXT)
RETURNS JSONB AS $$
DECLARE
    v_normalized_email TEXT;
    v_existing_user RECORD;
    v_existing_by_fingerprint RECORD;
BEGIN
    v_normalized_email := normalize_email(p_email);

    -- Check if normalized email already has an account
    SELECT * INTO v_existing_user FROM users
    WHERE normalize_email(email) = v_normalized_email
    LIMIT 1;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'EMAIL_ALIAS_EXISTS',
            'message', 'Un compte existe deja avec cet email ou un alias de cet email',
            'existing_email', v_existing_user.email
        );
    END IF;

    -- Check if device fingerprint already used for trial
    IF p_device_fingerprint IS NOT NULL AND p_device_fingerprint != '' THEN
        SELECT * INTO v_existing_by_fingerprint FROM users
        WHERE device_fingerprint_id = p_device_fingerprint
          AND subscription_type IN ('TRIAL', 'EXPIRED')  -- Had or has trial
        LIMIT 1;

        IF FOUND THEN
            RETURN jsonb_build_object(
                'allowed', false,
                'reason', 'DEVICE_ALREADY_TRIALED',
                'message', 'Cet appareil a deja beneficie d''un essai gratuit'
            );
        END IF;
    END IF;

    RETURN jsonb_build_object(
        'allowed', true
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION check_trial_abuse(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION normalize_email(TEXT) TO authenticated;

-- =============================================================================
-- VERIFICATION QUERIES
-- =============================================================================
-- Test unique constraints:
-- INSERT INTO stripe_payments (stripe_payment_intent_id, ...) VALUES ('pi_xxx', ...);
-- INSERT INTO stripe_payments (stripe_payment_intent_id, ...) VALUES ('pi_xxx', ...);
-- Second insert should fail with unique constraint violation

-- Test email normalization:
-- SELECT normalize_email('John.Doe+test@gmail.com');
-- Should return: johndoe@gmail.com

-- Test trial abuse check:
-- SELECT check_trial_abuse('john+spam@gmail.com', 'device123');

-- =============================================================================
-- SUMMARY
-- =============================================================================
-- 1. Added UNIQUE partial indexes for webhook idempotency
-- 2. Added atomic license assignment with row locking (FOR UPDATE SKIP LOCKED)
-- 3. Added trigger to handle Stripe subscription pause/resume
-- 4. Added email normalization to prevent Gmail alias abuse
-- 5. Added trial abuse check function
-- =============================================================================
