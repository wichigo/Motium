-- ========================================
-- SCRIPT DE NETTOYAGE: Isolation des données utilisateur
-- ========================================
-- Exécutez ce script dans Supabase SQL Editor pour corriger
-- les problèmes d'isolation des données entre utilisateurs
-- ========================================

-- Étape 1: Vérifier les trips sans user_id (DANGEREUX!)
SELECT
    id,
    start_time,
    end_time,
    start_address,
    end_address,
    distance_km,
    type,
    user_id
FROM trips
WHERE user_id IS NULL
ORDER BY start_time DESC;

-- Résultat attendu: Si vous voyez des trips ici, ce sont des trips orphelins
-- qui seront visibles par TOUS les utilisateurs à cause de la RLS policy

-- ========================================
-- OPTION A: Supprimer tous les trips orphelins (RECOMMANDÉ)
-- ========================================
-- Décommentez et exécutez cette ligne si vous voulez SUPPRIMER
-- tous les trips qui n'ont pas de user_id

-- DELETE FROM trips WHERE user_id IS NULL;

-- ========================================
-- OPTION B: Assigner les trips orphelins à un utilisateur spécifique
-- ========================================
-- Si vous voulez garder vos anciens trips, vous devez les assigner
-- à un utilisateur. Remplacez 'YOUR_USER_ID_HERE' par votre ID utilisateur.

-- Pour trouver votre user ID, exécutez d'abord:
SELECT id, email, name, auth_id FROM users ORDER BY created_at DESC;

-- Ensuite, remplacez 'YOUR_USER_ID_HERE' par l'ID de l'utilisateur (colonne 'id', pas 'auth_id')
-- et décommentez la ligne suivante:

-- UPDATE trips
-- SET user_id = 'YOUR_USER_ID_HERE'
-- WHERE user_id IS NULL;

-- ========================================
-- OPTION C: Supprimer tous les trips (RESET COMPLET)
-- ========================================
-- ATTENTION: Cela supprimera TOUS les trips de TOUS les utilisateurs!
-- N'utilisez cette option que si vous voulez repartir de zéro

-- DELETE FROM trips;

-- ========================================
-- Étape 2: Améliorer les RLS policies pour bloquer user_id NULL
-- ========================================

-- Supprimer l'ancienne policy
DROP POLICY IF EXISTS "Users can view own trips" ON trips;
DROP POLICY IF EXISTS "Users can manage own trips" ON trips;

-- Créer de nouvelles policies plus strictes qui bloquent user_id NULL
CREATE POLICY "Users can view own trips" ON trips FOR SELECT USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
    AND user_id IS NOT NULL  -- NOUVEAU: Bloquer les trips orphelins
);

CREATE POLICY "Users can insert own trips" ON trips FOR INSERT WITH CHECK (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
    AND user_id IS NOT NULL  -- NOUVEAU: Force user_id non NULL
);

CREATE POLICY "Users can update own trips" ON trips FOR UPDATE USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
    AND user_id IS NOT NULL
) WITH CHECK (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
    AND user_id IS NOT NULL
);

CREATE POLICY "Users can delete own trips" ON trips FOR DELETE USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
    AND user_id IS NOT NULL
);

-- ========================================
-- Étape 3: Vérifier que les policies sont actives
-- ========================================
SELECT
    schemaname,
    tablename,
    policyname,
    permissive,
    roles,
    cmd,
    qual,
    with_check
FROM pg_policies
WHERE tablename = 'trips';

-- Résultat attendu: Vous devriez voir 4 policies (SELECT, INSERT, UPDATE, DELETE)

-- ========================================
-- Étape 4: Tester l'isolation des données
-- ========================================
-- Cette requête devrait retourner UNIQUEMENT vos trips
-- (ceux avec votre user_id)
SELECT
    id,
    user_id,
    start_time,
    start_address,
    end_address,
    distance_km
FROM trips
ORDER BY start_time DESC
LIMIT 10;

-- ========================================
-- FIN DU SCRIPT
-- ========================================
