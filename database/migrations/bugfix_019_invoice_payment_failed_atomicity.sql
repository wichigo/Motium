-- ============================================================================
-- BUGFIX 019: RPC atomique pour handleInvoicePaymentFailed (Pro)
-- ============================================================================
-- Date: 2026-02-03
-- Contexte: handleInvoicePaymentFailed utilise plusieurs updates séparées non-atomiques
--           pour les subscriptions Pro. Si le webhook échoue au milieu, l'état peut
--           être incohérent (licenses suspendues mais users non mis à jour).
--
-- Cette fonction gère atomiquement:
-- 1. Suspendre les licences monthly (pas lifetime)
-- 2. Mettre à jour pro_accounts.status = 'suspended'
-- 3. Pour chaque user affecté: vérifier autres licences actives, si aucune → EXPIRED
-- ============================================================================

CREATE OR REPLACE FUNCTION process_invoice_payment_failed_pro(
    p_stripe_subscription_id TEXT,
    p_pro_account_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_subscription_ref UUID;
    v_suspended_count INTEGER := 0;
    v_affected_users INTEGER := 0;
    v_user_record RECORD;
BEGIN
    -- Étape 0: Trouver l'ID interne de la subscription
    SELECT id INTO v_subscription_ref
    FROM stripe_subscriptions
    WHERE stripe_subscription_id = p_stripe_subscription_id
    LIMIT 1;

    IF v_subscription_ref IS NULL THEN
        RAISE NOTICE 'process_invoice_payment_failed_pro: subscription not found %', p_stripe_subscription_id;
        -- Continue anyway - licenses might not be linked to subscription ref
    END IF;

    -- Étape 1: Suspendre les licences MONTHLY uniquement (pas lifetime)
    -- Note: pas besoin de FOR UPDATE car UPDATE verrouille automatiquement les lignes
    UPDATE licenses
    SET
        status = 'suspended',
        paused_at = NOW(),
        updated_at = NOW()
    WHERE pro_account_id = p_pro_account_id
      AND is_lifetime = false
      AND status IN ('active', 'available');  -- Ne pas re-suspendre

    GET DIAGNOSTICS v_suspended_count = ROW_COUNT;
    RAISE NOTICE 'Suspended % monthly licenses for pro_account %', v_suspended_count, p_pro_account_id;

    -- Étape 2: Mettre à jour le statut du pro_account
    UPDATE pro_accounts
    SET
        status = 'suspended',
        updated_at = NOW()
    WHERE id = p_pro_account_id
      AND status != 'suspended';  -- Idempotent

    -- Étape 3: Pour chaque utilisateur affecté, vérifier s'il a d'autres licences actives
    -- Si non, mettre son subscription_expires_at à NOW (expiration immédiate)
    FOR v_user_record IN
        SELECT DISTINCT l.linked_account_id
        FROM licenses l
        WHERE l.pro_account_id = p_pro_account_id
          AND l.linked_account_id IS NOT NULL
          AND l.status = 'suspended'
          AND l.is_lifetime = false
    LOOP
        -- Vérifier si cet utilisateur a d'autres licences actives (d'autres entreprises)
        IF NOT EXISTS (
            SELECT 1 FROM licenses other_l
            WHERE other_l.linked_account_id = v_user_record.linked_account_id
              AND other_l.status = 'active'
              AND other_l.id NOT IN (
                  SELECT id FROM licenses
                  WHERE pro_account_id = p_pro_account_id AND status = 'suspended'
              )
        ) THEN
            -- Pas d'autres licences actives → expirer l'utilisateur
            UPDATE users
            SET
                subscription_expires_at = NOW(),
                updated_at = NOW()
            WHERE id = v_user_record.linked_account_id
              AND subscription_type = 'LICENSED';  -- Seulement si LICENSED

            v_affected_users := v_affected_users + 1;
            RAISE NOTICE 'User % marked for expiration (no other active licenses)', v_user_record.linked_account_id;
        ELSE
            RAISE NOTICE 'User % has other active licenses, keeping access', v_user_record.linked_account_id;
        END IF;
    END LOOP;

    -- Note: On ne change PAS subscription_type ici car l'utilisateur reste LICENSED
    -- tant que la licence existe (même suspendue). C'est subscription_expires_at
    -- qui contrôle l'accès effectif.

    RETURN jsonb_build_object(
        'success', true,
        'suspended_count', v_suspended_count,
        'affected_users', v_affected_users,
        'pro_account_id', p_pro_account_id
    );

EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Error in process_invoice_payment_failed_pro: %', SQLERRM;
    RETURN jsonb_build_object(
        'success', false,
        'error', SQLERRM
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Grant execute permission
GRANT EXECUTE ON FUNCTION process_invoice_payment_failed_pro(TEXT, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION process_invoice_payment_failed_pro(TEXT, UUID) TO service_role;

-- ============================================================================
-- VERIFICATION
-- ============================================================================
-- Test de la fonction (à exécuter manuellement):
-- SELECT process_invoice_payment_failed_pro('sub_test123', 'pro-account-uuid-here');
