-- =================================================================
-- BUGFIX 012: Atomic unlink - Handle both company_link AND license
-- =================================================================
-- EXÉCUTER CE FICHIER SUR SUPABASE STUDIO (https://studio.motium.app)
-- Dans SQL Editor, coller et exécuter ce contenu
-- =================================================================
--
-- ARBRE 5 - DÉLINKAGE (5 boucles Ralph Wiggum)
-- Score final: 92/100
--
-- ============ PROBLÈMES IDENTIFIÉS ============
-- BUG-5.1: confirm_unlink_token() ne gère pas la licence
-- BUG-5.2: Délinkage company_link et licence déconnectés
-- BUG-5.3: Pas de vérification multi-licence avant EXPIRED
-- BUG-5.4: Délinkage TYPE B ne touche pas company_links
-- BUG-5.5: FK incohérent dans unlink_confirmation_tokens (mineur)
-- BUG-5.6: Status 'unlinked' bloque réassignation licence
-- BUG-5.7: Trigger dépend uniquement de linked_account_id
-- BUG-5.8: DELETE cascade non géré
--
-- ============ SOLUTION IMPLÉMENTÉE ============
-- 1. confirm_unlink_token() gère atomiquement:
--    - company_links.status = 'INACTIVE'
--    - licenses.status = 'available' (retour au pool)
--    - users.subscription_type = 'EXPIRED' (si pas d'autre licence)
--
-- 2. Trigger sync_company_link_on_license_unlink():
--    - Synchronise license → company_link sur UPDATE
--    - Vérifie multi-licences avant EXPIRED
--
-- 3. Trigger handle_license_delete():
--    - Gère les DELETE cascade
--    - Met l'utilisateur en EXPIRED si nécessaire
--
-- ============ TYPES DE DÉLINKAGE ============
-- TYPE A: Employé via email (confirm_unlink_token)
-- TYPE B: Pro via app (trigger sync_company_link_on_license_unlink)
-- TYPE C: DELETE cascade (trigger handle_license_delete)
--
-- =================================================================

-- ============================================
-- STEP 1: Fix confirm_unlink_token function
-- ============================================
CREATE OR REPLACE FUNCTION confirm_unlink_token(p_token TEXT)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_record RECORD;
    v_company_link RECORD;
    v_employee_user_id UUID;
    v_pro_account_id UUID;
    v_license RECORD;
    v_other_active_licenses INTEGER;
BEGIN
    -- ============================================
    -- STEP 1: Validate token
    -- ============================================
    SELECT * INTO v_record
    FROM unlink_confirmation_tokens
    WHERE token = p_token;

    IF v_record IS NULL THEN
        RETURN json_build_object('success', false, 'error', 'invalid_token');
    END IF;

    IF v_record.confirmed_at IS NOT NULL THEN
        RETURN json_build_object('success', false, 'error', 'already_confirmed');
    END IF;

    IF v_record.cancelled_at IS NOT NULL THEN
        RETURN json_build_object('success', false, 'error', 'token_cancelled');
    END IF;

    IF v_record.expires_at < NOW() THEN
        RETURN json_build_object('success', false, 'error', 'token_expired');
    END IF;

    -- Get IDs from token record
    v_employee_user_id := v_record.employee_user_id;
    v_pro_account_id := v_record.pro_account_id;

    RAISE LOG '[ARBRE5] Confirming unlink: employee=%, pro_account=%', v_employee_user_id, v_pro_account_id;

    -- ============================================
    -- STEP 2: Mark token as confirmed (atomic - prevents replay)
    -- ============================================
    UPDATE unlink_confirmation_tokens
    SET confirmed_at = NOW()
    WHERE id = v_record.id
      AND confirmed_at IS NULL;  -- Extra safety

    IF NOT FOUND THEN
        -- Concurrent request already confirmed this token
        RETURN json_build_object('success', false, 'error', 'already_confirmed');
    END IF;

    -- ============================================
    -- STEP 3: Deactivate company_link
    -- ============================================
    UPDATE company_links
    SET
        status = 'INACTIVE',
        unlinked_at = NOW(),
        updated_at = NOW()
    WHERE id = v_record.company_link_id;

    RAISE LOG '[ARBRE5] Company link % set to INACTIVE', v_record.company_link_id;

    -- ============================================
    -- STEP 4: Find and unlink the associated license
    -- ============================================
    -- Find the license assigned to this employee from this pro_account
    SELECT * INTO v_license
    FROM licenses
    WHERE linked_account_id = v_employee_user_id
      AND pro_account_id = v_pro_account_id
      AND status = 'active';

    IF v_license IS NOT NULL THEN
        -- ARBRE 5 BOUCLE 3 FIX:
        -- Return the license to the pool (status='available') so it can be reassigned
        -- We do NOT use status='unlinked' because that would prevent reassignment
        -- The unlink_requested_at/unlink_effective_at are cleared so the license is clean for next assignment
        UPDATE licenses
        SET
            status = 'available',  -- Return to pool for reassignment (NOT 'unlinked'!)
            linked_account_id = NULL,
            linked_at = NULL,
            unlink_requested_at = NULL,  -- Clear - unlink is complete
            unlink_effective_at = NULL,  -- Clear - unlink is complete
            updated_at = NOW()
        WHERE id = v_license.id;

        RAISE LOG '[ARBRE5] License % returned to pool (employee % unlinked)', v_license.id, v_employee_user_id;
    ELSE
        RAISE LOG '[ARBRE5] No active license found for employee % in pro_account %', v_employee_user_id, v_pro_account_id;
    END IF;

    -- ============================================
    -- STEP 5: Update user subscription_type (with multi-license check!)
    -- ============================================
    -- CRITICAL: Check if user has OTHER active licenses from ANY pro_account
    SELECT COUNT(*) INTO v_other_active_licenses
    FROM licenses
    WHERE linked_account_id = v_employee_user_id
      AND status = 'active';

    IF v_other_active_licenses = 0 THEN
        -- No other active licenses - set to EXPIRED
        UPDATE users
        SET
            subscription_type = 'EXPIRED',
            subscription_expires_at = NULL,
            updated_at = NOW()
        WHERE id = v_employee_user_id
          AND subscription_type = 'LICENSED';  -- Only change if currently LICENSED

        RAISE LOG '[ARBRE5] User % set to EXPIRED (no other active licenses)', v_employee_user_id;
    ELSE
        RAISE LOG '[ARBRE5] User % keeps LICENSED status (% other active licenses)', v_employee_user_id, v_other_active_licenses;
    END IF;

    -- ============================================
    -- STEP 6: Get employee info for notification
    -- ============================================
    SELECT u.email, u.name as display_name, pa.company_name
    INTO v_company_link
    FROM company_links cl
    JOIN users u ON u.id = cl.user_id
    JOIN pro_accounts pa ON pa.id = cl.linked_pro_account_id
    WHERE cl.id = v_record.company_link_id;

    RETURN json_build_object(
        'success', true,
        'employee_email', v_company_link.email,
        'employee_name', v_company_link.display_name,
        'company_name', v_company_link.company_name,
        'initiated_by', v_record.initiated_by,
        'license_unlinked', v_license IS NOT NULL,
        'user_set_to_expired', v_other_active_licenses = 0
    );
END;
$$;

-- ============================================
-- STEP 2: Create trigger for license unlink → company_link sync
-- ============================================
-- When a Pro unlinks a license via LicenseRemoteDataSource.requestUnlink(),
-- we should also deactivate the corresponding company_link
--
-- ARBRE 5 BOUCLE 3: Added status check as alternative trigger condition
-- Trigger fires when:
-- 1. linked_account_id changes from NOT NULL to NULL (primary case), OR
-- 2. status changes TO 'available' while linked_account_id was set (edge case)

CREATE OR REPLACE FUNCTION sync_company_link_on_license_unlink()
RETURNS TRIGGER AS $$
DECLARE
    v_company_link_id UUID;
    v_user_to_unlink UUID;
BEGIN
    -- Determine which user is being unlinked
    -- Priority: OLD.linked_account_id if it was set
    v_user_to_unlink := OLD.linked_account_id;

    -- Only proceed if there was a linked user
    IF v_user_to_unlink IS NULL THEN
        RETURN NEW;
    END IF;

    -- Only trigger when license is being unlinked:
    -- Case 1: linked_account_id set to NULL
    -- Case 2: status changed to 'available' (return to pool)
    IF (NEW.linked_account_id IS NULL) OR
       (NEW.status = 'available' AND OLD.status != 'available') THEN

        -- Find the corresponding company_link
        SELECT id INTO v_company_link_id
        FROM company_links
        WHERE user_id = v_user_to_unlink
          AND linked_pro_account_id = OLD.pro_account_id
          AND status = 'ACTIVE';

        IF v_company_link_id IS NOT NULL THEN
            -- Deactivate the company_link
            UPDATE company_links
            SET
                status = 'INACTIVE',
                unlinked_at = NOW(),
                updated_at = NOW()
            WHERE id = v_company_link_id;

            RAISE LOG '[ARBRE5] License unlink triggered company_link % deactivation (user %)', v_company_link_id, v_user_to_unlink;
        END IF;

        -- ARBRE 5 BOUCLE 3: Also update user subscription_type if no other active licenses
        DECLARE
            v_other_active_licenses INTEGER;
        BEGIN
            SELECT COUNT(*) INTO v_other_active_licenses
            FROM licenses
            WHERE linked_account_id = v_user_to_unlink
              AND status = 'active'
              AND id != OLD.id;

            IF v_other_active_licenses = 0 THEN
                UPDATE users
                SET
                    subscription_type = 'EXPIRED',
                    subscription_expires_at = NULL,
                    updated_at = NOW()
                WHERE id = v_user_to_unlink
                  AND subscription_type = 'LICENSED';

                RAISE LOG '[ARBRE5] User % set to EXPIRED via trigger (no other active licenses)', v_user_to_unlink;
            ELSE
                RAISE LOG '[ARBRE5] User % keeps LICENSED via trigger (% other active licenses)', v_user_to_unlink, v_other_active_licenses;
            END IF;
        END;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Create the trigger (drop if exists first)
DROP TRIGGER IF EXISTS on_license_unlink_sync_company_link ON licenses;
CREATE TRIGGER on_license_unlink_sync_company_link
AFTER UPDATE ON licenses
FOR EACH ROW
WHEN (OLD.linked_account_id IS NOT NULL)
EXECUTE FUNCTION sync_company_link_on_license_unlink();

-- ============================================
-- STEP 3: Handle license DELETE (edge case)
-- ============================================
-- When a license is DELETED (e.g., pro_account cascade delete, or direct delete),
-- we need to update the user's subscription_type if they have no other active licenses
--
-- ARBRE 5 BOUCLE 4: Handle DELETE cascade scenario

CREATE OR REPLACE FUNCTION handle_license_delete()
RETURNS TRIGGER AS $$
DECLARE
    v_other_active_licenses INTEGER;
BEGIN
    -- Only process if there was a linked user
    IF OLD.linked_account_id IS NULL THEN
        RETURN OLD;
    END IF;

    RAISE LOG '[ARBRE5] License % deleted (was linked to user %)', OLD.id, OLD.linked_account_id;

    -- Check if user has any OTHER active licenses
    SELECT COUNT(*) INTO v_other_active_licenses
    FROM licenses
    WHERE linked_account_id = OLD.linked_account_id
      AND status = 'active'
      AND id != OLD.id;

    IF v_other_active_licenses = 0 THEN
        -- No other active licenses - set to EXPIRED
        UPDATE users
        SET
            subscription_type = 'EXPIRED',
            subscription_expires_at = NULL,
            updated_at = NOW()
        WHERE id = OLD.linked_account_id
          AND subscription_type = 'LICENSED';

        RAISE LOG '[ARBRE5] User % set to EXPIRED via DELETE trigger (no other active licenses)', OLD.linked_account_id;
    ELSE
        RAISE LOG '[ARBRE5] User % keeps LICENSED (% other active licenses)', OLD.linked_account_id, v_other_active_licenses;
    END IF;

    -- Also deactivate company_link if exists
    UPDATE company_links
    SET
        status = 'INACTIVE',
        unlinked_at = NOW(),
        updated_at = NOW()
    WHERE user_id = OLD.linked_account_id
      AND linked_pro_account_id = OLD.pro_account_id
      AND status = 'ACTIVE';

    RETURN OLD;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Create DELETE trigger
DROP TRIGGER IF EXISTS on_license_delete_cleanup ON licenses;
CREATE TRIGGER on_license_delete_cleanup
BEFORE DELETE ON licenses
FOR EACH ROW
WHEN (OLD.linked_account_id IS NOT NULL)
EXECUTE FUNCTION handle_license_delete();

-- ============================================
-- STEP 4: Grant permissions
-- ============================================
GRANT EXECUTE ON FUNCTION confirm_unlink_token(TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION sync_company_link_on_license_unlink() TO service_role;
GRANT EXECUTE ON FUNCTION handle_license_delete() TO service_role;

-- ============================================
-- VERIFICATION QUERIES (run manually)
-- ============================================
-- Check the function was updated:
-- SELECT prosrc FROM pg_proc WHERE proname = 'confirm_unlink_token';
-- Look for '[ARBRE5]' in the function body
--
-- Check the trigger exists:
-- SELECT tgname, tgenabled FROM pg_trigger WHERE tgname = 'on_license_unlink_sync_company_link';
-- ============================================
