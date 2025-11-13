-- Motium Database Schema for Supabase (Version Simplifiée)
-- Exécutez ce SQL étape par étape dans votre éditeur SQL Supabase

-- ========================================
-- ÉTAPE 1: EXTENSIONS
-- ========================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ========================================
-- ÉTAPE 2: TABLES
-- ========================================

-- Table USERS
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    auth_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    role VARCHAR(20) DEFAULT 'INDIVIDUAL' CHECK (role IN ('INDIVIDUAL', 'ENTERPRISE')),
    organization_id UUID,
    organization_name VARCHAR(255),
    subscription_type VARCHAR(20) DEFAULT 'FREE' CHECK (subscription_type IN ('FREE', 'PREMIUM', 'LIFETIME')),
    subscription_expires_at TIMESTAMPTZ,
    monthly_trip_count INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Table VEHICLES
CREATE TABLE IF NOT EXISTS vehicles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('CAR', 'MOTORCYCLE', 'SCOOTER', 'BIKE')),
    license_plate VARCHAR(20),
    power VARCHAR(10) CHECK (power IN ('3CV', '4CV', '5CV', '6CV', '7CV+')),
    fuel_type VARCHAR(20) CHECK (fuel_type IN ('GASOLINE', 'DIESEL', 'ELECTRIC', 'HYBRID', 'OTHER')),
    mileage_rate DECIMAL(10,3) NOT NULL,
    is_default BOOLEAN DEFAULT FALSE,
    total_mileage_2025 DECIMAL(10,2) DEFAULT 0.0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Table SETTINGS
CREATE TABLE IF NOT EXISTS settings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE UNIQUE,
    auto_tracking_enabled BOOLEAN DEFAULT FALSE,
    min_speed_start_trip_kmh DECIMAL(5,2) DEFAULT 5.0,
    min_speed_stop_trip_kmh DECIMAL(5,2) DEFAULT 2.0,
    min_duration_start_trip_ms BIGINT DEFAULT 120000,
    min_duration_stop_trip_ms BIGINT DEFAULT 240000,
    gps_update_interval_ms BIGINT DEFAULT 5000,
    gps_accuracy_threshold_m REAL DEFAULT 20.0,
    working_hours_start TIME DEFAULT '08:00',
    working_hours_end TIME DEFAULT '18:00',
    working_days VARCHAR(20) DEFAULT '1,2,3,4,5',
    default_vehicle_id UUID REFERENCES vehicles(id) ON DELETE SET NULL,
    default_trip_type VARCHAR(20) DEFAULT 'PERSONAL' CHECK (default_trip_type IN ('PROFESSIONAL', 'PERSONAL')),
    notifications_enabled BOOLEAN DEFAULT TRUE,
    export_format VARCHAR(10) DEFAULT 'PDF' CHECK (export_format IN ('PDF', 'CSV')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Table TRIPS
CREATE TABLE IF NOT EXISTS trips (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    vehicle_id UUID REFERENCES vehicles(id) ON DELETE SET NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    start_latitude DECIMAL(10,8) NOT NULL,
    start_longitude DECIMAL(11,8) NOT NULL,
    end_latitude DECIMAL(10,8),
    end_longitude DECIMAL(11,8),
    start_address TEXT,
    end_address TEXT,
    distance_km DECIMAL(10,3) DEFAULT 0.0,
    duration_ms BIGINT DEFAULT 0,
    type VARCHAR(20) DEFAULT 'PERSONAL' CHECK (type IN ('PROFESSIONAL', 'PERSONAL')),
    is_validated BOOLEAN DEFAULT FALSE,
    cost DECIMAL(10,2) DEFAULT 0.0,
    trace_gps JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ========================================
-- ÉTAPE 3: INDEX BASIQUES
-- ========================================

-- Index pour les utilisateurs
CREATE INDEX IF NOT EXISTS idx_users_auth_id ON users(auth_id);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- Index pour les véhicules
CREATE INDEX IF NOT EXISTS idx_vehicles_user_id ON vehicles(user_id);

-- Index pour les paramètres
CREATE INDEX IF NOT EXISTS idx_settings_user_id ON settings(user_id);

-- Index pour les trajets
CREATE INDEX IF NOT EXISTS idx_trips_user_id ON trips(user_id);
CREATE INDEX IF NOT EXISTS idx_trips_start_time ON trips(user_id, start_time);
CREATE INDEX IF NOT EXISTS idx_trips_vehicle ON trips(vehicle_id);
CREATE INDEX IF NOT EXISTS idx_trips_type ON trips(user_id, type);

-- ========================================
-- ÉTAPE 4: ROW LEVEL SECURITY
-- ========================================

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicles ENABLE ROW LEVEL SECURITY;
ALTER TABLE settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE trips ENABLE ROW LEVEL SECURITY;

-- Politiques pour users
DROP POLICY IF EXISTS "Users can view own profile" ON users;
CREATE POLICY "Users can view own profile" ON users FOR SELECT USING (auth.uid() = auth_id);

DROP POLICY IF EXISTS "Users can update own profile" ON users;
CREATE POLICY "Users can update own profile" ON users FOR UPDATE USING (auth.uid() = auth_id);

DROP POLICY IF EXISTS "Users can insert own profile" ON users;
CREATE POLICY "Users can insert own profile" ON users FOR INSERT WITH CHECK (auth.uid() = auth_id);

-- Politiques pour vehicles
DROP POLICY IF EXISTS "Users can manage own vehicles" ON vehicles;
CREATE POLICY "Users can manage own vehicles" ON vehicles FOR ALL USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- Politiques pour settings
DROP POLICY IF EXISTS "Users can manage own settings" ON settings;
CREATE POLICY "Users can manage own settings" ON settings FOR ALL USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- Politiques pour trips
DROP POLICY IF EXISTS "Users can manage own trips" ON trips;
CREATE POLICY "Users can manage own trips" ON trips FOR ALL USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- ========================================
-- ÉTAPE 5: FONCTIONS TRIGGERS
-- ========================================

-- Fonction pour mettre à jour updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Appliquer les triggers
DROP TRIGGER IF EXISTS update_users_updated_at ON users;
CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_vehicles_updated_at ON vehicles;
CREATE TRIGGER update_vehicles_updated_at BEFORE UPDATE ON vehicles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_settings_updated_at ON settings;
CREATE TRIGGER update_settings_updated_at BEFORE UPDATE ON settings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_trips_updated_at ON trips;
CREATE TRIGGER update_trips_updated_at BEFORE UPDATE ON trips
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ========================================
-- ÉTAPE 6: FONCTIONS MÉTIER
-- ========================================

-- Fonction pour créer automatiquement les paramètres
CREATE OR REPLACE FUNCTION create_default_settings_for_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO settings (user_id) VALUES (NEW.id)
    ON CONFLICT (user_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS create_settings_on_user_insert ON users;
CREATE TRIGGER create_settings_on_user_insert
    AFTER INSERT ON users
    FOR EACH ROW
    EXECUTE FUNCTION create_default_settings_for_user();

-- Fonction pour calculer les statistiques de trajets
CREATE OR REPLACE FUNCTION get_trip_stats(
    p_user_id UUID,
    p_start_date TIMESTAMPTZ,
    p_end_date TIMESTAMPTZ
)
RETURNS JSON AS $$
DECLARE
    result JSON;
BEGIN
    SELECT JSON_BUILD_OBJECT(
        'total_trips', COUNT(*),
        'total_distance_km', COALESCE(SUM(distance_km), 0),
        'total_cost', COALESCE(SUM(cost), 0),
        'total_duration_hours', COALESCE(SUM(duration_ms), 0) / 3600000.0,
        'professional_trips', COUNT(*) FILTER (WHERE type = 'PROFESSIONAL'),
        'personal_trips', COUNT(*) FILTER (WHERE type = 'PERSONAL'),
        'validated_trips', COUNT(*) FILTER (WHERE is_validated = TRUE)
    ) INTO result
    FROM trips
    WHERE user_id = p_user_id
    AND start_time >= p_start_date
    AND start_time <= p_end_date;

    RETURN result;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ========================================
-- ÉTAPE 7: GRANTS
-- ========================================

GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO authenticated;
GRANT ALL ON users TO authenticated;
GRANT ALL ON vehicles TO authenticated;
GRANT ALL ON settings TO authenticated;
GRANT ALL ON trips TO authenticated;
GRANT EXECUTE ON FUNCTION get_trip_stats(UUID, TIMESTAMPTZ, TIMESTAMPTZ) TO authenticated;

-- ========================================
-- SUCCÈS !
-- ========================================
-- Votre base de données Motium est maintenant configurée.
-- Vous pouvez tester en créant un utilisateur dans l'onglet Authentication de Supabase.