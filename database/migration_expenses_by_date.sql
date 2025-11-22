-- ========================================
-- MIGRATION: Système de dépenses par JOURNÉE
-- ========================================
-- Exécutez ce script dans Supabase SQL Editor AVANT de build l'app
-- Ce script migre le système de dépenses de "par trip" vers "par journée"
-- ========================================

-- Étape 1: Ajouter la colonne date
ALTER TABLE expenses_trips ADD COLUMN IF NOT EXISTS date DATE;

-- Étape 2: Migrer les données existantes
-- Extraire la date depuis les trips associés et la copier dans la nouvelle colonne
UPDATE expenses_trips e
SET date = DATE(t.start_time)
FROM trips t
WHERE e.trip_id = t.id
  AND e.date IS NULL
  AND t.start_time IS NOT NULL;

-- Pour les dépenses orphelines (sans trip), utiliser created_at comme fallback
UPDATE expenses_trips
SET date = DATE(created_at)
WHERE date IS NULL AND created_at IS NOT NULL;

-- Étape 3: Rendre la colonne date obligatoire après migration
ALTER TABLE expenses_trips ALTER COLUMN date SET NOT NULL;

-- Étape 4: Rendre trip_id optionnel (car maintenant lié à la journée)
ALTER TABLE expenses_trips ALTER COLUMN trip_id DROP NOT NULL;

-- Étape 5: Créer un index sur date pour améliorer les performances
CREATE INDEX IF NOT EXISTS idx_expenses_trips_date ON expenses_trips(date);

-- Étape 6: Vérifier la migration
SELECT
    COUNT(*) as total_expenses,
    COUNT(DISTINCT date) as unique_dates,
    COUNT(CASE WHEN trip_id IS NULL THEN 1 END) as expenses_without_trip,
    COUNT(CASE WHEN date IS NULL THEN 1 END) as expenses_without_date
FROM expenses_trips;

-- Résultat attendu:
-- - total_expenses: nombre total de dépenses
-- - unique_dates: nombre de jours différents avec des dépenses
-- - expenses_without_trip: nombre de dépenses sans trip (nouveau système)
-- - expenses_without_date: doit être 0 (toutes les dépenses ont une date)

-- Étape 7: Afficher un échantillon des données migrées
SELECT
    id,
    date,
    trip_id,
    type,
    amount,
    created_at
FROM expenses_trips
ORDER BY date DESC
LIMIT 10;

-- ========================================
-- FIN DE LA MIGRATION
-- ========================================
-- Après avoir exécuté ce script:
-- 1. Vérifiez que toutes les dépenses ont une date (expenses_without_date = 0)
-- 2. Vous pouvez maintenant build et installer l'app Android
-- 3. Les dépenses seront maintenant organisées par journée
-- ========================================
