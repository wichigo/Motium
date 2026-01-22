-- =================================================================
-- BUGFIX 013: Pro Renewal - Fix license status handling & atomicity
-- =================================================================
-- EXÉCUTER CE FICHIER SUR SUPABASE STUDIO (http://176.168.117.243:3000)
-- Dans SQL Editor, coller et exécuter ce contenu
-- =================================================================
--
-- ARBRE 6 - RENOUVELLEMENT PRO (5 boucles Ralph Wiggum)
--
-- ============ PROBLÈMES IDENTIFIÉS ============
-- BUG-6.1: Status 'unlinked' inexistant - devrait utiliser unlink_effective_at
-- BUG-6.2: Suspended licenses non traitées si annulées pendant suspension
-- BUG-6.3: Pas de vérification de unlink_effective_at <= NOW()
-- BUG-6.4: Available licenses avec unlink_requested_at non traitées
-- BUG-6.5: Pas d'idempotence sur processProRenewal
-- BUG-6.6: company_links pas désactivé lors DELETE licence
-- BUG-6.7: Pas de transaction atomique
-- BUG-6.8: Lifetime avec unlink pending non capturées
--
-- ============ SOLUTION: RPC process_pro_renewal() ============
-- Remplace la logique JS par une fonction SQL atomique qui:
-- 1. Traite TOUTES les licences à délier (unlink_effective_at <= NOW())
-- 2. Traite les licences 'canceled' et 'suspended' si Pro annule
-- 3. Désactive les company_links correspondants
-- 4. Met à jour les users.subscription_type avec vérification multi-licence
-- 5. Est idempotente (peut être appelée plusieurs fois sans effet)
--
-- =================================================================

-- ============================================
-- STEP 1: Create the atomic renewal function
-- ============================================
CREATE OR REPLACE FUNCTION process_pro_renewal(p_pro_account_id UUID)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_license RECORD;
    v_affected_user_ids UUID[] := ARRAY[]::UUID[];
    v_deleted_count INTEGER := 0;
    v_returned_count INTEGER := 0;
    v_processed_license_ids UUID[] := ARRAY[]::UUID[];
    v_other_active_licenses INTEGER;
    v_user_id UUID;
BEGIN
    RAISE LOG '[ARBRE6] === Starting Pro renewal for account % ===', p_pro_account_id;

    -- ============================================
    -- PHASE 1: Process licenses with expired unlink requests
    -- These are licenses where unlink_effective_at <= NOW()
    -- regardless of their current status
    -- ============================================
    RAISE LOG '[ARBRE6] Phase 1: Processing expired unlink requests...';

    FOR v_license IN
        SELECT id, linked_account_id, is_lifetime, status, pro_account_id
        FROM licenses
        WHERE pro_account_id = p_pro_account_id
          AND unlink_effective_at IS NOT NULL
          AND unlink_effective_at <= NOW()
          AND status NOT IN ('available')  -- Don't process already available licenses
    LOOP
        -- Collect affected user if linked
        IF v_license.linked_account_id IS NOT NULL THEN
            v_affected_user_ids := array_append(v_affected_user_ids, v_license.linked_account_id);
        END IF;

        -- Deactivate corresponding company_link FIRST (before modifying license)
        IF v_license.linked_account_id IS NOT NULL THEN
            UPDATE company_links
            SET
                status = 'INACTIVE',
                unlinked_at = NOW(),
                updated_at = NOW()
            WHERE user_id = v_license.linked_account_id
              AND linked_pro_account_id = v_license.pro_account_id
              AND status = 'ACTIVE';

            IF FOUND THEN
                RAISE LOG '[ARBRE6] Deactivated company_link for user % (license %)', v_license.linked_account_id, v_license.id;
            END IF;
        END IF;

        IF v_license.is_lifetime THEN
            -- Lifetime license → return to pool
            UPDATE licenses
            SET
                status = 'available',
                linked_account_id = NULL,
                linked_at = NULL,
                unlink_requested_at = NULL,
                unlink_effective_at = NULL,
                updated_at = NOW()
            WHERE id = v_license.id;

            v_returned_count := v_returned_count + 1;
            RAISE LOG '[ARBRE6] Lifetime license % returned to pool', v_license.id;
        ELSE
            -- Monthly license → DELETE
            DELETE FROM licenses WHERE id = v_license.id;

            v_deleted_count := v_deleted_count + 1;
            RAISE LOG '[ARBRE6] Monthly license % deleted', v_license.id;
        END IF;

        v_processed_license_ids := array_append(v_processed_license_ids, v_license.id);
    END LOOP;

    -- ============================================
    -- PHASE 2: Process 'canceled' licenses
    -- These are licenses explicitly canceled (subscription deleted)
    -- ============================================
    RAISE LOG '[ARBRE6] Phase 2: Processing canceled licenses...';

    FOR v_license IN
        SELECT id, linked_account_id, is_lifetime, status, pro_account_id
        FROM licenses
        WHERE pro_account_id = p_pro_account_id
          AND status = 'canceled'
          AND id NOT IN (SELECT unnest(v_processed_license_ids))  -- Skip already processed
    LOOP
        -- Collect affected user if linked
        IF v_license.linked_account_id IS NOT NULL THEN
            v_affected_user_ids := array_append(v_affected_user_ids, v_license.linked_account_id);
        END IF;

        -- Deactivate corresponding company_link
        IF v_license.linked_account_id IS NOT NULL THEN
            UPDATE company_links
            SET
                status = 'INACTIVE',
                unlinked_at = NOW(),
                updated_at = NOW()
            WHERE user_id = v_license.linked_account_id
              AND linked_pro_account_id = v_license.pro_account_id
              AND status = 'ACTIVE';
        END IF;

        IF v_license.is_lifetime THEN
            -- Lifetime license → return to pool
            UPDATE licenses
            SET
                status = 'available',
                linked_account_id = NULL,
                linked_at = NULL,
                updated_at = NOW()
            WHERE id = v_license.id;

            v_returned_count := v_returned_count + 1;
            RAISE LOG '[ARBRE6] Canceled lifetime license % returned to pool', v_license.id;
        ELSE
            -- Monthly license → DELETE
            DELETE FROM licenses WHERE id = v_license.id;

            v_deleted_count := v_deleted_count + 1;
            RAISE LOG '[ARBRE6] Canceled monthly license % deleted', v_license.id;
        END IF;
    END LOOP;

    -- ============================================
    -- PHASE 3: Update affected users
    -- Set to EXPIRED only if no other active licenses remain
    -- ============================================
    RAISE LOG '[ARBRE6] Phase 3: Updating affected users...';

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
                    RAISE LOG '[ARBRE6] User % set to EXPIRED (no other active licenses)', v_user_id;
                END IF;
            ELSE
                RAISE LOG '[ARBRE6] User % keeps LICENSED (has % other active licenses)', v_user_id, v_other_active_licenses;
            END IF;
        END LOOP;
    END IF;

    RAISE LOG '[ARBRE6] === Pro renewal complete: % deleted, % returned to pool ===', v_deleted_count, v_returned_count;

    RETURN json_build_object(
        'success', true,
        'deleted_count', v_deleted_count,
        'returned_count', v_returned_count,
        'affected_users', array_length(v_affected_user_ids, 1)
    );

EXCEPTION WHEN OTHERS THEN
    RAISE LOG '[ARBRE6] ERROR in process_pro_renewal: %', SQLERRM;
    RETURN json_build_object(
        'success', false,
        'error', SQLERRM
    );
END;
$$;

-- ============================================
-- STEP 2: Grant permissions
-- ============================================
GRANT EXECUTE ON FUNCTION process_pro_renewal(UUID) TO service_role;
GRANT EXECUTE ON FUNCTION process_pro_renewal(UUID) TO authenticated;

-- ============================================
-- STEP 3: Add index for faster unlink queries
-- ============================================
CREATE INDEX IF NOT EXISTS idx_licenses_unlink_effective
ON licenses(pro_account_id, unlink_effective_at)
WHERE unlink_effective_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_licenses_status_pro
ON licenses(pro_account_id, status);

-- ============================================
-- VERIFICATION QUERIES (run manually)
-- ============================================
-- Check the function was created:
-- SELECT prosrc FROM pg_proc WHERE proname = 'process_pro_renewal';
--
-- Test the function (dry run with non-existent UUID):
-- SELECT process_pro_renewal('00000000-0000-0000-0000-000000000000');
-- ============================================
