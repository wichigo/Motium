-- =================================================================
-- BUGFIX 016: Atomic Invoice Paid Processing for Pro Accounts
-- =================================================================
-- EXÉCUTER CE FICHIER SUR SUPABASE STUDIO (https://studio.motium.app)
-- Dans SQL Editor, coller et exécuter ce contenu
-- =================================================================
--
-- AUDIT RALPH WIGGUM - Problème identifié:
-- handleInvoicePaid fait plusieurs opérations non-atomiques:
-- 1. processProRenewal()
-- 2. Réactiver les licences suspendues
-- 3. Mettre à jour les end_date des licences
-- 4. Mettre à jour les subscription_expires_at des utilisateurs
-- 5. Réactiver le pro_account si suspendu
--
-- Si une opération échoue au milieu, l'état est incohérent.
--
-- Solution: RPC atomique process_invoice_paid_pro()
--
-- =================================================================

CREATE OR REPLACE FUNCTION process_invoice_paid_pro(
    p_pro_account_id UUID,
    p_stripe_subscription_id TEXT,
    p_period_end TIMESTAMPTZ,
    p_stripe_subscription_item_id TEXT DEFAULT NULL,
    p_stripe_price_id TEXT DEFAULT NULL
)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_reactivated_count INTEGER := 0;
    v_renewed_count INTEGER := 0;
    v_user_count INTEGER := 0;
    v_deleted_count INTEGER := 0;
    v_returned_count INTEGER := 0;
    v_license RECORD;
    v_user_id UUID;
    v_affected_user_ids UUID[] := ARRAY[]::UUID[];
    v_other_active_licenses INTEGER;
    v_user_locked RECORD;
BEGIN
    RAISE LOG '[INVOICE-PAID] === Starting atomic invoice processing for pro account % ===', p_pro_account_id;
    RAISE LOG '[INVOICE-PAID] Subscription: %, Period end: %', p_stripe_subscription_id, p_period_end;

    -- ============================================
    -- STEP 1: Process Pro Renewal (canceled/unlinked licenses)
    -- This was previously a separate function call
    -- ============================================
    RAISE LOG '[INVOICE-PAID] Step 1: Processing canceled/unlinked licenses...';

    -- Step 1a: Process licenses with expired unlink_effective_at
    FOR v_license IN
        SELECT id, linked_account_id, is_lifetime, pro_account_id
        FROM licenses
        WHERE pro_account_id = p_pro_account_id
          AND unlink_effective_at IS NOT NULL
          AND unlink_effective_at <= NOW()
          AND status NOT IN ('available')
        FOR UPDATE
    LOOP
        IF v_license.linked_account_id IS NOT NULL THEN
            v_affected_user_ids := array_append(v_affected_user_ids, v_license.linked_account_id);

            -- Deactivate company_link
            UPDATE company_links
            SET status = 'INACTIVE', unlinked_at = NOW(), updated_at = NOW()
            WHERE user_id = v_license.linked_account_id
              AND linked_pro_account_id = v_license.pro_account_id
              AND status = 'ACTIVE';
        END IF;

        IF v_license.is_lifetime THEN
            UPDATE licenses
            SET status = 'available', linked_account_id = NULL, linked_at = NULL,
                unlink_requested_at = NULL, unlink_effective_at = NULL, updated_at = NOW()
            WHERE id = v_license.id;
            v_returned_count := v_returned_count + 1;
        ELSE
            DELETE FROM licenses WHERE id = v_license.id;
            v_deleted_count := v_deleted_count + 1;
        END IF;
    END LOOP;

    -- Step 1b: Process canceled licenses
    FOR v_license IN
        SELECT id, linked_account_id, is_lifetime, pro_account_id
        FROM licenses
        WHERE pro_account_id = p_pro_account_id
          AND status = 'canceled'
        FOR UPDATE
    LOOP
        IF v_license.linked_account_id IS NOT NULL THEN
            v_affected_user_ids := array_append(v_affected_user_ids, v_license.linked_account_id);

            UPDATE company_links
            SET status = 'INACTIVE', unlinked_at = NOW(), updated_at = NOW()
            WHERE user_id = v_license.linked_account_id
              AND linked_pro_account_id = v_license.pro_account_id
              AND status = 'ACTIVE';
        END IF;

        IF v_license.is_lifetime THEN
            UPDATE licenses
            SET status = 'available', linked_account_id = NULL, linked_at = NULL, updated_at = NOW()
            WHERE id = v_license.id;
            v_returned_count := v_returned_count + 1;
        ELSE
            DELETE FROM licenses WHERE id = v_license.id;
            v_deleted_count := v_deleted_count + 1;
        END IF;
    END LOOP;

    RAISE LOG '[INVOICE-PAID] Step 1 complete: % deleted, % returned', v_deleted_count, v_returned_count;

    -- ============================================
    -- STEP 2: Reactivate suspended licenses
    -- ============================================
    RAISE LOG '[INVOICE-PAID] Step 2: Reactivating suspended licenses...';

    UPDATE licenses
    SET status = 'active', updated_at = NOW()
    WHERE pro_account_id = p_pro_account_id
      AND is_lifetime = false
      AND status = 'suspended';

    GET DIAGNOSTICS v_reactivated_count = ROW_COUNT;
    RAISE LOG '[INVOICE-PAID] Reactivated % suspended licenses', v_reactivated_count;

    -- ============================================
    -- STEP 3: Update all monthly license end_dates and Stripe refs
    -- ============================================
    RAISE LOG '[INVOICE-PAID] Step 3: Updating license end_dates...';

    UPDATE licenses
    SET
        end_date = p_period_end,
        stripe_subscription_id = COALESCE(p_stripe_subscription_id, stripe_subscription_id),
        stripe_subscription_item_id = COALESCE(p_stripe_subscription_item_id, stripe_subscription_item_id),
        stripe_price_id = COALESCE(p_stripe_price_id, stripe_price_id),
        billing_starts_at = NOW(),
        updated_at = NOW()
    WHERE pro_account_id = p_pro_account_id
      AND is_lifetime = false
      AND status IN ('active', 'available');

    GET DIAGNOSTICS v_renewed_count = ROW_COUNT;
    RAISE LOG '[INVOICE-PAID] Renewed % licenses, new end_date: %', v_renewed_count, p_period_end;

    -- ============================================
    -- STEP 4: Update user subscription_expires_at for active licenses
    -- ============================================
    RAISE LOG '[INVOICE-PAID] Step 4: Updating user expiration dates...';

    FOR v_license IN
        SELECT DISTINCT linked_account_id
        FROM licenses
        WHERE pro_account_id = p_pro_account_id
          AND is_lifetime = false
          AND status = 'active'
          AND linked_account_id IS NOT NULL
    LOOP
        UPDATE users
        SET subscription_expires_at = p_period_end, updated_at = NOW()
        WHERE id = v_license.linked_account_id;

        v_user_count := v_user_count + 1;
    END LOOP;

    RAISE LOG '[INVOICE-PAID] Updated expiration for % users', v_user_count;

    -- ============================================
    -- STEP 5: Update affected users from Step 1 (set to EXPIRED if no active licenses)
    -- ============================================
    IF array_length(v_affected_user_ids, 1) > 0 THEN
        RAISE LOG '[INVOICE-PAID] Step 5: Processing % affected users from renewal...', array_length(v_affected_user_ids, 1);

        SELECT array_agg(DISTINCT x) INTO v_affected_user_ids
        FROM unnest(v_affected_user_ids) AS x;

        FOREACH v_user_id IN ARRAY v_affected_user_ids
        LOOP
            BEGIN
                SELECT * INTO v_user_locked FROM users WHERE id = v_user_id FOR UPDATE NOWAIT;
            EXCEPTION WHEN lock_not_available THEN
                CONTINUE;
            END;

            SELECT COUNT(*) INTO v_other_active_licenses
            FROM licenses WHERE linked_account_id = v_user_id AND status = 'active';

            IF v_other_active_licenses = 0 THEN
                UPDATE users
                SET subscription_type = 'EXPIRED', subscription_expires_at = NULL, updated_at = NOW()
                WHERE id = v_user_id AND subscription_type = 'LICENSED';

                IF FOUND THEN
                    RAISE LOG '[INVOICE-PAID] User % set to EXPIRED', v_user_id;
                END IF;
            END IF;
        END LOOP;
    END IF;

    -- ============================================
    -- STEP 6: Reactivate pro_account if suspended
    -- ============================================
    RAISE LOG '[INVOICE-PAID] Step 6: Checking pro_account status...';

    UPDATE pro_accounts
    SET status = 'active', updated_at = NOW()
    WHERE id = p_pro_account_id
      AND status = 'suspended';

    IF FOUND THEN
        RAISE LOG '[INVOICE-PAID] Pro account % reactivated from suspended', p_pro_account_id;
    END IF;

    RAISE LOG '[INVOICE-PAID] === Invoice processing complete ===';

    RETURN json_build_object(
        'success', true,
        'deleted_licenses', v_deleted_count,
        'returned_licenses', v_returned_count,
        'reactivated_licenses', v_reactivated_count,
        'renewed_licenses', v_renewed_count,
        'updated_users', v_user_count
    );

EXCEPTION WHEN OTHERS THEN
    RAISE LOG '[INVOICE-PAID] ERROR: %', SQLERRM;
    RETURN json_build_object(
        'success', false,
        'error', SQLERRM
    );
END;
$$;

-- ============================================
-- Grant permissions
-- ============================================
GRANT EXECUTE ON FUNCTION process_invoice_paid_pro(UUID, TEXT, TIMESTAMPTZ, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION process_invoice_paid_pro(UUID, TEXT, TIMESTAMPTZ, TEXT, TEXT) TO authenticated;

-- ============================================
-- VERIFICATION
-- ============================================
-- Check the function was created:
-- SELECT prosrc FROM pg_proc WHERE proname = 'process_invoice_paid_pro';
--
-- Test with dry run (non-existent UUID):
-- SELECT process_invoice_paid_pro('00000000-0000-0000-0000-000000000000', 'sub_test', NOW() + INTERVAL '30 days');
-- ============================================
