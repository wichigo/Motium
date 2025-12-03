-- ============================================================================
-- SIMULATION TEST : 1 AN DE TRAJETS AVEC 5 VÉHICULES
-- Barème kilométrique 2024 - Test des tranches progressives
-- ============================================================================

-- IMPORTANT: Remplacez 'YOUR_USER_ID' par votre vrai user_id de Supabase
-- Vous pouvez le trouver dans la table auth.users ou dans l'app (Settings)

-- Variable pour faciliter les remplacements
-- DO $$ DECLARE user_uuid UUID := 'YOUR_USER_ID'; BEGIN ... END $$;

-- ============================================================================
-- ÉTAPE 1: CRÉER LES 5 VÉHICULES DE TEST
-- ============================================================================

-- Supprimer les véhicules de test existants (optionnel - décommenter si besoin)
-- DELETE FROM vehicles WHERE name IN ('Twingo Test', 'Clio Test', '308 Test', 'Tesla M3 Test', 'BMW X5 Test');

INSERT INTO vehicles (id, user_id, name, type, license_plate, power, fuel_type, mileage_rate, is_default, created_at, updated_at)
VALUES
    -- 1. Twingo (3 CV, Essence) - Petite citadine pour trajets courts perso
    (gen_random_uuid(), 'YOUR_USER_ID', 'Twingo Test', 'CAR', 'AA-111-AA', '3CV', 'GASOLINE', 0.529, false, NOW(), NOW()),

    -- 2. Clio (4 CV, Diesel) - Domicile-travail quotidien
    (gen_random_uuid(), 'YOUR_USER_ID', 'Clio Test', 'CAR', 'BB-222-BB', '4CV', 'DIESEL', 0.606, true, NOW(), NOW()),

    -- 3. 308 (5 CV, Essence) - Trajets pro clients proches
    (gen_random_uuid(), 'YOUR_USER_ID', '308 Test', 'CAR', 'CC-333-CC', '5CV', 'GASOLINE', 0.636, false, NOW(), NOW()),

    -- 4. Tesla Model 3 (6 CV, Électrique) - Longue distance pro
    (gen_random_uuid(), 'YOUR_USER_ID', 'Tesla M3 Test', 'CAR', 'DD-444-DD', '6CV', 'ELECTRIC', 0.665, false, NOW(), NOW()),

    -- 5. BMW X5 (7 CV+, Diesel) - Usage mixte pro/perso
    (gen_random_uuid(), 'YOUR_USER_ID', 'BMW X5 Test', 'CAR', 'EE-555-EE', '7CV+', 'DIESEL', 0.697, false, NOW(), NOW());

-- ============================================================================
-- ÉTAPE 2: RÉCUPÉRER LES IDS DES VÉHICULES CRÉÉS
-- ============================================================================
-- Exécutez cette requête pour obtenir les IDs :
-- SELECT id, name, power FROM vehicles WHERE name LIKE '%Test' ORDER BY power;

-- ============================================================================
-- ÉTAPE 3: CRÉER LES TRAJETS DE L'ANNÉE
-- ============================================================================
-- Note: Les trajets doivent être créés avec les vrais vehicle_id obtenus à l'étape 2

-- Fonction pour générer des trajets de test
CREATE OR REPLACE FUNCTION generate_test_trips(
    p_user_id UUID,
    p_vehicle_id UUID,
    p_trip_type TEXT,
    p_month INTEGER,
    p_year INTEGER,
    p_num_trips INTEGER,
    p_avg_distance_km DOUBLE PRECISION
) RETURNS VOID AS $$
DECLARE
    i INTEGER;
    trip_date TIMESTAMP;
    distance DOUBLE PRECISION;
    duration_ms BIGINT;
BEGIN
    FOR i IN 1..p_num_trips LOOP
        -- Date aléatoire dans le mois
        trip_date := make_date(p_year, p_month, 1 + (random() * 27)::INTEGER)::TIMESTAMP
                     + (interval '1 hour' * (7 + random() * 12));

        -- Distance avec variation de +/- 20%
        distance := p_avg_distance_km * (0.8 + random() * 0.4);

        -- Durée basée sur 40 km/h moyen
        duration_ms := (distance / 40.0 * 3600 * 1000)::BIGINT;

        INSERT INTO trips (
            id, user_id, vehicle_id, start_time, end_time,
            start_latitude, start_longitude, end_latitude, end_longitude,
            distance_km, duration_ms, type, is_validated, cost,
            created_at, updated_at
        ) VALUES (
            gen_random_uuid(),
            p_user_id,
            p_vehicle_id,
            trip_date,
            trip_date + (duration_ms || ' milliseconds')::INTERVAL,
            48.8566, 2.3522,  -- Paris (départ fictif)
            48.8566 + (random() - 0.5) * 0.1, 2.3522 + (random() - 0.5) * 0.1,  -- Arrivée proche
            distance,
            duration_ms,
            p_trip_type,
            true,  -- Validé
            0.0,   -- Coût calculé par l'app
            NOW(),
            NOW()
        );
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- ÉTAPE 4: GÉNÉRER LES TRAJETS POUR CHAQUE VÉHICULE
-- ============================================================================
-- Remplacez les UUIDs par ceux obtenus à l'étape 2

-- EXEMPLE D'UTILISATION (décommentez et adaptez avec vos IDs):
/*
DO $$
DECLARE
    user_id UUID := 'YOUR_USER_ID';
    twingo_id UUID := 'TWINGO_VEHICLE_ID';
    clio_id UUID := 'CLIO_VEHICLE_ID';
    peugeot_id UUID := '308_VEHICLE_ID';
    tesla_id UUID := 'TESLA_VEHICLE_ID';
    bmw_id UUID := 'BMW_VEHICLE_ID';
BEGIN
    -- ========== TWINGO (3 CV) - PERSO UNIQUEMENT ==========
    -- 1700 km/an en perso, ~142 km/mois, ~5 trajets de 28 km
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 1, 2024, 8, 25.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 2, 2024, 6, 30.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 3, 2024, 5, 30.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 4, 2024, 10, 35.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 5, 2024, 8, 35.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 6, 2024, 12, 40.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 7, 2024, 5, 20.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 8, 2024, 3, 30.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 9, 2024, 8, 30.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 10, 2024, 5, 25.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 11, 2024, 6, 25.0);
    PERFORM generate_test_trips(user_id, twingo_id, 'PERSONAL', 12, 2024, 5, 30.0);

    -- ========== CLIO (4 CV) - PRO UNIQUEMENT (domicile-travail) ==========
    -- 8360 km/an en pro, ~697 km/mois, ~17 trajets de 41 km
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 1, 2024, 22, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 2, 2024, 20, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 3, 2024, 23, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 4, 2024, 18, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 5, 2024, 20, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 6, 2024, 22, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 7, 2024, 15, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 8, 2024, 10, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 9, 2024, 22, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 10, 2024, 23, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 11, 2024, 21, 40.0);
    PERFORM generate_test_trips(user_id, clio_id, 'PROFESSIONAL', 12, 2024, 18, 40.0);

    -- ========== 308 (5 CV) - PRO UNIQUEMENT (clients) ==========
    -- 3060 km/an en pro, ~255 km/mois, ~3 trajets de 85 km
    PERFORM generate_test_trips(user_id, peugeot_id, 'PROFESSIONAL', 3, 2024, 8, 80.0);
    PERFORM generate_test_trips(user_id, peugeot_id, 'PROFESSIONAL', 5, 2024, 10, 90.0);
    PERFORM generate_test_trips(user_id, peugeot_id, 'PROFESSIONAL', 9, 2024, 12, 85.0);
    PERFORM generate_test_trips(user_id, peugeot_id, 'PROFESSIONAL', 10, 2024, 5, 100.0);

    -- ========== TESLA M3 (6 CV) - MIXTE PRO/PERSO ==========
    -- PRO: 6160 km/an, PERSO: 600 km/an
    PERFORM generate_test_trips(user_id, tesla_id, 'PROFESSIONAL', 2, 2024, 5, 200.0);
    PERFORM generate_test_trips(user_id, tesla_id, 'PROFESSIONAL', 4, 2024, 8, 180.0);
    PERFORM generate_test_trips(user_id, tesla_id, 'PROFESSIONAL', 6, 2024, 6, 220.0);
    PERFORM generate_test_trips(user_id, tesla_id, 'PROFESSIONAL', 10, 2024, 7, 200.0);
    PERFORM generate_test_trips(user_id, tesla_id, 'PROFESSIONAL', 12, 2024, 4, 250.0);
    -- Perso
    PERFORM generate_test_trips(user_id, tesla_id, 'PERSONAL', 8, 2024, 1, 600.0);

    -- ========== BMW X5 (7 CV+) - MIXTE PRO/PERSO ==========
    -- PRO: 1170 km/an, PERSO: 5750 km/an
    PERFORM generate_test_trips(user_id, bmw_id, 'PROFESSIONAL', 1, 2024, 3, 150.0);
    PERFORM generate_test_trips(user_id, bmw_id, 'PROFESSIONAL', 11, 2024, 4, 180.0);
    -- Perso (vacances, weekends)
    PERFORM generate_test_trips(user_id, bmw_id, 'PERSONAL', 3, 2024, 2, 300.0);
    PERFORM generate_test_trips(user_id, bmw_id, 'PERSONAL', 5, 2024, 4, 250.0);
    PERFORM generate_test_trips(user_id, bmw_id, 'PERSONAL', 7, 2024, 3, 500.0);
    PERFORM generate_test_trips(user_id, bmw_id, 'PERSONAL', 8, 2024, 2, 800.0);
    PERFORM generate_test_trips(user_id, bmw_id, 'PERSONAL', 12, 2024, 3, 350.0);
END $$;
*/

-- ============================================================================
-- ÉTAPE 5: REQUÊTES DE VÉRIFICATION
-- ============================================================================

-- 5.1 Résumé par véhicule et type de trajet
SELECT
    v.name AS vehicule,
    v.power AS puissance,
    t.type AS type_trajet,
    COUNT(*) AS nb_trajets,
    ROUND(SUM(t.distance_km)::NUMERIC, 1) AS total_km,
    CASE
        WHEN SUM(t.distance_km) <= 5000 THEN 'Tranche 1 (0-5000)'
        WHEN SUM(t.distance_km) <= 20000 THEN 'Tranche 2 (5001-20000)'
        ELSE 'Tranche 3 (>20000)'
    END AS tranche
FROM trips t
JOIN vehicles v ON t.vehicle_id = v.id
WHERE v.name LIKE '%Test'
  AND t.start_time >= '2024-01-01'
  AND t.start_time < '2025-01-01'
GROUP BY v.name, v.power, t.type
ORDER BY v.power, t.type;

-- 5.2 Calcul des indemnités avec barème progressif
WITH vehicle_totals AS (
    SELECT
        v.id,
        v.name,
        v.power,
        t.type,
        SUM(t.distance_km) AS total_km
    FROM trips t
    JOIN vehicles v ON t.vehicle_id = v.id
    WHERE v.name LIKE '%Test'
      AND t.start_time >= '2024-01-01'
      AND t.start_time < '2025-01-01'
    GROUP BY v.id, v.name, v.power, t.type
)
SELECT
    name AS vehicule,
    power AS puissance,
    type AS type_trajet,
    ROUND(total_km::NUMERIC, 1) AS total_km,
    CASE power
        WHEN '3CV' THEN
            CASE
                WHEN total_km <= 5000 THEN ROUND((total_km * 0.529)::NUMERIC, 2)
                WHEN total_km <= 20000 THEN ROUND((total_km * 0.316 + 1065)::NUMERIC, 2)
                ELSE ROUND((total_km * 0.370)::NUMERIC, 2)
            END
        WHEN '4CV' THEN
            CASE
                WHEN total_km <= 5000 THEN ROUND((total_km * 0.606)::NUMERIC, 2)
                WHEN total_km <= 20000 THEN ROUND((total_km * 0.340 + 1330)::NUMERIC, 2)
                ELSE ROUND((total_km * 0.407)::NUMERIC, 2)
            END
        WHEN '5CV' THEN
            CASE
                WHEN total_km <= 5000 THEN ROUND((total_km * 0.636)::NUMERIC, 2)
                WHEN total_km <= 20000 THEN ROUND((total_km * 0.357 + 1395)::NUMERIC, 2)
                ELSE ROUND((total_km * 0.427)::NUMERIC, 2)
            END
        WHEN '6CV' THEN
            CASE
                WHEN total_km <= 5000 THEN ROUND((total_km * 0.665)::NUMERIC, 2)
                WHEN total_km <= 20000 THEN ROUND((total_km * 0.374 + 1457)::NUMERIC, 2)
                ELSE ROUND((total_km * 0.447)::NUMERIC, 2)
            END
        WHEN '7CV+' THEN
            CASE
                WHEN total_km <= 5000 THEN ROUND((total_km * 0.697)::NUMERIC, 2)
                WHEN total_km <= 20000 THEN ROUND((total_km * 0.394 + 1515)::NUMERIC, 2)
                ELSE ROUND((total_km * 0.464)::NUMERIC, 2)
            END
    END AS indemnite_calculee
FROM vehicle_totals
ORDER BY power, type;

-- ============================================================================
-- RÉSULTATS ATTENDUS (selon la simulation planifiée)
-- ============================================================================
/*
VÉHICULE      | PUISSANCE | TYPE  | KM TOTAL | TRANCHE          | INDEMNITÉ ATTENDUE
--------------|-----------|-------|----------|------------------|-------------------
Twingo Test   | CV_3      | PERSO | ~1700    | Tranche 1        | 1700 × 0.529 = 899.30 €
Clio Test     | CV_4      | PRO   | ~8360    | Tranche 2        | 8360 × 0.340 + 1330 = 4172.40 €
308 Test      | CV_5      | PRO   | ~3060    | Tranche 1        | 3060 × 0.636 = 1946.16 €
Tesla M3 Test | CV_6      | PRO   | ~6160    | Tranche 2        | 6160 × 0.374 + 1457 = 3760.84 €
Tesla M3 Test | CV_6      | PERSO | ~600     | Tranche 1        | 600 × 0.665 = 399.00 €
BMW X5 Test   | CV_7_PLUS | PRO   | ~1170    | Tranche 1        | 1170 × 0.697 = 815.49 €
BMW X5 Test   | CV_7_PLUS | PERSO | ~5750    | Tranche 2        | 5750 × 0.394 + 1515 = 3780.50 €

TOTAUX:
- PRO:   18,750 km → ~10,694.89 €
- PERSO:  8,050 km → ~5,078.80 €
- TOTAL: 26,800 km → ~15,773.69 €

POINTS DE VÉRIFICATION:
1. ✓ Chaque véhicule a son propre compteur kilométrique
2. ✓ PRO et PERSO sont comptabilisés séparément
3. ✓ Le taux change selon la tranche (ex: Clio 8360km = Tranche 2)
4. ✓ Le taux est basé sur la puissance fiscale du véhicule
5. ✓ Le type de carburant n'affecte PAS le taux
*/

-- ============================================================================
-- NETTOYAGE (optionnel - à exécuter après les tests)
-- ============================================================================
/*
-- Supprimer tous les trajets de test
DELETE FROM trips WHERE vehicle_id IN (
    SELECT id FROM vehicles WHERE name LIKE '%Test'
);

-- Supprimer les véhicules de test
DELETE FROM vehicles WHERE name LIKE '%Test';

-- Supprimer la fonction de génération
DROP FUNCTION IF EXISTS generate_test_trips;
*/
