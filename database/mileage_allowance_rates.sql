-- ========================================
-- Table pour les barèmes d'indemnité kilométrique (France 2024)
-- ========================================

CREATE TABLE IF NOT EXISTS mileage_allowance_rates (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Type de véhicule
    vehicle_type TEXT NOT NULL CHECK (vehicle_type IN ('CAR', 'MOTORCYCLE', 'MOPED', 'SCOOTER_50', 'SCOOTER_125')),

    -- Puissance fiscale (pour les voitures uniquement, NULL pour les 2-roues)
    fiscal_power TEXT CHECK (fiscal_power IN ('3CV_AND_LESS', '4CV', '5CV', '6CV', '7CV_AND_MORE')),

    -- Cylindrée (pour les 2-roues uniquement, NULL pour les voitures)
    cylinder_capacity TEXT CHECK (cylinder_capacity IN ('50CC_AND_LESS', '51CC_TO_125CC', 'MORE_THAN_125CC')),

    -- Tranche de kilométrage annuel
    mileage_bracket_min INTEGER NOT NULL, -- Kilométrage minimum (inclus)
    mileage_bracket_max INTEGER,          -- Kilométrage maximum (exclu), NULL = illimité

    -- Formule de calcul ou taux fixe
    calculation_type TEXT NOT NULL CHECK (calculation_type IN ('FORMULA', 'FIXED_RATE')),

    -- Coefficients pour la formule : (distance * coefficient_a) + coefficient_b
    coefficient_a DECIMAL(10, 6),
    coefficient_b DECIMAL(10, 2),

    -- Taux fixe par km (si calculation_type = 'FIXED_RATE')
    rate_per_km DECIMAL(10, 6),

    -- Métadonnées
    year INTEGER NOT NULL DEFAULT 2024,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index pour optimiser les recherches
CREATE INDEX idx_mileage_rates_vehicle_type ON mileage_allowance_rates(vehicle_type);
CREATE INDEX idx_mileage_rates_fiscal_power ON mileage_allowance_rates(fiscal_power);
CREATE INDEX idx_mileage_rates_year ON mileage_allowance_rates(year);

-- ========================================
-- Barèmes 2024 - VOITURES
-- ========================================

-- 3 CV et moins
INSERT INTO mileage_allowance_rates (vehicle_type, fiscal_power, mileage_bracket_min, mileage_bracket_max, calculation_type, coefficient_a, rate_per_km, year)
VALUES
    ('CAR', '3CV_AND_LESS', 0, 5000, 'FIXED_RATE', NULL, 0.529, 2024),
    ('CAR', '3CV_AND_LESS', 5001, 20000, 'FORMULA', 0.316, NULL, 2024),
    ('CAR', '3CV_AND_LESS', 20001, NULL, 'FIXED_RATE', NULL, 0.370, 2024);

-- 4 CV
INSERT INTO mileage_allowance_rates (vehicle_type, fiscal_power, mileage_bracket_min, mileage_bracket_max, calculation_type, coefficient_a, rate_per_km, year)
VALUES
    ('CAR', '4CV', 0, 5000, 'FIXED_RATE', NULL, 0.606, 2024),
    ('CAR', '4CV', 5001, 20000, 'FORMULA', 0.340, NULL, 2024),
    ('CAR', '4CV', 20001, NULL, 'FIXED_RATE', NULL, 0.407, 2024);

-- 5 CV
INSERT INTO mileage_allowance_rates (vehicle_type, fiscal_power, mileage_bracket_min, mileage_bracket_max, calculation_type, coefficient_a, rate_per_km, year)
VALUES
    ('CAR', '5CV', 0, 5000, 'FIXED_RATE', NULL, 0.636, 2024),
    ('CAR', '5CV', 5001, 20000, 'FORMULA', 0.357, NULL, 2024),
    ('CAR', '5CV', 20001, NULL, 'FIXED_RATE', NULL, 0.427, 2024);

-- 6 CV
INSERT INTO mileage_allowance_rates (vehicle_type, fiscal_power, mileage_bracket_min, mileage_bracket_max, calculation_type, coefficient_a, rate_per_km, year)
VALUES
    ('CAR', '6CV', 0, 5000, 'FIXED_RATE', NULL, 0.665, 2024),
    ('CAR', '6CV', 5001, 20000, 'FORMULA', 0.374, NULL, 2024),
    ('CAR', '6CV', 20001, NULL, 'FIXED_RATE', NULL, 0.447, 2024);

-- 7 CV et plus
INSERT INTO mileage_allowance_rates (vehicle_type, fiscal_power, mileage_bracket_min, mileage_bracket_max, calculation_type, coefficient_a, rate_per_km, year)
VALUES
    ('CAR', '7CV_AND_MORE', 0, 5000, 'FIXED_RATE', NULL, 0.685, 2024),
    ('CAR', '7CV_AND_MORE', 5001, 20000, 'FORMULA', 0.394, NULL, 2024),
    ('CAR', '7CV_AND_MORE', 20001, NULL, 'FIXED_RATE', NULL, 0.461, 2024);

-- ========================================
-- Barèmes 2024 - DEUX-ROUES MOTORISÉS
-- ========================================

-- Vélomoteurs, scooters et motos jusqu'à 50 cm³
INSERT INTO mileage_allowance_rates (vehicle_type, cylinder_capacity, mileage_bracket_min, mileage_bracket_max, calculation_type, coefficient_a, rate_per_km, year)
VALUES
    ('MOPED', '50CC_AND_LESS', 0, 3000, 'FIXED_RATE', NULL, 0.315, 2024),
    ('MOPED', '50CC_AND_LESS', 3001, 6000, 'FORMULA', 0.079, NULL, 2024),
    ('MOPED', '50CC_AND_LESS', 6001, NULL, 'FIXED_RATE', NULL, 0.198, 2024);

-- Scooters et motos de 51 cm³ à 125 cm³
INSERT INTO mileage_allowance_rates (vehicle_type, cylinder_capacity, mileage_bracket_min, mileage_bracket_max, calculation_type, coefficient_a, rate_per_km, year)
VALUES
    ('SCOOTER_125', '51CC_TO_125CC', 0, 3000, 'FIXED_RATE', NULL, 0.395, 2024),
    ('SCOOTER_125', '51CC_TO_125CC', 3001, 6000, 'FORMULA', 0.099, NULL, 2024),
    ('SCOOTER_125', '51CC_TO_125CC', 6001, NULL, 'FIXED_RATE', NULL, 0.248, 2024);

-- Motos de plus de 125 cm³
INSERT INTO mileage_allowance_rates (vehicle_type, cylinder_capacity, mileage_bracket_min, mileage_bracket_max, calculation_type, coefficient_a, rate_per_km, year)
VALUES
    ('MOTORCYCLE', 'MORE_THAN_125CC', 0, 3000, 'FIXED_RATE', NULL, 0.468, 2024),
    ('MOTORCYCLE', 'MORE_THAN_125CC', 3001, 6000, 'FORMULA', 0.082, NULL, 2024),
    ('MOTORCYCLE', 'MORE_THAN_125CC', 6001, NULL, 'FIXED_RATE', NULL, 0.282, 2024);

-- ========================================
-- Fonction pour calculer l'indemnité kilométrique
-- ========================================

CREATE OR REPLACE FUNCTION calculate_mileage_allowance(
    p_vehicle_type TEXT,
    p_annual_mileage_km INTEGER,
    p_fiscal_power TEXT DEFAULT NULL,
    p_cylinder_capacity TEXT DEFAULT NULL,
    p_year INTEGER DEFAULT 2024
)
RETURNS DECIMAL(10, 2) AS $$
DECLARE
    v_rate RECORD;
    v_allowance DECIMAL(10, 2);
BEGIN
    -- Trouver le barème correspondant
    SELECT * INTO v_rate
    FROM mileage_allowance_rates
    WHERE vehicle_type = p_vehicle_type
        AND year = p_year
        AND (fiscal_power = p_fiscal_power OR fiscal_power IS NULL)
        AND (cylinder_capacity = p_cylinder_capacity OR cylinder_capacity IS NULL)
        AND mileage_bracket_min <= p_annual_mileage_km
        AND (mileage_bracket_max IS NULL OR p_annual_mileage_km < mileage_bracket_max);

    IF NOT FOUND THEN
        RAISE EXCEPTION 'No mileage rate found for vehicle type %, power %, cylinder %, mileage %',
            p_vehicle_type, p_fiscal_power, p_cylinder_capacity, p_annual_mileage_km;
    END IF;

    -- Calculer l'indemnité selon le type de calcul
    IF v_rate.calculation_type = 'FIXED_RATE' THEN
        v_allowance := p_annual_mileage_km * v_rate.rate_per_km;
    ELSIF v_rate.calculation_type = 'FORMULA' THEN
        -- Formule : distance * coefficient_a + coefficient_b (mais coefficient_b = 0 dans les barèmes français)
        v_allowance := p_annual_mileage_km * v_rate.coefficient_a;
    END IF;

    RETURN ROUND(v_allowance, 2);
END;
$$ LANGUAGE plpgsql;

-- ========================================
-- Fonction pour obtenir le taux par km actuel
-- ========================================

CREATE OR REPLACE FUNCTION get_mileage_rate_per_km(
    p_vehicle_type TEXT,
    p_annual_mileage_km INTEGER,
    p_fiscal_power TEXT DEFAULT NULL,
    p_cylinder_capacity TEXT DEFAULT NULL,
    p_year INTEGER DEFAULT 2024
)
RETURNS DECIMAL(10, 6) AS $$
DECLARE
    v_rate RECORD;
BEGIN
    -- Trouver le barème correspondant
    SELECT * INTO v_rate
    FROM mileage_allowance_rates
    WHERE vehicle_type = p_vehicle_type
        AND year = p_year
        AND (fiscal_power = p_fiscal_power OR fiscal_power IS NULL)
        AND (cylinder_capacity = p_cylinder_capacity OR cylinder_capacity IS NULL)
        AND mileage_bracket_min <= p_annual_mileage_km
        AND (mileage_bracket_max IS NULL OR p_annual_mileage_km < mileage_bracket_max);

    IF NOT FOUND THEN
        RETURN 0.00;
    END IF;

    -- Retourner le taux selon le type de calcul
    IF v_rate.calculation_type = 'FIXED_RATE' THEN
        RETURN v_rate.rate_per_km;
    ELSIF v_rate.calculation_type = 'FORMULA' THEN
        RETURN v_rate.coefficient_a;
    END IF;

    RETURN 0.00;
END;
$$ LANGUAGE plpgsql;

-- ========================================
-- Exemples d'utilisation
-- ========================================

-- Calculer l'indemnité pour une voiture 7 CV ayant parcouru 15 000 km professionnels
-- SELECT calculate_mileage_allowance('CAR', 15000, '7CV_AND_MORE', NULL, 2024);
-- Résultat attendu : 15000 * 0.394 = 5910.00€

-- Obtenir le taux par km pour une voiture 7 CV ayant parcouru 15 000 km
-- SELECT get_mileage_rate_per_km('CAR', 15000, '7CV_AND_MORE', NULL, 2024);
-- Résultat attendu : 0.394€/km

-- Calculer l'indemnité pour une moto >125cc ayant parcouru 4 500 km
-- SELECT calculate_mileage_allowance('MOTORCYCLE', 4500, NULL, 'MORE_THAN_125CC', 2024);
-- Résultat attendu : 4500 * 0.082 = 369.00€

-- ========================================
-- ROW LEVEL SECURITY (RLS) Policies
-- ========================================
-- Cette table est en lecture seule pour tous les utilisateurs authentifiés.
-- Seul le service_role (admin) peut modifier les données.
-- ========================================

-- Activer RLS sur la table
ALTER TABLE mileage_allowance_rates ENABLE ROW LEVEL SECURITY;

-- Supprimer les anciennes policies si elles existent
DROP POLICY IF EXISTS "Authenticated users can read mileage rates" ON mileage_allowance_rates;
DROP POLICY IF EXISTS "Service role can manage mileage rates" ON mileage_allowance_rates;

-- Policy SELECT: Tous les utilisateurs authentifiés peuvent lire les barèmes
CREATE POLICY "Authenticated users can read mileage rates" ON mileage_allowance_rates
    FOR SELECT
    TO authenticated
    USING (true);

-- Policy ALL: Seul le service_role peut insérer/modifier/supprimer
-- Note: Cette policy ne s'applique qu'au service_role (bypass RLS par défaut)
-- mais on la définit explicitement pour la documentation
CREATE POLICY "Service role can manage mileage rates" ON mileage_allowance_rates
    FOR ALL
    TO service_role
    USING (true)
    WITH CHECK (true);

-- ========================================
-- Vérification RLS
-- ========================================
-- SELECT tablename, rowsecurity FROM pg_tables WHERE tablename = 'mileage_allowance_rates';
-- SELECT * FROM pg_policies WHERE tablename = 'mileage_allowance_rates';
