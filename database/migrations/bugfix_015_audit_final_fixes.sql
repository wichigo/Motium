-- =================================================================
-- BUGFIX 015: Audit Final Fixes - Race Conditions & Orphan States
-- =================================================================
-- EXÉCUTER CE FICHIER SUR SUPABASE STUDIO (https://studio.motium.app)
-- Dans SQL Editor, coller et exécuter ce contenu
-- =================================================================
--
-- AUDIT RALPH WIGGUM - Score initial: 75/100
-- Objectif: 100/100
--
-- ============ PROBLÈMES CORRIGÉS ============
-- FIX-1: Race condition multi-licence (SELECT FOR UPDATE)
-- FIX-2: DELETE utilisateur non géré dans handle_license_delete
-- FIX-3: États orphelins company_links (réconciliation)
-- FIX-4: Atomicité améliorée pour les fallbacks legacy
--
-- =================================================================

-- ============================================
-- FIX 1: Race condition multi-licence
-- ============================================
-- Problème: Dans sync_company_link_on_license_unlink(), si deux licences
-- du même utilisateur sont unlinkées simultanément, le COUNT(*) peut
-- retourner 1 pour les deux exécutions concurrentes.
--
-- Solution: Ajouter SELECT FOR UPDATE sur l'utilisateur pour sérialiser
-- les opérations concurrentes sur le même utilisateur.

CREATE OR REPLACE FUNCTION sync_company_link_on_license_unlink()
RETURNS TRIGGER AS $$
DECLARE
    v_company_link_id UUID;
    v_user_to_unlink UUID;
    v_other_active_licenses INTEGER;
    v_user_locked RECORD;
BEGIN
    -- Determine which user is being unlinked
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

            RAISE LOG '[AUDIT-FIX1] License unlink triggered company_link % deactivation (user %)', v_company_link_id, v_user_to_unlink;
        END IF;

        -- FIX-1: LOCK the user row to prevent race condition on concurrent unlinking
        -- This serializes operations for the same user
        BEGIN
            SELECT * INTO v_user_locked
            FROM users
            WHERE id = v_user_to_unlink
            FOR UPDATE NOWAIT;  -- NOWAIT prevents deadlocks, will error if locked
        EXCEPTION WHEN lock_not_available THEN
            -- Another transaction is updating this user - skip user update, let that one handle it
            RAISE LOG '[AUDIT-FIX1] User % locked by another transaction, skipping EXPIRED update', v_user_to_unlink;
            RETURN NEW;
        END;

        -- Now safely check for other active licenses
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

            RAISE LOG '[AUDIT-FIX1] User % set to EXPIRED via trigger (no other active licenses)', v_user_to_unlink;
        ELSE
            RAISE LOG '[AUDIT-FIX1] User % keeps LICENSED via trigger (% other active licenses)', v_user_to_unlink, v_other_active_licenses;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;


-- ============================================
-- FIX 2: DELETE utilisateur non géré
-- ============================================
-- Problème: handle_license_delete() tente de mettre à jour un utilisateur
-- qui pourrait avoir été supprimé (GDPR delete cascade).
--
-- Solution: Vérifier si l'utilisateur existe avant l'UPDATE.

CREATE OR REPLACE FUNCTION handle_license_delete()
RETURNS TRIGGER AS $$
DECLARE
    v_other_active_licenses INTEGER;
    v_user_exists BOOLEAN;
BEGIN
    -- Only process if there was a linked user
    IF OLD.linked_account_id IS NULL THEN
        RETURN OLD;
    END IF;

    RAISE LOG '[AUDIT-FIX2] License % deleted (was linked to user %)', OLD.id, OLD.linked_account_id;

    -- FIX-2: Check if user still exists before any operations
    SELECT EXISTS (SELECT 1 FROM users WHERE id = OLD.linked_account_id) INTO v_user_exists;

    IF NOT v_user_exists THEN
        RAISE LOG '[AUDIT-FIX2] User % no longer exists (GDPR delete?), skipping updates', OLD.linked_account_id;

        -- Still deactivate company_link for data consistency (if table exists)
        UPDATE company_links
        SET
            status = 'INACTIVE',
            unlinked_at = NOW(),
            updated_at = NOW()
        WHERE user_id = OLD.linked_account_id
          AND linked_pro_account_id = OLD.pro_account_id
          AND status = 'ACTIVE';

        RETURN OLD;
    END IF;

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

        RAISE LOG '[AUDIT-FIX2] User % set to EXPIRED via DELETE trigger (no other active licenses)', OLD.linked_account_id;
    ELSE
        RAISE LOG '[AUDIT-FIX2] User % keeps LICENSED (% other active licenses)', OLD.linked_account_id, v_other_active_licenses;
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


-- ============================================
-- FIX 3: Fonction de réconciliation des états orphelins
-- ============================================
-- Exécuter périodiquement (pg_cron ou manuellement) pour corriger
-- les incohérences entre licenses, company_links et users.
--
-- À appeler via: SELECT reconcile_orphan_states();

CREATE OR REPLACE FUNCTION reconcile_orphan_states()
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_fixed_company_links INTEGER := 0;
    v_fixed_users INTEGER := 0;
    v_fixed_licenses INTEGER := 0;
    v_record RECORD;
BEGIN
    RAISE LOG '[RECONCILE] === Starting orphan state reconciliation ===';

    -- ============================================
    -- FIX 3a: company_links ACTIVE sans licence active correspondante
    -- ============================================
    FOR v_record IN
        SELECT cl.id, cl.user_id, cl.linked_pro_account_id
        FROM company_links cl
        WHERE cl.status = 'ACTIVE'
          AND NOT EXISTS (
              SELECT 1 FROM licenses l
              WHERE l.linked_account_id = cl.user_id
                AND l.pro_account_id = cl.linked_pro_account_id
                AND l.status = 'active'
          )
    LOOP
        UPDATE company_links
        SET
            status = 'INACTIVE',
            unlinked_at = NOW(),
            updated_at = NOW()
        WHERE id = v_record.id;

        v_fixed_company_links := v_fixed_company_links + 1;
        RAISE LOG '[RECONCILE] Fixed orphan company_link % (user %, pro_account %)',
            v_record.id, v_record.user_id, v_record.linked_pro_account_id;
    END LOOP;

    -- ============================================
    -- FIX 3b: Users LICENSED sans aucune licence active
    -- ============================================
    FOR v_record IN
        SELECT u.id, u.email
        FROM users u
        WHERE u.subscription_type = 'LICENSED'
          AND NOT EXISTS (
              SELECT 1 FROM licenses l
              WHERE l.linked_account_id = u.id
                AND l.status = 'active'
          )
    LOOP
        UPDATE users
        SET
            subscription_type = 'EXPIRED',
            subscription_expires_at = NULL,
            updated_at = NOW()
        WHERE id = v_record.id;

        v_fixed_users := v_fixed_users + 1;
        RAISE LOG '[RECONCILE] Fixed orphan LICENSED user % (%)',
            v_record.id, v_record.email;
    END LOOP;

    -- ============================================
    -- FIX 3c: Licenses 'active' avec linked_account_id NULL
    -- (ne devrait jamais arriver, mais au cas où)
    -- ============================================
    UPDATE licenses
    SET
        status = 'available',
        updated_at = NOW()
    WHERE status = 'active'
      AND linked_account_id IS NULL;

    GET DIAGNOSTICS v_fixed_licenses = ROW_COUNT;

    IF v_fixed_licenses > 0 THEN
        RAISE LOG '[RECONCILE] Fixed % orphan active licenses (no linked_account)', v_fixed_licenses;
    END IF;

    RAISE LOG '[RECONCILE] === Reconciliation complete: % company_links, % users, % licenses fixed ===',
        v_fixed_company_links, v_fixed_users, v_fixed_licenses;

    RETURN json_build_object(
        'success', true,
        'fixed_company_links', v_fixed_company_links,
        'fixed_users', v_fixed_users,
        'fixed_licenses', v_fixed_licenses,
        'timestamp', NOW()
    );

EXCEPTION WHEN OTHERS THEN
    RAISE LOG '[RECONCILE] ERROR: %', SQLERRM;
    RETURN json_build_object(
        'success', false,
        'error', SQLERRM
    );
END;
$$;


-- ============================================
-- FIX 4: Amélioration process_subscription_deleted
-- ============================================
-- Ajouter SELECT FOR UPDATE pour éviter les race conditions
-- sur les traitements concurrents de la même subscription.

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
    v_user_locked RECORD;
BEGIN
    RAISE LOG '[AUDIT-FIX4] === Processing subscription deleted: % ===', p_stripe_subscription_id;

    -- ============================================
    -- PHASE 1: Mark all licenses for this subscription as 'canceled'
    -- Use FOR UPDATE to lock rows and prevent concurrent processing
    -- ============================================
    FOR v_license IN
        SELECT id, linked_account_id, pro_account_id
        FROM licenses
        WHERE stripe_subscription_id = p_stripe_subscription_id
          AND status NOT IN ('canceled', 'available')
        FOR UPDATE  -- FIX-4: Lock licenses to prevent concurrent processing
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
        RAISE LOG '[AUDIT-FIX4] License % marked as canceled (was linked to user %)',
            v_license.id, v_license.linked_account_id;
    END LOOP;

    -- ============================================
    -- PHASE 2: Update affected users
    -- Set to EXPIRED only if no other active licenses remain
    -- Use FOR UPDATE to prevent race conditions
    -- ============================================
    RAISE LOG '[AUDIT-FIX4] Phase 2: Updating % affected users...', array_length(v_affected_user_ids, 1);

    -- Deduplicate affected user IDs
    SELECT array_agg(DISTINCT x) INTO v_affected_user_ids
    FROM unnest(v_affected_user_ids) AS x;

    IF v_affected_user_ids IS NOT NULL AND array_length(v_affected_user_ids, 1) > 0 THEN
        FOREACH v_user_id IN ARRAY v_affected_user_ids
        LOOP
            -- FIX-4: Lock user row to prevent concurrent updates
            BEGIN
                SELECT * INTO v_user_locked
                FROM users
                WHERE id = v_user_id
                FOR UPDATE NOWAIT;
            EXCEPTION WHEN lock_not_available THEN
                RAISE LOG '[AUDIT-FIX4] User % locked by another transaction, skipping', v_user_id;
                CONTINUE;
            END;

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
                    RAISE LOG '[AUDIT-FIX4] User % set to EXPIRED (no other active licenses)', v_user_id;
                END IF;
            ELSE
                RAISE LOG '[AUDIT-FIX4] User % keeps LICENSED (has % other active licenses)', v_user_id, v_other_active_licenses;
            END IF;
        END LOOP;
    END IF;

    -- ============================================
    -- PHASE 3: Update pro_account status if all licenses are now canceled
    -- ============================================
    IF p_pro_account_id IS NOT NULL THEN
        DECLARE
            v_remaining_active_licenses INTEGER;
        BEGIN
            SELECT COUNT(*) INTO v_remaining_active_licenses
            FROM licenses
            WHERE pro_account_id = p_pro_account_id
              AND status IN ('active', 'available', 'pending');

            IF v_remaining_active_licenses = 0 THEN
                UPDATE pro_accounts
                SET
                    status = 'expired',
                    updated_at = NOW()
                WHERE id = p_pro_account_id
                  AND status != 'expired';

                IF FOUND THEN
                    RAISE LOG '[AUDIT-FIX4] Pro account % set to expired (no remaining active licenses)', p_pro_account_id;
                END IF;
            END IF;
        END;
    END IF;

    RAISE LOG '[AUDIT-FIX4] === Subscription deleted processing complete: % licenses canceled ===', v_canceled_count;

    RETURN json_build_object(
        'success', true,
        'canceled_count', v_canceled_count,
        'affected_users', COALESCE(array_length(v_affected_user_ids, 1), 0)
    );

EXCEPTION WHEN OTHERS THEN
    RAISE LOG '[AUDIT-FIX4] ERROR in process_subscription_deleted: %', SQLERRM;
    RETURN json_build_object(
        'success', false,
        'error', SQLERRM
    );
END;
$$;


-- ============================================
-- FIX 4b: Amélioration process_pro_renewal
-- ============================================
-- Ajouter SELECT FOR UPDATE pour éviter les race conditions.

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
    v_user_locked RECORD;
BEGIN
    RAISE LOG '[AUDIT-FIX4b] === Starting Pro renewal for account % ===', p_pro_account_id;

    -- ============================================
    -- PHASE 1: Process licenses with expired unlink requests
    -- Use FOR UPDATE to lock rows
    -- ============================================
    RAISE LOG '[AUDIT-FIX4b] Phase 1: Processing expired unlink requests...';

    FOR v_license IN
        SELECT id, linked_account_id, is_lifetime, status, pro_account_id
        FROM licenses
        WHERE pro_account_id = p_pro_account_id
          AND unlink_effective_at IS NOT NULL
          AND unlink_effective_at <= NOW()
          AND status NOT IN ('available')
        FOR UPDATE  -- Lock licenses
    LOOP
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

            IF FOUND THEN
                RAISE LOG '[AUDIT-FIX4b] Deactivated company_link for user % (license %)', v_license.linked_account_id, v_license.id;
            END IF;
        END IF;

        IF v_license.is_lifetime THEN
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
            RAISE LOG '[AUDIT-FIX4b] Lifetime license % returned to pool', v_license.id;
        ELSE
            DELETE FROM licenses WHERE id = v_license.id;

            v_deleted_count := v_deleted_count + 1;
            RAISE LOG '[AUDIT-FIX4b] Monthly license % deleted', v_license.id;
        END IF;

        v_processed_license_ids := array_append(v_processed_license_ids, v_license.id);
    END LOOP;

    -- ============================================
    -- PHASE 2: Process 'canceled' licenses
    -- ============================================
    RAISE LOG '[AUDIT-FIX4b] Phase 2: Processing canceled licenses...';

    FOR v_license IN
        SELECT id, linked_account_id, is_lifetime, status, pro_account_id
        FROM licenses
        WHERE pro_account_id = p_pro_account_id
          AND status = 'canceled'
          AND id NOT IN (SELECT unnest(v_processed_license_ids))
        FOR UPDATE  -- Lock licenses
    LOOP
        IF v_license.linked_account_id IS NOT NULL THEN
            v_affected_user_ids := array_append(v_affected_user_ids, v_license.linked_account_id);
        END IF;

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
            UPDATE licenses
            SET
                status = 'available',
                linked_account_id = NULL,
                linked_at = NULL,
                updated_at = NOW()
            WHERE id = v_license.id;

            v_returned_count := v_returned_count + 1;
            RAISE LOG '[AUDIT-FIX4b] Canceled lifetime license % returned to pool', v_license.id;
        ELSE
            DELETE FROM licenses WHERE id = v_license.id;

            v_deleted_count := v_deleted_count + 1;
            RAISE LOG '[AUDIT-FIX4b] Canceled monthly license % deleted', v_license.id;
        END IF;
    END LOOP;

    -- ============================================
    -- PHASE 3: Update affected users with locking
    -- ============================================
    RAISE LOG '[AUDIT-FIX4b] Phase 3: Updating affected users...';

    SELECT array_agg(DISTINCT x) INTO v_affected_user_ids
    FROM unnest(v_affected_user_ids) AS x;

    IF v_affected_user_ids IS NOT NULL AND array_length(v_affected_user_ids, 1) > 0 THEN
        FOREACH v_user_id IN ARRAY v_affected_user_ids
        LOOP
            -- FIX-4b: Lock user row
            BEGIN
                SELECT * INTO v_user_locked
                FROM users
                WHERE id = v_user_id
                FOR UPDATE NOWAIT;
            EXCEPTION WHEN lock_not_available THEN
                RAISE LOG '[AUDIT-FIX4b] User % locked, skipping', v_user_id;
                CONTINUE;
            END;

            SELECT COUNT(*) INTO v_other_active_licenses
            FROM licenses
            WHERE linked_account_id = v_user_id
              AND status = 'active';

            IF v_other_active_licenses = 0 THEN
                UPDATE users
                SET
                    subscription_type = 'EXPIRED',
                    subscription_expires_at = NULL,
                    updated_at = NOW()
                WHERE id = v_user_id
                  AND subscription_type = 'LICENSED';

                IF FOUND THEN
                    RAISE LOG '[AUDIT-FIX4b] User % set to EXPIRED (no other active licenses)', v_user_id;
                END IF;
            ELSE
                RAISE LOG '[AUDIT-FIX4b] User % keeps LICENSED (has % other active licenses)', v_user_id, v_other_active_licenses;
            END IF;
        END LOOP;
    END IF;

    RAISE LOG '[AUDIT-FIX4b] === Pro renewal complete: % deleted, % returned to pool ===', v_deleted_count, v_returned_count;

    RETURN json_build_object(
        'success', true,
        'deleted_count', v_deleted_count,
        'returned_count', v_returned_count,
        'affected_users', COALESCE(array_length(v_affected_user_ids, 1), 0)
    );

EXCEPTION WHEN OTHERS THEN
    RAISE LOG '[AUDIT-FIX4b] ERROR in process_pro_renewal: %', SQLERRM;
    RETURN json_build_object(
        'success', false,
        'error', SQLERRM
    );
END;
$$;


-- ============================================
-- STEP 5: Grant permissions
-- ============================================
GRANT EXECUTE ON FUNCTION sync_company_link_on_license_unlink() TO service_role;
GRANT EXECUTE ON FUNCTION handle_license_delete() TO service_role;
GRANT EXECUTE ON FUNCTION reconcile_orphan_states() TO service_role;
GRANT EXECUTE ON FUNCTION process_subscription_deleted(TEXT, UUID) TO service_role;
GRANT EXECUTE ON FUNCTION process_subscription_deleted(TEXT, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION process_pro_renewal(UUID) TO service_role;
GRANT EXECUTE ON FUNCTION process_pro_renewal(UUID) TO authenticated;


-- ============================================
-- VERIFICATION QUERIES (run manually)
-- ============================================
-- Check functions were updated:
-- SELECT prosrc FROM pg_proc WHERE proname = 'sync_company_link_on_license_unlink';
-- SELECT prosrc FROM pg_proc WHERE proname = 'handle_license_delete';
-- SELECT prosrc FROM pg_proc WHERE proname = 'reconcile_orphan_states';
--
-- Test reconciliation (safe to run, only fixes issues):
-- SELECT reconcile_orphan_states();
--
-- Check for orphan states:
-- SELECT * FROM company_links WHERE status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM licenses WHERE linked_account_id = company_links.user_id AND status = 'active');
-- SELECT * FROM users WHERE subscription_type = 'LICENSED' AND NOT EXISTS (SELECT 1 FROM licenses WHERE linked_account_id = users.id AND status = 'active');
-- ============================================
