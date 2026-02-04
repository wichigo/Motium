-- ============================================================================
-- BUGFIX 021: Sync Trigger LIFETIME Priority
-- ============================================================================
-- Date: 2026-02-04
-- Problem: When a user upgrades from PREMIUM to LIFETIME, two webhooks arrive:
--   1. payment_intent.succeeded → Creates LIFETIME subscription record
--   2. customer.subscription.updated → Monthly subscription updated (cancel_at_period_end=true)
--
-- The second webhook triggers sync_user_subscription_cache which sees
-- status='active' + subscription_type='individual_monthly' and sets user to PREMIUM,
-- effectively erasing the LIFETIME status that was just set.
--
-- Fix: The trigger must check if the user has an active LIFETIME subscription
-- and NOT overwrite it with a lesser subscription type.
-- ============================================================================

CREATE OR REPLACE FUNCTION sync_user_subscription_cache()
RETURNS TRIGGER AS $$
DECLARE
    v_has_active_lifetime BOOLEAN := FALSE;
    v_current_user_type TEXT;
BEGIN
    -- Only for individual subscriptions (Pro subscriptions have user_id = NULL)
    IF NEW.user_id IS NOT NULL THEN

        -- Check if user has an active LIFETIME subscription (other than this one if it's being updated)
        SELECT EXISTS(
            SELECT 1
            FROM stripe_subscriptions
            WHERE user_id = NEW.user_id
              AND subscription_type = 'individual_lifetime'
              AND status IN ('active', 'trialing')
              AND id != NEW.id  -- Exclude current record being updated
        ) INTO v_has_active_lifetime;

        -- Also check if THIS subscription is a LIFETIME (for INSERT case)
        IF NEW.subscription_type = 'individual_lifetime' AND NEW.status IN ('active', 'trialing') THEN
            v_has_active_lifetime := TRUE;
        END IF;

        -- Get current user subscription type for logging
        SELECT subscription_type INTO v_current_user_type
        FROM users WHERE id = NEW.user_id;

        -- PRIORITY RULE: If user has active LIFETIME, only LIFETIME changes can affect them
        -- A monthly subscription update should NEVER downgrade a LIFETIME user
        IF v_has_active_lifetime AND NEW.subscription_type != 'individual_lifetime' THEN
            -- User has LIFETIME - don't let monthly subscription changes affect their status
            -- But still update subscription_expires_at if this is the monthly one
            RAISE NOTICE 'sync_user_subscription_cache: User % has active LIFETIME, ignoring % subscription update',
                NEW.user_id, NEW.subscription_type;
            RETURN NEW;
        END IF;

        UPDATE users SET
            subscription_type = CASE
                -- Active LIFETIME always wins
                WHEN NEW.subscription_type = 'individual_lifetime' AND NEW.status IN ('active', 'trialing') THEN 'LIFETIME'

                -- If user currently has LIFETIME, don't downgrade (extra safety)
                WHEN v_current_user_type = 'LIFETIME' AND v_has_active_lifetime THEN 'LIFETIME'

                -- Active statuses: grant access
                WHEN NEW.status IN ('active', 'trialing') THEN
                    CASE
                        WHEN NEW.subscription_type = 'individual_lifetime' THEN 'LIFETIME'
                        WHEN NEW.subscription_type = 'individual_monthly' THEN 'PREMIUM'
                        ELSE 'PREMIUM'
                    END

                -- Terminated statuses: revoke access (but only if no active LIFETIME)
                WHEN NEW.status IN ('canceled', 'unpaid', 'incomplete_expired') THEN
                    CASE
                        WHEN v_has_active_lifetime THEN 'LIFETIME'  -- Keep LIFETIME
                        ELSE 'EXPIRED'
                    END

                -- Pending statuses: don't modify (payment in progress or grace period)
                WHEN NEW.status IN ('past_due', 'incomplete', 'paused') THEN subscription_type

                -- Fallback: don't modify
                ELSE subscription_type
            END,
            subscription_expires_at = CASE
                -- LIFETIME never expires
                WHEN NEW.subscription_type = 'individual_lifetime' THEN NULL
                -- If user has LIFETIME, keep NULL expiration
                WHEN v_has_active_lifetime THEN NULL
                -- For trialing, use trial_end from subscription if available
                WHEN NEW.status = 'trialing' THEN COALESCE(NEW.current_period_end, subscription_expires_at)
                -- For incomplete, don't modify
                WHEN NEW.status = 'incomplete' THEN subscription_expires_at
                -- Otherwise, use period end
                ELSE NEW.current_period_end
            END,
            updated_at = NOW()
        WHERE id = NEW.user_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Trigger already exists, function is replaced in-place
-- No need to recreate trigger

-- ============================================================================
-- VERIFICATION QUERY (run manually after applying migration)
-- ============================================================================
-- Check users with LIFETIME subscriptions:
-- SELECT u.id, u.email, u.subscription_type, ss.subscription_type as stripe_sub_type, ss.status
-- FROM users u
-- JOIN stripe_subscriptions ss ON ss.user_id = u.id
-- WHERE ss.subscription_type = 'individual_lifetime'
--   AND ss.status IN ('active', 'trialing');
