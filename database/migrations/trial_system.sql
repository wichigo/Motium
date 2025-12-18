-- Migration: Système d'essai gratuit 7 jours avec protection anti-abus
-- Date: 2024-12-17
-- Description: Convertit le système freemium en essai 7 jours + Device ID + SMS OTP

-- =============================================================================
-- 1. TABLE: device_fingerprints
-- Stocke les empreintes device (MediaDrm Widevine ID) pour prévenir les abus
-- =============================================================================
CREATE TABLE IF NOT EXISTS device_fingerprints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_drm_id TEXT NOT NULL,              -- MediaDrm Widevine ID (base64)
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    registered_at TIMESTAMPTZ,                -- Quand le device a été utilisé pour s'inscrire
    blocked BOOLEAN DEFAULT FALSE,            -- Flag de blocage manuel
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(device_drm_id)
);

-- Index pour recherche rapide par device ID
CREATE INDEX IF NOT EXISTS idx_device_fingerprints_drm_id ON device_fingerprints(device_drm_id);
CREATE INDEX IF NOT EXISTS idx_device_fingerprints_user_id ON device_fingerprints(user_id);

-- RLS pour device_fingerprints
ALTER TABLE device_fingerprints ENABLE ROW LEVEL SECURITY;

-- Les utilisateurs peuvent voir uniquement leur propre fingerprint
CREATE POLICY "Users can view own device fingerprint"
ON device_fingerprints FOR SELECT
USING (user_id = auth.uid());

-- L'insertion se fait via service role (Edge Function ou backend)
CREATE POLICY "Service role can insert device fingerprints"
ON device_fingerprints FOR INSERT
WITH CHECK (true);

CREATE POLICY "Service role can update device fingerprints"
ON device_fingerprints FOR UPDATE
USING (true);

-- =============================================================================
-- 2. TABLE: verified_phone_numbers
-- Stocke les numéros de téléphone vérifiés pour prévenir la réutilisation
-- =============================================================================
CREATE TABLE IF NOT EXISTS verified_phone_numbers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_number TEXT NOT NULL,               -- Format E.164: +33612345678
    phone_hash TEXT NOT NULL,                 -- Hash du numéro pour recherche (SHA256)
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    verified_at TIMESTAMPTZ DEFAULT NOW(),
    blocked BOOLEAN DEFAULT FALSE,            -- Flag de blocage manuel
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(phone_number),
    UNIQUE(phone_hash)
);

-- Index pour recherche rapide
CREATE INDEX IF NOT EXISTS idx_verified_phones_hash ON verified_phone_numbers(phone_hash);
CREATE INDEX IF NOT EXISTS idx_verified_phones_user_id ON verified_phone_numbers(user_id);

-- RLS pour verified_phone_numbers
ALTER TABLE verified_phone_numbers ENABLE ROW LEVEL SECURITY;

-- Les utilisateurs peuvent voir uniquement leur propre numéro
CREATE POLICY "Users can view own phone number"
ON verified_phone_numbers FOR SELECT
USING (user_id = auth.uid());

-- L'insertion se fait via service role
CREATE POLICY "Service role can insert verified phones"
ON verified_phone_numbers FOR INSERT
WITH CHECK (true);

-- =============================================================================
-- 3. MODIFICATIONS TABLE: users
-- Ajout des colonnes pour le système d'essai
-- =============================================================================

-- Colonne pour le début de l'essai
ALTER TABLE users ADD COLUMN IF NOT EXISTS trial_started_at TIMESTAMPTZ;

-- Colonne pour la fin de l'essai (trial_started_at + 7 jours)
ALTER TABLE users ADD COLUMN IF NOT EXISTS trial_ends_at TIMESTAMPTZ;

-- Flag indiquant si le téléphone a été vérifié
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN DEFAULT FALSE;

-- Référence vers le device fingerprint
ALTER TABLE users ADD COLUMN IF NOT EXISTS device_fingerprint_id UUID REFERENCES device_fingerprints(id);

-- Numéro de téléphone vérifié (stocké aussi dans users pour accès rapide)
ALTER TABLE users ADD COLUMN IF NOT EXISTS verified_phone TEXT;

-- Index pour recherche par date de fin d'essai
CREATE INDEX IF NOT EXISTS idx_users_trial_ends ON users(trial_ends_at);

-- =============================================================================
-- 4. MODIFICATIONS TABLE: pro_accounts
-- Ajout du système d'essai pour les comptes Pro
-- =============================================================================

ALTER TABLE pro_accounts ADD COLUMN IF NOT EXISTS trial_started_at TIMESTAMPTZ;
ALTER TABLE pro_accounts ADD COLUMN IF NOT EXISTS trial_ends_at TIMESTAMPTZ;

-- =============================================================================
-- 5. MIGRATION DES DONNÉES: FREE -> TRIAL
-- Les utilisateurs FREE existants passent en essai de 7 jours
-- =============================================================================

-- Migrer tous les utilisateurs FREE vers TRIAL
UPDATE users
SET
    subscription_type = 'TRIAL',
    trial_started_at = NOW(),
    trial_ends_at = NOW() + INTERVAL '7 days'
WHERE subscription_type = 'FREE';

-- Migrer les comptes Pro sans licences actives vers essai
UPDATE pro_accounts
SET
    trial_started_at = NOW(),
    trial_ends_at = NOW() + INTERVAL '7 days'
WHERE id NOT IN (
    SELECT DISTINCT pro_account_id
    FROM licenses
    WHERE status = 'ACTIVE'
);

-- =============================================================================
-- 6. FONCTION: Vérifier si un device peut s'inscrire
-- =============================================================================

CREATE OR REPLACE FUNCTION check_device_eligibility(p_device_drm_id TEXT)
RETURNS TABLE (
    eligible BOOLEAN,
    reason TEXT,
    existing_user_id UUID
) AS $$
DECLARE
    v_existing RECORD;
BEGIN
    -- Chercher si le device existe déjà
    SELECT df.*, u.email
    INTO v_existing
    FROM device_fingerprints df
    LEFT JOIN users u ON df.user_id = u.id
    WHERE df.device_drm_id = p_device_drm_id;

    IF v_existing IS NULL THEN
        -- Device jamais vu, eligible
        RETURN QUERY SELECT TRUE, 'Device eligible'::TEXT, NULL::UUID;
    ELSIF v_existing.blocked THEN
        -- Device bloqué
        RETURN QUERY SELECT FALSE, 'Device blocked'::TEXT, v_existing.user_id;
    ELSIF v_existing.user_id IS NOT NULL THEN
        -- Device déjà utilisé pour un compte
        RETURN QUERY SELECT FALSE, 'Device already registered'::TEXT, v_existing.user_id;
    ELSE
        -- Device connu mais pas lié à un utilisateur (cas rare)
        RETURN QUERY SELECT TRUE, 'Device eligible'::TEXT, NULL::UUID;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =============================================================================
-- 7. FONCTION: Vérifier si un numéro peut s'inscrire
-- =============================================================================

CREATE OR REPLACE FUNCTION check_phone_eligibility(p_phone_hash TEXT)
RETURNS TABLE (
    eligible BOOLEAN,
    reason TEXT,
    existing_user_id UUID
) AS $$
DECLARE
    v_existing RECORD;
BEGIN
    -- Chercher si le numéro existe déjà
    SELECT vp.*, u.email
    INTO v_existing
    FROM verified_phone_numbers vp
    LEFT JOIN users u ON vp.user_id = u.id
    WHERE vp.phone_hash = p_phone_hash;

    IF v_existing IS NULL THEN
        -- Numéro jamais vu, eligible
        RETURN QUERY SELECT TRUE, 'Phone eligible'::TEXT, NULL::UUID;
    ELSIF v_existing.blocked THEN
        -- Numéro bloqué
        RETURN QUERY SELECT FALSE, 'Phone blocked'::TEXT, v_existing.user_id;
    ELSIF v_existing.user_id IS NOT NULL THEN
        -- Numéro déjà utilisé pour un compte
        RETURN QUERY SELECT FALSE, 'Phone already registered'::TEXT, v_existing.user_id;
    ELSE
        RETURN QUERY SELECT TRUE, 'Phone eligible'::TEXT, NULL::UUID;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =============================================================================
-- 8. FONCTION: Enregistrer un nouveau device
-- =============================================================================

CREATE OR REPLACE FUNCTION register_device(
    p_device_drm_id TEXT,
    p_user_id UUID
)
RETURNS UUID AS $$
DECLARE
    v_device_id UUID;
BEGIN
    INSERT INTO device_fingerprints (device_drm_id, user_id, registered_at)
    VALUES (p_device_drm_id, p_user_id, NOW())
    ON CONFLICT (device_drm_id) DO UPDATE
    SET user_id = p_user_id, registered_at = NOW(), updated_at = NOW()
    RETURNING id INTO v_device_id;

    -- Mettre à jour l'utilisateur avec la référence au device
    UPDATE users SET device_fingerprint_id = v_device_id WHERE id = p_user_id;

    RETURN v_device_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =============================================================================
-- 9. FONCTION: Enregistrer un numéro vérifié
-- =============================================================================

CREATE OR REPLACE FUNCTION register_verified_phone(
    p_phone_number TEXT,
    p_phone_hash TEXT,
    p_user_id UUID
)
RETURNS UUID AS $$
DECLARE
    v_phone_id UUID;
BEGIN
    INSERT INTO verified_phone_numbers (phone_number, phone_hash, user_id)
    VALUES (p_phone_number, p_phone_hash, p_user_id)
    ON CONFLICT (phone_number) DO UPDATE
    SET user_id = p_user_id
    RETURNING id INTO v_phone_id;

    -- Mettre à jour l'utilisateur
    UPDATE users
    SET phone_verified = TRUE, verified_phone = p_phone_number
    WHERE id = p_user_id;

    RETURN v_phone_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =============================================================================
-- 10. TRIGGER: Mettre à jour updated_at automatiquement
-- =============================================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_device_fingerprints_updated_at
    BEFORE UPDATE ON device_fingerprints
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- =============================================================================
-- 11. COMMENTAIRES
-- =============================================================================

COMMENT ON TABLE device_fingerprints IS 'Stocke les empreintes device MediaDrm pour prévenir les inscriptions multiples';
COMMENT ON TABLE verified_phone_numbers IS 'Stocke les numéros de téléphone vérifiés par SMS OTP';
COMMENT ON COLUMN users.trial_started_at IS 'Date de début de l''essai gratuit';
COMMENT ON COLUMN users.trial_ends_at IS 'Date de fin de l''essai gratuit (trial_started_at + 7 jours)';
COMMENT ON COLUMN users.phone_verified IS 'True si le numéro de téléphone a été vérifié par SMS OTP';
COMMENT ON COLUMN users.device_fingerprint_id IS 'Référence vers le device fingerprint de l''utilisateur';
