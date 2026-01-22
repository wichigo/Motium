-- =================================================================
-- BUGFIX 014: handleSubscriptionDeleted - Set users to EXPIRED
-- =================================================================
-- EXÉCUTER CE FICHIER SUR SUPABASE STUDIO (http://176.168.117.243:3000)
-- Dans SQL Editor, coller et exécuter ce contenu
-- =================================================================
--
-- ARBRE 4 - RÉSILIATION LICENCE (Audit Ralph Wiggum)
--
-- ============ PROBLÈMES IDENTIFIÉS ============
-- BUG-4.1: handleSubscriptionDeleted() marque les licences 'canceled' mais ne
--          met pas les utilisateurs à EXPIRED
-- BUG-4.2: Trigger sync_subscription_type() ne gère pas 'canceled'
--          (intentionnel: l'utilisateur garde l'accès pendant la période payée)
-- BUG-4.3: Gap entre subscription.deleted et renewal laisse l'état incohérent
--
-- ============ ANALYSE DU FLUX ============
-- 1. User cancels subscription via Stripe Portal
-- 2. Stripe sets cancel_at_period_end = true
-- 3. At period end, Stripe fires customer.subscription.deleted
-- 4. handleSubscriptionDeleted() marks licenses as 'canceled'
-- 5. ** BUG: Users still have subscription_type='LICENSED' **
-- 6. Only at NEXT renewal would processProRenewal() clean up
--    But there IS no next renewal for a deleted subscription!
--
-- ============ SOLUTION ============
-- Create RPC function process_subscription_deleted() that:
-- 1. Marks licenses as 'canceled' (status change)
-- 2. Updates affected users to EXPIRED (with multi-license check)
-- 3. Is atomic and idempotent
--
-- The webhook will call this RPC instead of just updating licenses
-- =================================================================

-- ============================================
-- STEP 1: Create atomic subscription deleted function
-- ============================================
CREATE OR REPLACE FUNCTION process_subscription_deleted(
    p_stripe_subscription_id TEXT,
    p_pro_account_id UUID DEFAULT NULL
)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_license RECORD;
    v_affected_user_ids UUID[] := ARRAY[]::UUID[];
    v_canceled_count INTEGER := 0;
    v_other_active_licenses INTEGER;
    v_user_id UUID;
BEGIN
    RAISE LOG '[ARBRE4] === Processing subscription deleted: % ===', p_stripe_subscription_id;

    -- ============================================
    -- PHASE 1: Mark all licenses for this subscription as 'canceled'
    -- ============================================
    FOR v_license IN
        SELECT id, linked_account_id, pro_account_id
        FROM licenses
        WHERE stripe_subscription_id = p_stripe_subscription_id
          AND status NOT IN ('canceled', 'available')  -- Don't process already canceled or unassigned
    LOOP
        -- Collect affected user if license was assigned
        IF v_license.linked_account_id IS NOT NULL THEN
            v_affected_user_ids := array_append(v_affected_user_ids, v_license.linked_account_id);
        END IF;

        -- Mark license as canceled
        UPDATE licenses
        SET
            status = 'canceled',
            updated_at = NOW()
        WHERE id = v_license.id;

        v_canceled_count := v_canceled_count + 1;
        RAISE LOG '[ARBRE4] License % marked as canceled (was linked to user %)',
            v_license.id, v_license.linked_account_id;
    END LOOP;

    -- ============================================
    -- PHASE 2: Update affected users
    -- Set to EXPIRED only if no other active licenses remain
    -- ============================================
    RAISE LOG '[ARBRE4] Phase 2: Updating % affected users...', array_length(v_affected_user_ids, 1);

    -- Deduplicate affected user IDs
    SELECT array_agg(DISTINCT x) INTO v_affected_user_ids
    FROM unnest(v_affected_user_ids) AS x;

    IF v_affected_user_ids IS NOT NULL AND array_length(v_affected_user_ids, 1) > 0 THEN
        FOREACH v_user_id IN ARRAY v_affected_user_ids
        LOOP
            -- Check for other active licenses from ANY pro_account
            SELECT COUNT(*) INTO v_other_active_licenses
            FROM licenses
            WHERE linked_account_id = v_user_id
              AND status = 'active';

            IF v_other_active_licenses = 0 THEN
                -- No other active licenses - set to EXPIRED
                UPDATE users
                SET
                    subscription_type = 'EXPIRED',
                    subscription_expires_at = NULL,
                    updated_at = NOW()
                WHERE id = v_user_id
                  AND subscription_type = 'LICENSED';

                IF FOUND THEN
                    RAISE LOG '[ARBRE4] User % set to EXPIRED (no other active licenses)', v_user_id;
                END IF;
            ELSE
                RAISE LOG '[ARBRE4] User % keeps LICENSED (has % other active licenses)', v_user_id, v_other_active_licenses;
            END IF;
        END LOOP;
    END IF;

    -- ============================================
    -- PHASE 3: Update pro_account status if all licenses are now canceled
    -- ============================================
    IF p_pro_account_id IS NOT NULL THEN
        -- Check if pro_account has any non-canceled licenses
        DECLARE
            v_remaining_active_licenses INTEGER;
        BEGIN
            SELECT COUNT(*) INTO v_remaining_active_licenses
            FROM licenses
            WHERE pro_account_id = p_pro_account_id
              AND status IN ('active', 'available', 'pending');

            IF v_remaining_active_licenses = 0 THEN
                -- All licenses are canceled/suspended - mark pro_account as expired
                UPDATE pro_accounts
                SET
                    status = 'expired',
                    updated_at = NOW()
                WHERE id = p_pro_account_id
                  AND status != 'expired';

                IF FOUND THEN
                    RAISE LOG '[ARBRE4] Pro account % set to expired (no remaining active licenses)', p_pro_account_id;
                END IF;
            END IF;
        END;
    END IF;

    RAISE LOG '[ARBRE4] === Subscription deleted processing complete: % licenses canceled ===', v_canceled_count;

    RETURN json_build_object(
        'success', true,
        'canceled_count', v_canceled_count,
        'affected_users', COALESCE(array_length(v_affected_user_ids, 1), 0)
    );

EXCEPTION WHEN OTHERS THEN
    RAISE LOG '[ARBRE4] ERROR in process_subscription_deleted: %', SQLERRM;
    RETURN json_build_object(
        'success', false,
        'error', SQLERRM
    );
END;
$$;

-- ============================================
-- STEP 2: Grant permissions
-- ============================================
GRANT EXECUTE ON FUNCTION process_subscription_deleted(TEXT, UUID) TO service_role;
GRANT EXECUTE ON FUNCTION process_subscription_deleted(TEXT, UUID) TO authenticated;

-- ============================================
-- STEP 3: Add index for faster queries
-- ============================================
CREATE INDEX IF NOT EXISTS idx_licenses_stripe_subscription_id
ON licenses(stripe_subscription_id);

-- ============================================
-- VERIFICATION QUERIES (run manually)
-- ============================================
-- Check the function was created:
-- SELECT prosrc FROM pg_proc WHERE proname = 'process_subscription_deleted';
--
-- Test the function (dry run with non-existent subscription):
-- SELECT process_subscription_deleted('sub_test_123', NULL);
-- ============================================
