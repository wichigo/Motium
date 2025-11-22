-- ========================================
-- ACTIVATION DES ROW LEVEL SECURITY (RLS)
-- ========================================
-- Ce script active et configure les RLS policies
-- pour isoler les données entre utilisateurs
-- ========================================

-- ========================================
-- Étape 1: ACTIVER RLS sur les tables
-- ========================================
ALTER TABLE trips ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicles ENABLE ROW LEVEL SECURITY;

-- Vérifier que RLS est activée
SELECT tablename, rowsecurity
FROM pg_tables
WHERE schemaname = 'public'
AND tablename IN ('trips', 'vehicles', 'users', 'settings');

-- Résultat attendu: rowsecurity = true pour toutes les tables

-- ========================================
-- Étape 2: SUPPRIMER les anciennes policies (si elles existent)
-- ========================================
DROP POLICY IF EXISTS "Users can view own trips" ON trips;
DROP POLICY IF EXISTS "Users can manage own trips" ON trips;
DROP POLICY IF EXISTS "Users can insert own trips" ON trips;
DROP POLICY IF EXISTS "Users can update own trips" ON trips;
DROP POLICY IF EXISTS "Users can delete own trips" ON trips;

DROP POLICY IF EXISTS "Users can view own vehicles" ON vehicles;
DROP POLICY IF EXISTS "Users can manage own vehicles" ON vehicles;
DROP POLICY IF EXISTS "Users can insert own vehicles" ON vehicles;
DROP POLICY IF EXISTS "Users can update own vehicles" ON vehicles;
DROP POLICY IF EXISTS "Users can delete own vehicles" ON vehicles;

-- ========================================
-- Étape 3: CRÉER les policies STRICTES pour TRIPS
-- ========================================

-- Policy SELECT: Les utilisateurs ne peuvent voir QUE leurs propres trips
CREATE POLICY "Users can view own trips" ON trips
    FOR SELECT
    USING (
        user_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
    );

-- Policy INSERT: Les utilisateurs ne peuvent créer QUE leurs propres trips
CREATE POLICY "Users can insert own trips" ON trips
    FOR INSERT
    WITH CHECK (
        user_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
        AND user_id IS NOT NULL
    );

-- Policy UPDATE: Les utilisateurs ne peuvent modifier QUE leurs propres trips
CREATE POLICY "Users can update own trips" ON trips
    FOR UPDATE
    USING (
        user_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
    )
    WITH CHECK (
        user_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
        AND user_id IS NOT NULL
    );

-- Policy DELETE: Les utilisateurs ne peuvent supprimer QUE leurs propres trips
CREATE POLICY "Users can delete own trips" ON trips
    FOR DELETE
    USING (
        user_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
    );

-- ========================================
-- Étape 4: CRÉER les policies STRICTES pour VEHICLES
-- ========================================

-- Policy SELECT: Les utilisateurs ne peuvent voir QUE leurs propres véhicules
CREATE POLICY "Users can view own vehicles" ON vehicles
    FOR SELECT
    USING (
        user_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
    );

-- Policy INSERT: Les utilisateurs ne peuvent créer QUE leurs propres véhicules
CREATE POLICY "Users can insert own vehicles" ON vehicles
    FOR INSERT
    WITH CHECK (
        user_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
        AND user_id IS NOT NULL
    );

-- Policy UPDATE: Les utilisateurs ne peuvent modifier QUE leurs propres véhicules
CREATE POLICY "Users can update own vehicles" ON vehicles
    FOR UPDATE
    USING (
        user_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
    )
    WITH CHECK (
        user_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
        AND user_id IS NOT NULL
    );

-- Policy DELETE: Les utilisateurs ne peuvent supprimer QUE leurs propres véhicules
CREATE POLICY "Users can delete own vehicles" ON vehicles
    FOR DELETE
    USING (
        user_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
    );

-- ========================================
-- Étape 5: VÉRIFIER que les policies sont créées
-- ========================================
SELECT
    schemaname,
    tablename,
    policyname,
    permissive,
    cmd,
    qual,
    with_check
FROM pg_policies
WHERE tablename IN ('trips', 'vehicles')
ORDER BY tablename, cmd;

-- Résultat attendu:
-- - 4 policies pour 'trips' (SELECT, INSERT, UPDATE, DELETE)
-- - 4 policies pour 'vehicles' (SELECT, INSERT, UPDATE, DELETE)

-- ========================================
-- Étape 6: TESTER l'isolation des données
-- ========================================

-- Cette requête doit retourner UNIQUEMENT vos trips (ceux avec votre user_id)
SELECT
    id,
    user_id,
    start_time,
    start_address,
    end_address,
    distance_km,
    type
FROM trips
ORDER BY start_time DESC
LIMIT 5;

-- Cette requête doit retourner UNIQUEMENT vos véhicules
SELECT
    id,
    user_id,
    name,
    type,
    license_plate,
    is_default
FROM vehicles
ORDER BY created_at DESC;

-- ========================================
-- Étape 7: VÉRIFIER que RLS est active et fonctionnelle
-- ========================================

-- Cette requête montre l'état RLS de chaque table
SELECT
    schemaname,
    tablename,
    rowsecurity as rls_enabled,
    (SELECT COUNT(*) FROM pg_policies p WHERE p.tablename = t.tablename) as policy_count
FROM pg_tables t
WHERE schemaname = 'public'
AND tablename IN ('users', 'trips', 'vehicles', 'settings', 'expenses_trips')
ORDER BY tablename;

-- Résultat attendu:
-- - rls_enabled = true pour toutes les tables
-- - policy_count > 0 pour trips et vehicles

-- ========================================
-- FIN DU SCRIPT
-- ========================================
-- Après avoir exécuté ce script:
-- 1. Déconnectez-vous de l'application
-- 2. Reconnectez-vous
-- 3. Vous ne devriez voir QUE vos propres données
-- ========================================
