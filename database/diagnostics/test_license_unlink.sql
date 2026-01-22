-- =============================================================================
-- DIAGNOSTIC: Test license unlink/cancel-unlink functionality
-- =============================================================================
-- Exécuter ce fichier dans Supabase Studio (http://176.168.117.243:3000)
-- SQL Editor → Coller et exécuter chaque section
-- =============================================================================

-- =============================================================================
-- ÉTAPE 1: Vérifier que push_license_change() est correctement installée
-- =============================================================================
-- Chercher la version actuelle de la fonction sur le serveur

SELECT
    proname AS function_name,
    pg_get_functiondef(oid) AS function_definition
FROM pg_proc
WHERE proname = 'push_license_change';

-- Vérifier que le fix BUGFIX_011 est appliqué:
-- La définition devrait contenir "WHEN p_payload ? 'linked_account_id'"
-- Si cette ligne n'est pas présente, le bugfix n'est pas appliqué!

-- =============================================================================
-- ÉTAPE 2: Trouver un utilisateur Pro et une licence pour tester
-- =============================================================================
-- Remplacer les UUIDs ci-dessous par des valeurs réelles de votre base

-- Trouver les comptes Pro avec leurs licences
SELECT
    pa.id AS pro_account_id,
    pa.user_id AS owner_user_id,
    u.email AS owner_email,
    l.id AS license_id,
    l.linked_account_id,
    l.status,
    l.unlink_requested_at,
    l.unlink_effective_at,
    l.is_lifetime
FROM pro_accounts pa
JOIN users u ON u.id = pa.user_id
LEFT JOIN licenses l ON l.pro_account_id = pa.id
WHERE l.linked_account_id IS NOT NULL  -- Licences assignées
ORDER BY pa.id, l.id
LIMIT 10;

-- =============================================================================
-- ÉTAPE 3: Test de déliaison (unlink)
-- =============================================================================
-- REMPLACER LES UUIDS PAR DES VRAIES VALEURS!
-- Copier les IDs depuis l'étape 2

-- Test: Demander une déliaison
/*
SELECT * FROM push_license_change(
    '00000000-0000-0000-0000-000000000000'::UUID,  -- p_user_id (owner_user_id de l'étape 2)
    '00000000-0000-0000-0000-000000000000'::UUID,  -- p_current_pro_account_id (pro_account_id de l'étape 2)
    '00000000-0000-0000-0000-000000000000'::UUID,  -- p_entity_id (license_id de l'étape 2)
    'UPDATE',
    '{"unlink_requested": true, "unlink_effective_at": "2026-02-01T00:00:00Z"}'::JSONB
);
*/

-- Vérifier le résultat:
-- Si success = false, l'error_message indique le problème
-- Possibles erreurs:
--   - "Only Pro account owners can manage licenses" → p_current_pro_account_id est NULL
--   - "License not found" → p_entity_id invalide
--   - "Not authorized to modify this license" → la licence n'appartient pas à ce pro_account

-- =============================================================================
-- ÉTAPE 4: Vérifier que la licence a été mise à jour
-- =============================================================================
-- Après le test de l'étape 3, vérifier les champs

/*
SELECT
    id,
    linked_account_id,
    status,
    unlink_requested_at,
    unlink_effective_at,
    updated_at
FROM licenses
WHERE id = '00000000-0000-0000-0000-000000000000'::UUID;  -- license_id de l'étape 2
*/

-- =============================================================================
-- ÉTAPE 5: Test d'annulation de déliaison (cancel unlink)
-- =============================================================================
/*
SELECT * FROM push_license_change(
    '00000000-0000-0000-0000-000000000000'::UUID,  -- p_user_id
    '00000000-0000-0000-0000-000000000000'::UUID,  -- p_current_pro_account_id
    '00000000-0000-0000-0000-000000000000'::UUID,  -- p_entity_id
    'UPDATE',
    '{"unlink_requested": false}'::JSONB
);
*/

-- Vérifier que unlink_requested_at et unlink_effective_at sont NULL après

-- =============================================================================
-- ÉTAPE 6: Vérifier la fonction sync_changes() pour les opérations LICENSE
-- =============================================================================
-- Cette fonction est appelée par l'app pour synchroniser les changements

SELECT
    proname AS function_name,
    pg_get_functiondef(oid) AS function_definition
FROM pg_proc
WHERE proname = 'sync_changes';

-- Vérifier dans la définition:
-- 1. Il devrait y avoir un CASE WHEN 'LICENSE' THEN SELECT * INTO handler_result FROM push_license_change(...)
-- 2. current_pro_account_id devrait être récupéré depuis pro_accounts WHERE user_id = current_user_id

-- =============================================================================
-- ÉTAPE 7: Simuler ce que l'app envoie
-- =============================================================================
-- L'app envoie ceci via sync_changes():
/*
SELECT * FROM sync_changes(
    '[
        {
            "entity_type": "LICENSE",
            "entity_id": "00000000-0000-0000-0000-000000000000",
            "action": "UPDATE",
            "idempotency_key": "LICENSE:uuid:UPDATE:1705123456789",
            "payload": {
                "unlink_requested": true,
                "unlink_effective_at": "2026-02-01T00:00:00Z"
            }
        }
    ]'::JSONB,
    '1970-01-01T00:00:00Z'::TIMESTAMPTZ
);
*/

-- Cette requête retourne:
-- - push_results: tableau avec {success: true/false, error_message: "...", ...}
-- - pull_results: tableau des changements depuis le timestamp
-- - sync_timestamp: timestamp du sync

-- =============================================================================
-- DIAGNOSTIC FINAL: Vérifier les logs
-- =============================================================================
-- Si le test fonctionne ici mais pas depuis l'app, le problème est côté client.
-- Vérifier:
-- 1. Les logs Android pour "Push failed for LICENSE" ou autres erreurs
-- 2. Que l'utilisateur connecté a bien un pro_account associé
-- 3. Que la licence appartient bien au pro_account de l'utilisateur

-- =============================================================================
-- FIX POTENTIEL: Si les migrations ne sont pas appliquées
-- =============================================================================
-- Si la fonction push_license_change() ne contient pas le fix BUGFIX_011,
-- exécuter le fichier: database/migrations/bugfix_011_license_unlink_fix.sql
-- Puis exécuter: database/migrations/bugfix_017_cancel_unlink_reset_license.sql
-- =============================================================================
