-- ========================================
-- Tables pour la gestion du planning professionnel
-- et de l'auto-tracking intelligent
-- ========================================

-- ========================================
-- Table des créneaux horaires professionnels
-- ========================================

CREATE TABLE IF NOT EXISTS work_schedules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,

    -- Jour de la semaine (1 = Lundi, 7 = Dimanche, format ISO 8601)
    day_of_week INTEGER NOT NULL CHECK (day_of_week >= 1 AND day_of_week <= 7),

    -- Heure de début du créneau
    start_hour INTEGER NOT NULL CHECK (start_hour >= 0 AND start_hour <= 23),
    start_minute INTEGER NOT NULL CHECK (start_minute >= 0 AND start_minute <= 59),

    -- Heure de fin du créneau
    end_hour INTEGER NOT NULL CHECK (end_hour >= 0 AND end_hour <= 23),
    end_minute INTEGER NOT NULL CHECK (end_minute >= 0 AND end_minute <= 59),

    -- Actif ou non (permet de désactiver temporairement un créneau)
    is_active BOOLEAN DEFAULT TRUE,

    -- Métadonnées
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    -- Contrainte: l'heure de fin doit être après l'heure de début
    CONSTRAINT valid_time_range CHECK (
        (end_hour > start_hour) OR
        (end_hour = start_hour AND end_minute > start_minute)
    )
);

-- Index pour optimiser les recherches
CREATE INDEX idx_work_schedules_user_id ON work_schedules(user_id);
CREATE INDEX idx_work_schedules_day ON work_schedules(day_of_week);
CREATE INDEX idx_work_schedules_user_day ON work_schedules(user_id, day_of_week);

-- ========================================
-- Table des paramètres d'auto-tracking
-- ========================================

CREATE TABLE IF NOT EXISTS auto_tracking_settings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES auth.users(id) ON DELETE CASCADE,

    -- Mode d'auto-tracking
    -- 'ALWAYS': Toujours actif (même en dehors des horaires pro)
    -- 'WORK_HOURS_ONLY': Actif uniquement pendant les horaires professionnels
    -- 'DISABLED': Complètement désactivé
    tracking_mode TEXT NOT NULL DEFAULT 'DISABLED' CHECK (tracking_mode IN ('ALWAYS', 'WORK_HOURS_ONLY', 'DISABLED')),

    -- Paramètres avancés (pour future utilisation)
    min_trip_distance_meters INTEGER DEFAULT 100, -- Distance minimale pour démarrer un trajet
    min_trip_duration_seconds INTEGER DEFAULT 60, -- Durée minimale pour enregistrer un trajet

    -- Métadonnées
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index
CREATE INDEX idx_auto_tracking_user_id ON auto_tracking_settings(user_id);

-- ========================================
-- Fonction pour vérifier si l'utilisateur est dans ses horaires pro
-- ========================================

CREATE OR REPLACE FUNCTION is_in_work_hours(
    p_user_id UUID,
    p_timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
)
RETURNS BOOLEAN AS $$
DECLARE
    v_day_of_week INTEGER;
    v_hour INTEGER;
    v_minute INTEGER;
    v_in_work_hours BOOLEAN;
BEGIN
    -- Extraire le jour de la semaine (1 = Lundi, 7 = Dimanche)
    v_day_of_week := EXTRACT(ISODOW FROM p_timestamp);

    -- Extraire l'heure et les minutes
    v_hour := EXTRACT(HOUR FROM p_timestamp);
    v_minute := EXTRACT(MINUTE FROM p_timestamp);

    -- Vérifier s'il existe un créneau actif pour ce jour/heure
    SELECT EXISTS (
        SELECT 1
        FROM work_schedules
        WHERE user_id = p_user_id
            AND day_of_week = v_day_of_week
            AND is_active = TRUE
            AND (
                -- Le timestamp est dans le créneau
                (v_hour > start_hour OR (v_hour = start_hour AND v_minute >= start_minute))
                AND
                (v_hour < end_hour OR (v_hour = end_hour AND v_minute <= end_minute))
            )
    ) INTO v_in_work_hours;

    RETURN v_in_work_hours;
END;
$$ LANGUAGE plpgsql;

-- ========================================
-- Fonction pour obtenir les créneaux du jour actuel
-- ========================================

CREATE OR REPLACE FUNCTION get_today_work_schedules(
    p_user_id UUID,
    p_date DATE DEFAULT CURRENT_DATE
)
RETURNS TABLE (
    id UUID,
    start_hour INTEGER,
    start_minute INTEGER,
    end_hour INTEGER,
    end_minute INTEGER,
    is_active BOOLEAN
) AS $$
DECLARE
    v_day_of_week INTEGER;
BEGIN
    -- Extraire le jour de la semaine
    v_day_of_week := EXTRACT(ISODOW FROM p_date);

    -- Retourner les créneaux du jour
    RETURN QUERY
    SELECT
        ws.id,
        ws.start_hour,
        ws.start_minute,
        ws.end_hour,
        ws.end_minute,
        ws.is_active
    FROM work_schedules ws
    WHERE ws.user_id = p_user_id
        AND ws.day_of_week = v_day_of_week
    ORDER BY ws.start_hour, ws.start_minute;
END;
$$ LANGUAGE plpgsql;

-- ========================================
-- Fonction pour déterminer si l'auto-tracking doit être actif
-- ========================================

CREATE OR REPLACE FUNCTION should_autotrack(
    p_user_id UUID,
    p_timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
)
RETURNS BOOLEAN AS $$
DECLARE
    v_tracking_mode TEXT;
    v_in_work_hours BOOLEAN;
BEGIN
    -- Récupérer le mode d'auto-tracking
    SELECT tracking_mode INTO v_tracking_mode
    FROM auto_tracking_settings
    WHERE user_id = p_user_id;

    -- Si pas de paramètres, désactivé par défaut
    IF v_tracking_mode IS NULL THEN
        RETURN FALSE;
    END IF;

    -- Cas selon le mode
    CASE v_tracking_mode
        WHEN 'DISABLED' THEN
            RETURN FALSE;
        WHEN 'ALWAYS' THEN
            RETURN TRUE;
        WHEN 'WORK_HOURS_ONLY' THEN
            -- Vérifier si on est dans les horaires pro
            RETURN is_in_work_hours(p_user_id, p_timestamp);
        ELSE
            RETURN FALSE;
    END CASE;
END;
$$ LANGUAGE plpgsql;

-- ========================================
-- Trigger pour mettre à jour updated_at
-- ========================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_work_schedules_updated_at
    BEFORE UPDATE ON work_schedules
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_auto_tracking_settings_updated_at
    BEFORE UPDATE ON auto_tracking_settings
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ========================================
-- RLS (Row Level Security) Policies
-- ========================================

-- Activer RLS
ALTER TABLE work_schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE auto_tracking_settings ENABLE ROW LEVEL SECURITY;

-- Policies pour work_schedules
CREATE POLICY "Users can view their own work schedules"
    ON work_schedules FOR SELECT
    USING (auth.uid() = user_id);

CREATE POLICY "Users can insert their own work schedules"
    ON work_schedules FOR INSERT
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update their own work schedules"
    ON work_schedules FOR UPDATE
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can delete their own work schedules"
    ON work_schedules FOR DELETE
    USING (auth.uid() = user_id);

-- Policies pour auto_tracking_settings
CREATE POLICY "Users can view their own auto tracking settings"
    ON auto_tracking_settings FOR SELECT
    USING (auth.uid() = user_id);

CREATE POLICY "Users can insert their own auto tracking settings"
    ON auto_tracking_settings FOR INSERT
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update their own auto tracking settings"
    ON auto_tracking_settings FOR UPDATE
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can delete their own auto tracking settings"
    ON auto_tracking_settings FOR DELETE
    USING (auth.uid() = user_id);

-- ========================================
-- Données d'exemple (à supprimer en production)
-- ========================================

-- Exemple de créneaux pour un utilisateur (remplacer 'USER_UUID' par un vrai UUID)
-- INSERT INTO work_schedules (user_id, day_of_week, start_hour, start_minute, end_hour, end_minute) VALUES
--     ('USER_UUID', 1, 9, 0, 12, 0),   -- Lundi matin
--     ('USER_UUID', 1, 14, 0, 18, 0),  -- Lundi après-midi
--     ('USER_UUID', 2, 9, 0, 17, 0),   -- Mardi journée complète
--     ('USER_UUID', 3, 9, 0, 17, 0),   -- Mercredi
--     ('USER_UUID', 4, 9, 0, 17, 0),   -- Jeudi
--     ('USER_UUID', 5, 9, 0, 13, 0);   -- Vendredi matin seulement

-- Exemple de paramètres d'auto-tracking
-- INSERT INTO auto_tracking_settings (user_id, tracking_mode) VALUES
--     ('USER_UUID', 'WORK_HOURS_ONLY');

-- ========================================
-- Exemples d'utilisation
-- ========================================

-- Vérifier si l'utilisateur est dans ses horaires pro maintenant
-- SELECT is_in_work_hours('USER_UUID');

-- Vérifier si l'utilisateur était dans ses horaires pro à un moment donné
-- SELECT is_in_work_hours('USER_UUID', '2024-01-15 10:30:00+00');

-- Obtenir les créneaux d'aujourd'hui
-- SELECT * FROM get_today_work_schedules('USER_UUID');

-- Déterminer si l'auto-tracking doit être actif maintenant
-- SELECT should_autotrack('USER_UUID');

-- Obtenir tous les créneaux d'un utilisateur pour la semaine
-- SELECT
--     day_of_week,
--     start_hour || ':' || LPAD(start_minute::TEXT, 2, '0') as start_time,
--     end_hour || ':' || LPAD(end_minute::TEXT, 2, '0') as end_time,
--     is_active
-- FROM work_schedules
-- WHERE user_id = 'USER_UUID'
-- ORDER BY day_of_week, start_hour, start_minute;
