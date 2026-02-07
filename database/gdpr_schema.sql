-- ================================================================
-- MOTIUM GDPR/RGPD COMPLIANCE SCHEMA
-- ================================================================
-- This schema implements GDPR compliance features:
-- - User consent tracking (Articles 6 & 7)
-- - Privacy policy acceptances
-- - Data export requests (Article 15)
-- - Data deletion requests (Article 17)
-- - Data retention policies
-- - Audit logging for compliance proof
-- ================================================================

-- ========================================
-- TABLE: user_consents
-- ========================================
-- Tracks explicit user consent for each data processing purpose
-- GDPR Articles 6 & 7: Lawfulness of processing and conditions for consent

CREATE TABLE IF NOT EXISTS user_consents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Consent types (each must be individually toggleable)
    consent_type TEXT NOT NULL CHECK (consent_type IN (
        'location_tracking',      -- GPS tracking for trips
        'data_collection',        -- General data collection and storage (required for service)
        'company_data_sharing',   -- Share data with linked company
        'analytics',              -- Anonymous usage analytics
        'marketing'               -- Marketing communications
    )),

    -- Consent state
    granted BOOLEAN NOT NULL DEFAULT FALSE,
    granted_at TIMESTAMPTZ,         -- When consent was given
    revoked_at TIMESTAMPTZ,         -- When consent was revoked (null if still granted)

    -- Context for informed consent (GDPR requires "specific, informed")
    consent_version TEXT NOT NULL,   -- Version of consent text shown (e.g., "1.0", "2.0")
    ip_address TEXT,                 -- IP at time of consent (for proof)
    user_agent TEXT,                 -- Device info at time of consent

    -- Audit trail
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),

    -- Ensure one record per user per consent type
    UNIQUE(user_id, consent_type)
);

-- Indexes for fast consent checks
CREATE INDEX IF NOT EXISTS idx_user_consents_user_id ON user_consents(user_id);
CREATE INDEX IF NOT EXISTS idx_user_consents_type ON user_consents(consent_type, granted);

-- RLS: Users can only see/manage their own consents
ALTER TABLE user_consents ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own consents" ON user_consents;
CREATE POLICY "Users can view own consents" ON user_consents
    FOR SELECT USING (user_id IN (SELECT id FROM users WHERE auth_id = auth.uid()));

DROP POLICY IF EXISTS "Users can manage own consents" ON user_consents;
CREATE POLICY "Users can manage own consents" ON user_consents
    FOR ALL USING (user_id IN (SELECT id FROM users WHERE auth_id = auth.uid()));

DROP POLICY IF EXISTS "Service role manages consents" ON user_consents;
CREATE POLICY "Service role manages consents" ON user_consents
    FOR ALL TO service_role USING (true) WITH CHECK (true);


-- ========================================
-- TABLE: privacy_policy_acceptances
-- ========================================
-- Tracks acceptance of privacy policy versions
-- Required before account creation/continued use

CREATE TABLE IF NOT EXISTS privacy_policy_acceptances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Policy details
    policy_version TEXT NOT NULL,        -- e.g., "2025.01", "2025.02"
    policy_url TEXT NOT NULL,            -- URL to the policy accepted
    policy_hash TEXT,                    -- SHA-256 hash of policy content (proof)

    -- Acceptance details
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ip_address TEXT,
    user_agent TEXT,

    -- Metadata
    created_at TIMESTAMPTZ DEFAULT NOW(),

    -- One acceptance per version per user
    UNIQUE(user_id, policy_version)
);

CREATE INDEX IF NOT EXISTS idx_policy_acceptances_user ON privacy_policy_acceptances(user_id);
CREATE INDEX IF NOT EXISTS idx_policy_acceptances_version ON privacy_policy_acceptances(policy_version);

-- RLS
ALTER TABLE privacy_policy_acceptances ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own policy acceptances" ON privacy_policy_acceptances;
CREATE POLICY "Users can view own policy acceptances" ON privacy_policy_acceptances
    FOR SELECT USING (user_id IN (SELECT id FROM users WHERE auth_id = auth.uid()));

DROP POLICY IF EXISTS "Users can insert own policy acceptances" ON privacy_policy_acceptances;
CREATE POLICY "Users can insert own policy acceptances" ON privacy_policy_acceptances
    FOR INSERT WITH CHECK (user_id IN (SELECT id FROM users WHERE auth_id = auth.uid()));

DROP POLICY IF EXISTS "Service role manages policy acceptances" ON privacy_policy_acceptances;
CREATE POLICY "Service role manages policy acceptances" ON privacy_policy_acceptances
    FOR ALL TO service_role USING (true) WITH CHECK (true);


-- ========================================
-- TABLE: gdpr_data_requests
-- ========================================
-- Tracks GDPR data export and deletion requests
-- Provides audit trail for compliance

CREATE TABLE IF NOT EXISTS gdpr_data_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,

    -- User info snapshot (preserved after deletion)
    user_email TEXT,

    -- Request type
    request_type TEXT NOT NULL CHECK (request_type IN (
        'data_export',       -- Article 15: Right of access
        'data_deletion',     -- Article 17: Right to erasure
        'data_rectification' -- Article 16: Right to rectification
    )),

    -- Request status
    status TEXT NOT NULL CHECK (status IN (
        'pending',          -- Request received
        'processing',       -- Being processed
        'completed',        -- Successfully completed
        'failed',           -- Processing failed
        'cancelled'         -- Cancelled by user
    )) DEFAULT 'pending',

    -- Processing details
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,             -- For export download links

    -- Export-specific fields
    export_file_url TEXT,               -- Supabase Storage signed URL
    export_format TEXT CHECK (export_format IN ('json', 'pdf', 'zip')),
    export_size_bytes BIGINT,

    -- Deletion-specific fields
    deletion_reason TEXT,               -- Optional user-provided reason
    data_deleted JSONB,                 -- Summary of what was deleted
    stripe_cleanup_status TEXT,         -- Status of Stripe data cleanup

    -- Error handling
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,

    -- Audit
    ip_address TEXT,
    user_agent TEXT,
    processed_by TEXT,                  -- 'system' or admin user ID

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_gdpr_requests_user ON gdpr_data_requests(user_id);
CREATE INDEX IF NOT EXISTS idx_gdpr_requests_status ON gdpr_data_requests(status);
CREATE INDEX IF NOT EXISTS idx_gdpr_requests_type ON gdpr_data_requests(request_type);
CREATE INDEX IF NOT EXISTS idx_gdpr_requests_pending ON gdpr_data_requests(status, requested_at)
    WHERE status IN ('pending', 'processing');

-- RLS
ALTER TABLE gdpr_data_requests ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own GDPR requests" ON gdpr_data_requests;
CREATE POLICY "Users can view own GDPR requests" ON gdpr_data_requests
    FOR SELECT USING (user_id IN (SELECT id FROM users WHERE auth_id = auth.uid()));

DROP POLICY IF EXISTS "Users can create own GDPR requests" ON gdpr_data_requests;
CREATE POLICY "Users can create own GDPR requests" ON gdpr_data_requests
    FOR INSERT WITH CHECK (user_id IN (SELECT id FROM users WHERE auth_id = auth.uid()));

-- Service role can manage all (for background processing)
DROP POLICY IF EXISTS "Service role manages GDPR requests" ON gdpr_data_requests;
CREATE POLICY "Service role manages GDPR requests" ON gdpr_data_requests
    FOR ALL TO service_role USING (true) WITH CHECK (true);


-- ========================================
-- TABLE: data_retention_policies
-- ========================================
-- Configurable retention periods per data type

CREATE TABLE IF NOT EXISTS data_retention_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    data_type TEXT NOT NULL UNIQUE CHECK (data_type IN (
        'trips',
        'expenses',
        'vehicles',
        'work_schedules',
        'company_links',
        'stripe_payments',
        'gdpr_requests',
        'audit_logs'
    )),

    -- Retention configuration
    retention_days INTEGER NOT NULL,     -- Number of days to retain
    auto_delete BOOLEAN DEFAULT FALSE,   -- Automatically delete after retention
    notify_before_days INTEGER,          -- Notify user X days before deletion

    -- Policy details
    description TEXT,
    legal_basis TEXT,                    -- GDPR legal basis for retention

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Insert default retention policies (French regulations)
INSERT INTO data_retention_policies (data_type, retention_days, auto_delete, notify_before_days, description, legal_basis)
VALUES
    ('trips', 2190, FALSE, 30, 'Trip data retained for 6 years (fiscal requirements)', 'Legal obligation - French fiscal regulations'),
    ('expenses', 2190, FALSE, 30, 'Expense data retained for 6 years (fiscal requirements)', 'Legal obligation - French fiscal regulations'),
    ('vehicles', 2190, FALSE, 30, 'Vehicle data retained for 6 years (fiscal requirements)', 'Legal obligation - French fiscal regulations'),
    ('work_schedules', 365, TRUE, 7, 'Work schedules retained for 1 year', 'Legitimate interest - service improvement'),
    ('company_links', 365, FALSE, 30, 'Company links retained for 1 year after unlink', 'Contract performance'),
    ('stripe_payments', 3650, FALSE, 30, 'Payment records retained for 10 years (legal)', 'Legal obligation - Commercial records'),
    ('gdpr_requests', 1825, FALSE, 0, 'GDPR request logs retained for 5 years (proof)', 'Legal obligation - GDPR compliance proof'),
    ('audit_logs', 365, TRUE, 0, 'Audit logs retained for 1 year', 'Legitimate interest - security')
ON CONFLICT (data_type) DO NOTHING;


-- ========================================
-- TABLE: gdpr_audit_logs
-- ========================================
-- Audit trail for all GDPR-related actions

CREATE TABLE IF NOT EXISTS gdpr_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,

    -- Action details
    action TEXT NOT NULL CHECK (action IN (
        'consent_granted',
        'consent_revoked',
        'policy_accepted',
        'data_export_requested',
        'data_export_completed',
        'data_export_downloaded',
        'data_deletion_requested',
        'data_deletion_completed',
        'account_created',
        'account_login',
        'profile_updated'
    )),

    -- Additional context
    details JSONB,              -- Action-specific details
    consent_type TEXT,          -- For consent actions

    -- Request metadata
    ip_address TEXT,
    user_agent TEXT,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_gdpr_audit_user ON gdpr_audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_gdpr_audit_action ON gdpr_audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_gdpr_audit_date ON gdpr_audit_logs(created_at);

-- RLS
ALTER TABLE gdpr_audit_logs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own audit logs" ON gdpr_audit_logs;
CREATE POLICY "Users can view own audit logs" ON gdpr_audit_logs
    FOR SELECT USING (user_id IN (SELECT id FROM users WHERE auth_id = auth.uid()));

DROP POLICY IF EXISTS "Service role manages audit logs" ON gdpr_audit_logs;
CREATE POLICY "Service role manages audit logs" ON gdpr_audit_logs
    FOR ALL TO service_role USING (true) WITH CHECK (true);


-- ========================================
-- STORAGE BUCKET FOR EXPORTS
-- ========================================
-- Create bucket for GDPR exports (private)

INSERT INTO storage.buckets (id, name, public)
VALUES ('gdpr-exports', 'gdpr-exports', false)
ON CONFLICT (id) DO NOTHING;

-- Storage policy: users can only access their own exports
DROP POLICY IF EXISTS "Users can download own exports" ON storage.objects;
CREATE POLICY "Users can download own exports" ON storage.objects
    FOR SELECT USING (
        bucket_id = 'gdpr-exports' AND
        (storage.foldername(name))[1] = auth.uid()::text
    );


-- ========================================
-- FUNCTION: log_gdpr_action
-- ========================================
-- Logs GDPR actions for audit trail

CREATE OR REPLACE FUNCTION log_gdpr_action(
    p_user_id UUID,
    p_action TEXT,
    p_details JSONB DEFAULT NULL,
    p_consent_type TEXT DEFAULT NULL,
    p_ip_address TEXT DEFAULT NULL,
    p_user_agent TEXT DEFAULT NULL
)
RETURNS UUID AS $$
DECLARE
    v_log_id UUID;
BEGIN
    INSERT INTO gdpr_audit_logs (
        user_id, action, details, consent_type, ip_address, user_agent
    ) VALUES (
        p_user_id, p_action, p_details, p_consent_type, p_ip_address, p_user_agent
    )
    RETURNING id INTO v_log_id;

    RETURN v_log_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION log_gdpr_action(UUID, TEXT, JSONB, TEXT, TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION log_gdpr_action(UUID, TEXT, JSONB, TEXT, TEXT, TEXT) TO service_role;


-- ========================================
-- FUNCTION: update_user_consent
-- ========================================
-- Updates a single consent with full audit trail

CREATE OR REPLACE FUNCTION update_user_consent(
    p_user_id UUID,
    p_consent_type TEXT,
    p_granted BOOLEAN,
    p_consent_version TEXT,
    p_ip_address TEXT DEFAULT NULL,
    p_user_agent TEXT DEFAULT NULL
)
RETURNS BOOLEAN AS $$
DECLARE
    v_was_granted BOOLEAN;
BEGIN
    -- Get current state
    SELECT granted INTO v_was_granted
    FROM user_consents
    WHERE user_id = p_user_id AND consent_type = p_consent_type;

    -- Insert or update
    INSERT INTO user_consents (
        user_id, consent_type, granted,
        granted_at, revoked_at,
        consent_version, ip_address, user_agent,
        updated_at
    ) VALUES (
        p_user_id, p_consent_type, p_granted,
        CASE WHEN p_granted THEN NOW() ELSE NULL END,
        CASE WHEN NOT p_granted THEN NOW() ELSE NULL END,
        p_consent_version, p_ip_address, p_user_agent,
        NOW()
    )
    ON CONFLICT (user_id, consent_type)
    DO UPDATE SET
        granted = p_granted,
        granted_at = CASE
            WHEN p_granted AND NOT user_consents.granted THEN NOW()
            ELSE user_consents.granted_at
        END,
        revoked_at = CASE
            WHEN NOT p_granted AND user_consents.granted THEN NOW()
            ELSE user_consents.revoked_at
        END,
        consent_version = p_consent_version,
        ip_address = p_ip_address,
        user_agent = p_user_agent,
        updated_at = NOW();

    -- Log the action
    PERFORM log_gdpr_action(
        p_user_id,
        CASE WHEN p_granted THEN 'consent_granted' ELSE 'consent_revoked' END,
        jsonb_build_object('consent_type', p_consent_type, 'version', p_consent_version),
        p_consent_type,
        p_ip_address,
        p_user_agent
    );

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION update_user_consent(UUID, TEXT, BOOLEAN, TEXT, TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION update_user_consent(UUID, TEXT, BOOLEAN, TEXT, TEXT, TEXT) TO service_role;


-- ========================================
-- FUNCTION: get_user_consents
-- ========================================
-- Returns all consents for a user

CREATE OR REPLACE FUNCTION get_user_consents(p_user_id UUID)
RETURNS TABLE (
    consent_type TEXT,
    granted BOOLEAN,
    granted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    consent_version TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        uc.consent_type,
        uc.granted,
        uc.granted_at,
        uc.revoked_at,
        uc.consent_version
    FROM user_consents uc
    WHERE uc.user_id = p_user_id
    ORDER BY uc.consent_type;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION get_user_consents(UUID) TO authenticated;


-- ========================================
-- FUNCTION: accept_privacy_policy
-- ========================================
-- Records privacy policy acceptance

CREATE OR REPLACE FUNCTION accept_privacy_policy(
    p_user_id UUID,
    p_policy_version TEXT,
    p_policy_url TEXT,
    p_policy_hash TEXT DEFAULT NULL,
    p_ip_address TEXT DEFAULT NULL,
    p_user_agent TEXT DEFAULT NULL
)
RETURNS BOOLEAN AS $$
BEGIN
    INSERT INTO privacy_policy_acceptances (
        user_id, policy_version, policy_url, policy_hash,
        ip_address, user_agent, accepted_at
    ) VALUES (
        p_user_id, p_policy_version, p_policy_url, p_policy_hash,
        p_ip_address, p_user_agent, NOW()
    )
    ON CONFLICT (user_id, policy_version) DO NOTHING;

    -- Log the action
    PERFORM log_gdpr_action(
        p_user_id,
        'policy_accepted',
        jsonb_build_object('version', p_policy_version, 'url', p_policy_url),
        NULL,
        p_ip_address,
        p_user_agent
    );

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION accept_privacy_policy(UUID, TEXT, TEXT, TEXT, TEXT, TEXT) TO authenticated;


-- ========================================
-- FUNCTION: has_accepted_policy
-- ========================================
-- Checks if user has accepted a specific policy version

CREATE OR REPLACE FUNCTION has_accepted_policy(
    p_user_id UUID,
    p_policy_version TEXT
)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM privacy_policy_acceptances
        WHERE user_id = p_user_id AND policy_version = p_policy_version
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION has_accepted_policy(UUID, TEXT) TO authenticated;


-- ========================================
-- FUNCTION: export_user_data_json
-- ========================================
-- Exports ALL user data in a machine-readable JSON format
-- GDPR Article 15: Right of access and data portability

CREATE OR REPLACE FUNCTION export_user_data_json(p_user_id UUID)
RETURNS JSONB AS $$
DECLARE
    v_result JSONB;
    v_user_data JSONB;
    v_trips_data JSONB;
    v_vehicles_data JSONB;
    v_expenses_data JSONB;
    v_work_schedules_data JSONB;
    v_company_links_data JSONB;
    v_consents_data JSONB;
    v_payments_data JSONB;
    v_subscriptions_data JSONB;
    v_policy_acceptances JSONB;
BEGIN
    -- Get user profile (excluding internal fields like auth_id)
    SELECT jsonb_build_object(
        'id', u.id,
        'email', u.email,
        'name', u.name,
        'phone_number', u.phone_number,
        'address', u.address,
        'profile_photo_url', u.profile_photo_url,
        'role', u.role,
        'subscription_type', u.subscription_type,
        'subscription_expires_at', u.subscription_expires_at,
        'trial_started_at', u.trial_started_at,
        'trial_ends_at', u.trial_ends_at,
        'phone_verified', u.phone_verified,
        'verified_phone', u.verified_phone,
        'consider_full_distance', u.consider_full_distance,
        'favorite_colors', u.favorite_colors,
        'created_at', u.created_at,
        'updated_at', u.updated_at
    ) INTO v_user_data
    FROM users u
    WHERE u.id = p_user_id;

    IF v_user_data IS NULL THEN
        RAISE EXCEPTION 'User not found: %', p_user_id;
    END IF;

    -- Get all trips with GPS traces
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'id', t.id,
        'start_time', t.start_time,
        'end_time', t.end_time,
        'start_latitude', t.start_latitude,
        'start_longitude', t.start_longitude,
        'end_latitude', t.end_latitude,
        'end_longitude', t.end_longitude,
        'start_address', t.start_address,
        'end_address', t.end_address,
        'distance_km', t.distance_km,
        'duration_ms', t.duration_ms,
        'type', t.type,
        'is_validated', t.is_validated,
        'cost', t.cost,
        'trace_gps', t.trace_gps,
        'notes', t.notes,
        'is_work_home_trip', t.is_work_home_trip,
        'reimbursement_amount', t.reimbursement_amount,
        'created_at', t.created_at
    ) ORDER BY t.start_time DESC), '[]'::jsonb) INTO v_trips_data
    FROM trips t
    WHERE t.user_id = p_user_id;

    -- Get all vehicles
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'id', v.id,
        'name', v.name,
        'type', v.type,
        'license_plate', v.license_plate,
        'power', v.power,
        'fuel_type', v.fuel_type,
        'mileage_rate', v.mileage_rate,
        'is_default', v.is_default,
        'total_mileage_perso', v.total_mileage_perso,
        'total_mileage_pro', v.total_mileage_pro,
        'created_at', v.created_at
    )), '[]'::jsonb) INTO v_vehicles_data
    FROM vehicles v
    WHERE v.user_id = p_user_id;

    -- Get all expenses
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'id', e.id,
        'date', e.date,
        'type', e.type,
        'amount_ttc', e.amount_ttc,
        'amount_ht', e.amount_ht,
        'note', e.note,
        'photo_uri', e.photo_uri,
        'created_at', e.created_at
    )), '[]'::jsonb) INTO v_expenses_data
    FROM expenses_trips e
    WHERE e.user_id = p_user_id;

    -- Get work schedules
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'day_of_week', ws.day_of_week,
        'start_hour', ws.start_hour,
        'start_minute', ws.start_minute,
        'end_hour', ws.end_hour,
        'end_minute', ws.end_minute,
        'is_overnight', ws.is_overnight,
        'is_active', ws.is_active
    )), '[]'::jsonb) INTO v_work_schedules_data
    FROM work_schedules ws
    WHERE ws.user_id = p_user_id;

    -- Get company links
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'company_name', cl.company_name,
        'department', cl.department,
        'status', cl.status,
        'share_professional_trips', cl.share_professional_trips,
        'share_personal_trips', cl.share_personal_trips,
        'share_personal_info', cl.share_personal_info,
        'share_expenses', cl.share_expenses,
        'linked_at', cl.linked_at,
        'created_at', cl.created_at
    )), '[]'::jsonb) INTO v_company_links_data
    FROM company_links cl
    WHERE cl.user_id = p_user_id;

    -- Get consents
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'consent_type', uc.consent_type,
        'granted', uc.granted,
        'granted_at', uc.granted_at,
        'revoked_at', uc.revoked_at,
        'consent_version', uc.consent_version
    )), '[]'::jsonb) INTO v_consents_data
    FROM user_consents uc
    WHERE uc.user_id = p_user_id;

    -- Get payment history (if stripe_payments table exists)
    BEGIN
        SELECT COALESCE(jsonb_agg(jsonb_build_object(
            'payment_type', sp.payment_type,
            'amount_cents', sp.amount_cents,
            'currency', sp.currency,
            'status', sp.status,
            'invoice_number', sp.invoice_number,
            'paid_at', sp.paid_at
        )), '[]'::jsonb) INTO v_payments_data
        FROM stripe_payments sp
        WHERE sp.user_id = p_user_id;
    EXCEPTION WHEN undefined_table THEN
        v_payments_data := '[]'::jsonb;
    END;

    -- Get subscriptions (if stripe_subscriptions table exists)
    BEGIN
        SELECT COALESCE(jsonb_agg(jsonb_build_object(
            'subscription_type', ss.subscription_type,
            'status', ss.status,
            'current_period_start', ss.current_period_start,
            'current_period_end', ss.current_period_end,
            'cancel_at_period_end', ss.cancel_at_period_end
        )), '[]'::jsonb) INTO v_subscriptions_data
        FROM stripe_subscriptions ss
        WHERE ss.user_id = p_user_id;
    EXCEPTION WHEN undefined_table THEN
        v_subscriptions_data := '[]'::jsonb;
    END;

    -- Get policy acceptances
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'policy_version', ppa.policy_version,
        'policy_url', ppa.policy_url,
        'accepted_at', ppa.accepted_at
    )), '[]'::jsonb) INTO v_policy_acceptances
    FROM privacy_policy_acceptances ppa
    WHERE ppa.user_id = p_user_id;

    -- Build final result
    v_result := jsonb_build_object(
        'export_metadata', jsonb_build_object(
            'export_date', NOW(),
            'export_format', 'GDPR Article 15 - Data Portability',
            'app_name', 'Motium',
            'user_id', p_user_id
        ),
        'user_profile', v_user_data,
        'trips', v_trips_data,
        'vehicles', v_vehicles_data,
        'expenses', v_expenses_data,
        'work_schedules', v_work_schedules_data,
        'company_links', v_company_links_data,
        'consents', v_consents_data,
        'payments', v_payments_data,
        'subscriptions', v_subscriptions_data,
        'policy_acceptances', v_policy_acceptances
    );

    RETURN v_result;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION export_user_data_json(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION export_user_data_json(UUID) TO service_role;


-- ========================================
-- FUNCTION: delete_user_data_complete
-- ========================================
-- Permanently deletes ALL user data with cascading
-- GDPR Article 17: Right to erasure ("Right to be forgotten")
-- Returns a summary of what was deleted

CREATE OR REPLACE FUNCTION delete_user_data_complete(
    p_user_id UUID,
    p_deletion_reason TEXT DEFAULT NULL,
    p_cancel_stripe BOOLEAN DEFAULT TRUE
)
RETURNS JSONB AS $$
DECLARE
    v_auth_id UUID;
    v_email TEXT;
    v_stripe_customer_id TEXT;
    v_deleted_summary JSONB;
    v_trips_count INTEGER;
    v_vehicles_count INTEGER;
    v_expenses_count INTEGER;
    v_schedules_count INTEGER;
    v_links_count INTEGER;
    v_consents_count INTEGER;
BEGIN
    -- Get user info before deletion
    SELECT auth_id, email, stripe_customer_id
    INTO v_auth_id, v_email, v_stripe_customer_id
    FROM users WHERE id = p_user_id;

    IF v_auth_id IS NULL THEN
        RAISE EXCEPTION 'User not found: %', p_user_id;
    END IF;

    -- Count records before deletion for summary
    SELECT COUNT(*) INTO v_trips_count FROM trips WHERE user_id = p_user_id;
    SELECT COUNT(*) INTO v_vehicles_count FROM vehicles WHERE user_id = p_user_id;

    -- Count expenses (handle table name variation)
    BEGIN
        SELECT COUNT(*) INTO v_expenses_count FROM expenses_trips WHERE user_id = p_user_id;
    EXCEPTION WHEN undefined_table THEN
        v_expenses_count := 0;
    END;

    SELECT COUNT(*) INTO v_schedules_count FROM work_schedules WHERE user_id = p_user_id;
    SELECT COUNT(*) INTO v_links_count FROM company_links WHERE user_id = p_user_id;
    SELECT COUNT(*) INTO v_consents_count FROM user_consents WHERE user_id = p_user_id;

    -- Delete in proper order (respecting foreign keys)
    -- CASCADE handles most of this, but explicit for clarity

    -- 1. Delete GPS traces and trips
    DELETE FROM trips WHERE user_id = p_user_id;

    -- 2. Delete vehicles
    DELETE FROM vehicles WHERE user_id = p_user_id;

    -- 3. Delete expenses
    BEGIN
        DELETE FROM expenses_trips WHERE user_id = p_user_id;
    EXCEPTION WHEN undefined_table THEN
        NULL;
    END;

    -- 4. Delete work schedules and auto-tracking settings
    DELETE FROM work_schedules WHERE user_id = p_user_id;
    DELETE FROM auto_tracking_settings WHERE user_id = p_user_id;

    -- 5. Delete company links
    DELETE FROM company_links WHERE user_id = p_user_id;

    -- 6. Delete consents
    DELETE FROM user_consents WHERE user_id = p_user_id;

    -- 7. Delete privacy policy acceptances
    DELETE FROM privacy_policy_acceptances WHERE user_id = p_user_id;

    -- 8. Delete settings
    DELETE FROM settings WHERE user_id = p_user_id;

    -- 9. Anonymize payment history (keep for legal, but remove PII)
    BEGIN
        UPDATE stripe_payments
        SET
            user_id = NULL,
            receipt_email = NULL,
            metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{anonymized}', 'true'),
            updated_at = NOW()
        WHERE user_id = p_user_id;
    EXCEPTION WHEN undefined_table THEN
        NULL;
    END;

    -- 10. Update stripe_subscriptions (keep for billing records, anonymize)
    BEGIN
        UPDATE stripe_subscriptions
        SET
            user_id = NULL,
            status = 'canceled',
            ended_at = NOW(),
            metadata = jsonb_set(
                COALESCE(metadata, '{}'::jsonb),
                '{deleted_user}',
                to_jsonb(p_user_id::text)
            ),
            updated_at = NOW()
        WHERE user_id = p_user_id;
    EXCEPTION WHEN undefined_table THEN
        NULL;
    END;

    -- 11. Finally, delete the user record (CASCADE handles remaining FKs)
    DELETE FROM users WHERE id = p_user_id;

    -- Build summary
    v_deleted_summary := jsonb_build_object(
        'user_id', p_user_id,
        'email', v_email,
        'auth_id', v_auth_id,
        'deleted_at', NOW(),
        'reason', p_deletion_reason,
        'counts', jsonb_build_object(
            'trips', v_trips_count,
            'vehicles', v_vehicles_count,
            'expenses', v_expenses_count,
            'work_schedules', v_schedules_count,
            'company_links', v_links_count,
            'consents', v_consents_count
        ),
        'stripe_customer_id', v_stripe_customer_id,
        'stripe_cleanup_required', p_cancel_stripe AND v_stripe_customer_id IS NOT NULL
    );

    RETURN v_deleted_summary;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Only service role can execute (called from Edge Function)
GRANT EXECUTE ON FUNCTION delete_user_data_complete(UUID, TEXT, BOOLEAN) TO service_role;


-- ========================================
-- FUNCTION: initialize_default_consents
-- ========================================
-- Initialize default consents for a new user

CREATE OR REPLACE FUNCTION initialize_default_consents(
    p_user_id UUID,
    p_consent_version TEXT DEFAULT '1.0'
)
RETURNS VOID AS $$
BEGIN
    -- Location tracking - default TRUE (they signed up for a tracking app)
    INSERT INTO user_consents (user_id, consent_type, granted, granted_at, consent_version)
    VALUES (p_user_id, 'location_tracking', TRUE, NOW(), p_consent_version)
    ON CONFLICT (user_id, consent_type) DO NOTHING;

    -- Data collection - default TRUE (required for service)
    INSERT INTO user_consents (user_id, consent_type, granted, granted_at, consent_version)
    VALUES (p_user_id, 'data_collection', TRUE, NOW(), p_consent_version)
    ON CONFLICT (user_id, consent_type) DO NOTHING;

    -- Company data sharing - default FALSE (opt-in)
    INSERT INTO user_consents (user_id, consent_type, granted, consent_version)
    VALUES (p_user_id, 'company_data_sharing', FALSE, p_consent_version)
    ON CONFLICT (user_id, consent_type) DO NOTHING;

    -- Analytics - default FALSE (opt-in)
    INSERT INTO user_consents (user_id, consent_type, granted, consent_version)
    VALUES (p_user_id, 'analytics', FALSE, p_consent_version)
    ON CONFLICT (user_id, consent_type) DO NOTHING;

    -- Marketing - default FALSE (opt-in)
    INSERT INTO user_consents (user_id, consent_type, granted, consent_version)
    VALUES (p_user_id, 'marketing', FALSE, p_consent_version)
    ON CONFLICT (user_id, consent_type) DO NOTHING;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION initialize_default_consents(UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION initialize_default_consents(UUID, TEXT) TO service_role;


-- ========================================
-- MIGRATION: Initialize consents for existing users
-- ========================================
-- Run this ONCE after deploying the GDPR tables

DO $$
DECLARE
    v_user RECORD;
BEGIN
    FOR v_user IN SELECT id, created_at FROM users LOOP
        -- Initialize consents with migration version
        PERFORM initialize_default_consents(v_user.id, '1.0-migration');

        -- Log the migration
        INSERT INTO gdpr_audit_logs (user_id, action, details)
        VALUES (v_user.id, 'consent_granted', '{"migration": true, "version": "1.0-migration"}'::jsonb);
    END LOOP;
END $$;
