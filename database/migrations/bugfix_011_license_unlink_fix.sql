-- =================================================================
-- BUGFIX 011: Fix license unlink - Don't reset linked_account_id
-- =================================================================
-- EXÉCUTER CE FICHIER SUR SUPABASE STUDIO (https://studio.motium.app)
-- Dans SQL Editor, coller et exécuter ce contenu
-- =================================================================
--
-- PROBLÈME:
-- Quand l'app envoie {"unlink_requested": true, "unlink_effective_at": "..."},
-- la fonction push_license_change() écrasait linked_account_id à NULL
-- car ce champ n'était pas dans le payload.
--
-- SOLUTION:
-- - Ne modifier linked_account_id QUE si explicitement présent dans payload
-- - Lire unlink_effective_at depuis le payload
-- =================================================================

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

-- S'assurer que les permissions sont accordées
GRANT EXECUTE ON FUNCTION push_license_change(UUID, UUID, UUID, TEXT, JSONB) TO authenticated;

-- =================================================================
-- TEST DE VÉRIFICATION (optionnel)
-- =================================================================
-- Après exécution, vous pouvez vérifier avec:
--
-- SELECT prosrc FROM pg_proc WHERE proname = 'push_license_change';
--
-- Recherchez "WHEN p_payload ? 'linked_account_id'" dans le résultat
-- Si présent, le fix est appliqué.
-- =================================================================
