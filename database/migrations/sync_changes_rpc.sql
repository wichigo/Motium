-- ========================================
-- RPC: sync_changes(operations JSONB, since TIMESTAMPTZ)
-- ========================================
-- Date: 2026-01-20
-- Version: 1.0.0
-- Description: Synchronisation bidirectionnelle atomique (push + pull)
-- Remplace les appels API individuels par un seul appel RPC batch
--
-- FLUX:
-- 1. Traite TOUS les push d'abord (opérations pending de l'app)
-- 2. PUIS récupère les changes depuis 'since' (delta pull)
-- 3. Retourne les deux résultats en une réponse atomique
--
-- AVANTAGES:
-- - 1 seul appel réseau au lieu de N+1
-- - Push toujours AVANT pull (pas de race condition)
-- - Atomique = cohérent
-- - Supporte le mode offline (queue accumulée)
-- - Idempotent (table processed_sync_operations)
--
-- FORMAT D'ENTRÉE operations (JSONB array):
-- [
--   {
--     "idempotency_key": "TRIP:uuid:UPDATE:1705123456789",
--     "entity_type": "TRIP",
--     "entity_id": "uuid",
--     "action": "CREATE|UPDATE|DELETE",
--     "payload": { ... entity data ... },
--     "client_version": 2  // Seulement pour TRIP, VEHICLE, USER
--   },
--   ...
-- ]
--
-- FORMAT DE SORTIE:
-- TABLE(
--   push_results JSONB,      -- Array des résultats push
--   pull_results JSONB,      -- Array des changements à pull
--   sync_timestamp TIMESTAMPTZ  -- Timestamp pour le prochain sync
-- )
--
-- ENTITÉS SUPPORTÉES:
-- - TRIP, VEHICLE, USER (avec gestion de version/conflit)
-- - EXPENSE, WORK_SCHEDULE, AUTO_TRACKING_SETTINGS (sans version)
-- - PRO_ACCOUNT, COMPANY_LINK, LICENSE, CONSENT (sans version)
-- - STRIPE_SUBSCRIPTION: INTERDIT (géré par webhooks)
-- ========================================

-- ========================================
-- TABLE D'IDEMPOTENCE
-- ========================================
CREATE TABLE IF NOT EXISTS processed_sync_operations (
    idempotency_key TEXT PRIMARY KEY,
    entity_type TEXT NOT NULL,
    entity_id UUID NOT NULL,
    action TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    success BOOLEAN NOT NULL,
    error_message TEXT
);

-- Index pour le nettoyage automatique
CREATE INDEX IF NOT EXISTS idx_processed_sync_ops_processed_at
ON processed_sync_operations(processed_at);

-- Index pour lookups par entité
CREATE INDEX IF NOT EXISTS idx_processed_sync_ops_entity
ON processed_sync_operations(entity_type, entity_id);

-- ========================================
-- CONSTANTES
-- ========================================
-- MAX_BATCH_SIZE: 500 opérations max par appel
-- IDEMPOTENCY_TTL: 7 jours

-- ========================================
-- FONCTION PRINCIPALE: sync_changes()
-- ========================================
CREATE OR REPLACE FUNCTION sync_changes(
    operations JSONB DEFAULT '[]'::JSONB,
    since TIMESTAMPTZ DEFAULT '1970-01-01T00:00:00Z'
)
RETURNS TABLE (
    push_results JSONB,
    pull_results JSONB,
    sync_timestamp TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    -- Constantes
    MAX_BATCH_SIZE CONSTANT INTEGER := 500;

    -- Variables utilisateur
    current_user_id UUID;
    current_pro_account_id UUID;

    -- Variables de traitement
    v_sync_timestamp TIMESTAMPTZ;
    v_push_results JSONB := '[]'::JSONB;
    v_pull_results JSONB := '[]'::JSONB;

    -- Variables de boucle
    op JSONB;
    op_result JSONB;
    op_idempotency_key TEXT;
    op_entity_type TEXT;
    op_entity_id UUID;
    op_action TEXT;
    op_payload JSONB;
    op_client_version INTEGER;
    existing_op RECORD;
    handler_result RECORD;
    op_success BOOLEAN;
    op_error TEXT;
    op_conflict BOOLEAN;
    op_server_version INTEGER;
BEGIN
    -- Capturer le timestamp AVANT toute opération pour cohérence
    v_sync_timestamp := now();

    -- ==================== AUTHENTIFICATION ====================
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'User not authenticated';
    END IF;

    -- Récupérer l'ID utilisateur depuis la table users
    SELECT id INTO current_user_id
    FROM public.users
    WHERE auth_id = auth.uid();

    IF current_user_id IS NULL THEN
        RAISE EXCEPTION 'User not found in users table';
    END IF;

    -- Récupérer le pro_account_id si l'utilisateur a un compte Pro
    SELECT id INTO current_pro_account_id
    FROM public.pro_accounts
    WHERE user_id = current_user_id;

    -- ==================== VALIDATION INPUT ====================
    IF operations IS NULL THEN
        operations := '[]'::JSONB;
    END IF;

    -- Vérifier la taille du batch (protection DoS)
    IF jsonb_array_length(operations) > MAX_BATCH_SIZE THEN
        RAISE EXCEPTION 'Batch too large: % operations (max: %)',
            jsonb_array_length(operations), MAX_BATCH_SIZE;
    END IF;

    -- ==================== PHASE 1: PUSH (MONTANT) ====================
    IF jsonb_array_length(operations) > 0 THEN
        FOR op IN SELECT * FROM jsonb_array_elements(operations)
        LOOP
            -- Extraire les champs de l'opération
            op_idempotency_key := op->>'idempotency_key';
            op_entity_type := op->>'entity_type';
            op_action := op->>'action';
            op_payload := op->'payload';
            op_client_version := COALESCE((op->>'client_version')::INTEGER, 1);

            -- Parser entity_id avec gestion d'erreur
            BEGIN
                op_entity_id := (op->>'entity_id')::UUID;
            EXCEPTION WHEN invalid_text_representation THEN
                op_result := jsonb_build_object(
                    'idempotency_key', op_idempotency_key,
                    'entity_type', op_entity_type,
                    'entity_id', op->>'entity_id',
                    'success', false,
                    'error_message', 'Invalid entity_id format',
                    'conflict', false,
                    'server_version', NULL
                );
                v_push_results := v_push_results || op_result;
                CONTINUE;
            END;

            -- Initialiser les valeurs de retour
            op_success := false;
            op_error := NULL;
            op_conflict := false;
            op_server_version := NULL;

            -- Vérifier l'idempotence
            SELECT * INTO existing_op
            FROM processed_sync_operations pso
            WHERE pso.idempotency_key = op_idempotency_key;

            IF FOUND THEN
                -- Opération déjà traitée, retourner le résultat précédent
                op_result := jsonb_build_object(
                    'idempotency_key', op_idempotency_key,
                    'entity_type', existing_op.entity_type,
                    'entity_id', existing_op.entity_id,
                    'success', existing_op.success,
                    'error_message', existing_op.error_message,
                    'conflict', false,
                    'server_version', NULL,
                    'already_processed', true
                );
                v_push_results := v_push_results || op_result;
                CONTINUE;
            END IF;

            BEGIN
                -- Router vers le handler approprié
                CASE op_entity_type
                    -- ===== Entités AVEC gestion de version =====
                    WHEN 'TRIP' THEN
                        SELECT * INTO handler_result FROM push_trip_change(
                            current_user_id, op_entity_id, op_action, op_payload, op_client_version
                        );
                        op_success := handler_result.success;
                        op_error := handler_result.error_message;
                        op_conflict := handler_result.conflict;
                        op_server_version := handler_result.server_version;

                    WHEN 'VEHICLE' THEN
                        SELECT * INTO handler_result FROM push_vehicle_change(
                            current_user_id, op_entity_id, op_action, op_payload, op_client_version
                        );
                        op_success := handler_result.success;
                        op_error := handler_result.error_message;
                        op_conflict := handler_result.conflict;
                        op_server_version := handler_result.server_version;

                    WHEN 'USER' THEN
                        SELECT * INTO handler_result FROM push_user_change(
                            current_user_id, op_entity_id, op_action, op_payload, op_client_version
                        );
                        op_success := handler_result.success;
                        op_error := handler_result.error_message;
                        op_conflict := handler_result.conflict;
                        op_server_version := handler_result.server_version;

                    -- ===== Entités SANS gestion de version =====
                    WHEN 'EXPENSE' THEN
                        SELECT * INTO handler_result FROM push_expense_change(
                            current_user_id, op_entity_id, op_action, op_payload
                        );
                        op_success := handler_result.success;
                        op_error := handler_result.error_message;
                        op_conflict := false;
                        op_server_version := NULL;

                    WHEN 'WORK_SCHEDULE' THEN
                        SELECT * INTO handler_result FROM push_work_schedule_change(
                            current_user_id, op_entity_id, op_action, op_payload
                        );
                        op_success := handler_result.success;
                        op_error := handler_result.error_message;
                        op_conflict := false;
                        op_server_version := NULL;

                    WHEN 'AUTO_TRACKING_SETTINGS' THEN
                        SELECT * INTO handler_result FROM push_auto_tracking_settings_change(
                            current_user_id, op_action, op_payload
                        );
                        op_success := handler_result.success;
                        op_error := handler_result.error_message;
                        op_conflict := false;
                        op_server_version := NULL;

                    WHEN 'PRO_ACCOUNT' THEN
                        SELECT * INTO handler_result FROM push_pro_account_change(
                            current_user_id, current_pro_account_id, op_entity_id, op_action, op_payload
                        );
                        op_success := handler_result.success;
                        op_error := handler_result.error_message;
                        op_conflict := false;
                        op_server_version := NULL;

                    WHEN 'COMPANY_LINK' THEN
                        SELECT * INTO handler_result FROM push_company_link_change(
                            current_user_id, current_pro_account_id, op_entity_id, op_action, op_payload
                        );
                        op_success := handler_result.success;
                        op_error := handler_result.error_message;
                        op_conflict := false;
                        op_server_version := NULL;

                    WHEN 'LICENSE' THEN
                        SELECT * INTO handler_result FROM push_license_change(
                            current_user_id, current_pro_account_id, op_entity_id, op_action, op_payload
                        );
                        op_success := handler_result.success;
                        op_error := handler_result.error_message;
                        op_conflict := false;
                        op_server_version := NULL;

                    WHEN 'CONSENT' THEN
                        SELECT * INTO handler_result FROM push_consent_change(
                            current_user_id, op_action, op_payload
                        );
                        op_success := handler_result.success;
                        op_error := handler_result.error_message;
                        op_conflict := false;
                        op_server_version := NULL;

                    -- ===== Entités INTERDITES =====
                    WHEN 'STRIPE_SUBSCRIPTION' THEN
                        op_success := false;
                        op_error := 'STRIPE_SUBSCRIPTION cannot be modified from the app (managed by webhooks)';

                    ELSE
                        op_success := false;
                        op_error := 'Unknown entity type: ' || COALESCE(op_entity_type, 'null');
                END CASE;

            EXCEPTION WHEN OTHERS THEN
                op_success := false;
                op_error := SQLERRM;
            END;

            -- Enregistrer pour l'idempotence
            INSERT INTO processed_sync_operations (
                idempotency_key, entity_type, entity_id, action, success, error_message
            ) VALUES (
                op_idempotency_key,
                COALESCE(op_entity_type, 'UNKNOWN'),
                COALESCE(op_entity_id, '00000000-0000-0000-0000-000000000000'::UUID),
                COALESCE(op_action, 'UNKNOWN'),
                op_success,
                op_error
            ) ON CONFLICT (idempotency_key) DO NOTHING;

            -- Ajouter au résultat
            op_result := jsonb_build_object(
                'idempotency_key', op_idempotency_key,
                'entity_type', op_entity_type,
                'entity_id', op_entity_id,
                'success', op_success,
                'error_message', op_error,
                'conflict', op_conflict,
                'server_version', op_server_version,
                'already_processed', false
            );
            v_push_results := v_push_results || op_result;
        END LOOP;
    END IF;

    -- ==================== PHASE 2: PULL (DESCENDANT) ====================
    -- Récupère toutes les entités modifiées depuis 'since'
    -- Note: Les entités qu'on vient de pusher seront incluses (updated_at = now())
    -- C'est voulu pour confirmer le push côté client

    SELECT COALESCE(jsonb_agg(row_to_json(changes) ORDER BY changes.updated_at ASC), '[]'::JSONB)
    INTO v_pull_results
    FROM (
        -- ==================== TRIPS (propres trajets) ====================
        SELECT
            'TRIP'::TEXT as entity_type,
            t.id as entity_id,
            CASE WHEN t.deleted_at IS NOT NULL THEN 'DELETE' ELSE 'UPSERT' END as action,
            to_jsonb(t) as data,
            COALESCE(t.updated_at, t.created_at) as updated_at
        FROM public.trips t
        WHERE t.user_id = current_user_id
          AND COALESCE(t.updated_at, t.created_at) > since

        UNION ALL

        -- ==================== LINKED_TRIPS (trajets validés des collaborateurs) ====================
        -- Pour les comptes Pro uniquement: trajets validés des employés liés
        -- Différent de TRIP car ce sont les trajets d'AUTRES utilisateurs
        SELECT
            'LINKED_TRIP'::TEXT as entity_type,
            t.id as entity_id,
            CASE WHEN t.deleted_at IS NOT NULL THEN 'DELETE' ELSE 'UPSERT' END as action,
            to_jsonb(t) as data,
            COALESCE(t.updated_at, t.created_at) as updated_at
        FROM public.trips t
        INNER JOIN public.company_links cl ON cl.user_id = t.user_id
        WHERE cl.linked_pro_account_id = current_pro_account_id
          AND current_pro_account_id IS NOT NULL
          AND cl.status = 'active'
          AND cl.unlinked_at IS NULL
          AND t.is_validated = true
          AND t.user_id != current_user_id
          AND COALESCE(t.updated_at, t.created_at) > since

        UNION ALL

        -- ==================== VEHICLES ====================
        SELECT
            'VEHICLE'::TEXT,
            v.id,
            CASE WHEN v.deleted_at IS NOT NULL THEN 'DELETE' ELSE 'UPSERT' END,
            to_jsonb(v),
            COALESCE(v.updated_at, v.created_at)
        FROM public.vehicles v
        WHERE v.user_id = current_user_id
          AND COALESCE(v.updated_at, v.created_at) > since

        UNION ALL

        -- ==================== EXPENSES ====================
        SELECT
            'EXPENSE'::TEXT,
            e.id,
            CASE WHEN e.deleted_at IS NOT NULL THEN 'DELETE' ELSE 'UPSERT' END,
            to_jsonb(e),
            COALESCE(e.updated_at, e.created_at)
        FROM public.expenses_trips e
        WHERE e.user_id = current_user_id
          AND COALESCE(e.updated_at, e.created_at) > since

        UNION ALL

        -- ==================== USER (profil) ====================
        SELECT
            'USER'::TEXT,
            u.id,
            'UPSERT',
            to_jsonb(u),
            COALESCE(u.updated_at, u.created_at)
        FROM public.users u
        WHERE u.id = current_user_id
          AND COALESCE(u.updated_at, u.created_at) > since

        UNION ALL

        -- ==================== WORK_SCHEDULES ====================
        SELECT
            'WORK_SCHEDULE'::TEXT,
            ws.id,
            CASE WHEN ws.deleted_at IS NOT NULL THEN 'DELETE' ELSE 'UPSERT' END,
            to_jsonb(ws),
            COALESCE(ws.updated_at, ws.created_at)
        FROM public.work_schedules ws
        WHERE ws.user_id = current_user_id
          AND COALESCE(ws.updated_at, ws.created_at) > since

        UNION ALL

        -- ==================== AUTO_TRACKING_SETTINGS ====================
        SELECT
            'AUTO_TRACKING_SETTINGS'::TEXT,
            ats.id,
            'UPSERT',
            to_jsonb(ats),
            COALESCE(ats.updated_at, ats.created_at)
        FROM public.auto_tracking_settings ats
        WHERE ats.user_id = current_user_id
          AND COALESCE(ats.updated_at, ats.created_at) > since

        UNION ALL

        -- ==================== PRO_ACCOUNT (si owner) ====================
        SELECT
            'PRO_ACCOUNT'::TEXT,
            pa.id,
            'UPSERT',
            to_jsonb(pa),
            COALESCE(pa.updated_at, pa.created_at)
        FROM public.pro_accounts pa
        WHERE pa.user_id = current_user_id
          AND COALESCE(pa.updated_at, pa.created_at) > since

        UNION ALL

        -- ==================== COMPANY_LINKS (en tant qu'employé) ====================
        SELECT
            'COMPANY_LINK'::TEXT,
            cl.id,
            CASE WHEN cl.unlinked_at IS NOT NULL THEN 'DELETE' ELSE 'UPSERT' END,
            to_jsonb(cl),
            COALESCE(cl.updated_at, cl.created_at)
        FROM public.company_links cl
        WHERE cl.user_id = current_user_id
          AND COALESCE(cl.updated_at, cl.created_at) > since

        UNION ALL

        -- ==================== LICENSE (assignées à MOI en tant qu'employé) ====================
        -- Ce sont les licences que j'utilise, assignées par un compte Pro
        SELECT
            'LICENSE'::TEXT,
            l.id,
            CASE WHEN l.status = 'cancelled' THEN 'DELETE' ELSE 'UPSERT' END,
            to_jsonb(l),
            COALESCE(l.updated_at, l.created_at)
        FROM public.licenses l
        WHERE l.linked_account_id = current_user_id
          AND COALESCE(l.updated_at, l.created_at) > since

        UNION ALL

        -- ==================== PRO_LICENSE (licences de MON compte Pro) ====================
        -- Ce sont les licences que je gère en tant que propriétaire Pro
        -- Différent de LICENSE: ici je suis le PROPRIÉTAIRE, pas l'UTILISATEUR
        SELECT
            'PRO_LICENSE'::TEXT,
            l.id,
            CASE WHEN l.status = 'cancelled' THEN 'DELETE' ELSE 'UPSERT' END,
            to_jsonb(l),
            COALESCE(l.updated_at, l.created_at)
        FROM public.licenses l
        WHERE l.pro_account_id = current_pro_account_id
          AND current_pro_account_id IS NOT NULL
          AND COALESCE(l.updated_at, l.created_at) > since

        UNION ALL

        -- ==================== CONSENTS ====================
        SELECT
            'CONSENT'::TEXT,
            uc.id,
            CASE WHEN uc.revoked_at IS NOT NULL THEN 'DELETE' ELSE 'UPSERT' END,
            to_jsonb(uc),
            GREATEST(COALESCE(uc.granted_at, uc.created_at), COALESCE(uc.revoked_at, uc.created_at))
        FROM public.user_consents uc
        WHERE uc.user_id = current_user_id
          AND GREATEST(COALESCE(uc.granted_at, uc.created_at), COALESCE(uc.revoked_at, uc.created_at)) > since
    ) AS changes;

    -- ==================== RETOUR ====================
    RETURN QUERY SELECT v_push_results, v_pull_results, v_sync_timestamp;
END;
$$;

-- ========================================
-- HELPER: push_trip_change() - AVEC VERSION
-- ========================================
CREATE OR REPLACE FUNCTION push_trip_change(
    p_user_id UUID,
    p_entity_id UUID,
    p_action TEXT,
    p_payload JSONB,
    p_client_version INTEGER
)
RETURNS TABLE (success BOOLEAN, error_message TEXT, conflict BOOLEAN, server_version INTEGER)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    existing_trip RECORD;
BEGIN
    -- Validation pour UPDATE/DELETE
    IF p_action IN ('UPDATE', 'DELETE') THEN
        SELECT * INTO existing_trip FROM trips WHERE id = p_entity_id;

        IF NOT FOUND THEN
            RETURN QUERY SELECT false, 'Trip not found'::TEXT, false, NULL::INTEGER;
            RETURN;
        END IF;

        IF existing_trip.user_id != p_user_id THEN
            RETURN QUERY SELECT false, 'Not authorized to modify this trip'::TEXT, false, NULL::INTEGER;
            RETURN;
        END IF;

        -- Vérifier les conflits de version
        IF existing_trip.version > p_client_version THEN
            RETURN QUERY SELECT false, 'Version conflict - server has newer version'::TEXT, true, existing_trip.version;
            RETURN;
        END IF;
    END IF;

    CASE p_action
        WHEN 'CREATE' THEN
            -- Validation des champs obligatoires
            IF p_payload->>'start_time' IS NULL THEN
                RETURN QUERY SELECT false, 'start_time is required'::TEXT, false, NULL::INTEGER;
                RETURN;
            END IF;
            IF p_payload->>'start_latitude' IS NULL OR p_payload->>'start_longitude' IS NULL THEN
                RETURN QUERY SELECT false, 'start_latitude and start_longitude are required'::TEXT, false, NULL::INTEGER;
                RETURN;
            END IF;

            INSERT INTO trips (
                id, user_id, vehicle_id, start_time, end_time,
                start_latitude, start_longitude, end_latitude, end_longitude,
                start_address, end_address, distance_km, duration_ms,
                type, is_validated, cost, trace_gps, is_work_home_trip,
                reimbursement_amount, notes, matched_route_coordinates,
                version, created_at, updated_at
            ) VALUES (
                p_entity_id,
                p_user_id,
                (p_payload->>'vehicle_id')::UUID,
                (p_payload->>'start_time')::TIMESTAMPTZ,
                (p_payload->>'end_time')::TIMESTAMPTZ,
                (p_payload->>'start_latitude')::NUMERIC,
                (p_payload->>'start_longitude')::NUMERIC,
                (p_payload->>'end_latitude')::NUMERIC,
                (p_payload->>'end_longitude')::NUMERIC,
                p_payload->>'start_address',
                p_payload->>'end_address',
                COALESCE((p_payload->>'distance_km')::NUMERIC, 0),
                COALESCE((p_payload->>'duration_ms')::BIGINT, 0),
                COALESCE(p_payload->>'type', 'PERSONAL'),
                COALESCE((p_payload->>'is_validated')::BOOLEAN, false),
                COALESCE((p_payload->>'cost')::NUMERIC, 0),
                p_payload->'trace_gps',
                COALESCE((p_payload->>'is_work_home_trip')::BOOLEAN, false),
                (p_payload->>'reimbursement_amount')::FLOAT8,
                p_payload->>'notes',
                p_payload->>'matched_route_coordinates',
                p_client_version,
                COALESCE((p_payload->>'created_at')::TIMESTAMPTZ, now()),
                now()
            )
            ON CONFLICT (id) DO UPDATE SET
                vehicle_id = EXCLUDED.vehicle_id,
                start_time = EXCLUDED.start_time,
                end_time = EXCLUDED.end_time,
                start_latitude = EXCLUDED.start_latitude,
                start_longitude = EXCLUDED.start_longitude,
                end_latitude = EXCLUDED.end_latitude,
                end_longitude = EXCLUDED.end_longitude,
                start_address = EXCLUDED.start_address,
                end_address = EXCLUDED.end_address,
                distance_km = EXCLUDED.distance_km,
                duration_ms = EXCLUDED.duration_ms,
                type = EXCLUDED.type,
                is_validated = EXCLUDED.is_validated,
                cost = EXCLUDED.cost,
                trace_gps = EXCLUDED.trace_gps,
                is_work_home_trip = EXCLUDED.is_work_home_trip,
                reimbursement_amount = EXCLUDED.reimbursement_amount,
                notes = EXCLUDED.notes,
                matched_route_coordinates = EXCLUDED.matched_route_coordinates,
                version = EXCLUDED.version,
                updated_at = now();

            RETURN QUERY SELECT true, NULL::TEXT, false, p_client_version;

        WHEN 'UPDATE' THEN
            UPDATE trips SET
                vehicle_id = COALESCE((p_payload->>'vehicle_id')::UUID, vehicle_id),
                start_time = COALESCE((p_payload->>'start_time')::TIMESTAMPTZ, start_time),
                end_time = (p_payload->>'end_time')::TIMESTAMPTZ,
                start_latitude = COALESCE((p_payload->>'start_latitude')::NUMERIC, start_latitude),
                start_longitude = COALESCE((p_payload->>'start_longitude')::NUMERIC, start_longitude),
                end_latitude = (p_payload->>'end_latitude')::NUMERIC,
                end_longitude = (p_payload->>'end_longitude')::NUMERIC,
                start_address = COALESCE(p_payload->>'start_address', start_address),
                end_address = p_payload->>'end_address',
                distance_km = COALESCE((p_payload->>'distance_km')::NUMERIC, distance_km),
                duration_ms = COALESCE((p_payload->>'duration_ms')::BIGINT, duration_ms),
                type = COALESCE(p_payload->>'type', type),
                is_validated = COALESCE((p_payload->>'is_validated')::BOOLEAN, is_validated),
                cost = COALESCE((p_payload->>'cost')::NUMERIC, cost),
                trace_gps = COALESCE(p_payload->'trace_gps', trace_gps),
                is_work_home_trip = COALESCE((p_payload->>'is_work_home_trip')::BOOLEAN, is_work_home_trip),
                reimbursement_amount = (p_payload->>'reimbursement_amount')::FLOAT8,
                notes = p_payload->>'notes',
                matched_route_coordinates = p_payload->>'matched_route_coordinates',
                version = p_client_version,
                updated_at = now()
            WHERE id = p_entity_id AND user_id = p_user_id;

            RETURN QUERY SELECT true, NULL::TEXT, false, p_client_version;

        WHEN 'DELETE' THEN
            UPDATE trips SET
                deleted_at = now(),
                version = p_client_version,
                updated_at = now()
            WHERE id = p_entity_id AND user_id = p_user_id AND deleted_at IS NULL;

            RETURN QUERY SELECT true, NULL::TEXT, false, p_client_version;

        ELSE
            RETURN QUERY SELECT false, ('Unknown action: ' || p_action)::TEXT, false, NULL::INTEGER;
    END CASE;
END;
$$;

-- ========================================
-- HELPER: push_vehicle_change() - AVEC VERSION
-- ========================================
CREATE OR REPLACE FUNCTION push_vehicle_change(
    p_user_id UUID,
    p_entity_id UUID,
    p_action TEXT,
    p_payload JSONB,
    p_client_version INTEGER
)
RETURNS TABLE (success BOOLEAN, error_message TEXT, conflict BOOLEAN, server_version INTEGER)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    existing_vehicle RECORD;
BEGIN
    IF p_action IN ('UPDATE', 'DELETE') THEN
        SELECT * INTO existing_vehicle FROM vehicles WHERE id = p_entity_id;

        IF NOT FOUND THEN
            RETURN QUERY SELECT false, 'Vehicle not found'::TEXT, false, NULL::INTEGER;
            RETURN;
        END IF;

        IF existing_vehicle.user_id != p_user_id THEN
            RETURN QUERY SELECT false, 'Not authorized'::TEXT, false, NULL::INTEGER;
            RETURN;
        END IF;

        IF existing_vehicle.version > p_client_version THEN
            RETURN QUERY SELECT false, 'Version conflict'::TEXT, true, existing_vehicle.version;
            RETURN;
        END IF;
    END IF;

    CASE p_action
        WHEN 'CREATE' THEN
            -- Validation champs obligatoires
            IF p_payload->>'name' IS NULL THEN
                RETURN QUERY SELECT false, 'name is required'::TEXT, false, NULL::INTEGER;
                RETURN;
            END IF;
            IF p_payload->>'type' IS NULL THEN
                RETURN QUERY SELECT false, 'type is required'::TEXT, false, NULL::INTEGER;
                RETURN;
            END IF;

            INSERT INTO vehicles (
                id, user_id, name, type, license_plate, power, fuel_type,
                mileage_rate, is_default, total_mileage_perso, total_mileage_pro,
                total_mileage_work_home, version, created_at, updated_at
            ) VALUES (
                p_entity_id,
                p_user_id,
                p_payload->>'name',
                p_payload->>'type',
                p_payload->>'license_plate',
                p_payload->>'power',
                p_payload->>'fuel_type',
                COALESCE((p_payload->>'mileage_rate')::NUMERIC, 0),
                COALESCE((p_payload->>'is_default')::BOOLEAN, false),
                COALESCE((p_payload->>'total_mileage_perso')::FLOAT8, 0),
                COALESCE((p_payload->>'total_mileage_pro')::FLOAT8, 0),
                COALESCE((p_payload->>'total_mileage_work_home')::FLOAT8, 0),
                p_client_version,
                COALESCE((p_payload->>'created_at')::TIMESTAMPTZ, now()),
                now()
            )
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                type = EXCLUDED.type,
                license_plate = EXCLUDED.license_plate,
                power = EXCLUDED.power,
                fuel_type = EXCLUDED.fuel_type,
                mileage_rate = EXCLUDED.mileage_rate,
                is_default = EXCLUDED.is_default,
                total_mileage_perso = EXCLUDED.total_mileage_perso,
                total_mileage_pro = EXCLUDED.total_mileage_pro,
                total_mileage_work_home = EXCLUDED.total_mileage_work_home,
                version = EXCLUDED.version,
                updated_at = now();

            RETURN QUERY SELECT true, NULL::TEXT, false, p_client_version;

        WHEN 'UPDATE' THEN
            UPDATE vehicles SET
                name = COALESCE(p_payload->>'name', name),
                type = COALESCE(p_payload->>'type', type),
                license_plate = p_payload->>'license_plate',
                power = p_payload->>'power',
                fuel_type = p_payload->>'fuel_type',
                mileage_rate = COALESCE((p_payload->>'mileage_rate')::NUMERIC, mileage_rate),
                is_default = COALESCE((p_payload->>'is_default')::BOOLEAN, is_default),
                total_mileage_perso = COALESCE((p_payload->>'total_mileage_perso')::FLOAT8, total_mileage_perso),
                total_mileage_pro = COALESCE((p_payload->>'total_mileage_pro')::FLOAT8, total_mileage_pro),
                total_mileage_work_home = COALESCE((p_payload->>'total_mileage_work_home')::FLOAT8, total_mileage_work_home),
                version = p_client_version,
                updated_at = now()
            WHERE id = p_entity_id AND user_id = p_user_id;

            RETURN QUERY SELECT true, NULL::TEXT, false, p_client_version;

        WHEN 'DELETE' THEN
            UPDATE vehicles SET
                deleted_at = now(),
                version = p_client_version,
                updated_at = now()
            WHERE id = p_entity_id AND user_id = p_user_id AND deleted_at IS NULL;

            RETURN QUERY SELECT true, NULL::TEXT, false, p_client_version;

        ELSE
            RETURN QUERY SELECT false, ('Unknown action: ' || p_action)::TEXT, false, NULL::INTEGER;
    END CASE;
END;
$$;

-- ========================================
-- HELPER: push_user_change() - AVEC VERSION
-- ========================================
CREATE OR REPLACE FUNCTION push_user_change(
    p_user_id UUID,
    p_entity_id UUID,
    p_action TEXT,
    p_payload JSONB,
    p_client_version INTEGER
)
RETURNS TABLE (success BOOLEAN, error_message TEXT, conflict BOOLEAN, server_version INTEGER)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    existing_user RECORD;
BEGIN
    -- Seul UPDATE est autorisé pour USER
    IF p_action != 'UPDATE' THEN
        RETURN QUERY SELECT false, 'Only UPDATE is allowed for USER'::TEXT, false, NULL::INTEGER;
        RETURN;
    END IF;

    -- On ne peut modifier que son propre profil
    IF p_entity_id != p_user_id THEN
        RETURN QUERY SELECT false, 'Can only update your own profile'::TEXT, false, NULL::INTEGER;
        RETURN;
    END IF;

    SELECT * INTO existing_user FROM users WHERE id = p_entity_id;

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, 'User not found'::TEXT, false, NULL::INTEGER;
        RETURN;
    END IF;

    IF existing_user.version > p_client_version THEN
        RETURN QUERY SELECT false, 'Version conflict'::TEXT, true, existing_user.version;
        RETURN;
    END IF;

    -- Mise à jour des champs autorisés uniquement
    -- NE PAS permettre: email, subscription_type, subscription_expires_at (gérés serveur)
    UPDATE users SET
        name = COALESCE(p_payload->>'name', name),
        phone_number = COALESCE(p_payload->>'phone_number', phone_number),
        address = COALESCE(p_payload->>'address', address),
        favorite_colors = COALESCE(p_payload->'favorite_colors', favorite_colors),
        consider_full_distance = COALESCE((p_payload->>'consider_full_distance')::BOOLEAN, consider_full_distance),
        version = p_client_version,
        updated_at = now()
    WHERE id = p_entity_id;

    RETURN QUERY SELECT true, NULL::TEXT, false, p_client_version;
END;
$$;

-- ========================================
-- HELPER: push_expense_change() - SANS VERSION
-- ========================================
CREATE OR REPLACE FUNCTION push_expense_change(
    p_user_id UUID,
    p_entity_id UUID,
    p_action TEXT,
    p_payload JSONB
)
RETURNS TABLE (success BOOLEAN, error_message TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    existing_expense RECORD;
BEGIN
    IF p_action IN ('UPDATE', 'DELETE') THEN
        SELECT * INTO existing_expense FROM expenses_trips WHERE id = p_entity_id;

        IF NOT FOUND THEN
            RETURN QUERY SELECT false, 'Expense not found'::TEXT;
            RETURN;
        END IF;

        IF existing_expense.user_id != p_user_id THEN
            RETURN QUERY SELECT false, 'Not authorized'::TEXT;
            RETURN;
        END IF;
    END IF;

    CASE p_action
        WHEN 'CREATE' THEN
            -- Validation champs obligatoires
            IF p_payload->>'type' IS NULL THEN
                RETURN QUERY SELECT false, 'type is required'::TEXT;
                RETURN;
            END IF;
            IF p_payload->>'amount' IS NULL THEN
                RETURN QUERY SELECT false, 'amount is required'::TEXT;
                RETURN;
            END IF;

            INSERT INTO expenses_trips (
                id, user_id, type, amount, amount_ht, note, photo_uri, date, created_at, updated_at
            ) VALUES (
                p_entity_id,
                p_user_id,
                p_payload->>'type',
                (p_payload->>'amount')::NUMERIC,
                (p_payload->>'amount_ht')::FLOAT8,
                p_payload->>'note',
                p_payload->>'photo_uri',
                (p_payload->>'date')::DATE,
                COALESCE((p_payload->>'created_at')::TIMESTAMPTZ, now()),
                now()
            )
            ON CONFLICT (id) DO UPDATE SET
                type = EXCLUDED.type,
                amount = EXCLUDED.amount,
                amount_ht = EXCLUDED.amount_ht,
                note = EXCLUDED.note,
                photo_uri = EXCLUDED.photo_uri,
                date = EXCLUDED.date,
                updated_at = now();

            RETURN QUERY SELECT true, NULL::TEXT;

        WHEN 'UPDATE' THEN
            UPDATE expenses_trips SET
                type = COALESCE(p_payload->>'type', type),
                amount = COALESCE((p_payload->>'amount')::NUMERIC, amount),
                amount_ht = (p_payload->>'amount_ht')::FLOAT8,
                note = p_payload->>'note',
                photo_uri = p_payload->>'photo_uri',
                date = COALESCE((p_payload->>'date')::DATE, date),
                updated_at = now()
            WHERE id = p_entity_id AND user_id = p_user_id;

            RETURN QUERY SELECT true, NULL::TEXT;

        WHEN 'DELETE' THEN
            UPDATE expenses_trips SET
                deleted_at = now(),
                updated_at = now()
            WHERE id = p_entity_id AND user_id = p_user_id AND deleted_at IS NULL;

            RETURN QUERY SELECT true, NULL::TEXT;

        ELSE
            RETURN QUERY SELECT false, ('Unknown action: ' || p_action)::TEXT;
    END CASE;
END;
$$;

-- ========================================
-- HELPER: push_work_schedule_change() - SANS VERSION
-- ========================================
CREATE OR REPLACE FUNCTION push_work_schedule_change(
    p_user_id UUID,
    p_entity_id UUID,
    p_action TEXT,
    p_payload JSONB
)
RETURNS TABLE (success BOOLEAN, error_message TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    existing_schedule RECORD;
BEGIN
    IF p_action IN ('UPDATE', 'DELETE') THEN
        SELECT * INTO existing_schedule FROM work_schedules WHERE id = p_entity_id;

        IF NOT FOUND THEN
            RETURN QUERY SELECT false, 'Work schedule not found'::TEXT;
            RETURN;
        END IF;

        IF existing_schedule.user_id != p_user_id THEN
            RETURN QUERY SELECT false, 'Not authorized'::TEXT;
            RETURN;
        END IF;
    END IF;

    CASE p_action
        WHEN 'CREATE' THEN
            -- Validation champs obligatoires
            IF p_payload->>'day_of_week' IS NULL THEN
                RETURN QUERY SELECT false, 'day_of_week is required'::TEXT;
                RETURN;
            END IF;

            INSERT INTO work_schedules (
                id, user_id, day_of_week, start_hour, start_minute,
                end_hour, end_minute, is_active, is_overnight, created_at, updated_at
            ) VALUES (
                p_entity_id,
                p_user_id,
                (p_payload->>'day_of_week')::INTEGER,
                COALESCE((p_payload->>'start_hour')::INTEGER, 9),
                COALESCE((p_payload->>'start_minute')::INTEGER, 0),
                COALESCE((p_payload->>'end_hour')::INTEGER, 18),
                COALESCE((p_payload->>'end_minute')::INTEGER, 0),
                COALESCE((p_payload->>'is_active')::BOOLEAN, true),
                COALESCE((p_payload->>'is_overnight')::BOOLEAN, false),
                COALESCE((p_payload->>'created_at')::TIMESTAMPTZ, now()),
                now()
            )
            ON CONFLICT (id) DO UPDATE SET
                day_of_week = EXCLUDED.day_of_week,
                start_hour = EXCLUDED.start_hour,
                start_minute = EXCLUDED.start_minute,
                end_hour = EXCLUDED.end_hour,
                end_minute = EXCLUDED.end_minute,
                is_active = EXCLUDED.is_active,
                is_overnight = EXCLUDED.is_overnight,
                updated_at = now();

            RETURN QUERY SELECT true, NULL::TEXT;

        WHEN 'UPDATE' THEN
            UPDATE work_schedules SET
                day_of_week = COALESCE((p_payload->>'day_of_week')::INTEGER, day_of_week),
                start_hour = COALESCE((p_payload->>'start_hour')::INTEGER, start_hour),
                start_minute = COALESCE((p_payload->>'start_minute')::INTEGER, start_minute),
                end_hour = COALESCE((p_payload->>'end_hour')::INTEGER, end_hour),
                end_minute = COALESCE((p_payload->>'end_minute')::INTEGER, end_minute),
                is_active = COALESCE((p_payload->>'is_active')::BOOLEAN, is_active),
                is_overnight = COALESCE((p_payload->>'is_overnight')::BOOLEAN, is_overnight),
                updated_at = now()
            WHERE id = p_entity_id AND user_id = p_user_id;

            RETURN QUERY SELECT true, NULL::TEXT;

        WHEN 'DELETE' THEN
            UPDATE work_schedules SET
                deleted_at = now(),
                updated_at = now()
            WHERE id = p_entity_id AND user_id = p_user_id AND deleted_at IS NULL;

            RETURN QUERY SELECT true, NULL::TEXT;

        ELSE
            RETURN QUERY SELECT false, ('Unknown action: ' || p_action)::TEXT;
    END CASE;
END;
$$;

-- ========================================
-- HELPER: push_auto_tracking_settings_change() - SANS VERSION
-- ========================================
-- Note: Cette table a une contrainte UNIQUE sur user_id, pas sur id
-- L'id est géré côté serveur, pas côté client
CREATE OR REPLACE FUNCTION push_auto_tracking_settings_change(
    p_user_id UUID,
    p_action TEXT,
    p_payload JSONB
)
RETURNS TABLE (success BOOLEAN, error_message TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    -- Seuls CREATE et UPDATE sont autorisés (pas de DELETE)
    IF p_action NOT IN ('UPDATE', 'CREATE') THEN
        RETURN QUERY SELECT false, 'Only UPDATE/CREATE allowed for AUTO_TRACKING_SETTINGS'::TEXT;
        RETURN;
    END IF;

    -- Upsert basé sur user_id (pas sur id fourni par le client)
    INSERT INTO auto_tracking_settings (
        user_id, tracking_mode, min_trip_distance_meters, min_trip_duration_seconds,
        created_at, updated_at
    ) VALUES (
        p_user_id,
        COALESCE(p_payload->>'tracking_mode', 'DISABLED'),
        COALESCE((p_payload->>'min_trip_distance_meters')::INTEGER, 100),
        COALESCE((p_payload->>'min_trip_duration_seconds')::INTEGER, 60),
        now(),
        now()
    )
    ON CONFLICT (user_id) DO UPDATE SET
        tracking_mode = COALESCE(EXCLUDED.tracking_mode, auto_tracking_settings.tracking_mode),
        min_trip_distance_meters = COALESCE(EXCLUDED.min_trip_distance_meters, auto_tracking_settings.min_trip_distance_meters),
        min_trip_duration_seconds = COALESCE(EXCLUDED.min_trip_duration_seconds, auto_tracking_settings.min_trip_duration_seconds),
        updated_at = now();

    RETURN QUERY SELECT true, NULL::TEXT;
END;
$$;

-- ========================================
-- HELPER: push_pro_account_change() - SANS VERSION
-- ========================================
CREATE OR REPLACE FUNCTION push_pro_account_change(
    p_user_id UUID,
    p_current_pro_account_id UUID,
    p_entity_id UUID,
    p_action TEXT,
    p_payload JSONB
)
RETURNS TABLE (success BOOLEAN, error_message TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    -- Seul UPDATE est autorisé (CREATE/DELETE gérés par le système)
    IF p_action != 'UPDATE' THEN
        RETURN QUERY SELECT false, 'Only UPDATE is allowed for PRO_ACCOUNT'::TEXT;
        RETURN;
    END IF;

    -- Vérifier que c'est bien son propre compte Pro
    IF p_current_pro_account_id IS NULL OR p_entity_id != p_current_pro_account_id THEN
        RETURN QUERY SELECT false, 'Can only update your own pro account'::TEXT;
        RETURN;
    END IF;

    UPDATE pro_accounts SET
        company_name = COALESCE(p_payload->>'company_name', company_name),
        siret = p_payload->>'siret',
        vat_number = p_payload->>'vat_number',
        legal_form = p_payload->>'legal_form',
        billing_address = p_payload->>'billing_address',
        billing_email = p_payload->>'billing_email',
        departments = COALESCE(p_payload->'departments', departments),
        billing_day = COALESCE((p_payload->>'billing_day')::INTEGER, billing_day),
        billing_anchor_day = (p_payload->>'billing_anchor_day')::INTEGER,
        updated_at = now()
    WHERE id = p_entity_id AND user_id = p_user_id;

    RETURN QUERY SELECT true, NULL::TEXT;
END;
$$;

-- ========================================
-- HELPER: push_company_link_change() - SANS VERSION
-- ========================================
CREATE OR REPLACE FUNCTION push_company_link_change(
    p_user_id UUID,
    p_current_pro_account_id UUID,
    p_entity_id UUID,
    p_action TEXT,
    p_payload JSONB
)
RETURNS TABLE (success BOOLEAN, error_message TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    existing_link RECORD;
    is_employee BOOLEAN;
    is_pro_owner BOOLEAN;
BEGIN
    SELECT * INTO existing_link FROM company_links WHERE id = p_entity_id;

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, 'Company link not found'::TEXT;
        RETURN;
    END IF;

    -- Déterminer les droits
    is_employee := existing_link.user_id = p_user_id;
    is_pro_owner := p_current_pro_account_id IS NOT NULL
                    AND existing_link.linked_pro_account_id = p_current_pro_account_id;

    IF NOT (is_employee OR is_pro_owner) THEN
        RETURN QUERY SELECT false, 'Not authorized to modify this company link'::TEXT;
        RETURN;
    END IF;

    CASE p_action
        WHEN 'UPDATE' THEN
            -- L'employé peut modifier ses préférences de partage
            IF is_employee THEN
                UPDATE company_links SET
                    share_professional_trips = COALESCE((p_payload->>'share_professional_trips')::BOOLEAN, share_professional_trips),
                    share_personal_trips = COALESCE((p_payload->>'share_personal_trips')::BOOLEAN, share_personal_trips),
                    share_personal_info = COALESCE((p_payload->>'share_personal_info')::BOOLEAN, share_personal_info),
                    share_expenses = COALESCE((p_payload->>'share_expenses')::BOOLEAN, share_expenses),
                    updated_at = now()
                WHERE id = p_entity_id;
            END IF;

            -- Le Pro peut modifier le département
            IF is_pro_owner THEN
                UPDATE company_links SET
                    department = p_payload->>'department',
                    updated_at = now()
                WHERE id = p_entity_id;
            END IF;

            RETURN QUERY SELECT true, NULL::TEXT;

        WHEN 'REQUEST_UNLINK' THEN
            -- Seul l'employé peut demander à être délié
            IF NOT is_employee THEN
                RETURN QUERY SELECT false, 'Only the employee can request unlink'::TEXT;
                RETURN;
            END IF;

            -- Marquer la demande (le processus complet passe par Edge Functions)
            UPDATE company_links SET
                status = 'PENDING',
                updated_at = now()
            WHERE id = p_entity_id;

            RETURN QUERY SELECT true, NULL::TEXT;

        ELSE
            RETURN QUERY SELECT false, ('Unknown action: ' || p_action)::TEXT;
    END CASE;
END;
$$;

-- ========================================
-- HELPER: push_license_change() - SANS VERSION
-- ========================================
CREATE OR REPLACE FUNCTION push_license_change(
    p_user_id UUID,
    p_current_pro_account_id UUID,
    p_entity_id UUID,
    p_action TEXT,
    p_payload JSONB
)
RETURNS TABLE (success BOOLEAN, error_message TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    existing_license RECORD;
BEGIN
    -- Seuls les propriétaires de compte Pro peuvent gérer les licences
    IF p_current_pro_account_id IS NULL THEN
        RETURN QUERY SELECT false, 'Only Pro account owners can manage licenses'::TEXT;
        RETURN;
    END IF;

    SELECT * INTO existing_license FROM licenses WHERE id = p_entity_id;

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, 'License not found'::TEXT;
        RETURN;
    END IF;

    -- Vérifier que la licence appartient au compte Pro de l'utilisateur
    IF existing_license.pro_account_id != p_current_pro_account_id THEN
        RETURN QUERY SELECT false, 'Not authorized to modify this license'::TEXT;
        RETURN;
    END IF;

    -- Seul UPDATE est autorisé depuis l'app (CREATE/DELETE gérés par webhooks Stripe)
    IF p_action != 'UPDATE' THEN
        RETURN QUERY SELECT false, 'Only UPDATE is allowed for LICENSE from app'::TEXT;
        RETURN;
    END IF;

    -- L'app peut seulement modifier certains champs
    UPDATE licenses SET
        -- Assigner/désassigner une licence (SEULEMENT si explicitement fourni dans payload)
        -- BUGFIX: Ne pas écraser linked_account_id si non fourni (évite NULL lors de unlink_requested)
        linked_account_id = CASE
            WHEN p_payload ? 'linked_account_id' THEN (p_payload->>'linked_account_id')::UUID
            ELSE linked_account_id  -- Garder la valeur existante si pas fourni
        END,
        linked_at = CASE
            WHEN p_payload ? 'linked_account_id' AND p_payload->>'linked_account_id' IS NOT NULL
                 AND existing_license.linked_account_id IS NULL
            THEN now()
            ELSE linked_at
        END,
        -- Demande de déliaison
        unlink_requested_at = CASE
            WHEN (p_payload->>'unlink_requested')::BOOLEAN = true
            THEN now()
            WHEN (p_payload->>'unlink_requested')::BOOLEAN = false
            THEN NULL  -- Annulation de la demande de déliaison
            ELSE unlink_requested_at
        END,
        -- Date effective de déliaison (fournie par l'app: endDate pour mensuelle, now pour lifetime)
        unlink_effective_at = CASE
            WHEN p_payload ? 'unlink_effective_at'
            THEN (p_payload->>'unlink_effective_at')::TIMESTAMPTZ
            WHEN (p_payload->>'unlink_requested')::BOOLEAN = false
            THEN NULL  -- Annulation de la demande de déliaison
            ELSE unlink_effective_at
        END,
        -- Pause d'une licence non assignée (fonctionnalité dépréciée)
        paused_at = CASE
            WHEN (p_payload->>'paused')::BOOLEAN = true AND existing_license.linked_account_id IS NULL
            THEN now()
            WHEN (p_payload->>'paused')::BOOLEAN = false
            THEN NULL
            ELSE paused_at
        END,
        status = CASE
            WHEN (p_payload->>'paused')::BOOLEAN = true AND existing_license.linked_account_id IS NULL
            THEN 'paused'
            WHEN (p_payload->>'paused')::BOOLEAN = false AND existing_license.status = 'paused'
            THEN 'pending'
            ELSE status
        END,
        updated_at = now()
    WHERE id = p_entity_id;

    RETURN QUERY SELECT true, NULL::TEXT;
END;
$$;

-- ========================================
-- HELPER: push_consent_change() - SANS VERSION
-- ========================================
-- Note: La table user_consents a une contrainte UNIQUE sur (user_id, consent_type)
CREATE OR REPLACE FUNCTION push_consent_change(
    p_user_id UUID,
    p_action TEXT,
    p_payload JSONB
)
RETURNS TABLE (success BOOLEAN, error_message TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    -- Validation
    IF p_action NOT IN ('CREATE', 'UPDATE') THEN
        RETURN QUERY SELECT false, ('Unknown action for CONSENT: ' || p_action)::TEXT;
        RETURN;
    END IF;

    IF p_payload->>'consent_type' IS NULL THEN
        RETURN QUERY SELECT false, 'consent_type is required'::TEXT;
        RETURN;
    END IF;

    -- Upsert basé sur (user_id, consent_type)
    INSERT INTO user_consents (
        user_id, consent_type, granted, granted_at, revoked_at,
        consent_version, ip_address, user_agent, created_at, updated_at
    ) VALUES (
        p_user_id,
        p_payload->>'consent_type',
        COALESCE((p_payload->>'granted')::BOOLEAN, false),
        CASE WHEN (p_payload->>'granted')::BOOLEAN = true THEN now() ELSE NULL END,
        CASE WHEN (p_payload->>'granted')::BOOLEAN = false THEN now() ELSE NULL END,
        COALESCE(p_payload->>'consent_version', '1.0'),
        p_payload->>'ip_address',
        p_payload->>'user_agent',
        now(),
        now()
    )
    ON CONFLICT (user_id, consent_type) DO UPDATE SET
        granted = COALESCE(EXCLUDED.granted, user_consents.granted),
        granted_at = CASE
            WHEN EXCLUDED.granted = true THEN now()
            ELSE user_consents.granted_at
        END,
        revoked_at = CASE
            WHEN EXCLUDED.granted = false THEN now()
            ELSE user_consents.revoked_at
        END,
        consent_version = COALESCE(EXCLUDED.consent_version, user_consents.consent_version),
        updated_at = now();

    RETURN QUERY SELECT true, NULL::TEXT;
END;
$$;

-- ========================================
-- NETTOYAGE AUTOMATIQUE DES OPÉRATIONS TRAITÉES
-- ========================================
CREATE OR REPLACE FUNCTION cleanup_old_sync_operations()
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    -- Supprimer les opérations de plus de 7 jours
    DELETE FROM processed_sync_operations
    WHERE processed_at < now() - INTERVAL '7 days';

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;

-- ========================================
-- PERMISSIONS
-- ========================================
GRANT EXECUTE ON FUNCTION sync_changes(JSONB, TIMESTAMPTZ) TO authenticated;
GRANT EXECUTE ON FUNCTION push_trip_change(UUID, UUID, TEXT, JSONB, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION push_vehicle_change(UUID, UUID, TEXT, JSONB, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION push_user_change(UUID, UUID, TEXT, JSONB, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION push_expense_change(UUID, UUID, TEXT, JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION push_work_schedule_change(UUID, UUID, TEXT, JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION push_auto_tracking_settings_change(UUID, TEXT, JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION push_pro_account_change(UUID, UUID, UUID, TEXT, JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION push_company_link_change(UUID, UUID, UUID, TEXT, JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION push_license_change(UUID, UUID, UUID, TEXT, JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION push_consent_change(UUID, TEXT, JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION cleanup_old_sync_operations() TO authenticated;

-- Table d'idempotence accessible
GRANT SELECT, INSERT, DELETE ON processed_sync_operations TO authenticated;

-- ========================================
-- COMMENTAIRE DE DOCUMENTATION
-- ========================================
COMMENT ON FUNCTION sync_changes IS 'Synchronisation bidirectionnelle atomique (push + pull).
Appel unique qui remplace N appels API individuels.
Phase 1: Traite le batch d''opérations pending (push montant)
Phase 2: Récupère les changements depuis le timestamp (pull descendant)
Retourne: push_results (succès/erreurs), pull_results (entités à appliquer), sync_timestamp (pour prochain appel)';
