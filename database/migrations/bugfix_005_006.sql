-- =============================================================================
-- BUGFIX-005 & BUGFIX-006 - License Assignment & Trigger Fixes
-- =============================================================================
-- BUG-005: assign_license_to_collaborator cherche status='active' au lieu de 'available'
-- BUG-006: Trigger révoque immédiatement l'accès sur 'canceled' au lieu d'attendre
-- =============================================================================

-- =============================================================================
-- FIX BUG-005: Corriger assign_license_to_collaborator
-- =============================================================================
-- Les licences non-assignées dans le pool ont status='available', pas 'active'
-- 'active' = assignée et en cours d'utilisation
-- 'available' = dans le pool, prête à être assignée

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
    -- BUGFIX-005: Chercher status='available' (licences du pool non-assignées)
    -- au lieu de status='active' (licences déjà assignées)
    SELECT * INTO v_license FROM licenses
    WHERE id = p_license_id
      AND pro_account_id = p_pro_account_id
      AND linked_account_id IS NULL
      AND status = 'available';  -- FIXED: était 'active'

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

    -- Handle PREMIUM users - need to cancel existing subscription first
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

    -- TRIAL or EXPIRED - can assign directly
    -- Update license: available -> active, set linked_account_id
    UPDATE licenses SET
        status = 'active',  -- Change from available to active when assigned
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
        'action', 'ASSIGNED',
        'message', 'Licence attribuee avec succes',
        'license_id', p_license_id,
        'collaborator_id', p_collaborator_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION assign_license_to_collaborator(UUID, UUID, UUID) TO authenticated;

-- =============================================================================
-- FIX BUG-005 (bis): Corriger finalize_license_assignment
-- =============================================================================

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
    -- BUGFIX-005: Chercher status='available' pour les licences du pool
    SELECT * INTO v_license FROM licenses
    WHERE id = p_license_id
      AND pro_account_id = p_pro_account_id
      AND linked_account_id IS NULL
      AND status = 'available';  -- FIXED: était 'active'

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

    -- Assign license: available -> active
    UPDATE licenses SET
        status = 'active',
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
-- FIX BUG-006: Corriger sync_subscription_type trigger
-- =============================================================================
-- Problème: Le trigger révoque immédiatement l'accès quand status='canceled'
-- Solution:
--   - 'canceled' = l'utilisateur garde l'accès jusqu'à la prochaine facturation
--   - 'unlinked' = l'utilisateur garde l'accès jusqu'à unlink_effective_at
--   - Seul le webhook/processus de renouvellement doit révoquer l'accès
--   - Changer FREE -> EXPIRED (FREE n'existe plus dans la contrainte)

DROP TRIGGER IF EXISTS on_license_change ON licenses;
DROP FUNCTION IF EXISTS sync_subscription_type();

CREATE OR REPLACE FUNCTION sync_subscription_type()
RETURNS TRIGGER AS $$
BEGIN
    -- Case 1: License assigned (linked_account_id was null, now has a value)
    -- Quand une licence est assignée -> LICENSED
    IF (OLD.linked_account_id IS NULL AND NEW.linked_account_id IS NOT NULL) THEN
        -- Update if license is active (just assigned)
        IF NEW.status = 'active' THEN
            UPDATE users
            SET subscription_type = 'LICENSED',
                updated_at = NOW()
            WHERE id = NEW.linked_account_id;

            RAISE LOG 'License % assigned to user %, subscription_type set to LICENSED',
                NEW.id, NEW.linked_account_id;
        END IF;
    END IF;

    -- Case 2: License physically unassigned (linked_account_id cleared)
    -- Cela arrive quand le processus de renouvellement supprime la licence
    IF (OLD.linked_account_id IS NOT NULL AND NEW.linked_account_id IS NULL) THEN
        -- Check if user has any other active licenses before setting to EXPIRED
        IF NOT EXISTS (
            SELECT 1 FROM licenses
            WHERE linked_account_id = OLD.linked_account_id
            AND status = 'active'
            AND id != OLD.id
        ) THEN
            UPDATE users
            SET subscription_type = 'EXPIRED',  -- FIXED: était 'FREE'
                updated_at = NOW()
            WHERE id = OLD.linked_account_id;

            RAISE LOG 'License % unassigned from user %, subscription_type set to EXPIRED',
                OLD.id, OLD.linked_account_id;
        END IF;
    END IF;

    -- Case 3: License status changed to suspended (payment failed)
    -- BUGFIX-006: Ne PAS révoquer sur 'canceled' ou 'unlinked' - l'utilisateur garde l'accès
    -- Seulement 'suspended' révoque immédiatement (échec de paiement critique)
    IF (OLD.status = 'active' AND NEW.status = 'suspended' AND NEW.linked_account_id IS NOT NULL) THEN
        -- Check if user has any other active licenses
        IF NOT EXISTS (
            SELECT 1 FROM licenses
            WHERE linked_account_id = NEW.linked_account_id
            AND status = 'active'
            AND id != NEW.id
        ) THEN
            UPDATE users
            SET subscription_type = 'EXPIRED',  -- FIXED: était 'FREE'
                updated_at = NOW()
            WHERE id = NEW.linked_account_id;

            RAISE LOG 'License % suspended for user %, subscription_type set to EXPIRED',
                NEW.id, NEW.linked_account_id;
        END IF;
    END IF;

    -- Case 4: License reactivated (suspended -> active)
    IF (OLD.status = 'suspended' AND NEW.status = 'active' AND NEW.linked_account_id IS NOT NULL) THEN
        UPDATE users
        SET subscription_type = 'LICENSED',
            updated_at = NOW()
        WHERE id = NEW.linked_account_id;

        RAISE LOG 'License % reactivated for user %, subscription_type set to LICENSED',
            NEW.id, NEW.linked_account_id;
    END IF;

    -- NOTE: 'canceled' et 'unlinked' ne changent PAS subscription_type
    -- L'utilisateur garde LICENSED jusqu'à ce que:
    -- - Le webhook de renouvellement supprime la licence canceled
    -- - unlink_effective_at est atteint et le processus supprime la liaison

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Recreate trigger
CREATE TRIGGER on_license_change
AFTER UPDATE ON licenses
FOR EACH ROW EXECUTE FUNCTION sync_subscription_type();

-- Update insert trigger to use EXPIRED instead of FREE
DROP TRIGGER IF EXISTS on_license_insert ON licenses;
DROP FUNCTION IF EXISTS sync_subscription_type_on_insert();

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

CREATE TRIGGER on_license_insert
AFTER INSERT ON licenses
FOR EACH ROW EXECUTE FUNCTION sync_subscription_type_on_insert();

-- Grant permissions
GRANT EXECUTE ON FUNCTION sync_subscription_type() TO service_role;
GRANT EXECUTE ON FUNCTION sync_subscription_type_on_insert() TO service_role;

-- =============================================================================
-- VERIFICATION QUERIES
-- =============================================================================
-- Test que les licences available peuvent être trouvées:
-- SELECT * FROM licenses WHERE status = 'available' AND linked_account_id IS NULL;

-- Test que les licences canceled gardent l'utilisateur en LICENSED:
-- UPDATE licenses SET status = 'canceled' WHERE id = 'xxx';
-- SELECT subscription_type FROM users WHERE id = (SELECT linked_account_id FROM licenses WHERE id = 'xxx');
-- Devrait toujours être 'LICENSED'

-- =============================================================================
-- SUMMARY OF FIXES
-- =============================================================================
-- BUG-005:
--   - assign_license_to_collaborator: status = 'available' (pas 'active')
--   - finalize_license_assignment: status = 'available' (pas 'active')
--   - Les licences passent de 'available' -> 'active' quand assignées
--
-- BUG-006:
--   - Le trigger ne révoque plus sur 'canceled' ou 'unlinked'
--   - Seul 'suspended' révoque immédiatement (échec paiement)
--   - 'canceled' = résiliation planifiée, accès maintenu jusqu'au renouvellement
--   - 'unlinked' = déliaison planifiée, accès maintenu jusqu'à unlink_effective_at
--   - FREE remplacé par EXPIRED (FREE n'existe plus)
-- =============================================================================
