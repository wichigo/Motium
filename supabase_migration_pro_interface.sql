-- =============================================================================
-- MOTIUM PRO INTERFACE - SUPABASE MIGRATION
-- =============================================================================
-- This migration creates the necessary tables and columns for the Pro interface.
-- Run this in the Supabase SQL Editor.
-- =============================================================================

-- =============================================================================
-- 1. PRO_ACCOUNTS TABLE
-- =============================================================================
-- Stores Pro (Enterprise) account information

CREATE TABLE IF NOT EXISTS pro_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    company_name TEXT NOT NULL,
    siret TEXT,
    vat_number TEXT,
    legal_form TEXT CHECK (legal_form IN ('SARL', 'SAS', 'SASU', 'EURL', 'SA', 'EI', 'MICRO', 'OTHER')),
    billing_address TEXT,
    billing_email TEXT,
    stripe_customer_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT unique_user_pro_account UNIQUE (user_id)
);

-- Index for faster lookups
CREATE INDEX IF NOT EXISTS idx_pro_accounts_user_id ON pro_accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_pro_accounts_stripe_customer_id ON pro_accounts(stripe_customer_id);

-- RLS Policies for pro_accounts
ALTER TABLE pro_accounts ENABLE ROW LEVEL SECURITY;

-- Pro users can read their own account
CREATE POLICY "Pro users can read own account" ON pro_accounts
    FOR SELECT USING (auth.uid() = user_id);

-- Pro users can update their own account
CREATE POLICY "Pro users can update own account" ON pro_accounts
    FOR UPDATE USING (auth.uid() = user_id);

-- Pro users can insert their own account
CREATE POLICY "Pro users can insert own account" ON pro_accounts
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- =============================================================================
-- 2. ADD PRO LINK COLUMNS TO USERS TABLE
-- =============================================================================
-- These columns link Individual users to Pro accounts
-- (No separate linked_accounts table needed - data is directly on the user)

-- Link to Pro account
ALTER TABLE users ADD COLUMN IF NOT EXISTS linked_pro_account_id UUID REFERENCES pro_accounts(id) ON DELETE SET NULL;

-- Link status: pending, active, revoked
ALTER TABLE users ADD COLUMN IF NOT EXISTS link_status TEXT CHECK (link_status IN ('pending', 'active', 'revoked'));

-- Invitation management
ALTER TABLE users ADD COLUMN IF NOT EXISTS invitation_token TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS invited_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS link_activated_at TIMESTAMPTZ;

-- Sharing preferences (Individual controls what Pro can see)
ALTER TABLE users ADD COLUMN IF NOT EXISTS share_professional_trips BOOLEAN DEFAULT true;
ALTER TABLE users ADD COLUMN IF NOT EXISTS share_personal_trips BOOLEAN DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS share_vehicle_info BOOLEAN DEFAULT true;
ALTER TABLE users ADD COLUMN IF NOT EXISTS share_expenses BOOLEAN DEFAULT false;

-- Indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_users_linked_pro_account_id ON users(linked_pro_account_id);
CREATE INDEX IF NOT EXISTS idx_users_link_status ON users(link_status);
CREATE INDEX IF NOT EXISTS idx_users_invitation_token ON users(invitation_token);

-- =============================================================================
-- 3. RLS POLICIES FOR LINKED USERS
-- =============================================================================

-- Pro users can read linked users (users linked to their pro_account)
CREATE POLICY "Pro can read linked users" ON users
    FOR SELECT USING (
        linked_pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- Pro users can update link_status for users linked to them
CREATE POLICY "Pro can update linked users status" ON users
    FOR UPDATE USING (
        linked_pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    )
    WITH CHECK (
        linked_pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- =============================================================================
-- 4. LICENSES TABLE
-- =============================================================================
-- Manages licenses purchased by Pro accounts

CREATE TABLE IF NOT EXISTS licenses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pro_account_id UUID NOT NULL REFERENCES pro_accounts(id) ON DELETE CASCADE,
    assigned_user_id UUID REFERENCES users(id) ON DELETE SET NULL,  -- User who has this license

    -- Pricing (in EUR)
    price_monthly_ht DECIMAL(10, 2) NOT NULL DEFAULT 5.00,
    vat_rate DECIMAL(5, 4) NOT NULL DEFAULT 0.20,

    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active', 'expired', 'cancelled')),
    start_date TIMESTAMPTZ,
    end_date TIMESTAMPTZ,

    -- Stripe integration
    stripe_subscription_id TEXT,
    stripe_subscription_item_id TEXT,
    stripe_price_id TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_licenses_pro_account_id ON licenses(pro_account_id);
CREATE INDEX IF NOT EXISTS idx_licenses_assigned_user_id ON licenses(assigned_user_id);
CREATE INDEX IF NOT EXISTS idx_licenses_status ON licenses(status);
CREATE INDEX IF NOT EXISTS idx_licenses_stripe_subscription_id ON licenses(stripe_subscription_id);

-- RLS Policies for licenses
ALTER TABLE licenses ENABLE ROW LEVEL SECURITY;

-- Pro users can read licenses for their pro_account
CREATE POLICY "Pro users can read licenses" ON licenses
    FOR SELECT USING (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- Pro users can insert licenses for their pro_account
CREATE POLICY "Pro users can insert licenses" ON licenses
    FOR INSERT WITH CHECK (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- Pro users can update licenses for their pro_account
CREATE POLICY "Pro users can update licenses" ON licenses
    FOR UPDATE USING (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- =============================================================================
-- 5. AUTO-UPDATE TRIGGER FOR updated_at
-- =============================================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply trigger to pro_accounts
DROP TRIGGER IF EXISTS update_pro_accounts_updated_at ON pro_accounts;
CREATE TRIGGER update_pro_accounts_updated_at
    BEFORE UPDATE ON pro_accounts
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Apply trigger to licenses
DROP TRIGGER IF EXISTS update_licenses_updated_at ON licenses;
CREATE TRIGGER update_licenses_updated_at
    BEFORE UPDATE ON licenses
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- =============================================================================
-- 6. HELPER FUNCTIONS
-- =============================================================================

-- Function to generate a unique invitation token
CREATE OR REPLACE FUNCTION generate_invitation_token()
RETURNS TEXT AS $$
BEGIN
    RETURN encode(gen_random_bytes(32), 'hex');
END;
$$ LANGUAGE plpgsql;

-- Function to invite a user by email (creates or updates user link)
CREATE OR REPLACE FUNCTION invite_user_to_pro(
    p_pro_account_id UUID,
    p_email TEXT
)
RETURNS UUID AS $$
DECLARE
    v_user_id UUID;
    v_token TEXT;
BEGIN
    -- Generate invitation token
    v_token := generate_invitation_token();

    -- Check if user exists
    SELECT id INTO v_user_id FROM users WHERE email = p_email;

    IF v_user_id IS NOT NULL THEN
        -- User exists - update with invitation
        UPDATE users SET
            linked_pro_account_id = p_pro_account_id,
            link_status = 'pending',
            invitation_token = v_token,
            invited_at = NOW(),
            link_activated_at = NULL
        WHERE id = v_user_id;
    END IF;

    -- Return user_id if found, NULL otherwise (invitation will be sent by email)
    RETURN v_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to accept an invitation
CREATE OR REPLACE FUNCTION accept_invitation(p_token TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    v_updated BOOLEAN := FALSE;
BEGIN
    UPDATE users SET
        link_status = 'active',
        invitation_token = NULL,
        link_activated_at = NOW()
    WHERE invitation_token = p_token
      AND link_status = 'pending';

    IF FOUND THEN
        v_updated := TRUE;
    END IF;

    RETURN v_updated;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to get license summary for a Pro account
CREATE OR REPLACE FUNCTION get_license_summary(p_pro_account_id UUID)
RETURNS TABLE (
    total_licenses BIGINT,
    active_licenses BIGINT,
    pending_licenses BIGINT,
    assigned_licenses BIGINT,
    available_licenses BIGINT,
    monthly_cost_ht DECIMAL,
    monthly_cost_ttc DECIMAL
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        COUNT(*)::BIGINT as total_licenses,
        COUNT(*) FILTER (WHERE status = 'active')::BIGINT as active_licenses,
        COUNT(*) FILTER (WHERE status = 'pending')::BIGINT as pending_licenses,
        COUNT(*) FILTER (WHERE assigned_user_id IS NOT NULL AND status IN ('active', 'pending'))::BIGINT as assigned_licenses,
        COUNT(*) FILTER (WHERE assigned_user_id IS NULL AND status IN ('active', 'pending'))::BIGINT as available_licenses,
        COALESCE(SUM(price_monthly_ht) FILTER (WHERE status IN ('active', 'pending')), 0) as monthly_cost_ht,
        COALESCE(SUM(price_monthly_ht * (1 + vat_rate)) FILTER (WHERE status IN ('active', 'pending')), 0) as monthly_cost_ttc
    FROM licenses
    WHERE pro_account_id = p_pro_account_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to get linked users for a Pro account
CREATE OR REPLACE FUNCTION get_linked_users(p_pro_account_id UUID)
RETURNS TABLE (
    user_id UUID,
    user_name TEXT,
    user_email TEXT,
    link_status TEXT,
    invited_at TIMESTAMPTZ,
    link_activated_at TIMESTAMPTZ,
    share_professional_trips BOOLEAN,
    share_personal_trips BOOLEAN,
    share_vehicle_info BOOLEAN,
    share_expenses BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        u.id,
        u.name,
        u.email,
        u.link_status,
        u.invited_at,
        u.link_activated_at,
        u.share_professional_trips,
        u.share_personal_trips,
        u.share_vehicle_info,
        u.share_expenses
    FROM users u
    WHERE u.linked_pro_account_id = p_pro_account_id
    ORDER BY u.link_status, u.name;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =============================================================================
-- MIGRATION COMPLETE
-- =============================================================================
-- Run this SQL in your Supabase SQL Editor.
--
-- Tables created:
--   - pro_accounts: Pro company information
--   - licenses: License management with Stripe integration
--
-- Columns added to users:
--   - linked_pro_account_id: Link to Pro account
--   - link_status: pending/active/revoked
--   - invitation_token: For pending invitations
--   - invited_at, link_activated_at: Timestamps
--   - share_*: Sharing preferences
--
-- Functions created:
--   - generate_invitation_token()
--   - invite_user_to_pro(pro_account_id, email)
--   - accept_invitation(token)
--   - get_license_summary(pro_account_id)
--   - get_linked_users(pro_account_id)
-- =============================================================================
