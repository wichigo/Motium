-- Motium Database Schema for Supabase
-- Run this SQL in your Supabase SQL Editor

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Enable PostGIS for future geographic features (optional)
-- CREATE EXTENSION IF NOT EXISTS postgis;

-- ========================================
-- USERS TABLE
-- ========================================
CREATE TABLE users (
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
    phone_number VARCHAR(20) DEFAULT '',
    address TEXT DEFAULT '',
    linked_to_company BOOLEAN DEFAULT FALSE,
    share_professional_trips BOOLEAN DEFAULT TRUE,
    share_personal_trips BOOLEAN DEFAULT FALSE,
    share_personal_info BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ========================================
-- VEHICLES TABLE
-- ========================================
CREATE TABLE vehicles (
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

-- ========================================
-- SETTINGS TABLE
-- ========================================
CREATE TABLE settings (
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
    working_days VARCHAR(20) DEFAULT '1,2,3,4,5', -- Monday to Friday
    default_vehicle_id UUID REFERENCES vehicles(id) ON DELETE SET NULL,
    default_trip_type VARCHAR(20) DEFAULT 'PERSONAL' CHECK (default_trip_type IN ('PROFESSIONAL', 'PERSONAL')),
    notifications_enabled BOOLEAN DEFAULT TRUE,
    export_format VARCHAR(10) DEFAULT 'PDF' CHECK (export_format IN ('PDF', 'CSV')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ========================================
-- TRIPS TABLE
-- ========================================
CREATE TABLE trips (
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
    trace_gps JSONB, -- JSON array of GPS points [{"lat":48.8566,"lng":2.3522,"timestamp":1234567890,"accuracy":5.0}]
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ========================================
-- INDEXES FOR PERFORMANCE
-- ========================================

-- Users indexes
CREATE INDEX idx_users_auth_id ON users(auth_id);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_subscription ON users(subscription_type, subscription_expires_at);

-- Vehicles indexes
CREATE INDEX idx_vehicles_user_id ON vehicles(user_id);
CREATE INDEX idx_vehicles_default ON vehicles(user_id, is_default) WHERE is_default = TRUE;

-- Settings indexes
CREATE INDEX idx_settings_user_id ON settings(user_id);

-- Trips indexes
CREATE INDEX idx_trips_user_id ON trips(user_id);
CREATE INDEX idx_trips_date_range ON trips(user_id, start_time);
CREATE INDEX idx_trips_vehicle ON trips(vehicle_id);
CREATE INDEX idx_trips_type ON trips(user_id, type);
CREATE INDEX idx_trips_active ON trips(user_id, end_time) WHERE end_time IS NULL;
CREATE INDEX idx_trips_monthly ON trips(user_id, EXTRACT(YEAR FROM start_time), EXTRACT(MONTH FROM start_time));

-- JSONB index for GPS traces (for future spatial queries)
CREATE INDEX idx_trips_trace_gps ON trips USING GIN (trace_gps);

-- ========================================
-- ROW LEVEL SECURITY (RLS)
-- ========================================

-- Enable RLS on all tables
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicles ENABLE ROW LEVEL SECURITY;
ALTER TABLE settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE trips ENABLE ROW LEVEL SECURITY;

-- Users can only see their own data
CREATE POLICY "Users can view own profile" ON users FOR SELECT USING (auth.uid() = auth_id);
CREATE POLICY "Users can update own profile" ON users FOR UPDATE USING (auth.uid() = auth_id);
CREATE POLICY "Users can insert own profile" ON users FOR INSERT WITH CHECK (auth.uid() = auth_id);

-- Vehicles policies
CREATE POLICY "Users can view own vehicles" ON vehicles FOR SELECT USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);
CREATE POLICY "Users can manage own vehicles" ON vehicles FOR ALL USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- Settings policies
CREATE POLICY "Users can view own settings" ON settings FOR SELECT USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);
CREATE POLICY "Users can manage own settings" ON settings FOR ALL USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- Trips policies
CREATE POLICY "Users can view own trips" ON trips FOR SELECT USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);
CREATE POLICY "Users can manage own trips" ON trips FOR ALL USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- ========================================
-- TRIGGERS FOR AUTO-UPDATING TIMESTAMPS
-- ========================================

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply trigger to all tables
CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_vehicles_updated_at BEFORE UPDATE ON vehicles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_settings_updated_at BEFORE UPDATE ON settings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_trips_updated_at BEFORE UPDATE ON trips
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ========================================
-- FUNCTIONS FOR BUSINESS LOGIC
-- ========================================

-- Function to calculate trip statistics for a user in a date range
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
        'professional_distance', COALESCE(SUM(distance_km) FILTER (WHERE type = 'PROFESSIONAL'), 0),
        'personal_distance', COALESCE(SUM(distance_km) FILTER (WHERE type = 'PERSONAL'), 0),
        'validated_trips', COUNT(*) FILTER (WHERE is_validated = TRUE),
        'average_distance', CASE WHEN COUNT(*) > 0 THEN COALESCE(AVG(distance_km), 0) ELSE 0 END
    ) INTO result
    FROM trips
    WHERE user_id = p_user_id
    AND start_time >= p_start_date
    AND start_time <= p_end_date;

    RETURN result;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to get monthly trip count for subscription limits
CREATE OR REPLACE FUNCTION get_monthly_trip_count(p_user_id UUID, p_month_start TIMESTAMPTZ)
RETURNS INTEGER AS $$
DECLARE
    trip_count INTEGER;
BEGIN
    SELECT COUNT(*)
    INTO trip_count
    FROM trips
    WHERE user_id = p_user_id
    AND start_time >= p_month_start
    AND start_time < p_month_start + INTERVAL '1 month';

    RETURN COALESCE(trip_count, 0);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to auto-create settings when user is created
CREATE OR REPLACE FUNCTION create_default_settings_for_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO settings (user_id) VALUES (NEW.id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER create_settings_on_user_insert
    AFTER INSERT ON users
    FOR EACH ROW
    EXECUTE FUNCTION create_default_settings_for_user();

-- ========================================
-- INITIAL DATA / SAMPLE VEHICLE RATES
-- ========================================

-- This will be populated by the application based on Constants.kt mileage rates

-- ========================================
-- VIEWS FOR COMMON QUERIES
-- ========================================

-- View for trip summary with vehicle info
CREATE VIEW trip_summary_view AS
SELECT
    t.*,
    v.name as vehicle_name,
    v.type as vehicle_type,
    v.license_plate,
    u.name as user_name,
    u.email as user_email
FROM trips t
LEFT JOIN vehicles v ON t.vehicle_id = v.id
JOIN users u ON t.user_id = u.id;

-- View for daily trip summaries
CREATE VIEW daily_trip_summary AS
SELECT
    user_id,
    start_time::date as trip_date,
    COUNT(*) as total_trips,
    SUM(distance_km) as total_distance,
    SUM(cost) as total_cost,
    SUM(duration_ms) as total_duration_ms,
    COUNT(*) FILTER (WHERE type = 'PROFESSIONAL') as professional_trips,
    COUNT(*) FILTER (WHERE type = 'PERSONAL') as personal_trips
FROM trips
WHERE end_time IS NOT NULL
GROUP BY user_id, start_time::date;

-- ========================================
-- GRANTS FOR PUBLIC SCHEMA ACCESS
-- ========================================

-- Grant usage on all sequences to authenticated users
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO authenticated;

-- These tables will be accessible via RLS policies
GRANT ALL ON users TO authenticated;
GRANT ALL ON vehicles TO authenticated;
GRANT ALL ON settings TO authenticated;
GRANT ALL ON trips TO authenticated;

-- Grant access to views
GRANT SELECT ON trip_summary_view TO authenticated;
GRANT SELECT ON daily_trip_summary TO authenticated;

-- Grant execute on functions
GRANT EXECUTE ON FUNCTION get_trip_stats(UUID, TIMESTAMPTZ, TIMESTAMPTZ) TO authenticated;
GRANT EXECUTE ON FUNCTION get_monthly_trip_count(UUID, TIMESTAMPTZ) TO authenticated;