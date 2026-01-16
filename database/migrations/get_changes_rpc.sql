-- ========================================
-- RPC: get_changes(since TIMESTAMPTZ)
-- ========================================
-- Date: 2025-12-29
-- Description: Retourne toutes les entites modifiees depuis le timestamp donne
-- Remplace 8+ appels API par un seul appel RPC pour la synchronisation
-- ========================================

CREATE OR REPLACE FUNCTION get_changes(since TIMESTAMPTZ DEFAULT '1970-01-01T00:00:00Z')
RETURNS TABLE (
    entity_type TEXT,
    entity_id UUID,
    action TEXT,
    data JSONB,
    updated_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    current_user_id UUID;
    current_pro_account_id UUID;
BEGIN
    -- Recuperer l'ID utilisateur depuis auth.uid()
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'User not authenticated';
    END IF;

    -- Recuperer le user_id depuis la table users
    SELECT id INTO current_user_id
    FROM public.users
    WHERE auth_id = auth.uid();

    IF current_user_id IS NULL THEN
        RAISE EXCEPTION 'User not found in users table';
    END IF;

    -- Recuperer le pro_account_id si l'utilisateur a un compte Pro
    SELECT id INTO current_pro_account_id
    FROM public.pro_accounts
    WHERE user_id = current_user_id;

    -- Wrap all UNION queries in a subquery to allow ORDER BY on expressions
    RETURN QUERY
    SELECT * FROM (
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

        -- ==================== LINKED_TRIPS (trajets valides des collaborateurs) ====================
        -- Seulement pour les comptes Pro, trajets valides uniquement
        SELECT
            'LINKED_TRIP'::TEXT as entity_type,
            t.id as entity_id,
            CASE WHEN t.deleted_at IS NOT NULL THEN 'DELETE' ELSE 'UPSERT' END as action,
            to_jsonb(t) as data,
            COALESCE(t.updated_at, t.created_at) as updated_at
        FROM public.trips t
        INNER JOIN public.company_links cl ON cl.user_id = t.user_id
        WHERE cl.linked_pro_account_id = current_pro_account_id
          AND current_pro_account_id IS NOT NULL  -- Seulement si l'utilisateur a un compte Pro
          AND cl.status = 'active'                 -- Lien actif
          AND cl.unlinked_at IS NULL               -- Pas delie
          AND t.is_validated = true                -- Seulement trajets valides
          AND t.user_id != current_user_id         -- Exclure ses propres trajets (deja dans TRIP)
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
            'UPSERT',  -- Jamais de DELETE pour le profil user
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
        WHERE ws.user_id = auth.uid()  -- work_schedules utilise auth_id directement
          AND COALESCE(ws.updated_at, ws.created_at) > since

        UNION ALL

        -- ==================== AUTO_TRACKING_SETTINGS ====================
        SELECT
            'AUTO_TRACKING_SETTINGS'::TEXT,
            ats.id,
            'UPSERT',  -- Pas de DELETE, on update
            to_jsonb(ats),
            COALESCE(ats.updated_at, ats.created_at)
        FROM public.auto_tracking_settings ats
        WHERE ats.user_id = auth.uid()  -- auto_tracking_settings utilise auth_id directement
          AND COALESCE(ats.updated_at, ats.created_at) > since

        UNION ALL

        -- ==================== PRO_ACCOUNT (si owner) ====================
        SELECT
            'PRO_ACCOUNT'::TEXT,
            pa.id,
            'UPSERT',  -- Pas de DELETE pour pro_account
            to_jsonb(pa),
            COALESCE(pa.updated_at, pa.created_at)
        FROM public.pro_accounts pa
        WHERE pa.user_id = current_user_id
          AND COALESCE(pa.updated_at, pa.created_at) > since

        UNION ALL

        -- ==================== COMPANY_LINKS (en tant qu'employe) ====================
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

        -- ==================== LICENSES (assignees a l'utilisateur en tant qu'employe) ====================
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

        -- ==================== LICENSES (pour Pro: toutes les licenses de son compte) ====================
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
    ) AS all_changes
    ORDER BY updated_at ASC;  -- Order by column name from subquery
END;
$$;

-- Grant execute permission to authenticated users
GRANT EXECUTE ON FUNCTION get_changes(TIMESTAMPTZ) TO authenticated;

-- ========================================
-- INDEX pour optimiser la RPC
-- ========================================
-- Ces index ameliorent les performances des requetes delta

-- Index composite pour trips par user_id + updated_at
CREATE INDEX IF NOT EXISTS idx_trips_user_updated ON trips(user_id, updated_at);

-- Index composite pour vehicles par user_id + updated_at
CREATE INDEX IF NOT EXISTS idx_vehicles_user_updated ON vehicles(user_id, updated_at);

-- Index composite pour expenses par user_id + updated_at
CREATE INDEX IF NOT EXISTS idx_expenses_user_updated ON expenses_trips(user_id, updated_at);

-- Index pour company_links
CREATE INDEX IF NOT EXISTS idx_company_links_user_updated ON company_links(user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_company_links_pro_updated ON company_links(linked_pro_account_id, status, updated_at);

-- Index pour licenses
CREATE INDEX IF NOT EXISTS idx_licenses_linked_updated ON licenses(linked_account_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_licenses_pro_updated ON licenses(pro_account_id, updated_at);

-- ========================================
-- COMMENTAIRES
-- ========================================
-- Note: COMMENT ON FUNCTION removed due to Supabase dashboard SQL parser issues
-- Function returns all entities modified since the given timestamp for the authenticated user.
-- Used for delta sync (offline-first). Entity types: TRIP, LINKED_TRIP, VEHICLE, EXPENSE,
-- USER, WORK_SCHEDULE, AUTO_TRACKING_SETTINGS, PRO_ACCOUNT, COMPANY_LINK, LICENSE, PRO_LICENSE, CONSENT
